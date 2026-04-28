package app.secure.kyber.onionrouting

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import app.secure.kyber.R
import app.secure.kyber.Utils.MessageEncryptionManager
import app.secure.kyber.backend.KyberRepository
import app.secure.kyber.backend.beans.ApiMessage
import app.secure.kyber.activities.AppIntroSliderActivity
import app.secure.kyber.backend.beans.MediaChunkDto
import app.secure.kyber.backend.beans.PrivateMessageTransportDto
import app.secure.kyber.backend.common.DisappearTime
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.fragments.SettingFragment
import app.secure.kyber.media.MediaChunkManager
import app.secure.kyber.media.MediaTransferNotifier
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.ContactRepository
import app.secure.kyber.roomdb.MessageDao
import app.secure.kyber.roomdb.MessageEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class UnionService : Service() {

    companion object {
        private const val TAG = "UnionService"
        private const val NOTIFICATION_ID = 1001
        private const val MSG_CHANNEL_ID = "union_messages_channel"
        private const val CHANNEL_ID = "union_service_channel"

        const val ACTION_START_SERVICE = "START_UNION_SERVICE"
        const val ACTION_STOP_SERVICE = "STOP_UNION_SERVICE"

        const val EXTRA_SERVER_HOST = "server_host"
        const val EXTRA_SERVER_PORT = "server_port"
    }

    private val binder = UnionBinder()
    private lateinit var unionClient: UnionClient
    private lateinit var messageDao: MessageDao
    private lateinit var contactRepo: ContactRepository
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val chunkMutexes = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    private lateinit var transferNotifier: MediaTransferNotifier

    @Inject
    lateinit var repository: KyberRepository

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val transportAdapter = moshi.adapter(PrivateMessageTransportDto::class.java)

    private val chunkAdapter = moshi.adapter(MediaChunkDto::class.java)

    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceRunning = false
    private var pollingJob: Job? = null

    private val unreadNotifications = mutableMapOf<String, MutableList<String>>()

    inner class UnionBinder : Binder() {
        fun getService(): UnionService = this@UnionService
        fun getUnionClient(): UnionClient = unionClient
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Union Service created")
        isServiceRunning = true

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kyber::SocketWakeLock")
        wakeLock?.acquire()

        val db = AppDb.get(applicationContext)
        messageDao = db.messageDao()
        contactRepo = ContactRepository(db.contactDao())

        unionClient = UnionClient()
        createNotificationChannels()
        transferNotifier = MediaTransferNotifier(this)

        unionClient.setMessageCallback { message ->
            handleSocketMessage(message)
        }
    }


    private fun startGlobalPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            val myId = Prefs.getOnionAddress(applicationContext) ?: ""
            if (myId.isNotEmpty()) {
                app.secure.kyber.GroupCreationBackend.GlobalGroupSync.startGlobalSync(applicationContext, myId)
            }
            
            refreshCircuitOnStart()
            var pollCount = 0
            while (isActive && isServiceRunning) {
                fetchGlobalMessages()
                
                if (pollCount % 20 == 0) {
                    fetchContactPublicKeysGlobally()
                }
                
                val delayMs = if (pollCount < 10) 1000L else 3000L
                pollCount++
                delay(delayMs)
            }
        }
    }

    private suspend fun refreshCircuitOnStart() {
        try {
            val existing = Prefs.getCircuitId(applicationContext)
            if (existing.isNullOrEmpty()) {
                val resp = repository.createCircuit()
                if (resp.isSuccessful) {
                    Prefs.setCircuitId(applicationContext, resp.body()?.circuitId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Circuit refresh on start failed: ${e.message}")
        }
    }

    private suspend fun refreshCircuitIfNeeded(forceNew: Boolean = false): String? {
        var circuitId = if (forceNew) null else Prefs.getCircuitId(applicationContext)
        if (circuitId.isNullOrEmpty()) {
            try {
                val resp = repository.createCircuit()
                if (resp.isSuccessful) {
                    circuitId = resp.body()?.circuitId
                    Prefs.setCircuitId(applicationContext, circuitId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Circuit creation failed: ${e.message}")
            }
        }
        return circuitId
    }

    private suspend fun fetchContactPublicKeysGlobally() {
        try {
            val allContacts = contactRepo.getAllOnce()
            for (contact in allContacts) {
                try {
                    val resp = repository.getPublicKey(contact.onionAddress)
                    if (resp.isSuccessful) {
                        val freshKey = resp.body()?.publicKey
                        if (!freshKey.isNullOrBlank() && freshKey != contact.publicKey) {
                            contactRepo.saveContact(
                                onionAddress = contact.onionAddress,
                                name = contact.name,
                                publicKey = freshKey
                            )
                            Log.d(TAG, "UnionService globally refreshed public key for contact ${contact.onionAddress}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "UnionService could not refresh key for ${contact.onionAddress}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Global contact key polling error", e)
        }
    }

    private suspend fun fetchGlobalMessages() {
        try {
            val myOnion = Prefs.getOnionAddress(applicationContext) ?: return
            var circuitId = refreshCircuitIfNeeded()

            val since = Prefs.getLastSyncTime(applicationContext)
                .takeIf { it > 0L }

            var response = repository.getMessages(myOnion, circuitId, since)

            if (!response.isSuccessful && response.code() == 400) {
                Log.w(TAG, "Circuit expired during poll, refreshing...")
                circuitId = refreshCircuitIfNeeded(forceNew = true)
                if (circuitId.isNullOrEmpty()) return
                response = repository.getMessages(myOnion, circuitId, since)
            }

            if (response.isSuccessful) {
                val messages = response.body()?.messages ?: emptyList()
                processApiMessages(messages)
                Prefs.setLastSyncTime(applicationContext, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Global polling error", e)
        }
    }

    private fun processApiMessages(messages: List<ApiMessage>) {
        if (messages.isEmpty()) return
        messages.forEach { apiMsg ->
            val decodedPayload = try {
                String(Base64.decode(apiMsg.payload, Base64.NO_WRAP), Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "Base64 decode failed for msg ${apiMsg.id}", e)
                return@forEach
            }

            try {
                val transport = transportAdapter.fromJson(decodedPayload)
                if (transport != null) {
                    serviceScope.launch {
                        try {
                            handleTransport(transport, apiMsg)
                        } catch (e: Exception) {
                            Log.e(TAG, "handleTransport failed for ${apiMsg.id}", e)
                        }
                    }
                } else {
                    serviceScope.launch {
                        try {
                            handleLegacyMessage(apiMsg, decodedPayload)
                        } catch (e: Exception) {
                            Log.e(TAG, "handleLegacyMessage failed for ${apiMsg.id}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                serviceScope.launch {
                    try {
                        handleLegacyMessage(apiMsg, decodedPayload)
                    } catch (ex: Exception) {
                        Log.e(TAG, "Legacy fallback failed for ${apiMsg.id}", ex)
                    }
                }
            }
        }
    }

    private suspend fun handleTransport(
        transport: PrivateMessageTransportDto,
        apiMsg: app.secure.kyber.backend.beans.ApiMessage?
    ) {
        if (transport.isAcceptance) {
            handleAcceptanceAck(transport)
            return
        }

        var contact = contactRepo.getContact(transport.senderOnion)
        var decryptedPayloadText = transport.msg
        var isDecryptionSuccessful = true
        
        if (!transport.iv.isNullOrBlank() && !transport.recipientKeyFingerprint.isNullOrBlank()) {
            try {
                var senderPublicKey = transport.senderPublicKey ?: contact?.publicKey
                
                if (senderPublicKey == null) {
                    senderPublicKey = applicationContext.getSharedPreferences("contact_name_cache", Context.MODE_PRIVATE)
                        .getString("pending_key_${transport.senderOnion}", null)
                }

                if (senderPublicKey == null) {
                    val resp = repository.getPublicKey(transport.senderOnion)
                    if (resp.isSuccessful && resp.body() != null) {
                        senderPublicKey = resp.body()!!.publicKey
                    }
                }

                // If we got a NEW public key in the transport, or a better name,
                // we should update the contact entry.
                if (transport.senderPublicKey != null) {
                    val currentName = contact?.name
                    val transportName = transport.senderName
                    
                    val betterName = if (!transportName.isNullOrBlank() && 
                        (currentName == null || currentName == "Unknown User" || currentName == transport.senderOnion)) {
                        transportName
                    } else {
                        currentName ?: transportName.ifBlank { transport.senderOnion }
                    }

                    if (contact != null) {
                        if (transport.senderPublicKey != contact.publicKey || betterName != contact.name) {
                            contactRepo.saveContact(
                                onionAddress = contact.onionAddress,
                                name = betterName,
                                publicKey = transport.senderPublicKey,
                                isContact = contact.isContact
                            )
                        }
                    } else {
                        cachePendingContactInfo(transport.senderOnion, transportName, transport.senderPublicKey!!)
                    }
                }
                
                if (senderPublicKey != null) {
                    decryptedPayloadText = MessageEncryptionManager.decryptMessage(
                        applicationContext,
                        senderPublicKey!!,
                        transport.recipientKeyFingerprint!!, //targeted MY fingerprint
                        transport.msg,
                        transport.iv
                    )
                } else {
                    isDecryptionSuccessful = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Decryption failed for message ${transport.messageId}", e)
                isDecryptionSuccessful = false
            }
        }

        if (transport.type == "CHUNK") {
            handleChunk(transport, apiMsg)
            return
        }

        if (contact == null && transport.senderName.isNotBlank()) {
            cachePendingContactName(transport.senderOnion, transport.senderName)
        }
        
        var effectiveType = transport.type ?: "TEXT"
        if (transport.type == "WIPE_RESPONSE" && parseWipeResponseAction(decryptedPayloadText) == "ACCEPTED") {
            val now = System.currentTimeMillis().toString()
            messageDao.expireAllBySender(transport.senderOnion, now)
            decryptedPayloadText = "Chat history cleared on ${formatWipeTimestamp(System.currentTimeMillis())}"
            effectiveType = "WIPE_SYSTEM"
        }

        if (transport.type == "REMOTE_WIPE_REQUEST") {
            handleRemoteWipeRequest(decryptedPayloadText, transport.senderOnion)
            return
        }

        if (transport.type == "REMOTE_WIPE_ACK") {
            handleRemoteWipeAck(decryptedPayloadText)
            return
        }

        if (transport.type == "SEEN_RECEIPT") {
            Log.d(TAG, "RECEIVE SEEN_RECEIPT from ${transport.senderOnion} for all messages")
            messageDao.markAllSeenForContact(transport.senderOnion, System.currentTimeMillis())
            return
        }
        if (transport.type == "DELIVERED_RECEIPT") {
            Log.d(TAG, "RECEIVE DELIVERED_RECEIPT from ${transport.senderOnion} for msg ${transport.messageId}")
            // Update the specific message that was delivered
            messageDao.updateDeliveredAt(transport.messageId, System.currentTimeMillis())
            // Fallback: also mark all older ones as delivered to be safe
            messageDao.markAllDeliveredForContact(transport.senderOnion, System.currentTimeMillis())
            return
        }

        // Always send a Delivered Receipt when a message arrives via socket.
        // Optimization: Use the sender's public key provided in the transport DTO 
        // to send the receipt instantly, bypassing slow API lookups.
        if (transport.type != "DELIVERED_RECEIPT" && transport.type != "SEEN_RECEIPT") {
            // Robustness: If we are receiving a message from this person, they are clearly online.
            // Mark all our previously sent messages to them as delivered.
            messageDao.markAllDeliveredForContact(transport.senderOnion, System.currentTimeMillis())

            Log.d(TAG, "SEND DELIVERED_RECEIPT to ${transport.senderOnion} for msg ${transport.messageId}")
            sendReceipt(transport.senderOnion, transport.messageId, "DELIVERED_RECEIPT", transport.senderPublicKey)
        }

        val existing = messageDao.getMessageByMessageId(transport.messageId)
        if (existing == null) {
            val encryptedUri = if (!transport.uri.isNullOrBlank())
                MessageEncryptionManager.encryptLocal(applicationContext, transport.uri).encryptedBlob
            else null

            val sentMs = DisappearTime.parseMessageTimestampMs(transport.timestamp)
            val localExpiresAt = DisappearTime.expiresAtFromSent(sentMs, transport.disappear_ttl)

            val entity = MessageEntity(
                messageId = transport.messageId,
                msg = if (isDecryptionSuccessful) MessageEncryptionManager.encryptLocal(applicationContext, decryptedPayloadText).encryptedBlob else transport.msg,
                senderOnion = transport.senderOnion,
                time = sentMs.toString(),
                isSent = false,
                type = effectiveType,
                uri = encryptedUri,
                ampsJson = if (transport.ampsJson.isNotBlank())
                    MessageEncryptionManager.encryptLocal(applicationContext, transport.ampsJson).encryptedBlob else "",
                apiMessageId = apiMsg?.id ?: "",
                reaction = transport.reaction ?: "",
                isRequest = transport.isRequest,
                keyFingerprint = transport.recipientKeyFingerprint, // Targeted my key
                iv = transport.iv,
                expiresAt = localExpiresAt,
                replyToText = transport.replyToText
            )
            messageDao.insert(entity)



            val isAccepted = contactRepo.getContact(transport.senderOnion) != null
            if (transport.isRequest || !isAccepted) {
                val msgCount = messageDao.getBySender(transport.senderOnion).size
                if (msgCount == 1) {
                    showPushNotification(transport.senderOnion, "Someone", "New message request")
                }
            } else {
                val displayMsg = generateDisplayMessage(decryptedPayloadText, effectiveType)
                showPushNotification(transport.senderOnion, "contact", displayMsg)
            }

        } else {
            if (existing.reaction != transport.reaction) {
                existing.reaction = transport.reaction ?: ""
                val isRemoval =
                    transport.reaction.isNullOrEmpty() || transport.reaction.endsWith("|")
                existing.updatedAt = if (isRemoval) "" else System.currentTimeMillis().toString()
                messageDao.update(existing)

                if (!isRemoval && existing.reaction.isNotBlank()) {
                    val contactForReaction = contactRepo.getContact(transport.senderOnion)
                    val senderName = contactForReaction?.name ?: "Unknown User"
                    val actualEmoji = existing.reaction.substringAfter("|")
                    val notificationMsg = when (existing.type.uppercase(java.util.Locale.US)) {
                        "IMAGE" -> "Reacted $actualEmoji to a photo"
                        "VIDEO" -> "Reacted $actualEmoji to a video"
                        "AUDIO" -> "Reacted $actualEmoji to a voice message"
                        else -> {
                            val decryptedOriginalMsg = MessageEncryptionManager.decryptLocal(applicationContext, existing.msg)
                            val preview =
                                if (decryptedOriginalMsg.length > 30) decryptedOriginalMsg.take(27) + "..." else decryptedOriginalMsg
                            "Reacted $actualEmoji to: \"$preview\""
                        }
                    }
                    showPushNotification(transport.senderOnion, "reaction", "Someone reacted to your message")
                }
            }
        }
    }

    private suspend fun handleAcceptanceAck(transport: PrivateMessageTransportDto) {
        val existing = contactRepo.getContact(transport.senderOnion)
        val name = transport.senderName.ifBlank { existing?.name ?: "Unknown User" }
        var pk: String? = transport.senderPublicKey ?: existing?.publicKey
        
        if (pk == null) {
            try {
                val resp = repository.getPublicKey(transport.senderOnion)
                if (resp.isSuccessful) pk = resp.body()?.publicKey
            } catch (e: Exception) {}
        }
        
        // Always save to update name, public key, and set isContact = true
        contactRepo.saveContact(
            onionAddress = transport.senderOnion,
            name = name,
            publicKey = pk,
            isContact = true
        )
    }

    private fun sendReceipt(senderOnion: String, messageId: String, type: String, providedPublicKey: String? = null) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val ctx = applicationContext
                val myOnion = Prefs.getOnionAddress(ctx) ?: return@launch
                val myName = Prefs.getName(ctx) ?: ""
                
                // Priority 1: Use the public key provided in the transport (if available)
                var recipientPublicKey: String? = providedPublicKey
                
                // Priority 2: Keep the necessary API call as requested
                if (recipientPublicKey == null) {
                    try {
                        val pubKeyResp = repository.getPublicKey(senderOnion)
                        if (pubKeyResp.isSuccessful) {
                            recipientPublicKey = pubKeyResp.body()?.publicKey
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "getPublicKey API failed for $type, attempting local fallback")
                    }
                }
                
                // Priority 3: Fallback to local DB
                if (recipientPublicKey == null) {
                    recipientPublicKey = contactRepo.getContact(senderOnion)?.publicKey
                }
                
                if (recipientPublicKey == null) {
                    Log.e(TAG, "No public key available for $senderOnion, cannot send $type")
                    return@launch
                }
                
                val enc = app.secure.kyber.Utils.MessageEncryptionManager.encryptMessage(ctx, recipientPublicKey!!, "")
                
                val transport = PrivateMessageTransportDto(
                    messageId = messageId, // Use the original message ID for the receipt
                    msg = enc.encryptedPayload,
                    senderOnion = myOnion,
                    senderName = myName,
                    timestamp = System.currentTimeMillis().toString(),
                    type = type,
                    iv = enc.iv,
                    senderKeyFingerprint = enc.senderKeyFingerprint,
                    recipientKeyFingerprint = enc.recipientKeyFingerprint,
                    senderPublicKey = enc.senderPublicKeyBase64
                )
                
                val json = transportAdapter.toJson(transport)
                val base64 = android.util.Base64.encodeToString(json.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                
                var sentViaSocket = false
                try {
                    val result = unionClient.sendMessage(senderOnion, base64)
                    if (result.isSuccess) {
                        sentViaSocket = true
                        Log.d(TAG, "SUCCESS dispatch $type via SOCKET to $senderOnion")
                    } else {
                        Log.w(TAG, "FAILED dispatch $type via SOCKET to $senderOnion: ${result.exceptionOrNull()?.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "ERROR dispatch $type via SOCKET", e)
                }
                
                if (!sentViaSocket) {
                    var circuit = Prefs.getCircuitId(ctx) ?: ""
                    if (circuit.isEmpty()) {
                        val r = repository.createCircuit()
                        if (r.isSuccessful) { circuit = r.body()?.circuitId ?: ""; Prefs.setCircuitId(ctx, circuit) }
                    }
                    if (circuit.isNotEmpty()) {
                        val resp = repository.sendMessage(senderOnion, base64, circuit)
                        if (resp.isSuccessful) {
                            Log.d(TAG, "SUCCESS dispatch $type via API to $senderOnion")
                        } else {
                            Log.e(TAG, "FAILED dispatch $type via API to $senderOnion (code: ${resp.code()})")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "FATAL error in sendReceipt for $type", e)
            }
        }
    }

    private fun cachePendingContactName(senderOnion: String, senderName: String) {
        applicationContext.getSharedPreferences("contact_name_cache", Context.MODE_PRIVATE)
            .edit()
            .putString("pending_name_$senderOnion", senderName)
            .apply()
    }

    private fun cachePendingContactInfo(senderOnion: String, senderName: String, publicKey: String) {
        applicationContext.getSharedPreferences("contact_name_cache", Context.MODE_PRIVATE)
            .edit()
            .putString("pending_name_$senderOnion", senderName)
            .putString("pending_key_$senderOnion", publicKey)
            .apply()
    }

    private suspend fun handleLegacyMessage(
        apiMsg: app.secure.kyber.backend.beans.ApiMessage,
        decodedPayload: String
    ) {
        val senderOnion = apiMsg.senderOnion ?: return
        if (senderOnion.isEmpty()) return

        var msgText = decodedPayload
        var msgType = "TEXT"
        var msgUri: String? = null

        when {
            decodedPayload.startsWith("[IMAGE] ") -> {
                msgType = "IMAGE"
                msgUri = decodedPayload.removePrefix("[IMAGE] ")
                msgText = "photo"
            }

            decodedPayload.startsWith("[VIDEO] ") -> {
                msgType = "VIDEO"
                msgUri = decodedPayload.removePrefix("[VIDEO] ")
                msgText = "video"
            }

            decodedPayload.startsWith("[AUDIO] ") -> {
                msgType = "AUDIO"
                msgUri = decodedPayload.removePrefix("[AUDIO] ")
                msgText = "Voice Message"
            }
        }

        val encryptedUri = if (!msgUri.isNullOrBlank()) MessageEncryptionManager.encryptLocal(applicationContext, msgUri).encryptedBlob else null

        val entity = MessageEntity(
            messageId = UUID.randomUUID().toString(),
            msg = MessageEncryptionManager.encryptLocal(applicationContext, msgText).encryptedBlob,
            senderOnion = senderOnion,
            time = System.currentTimeMillis().toString(),
            isSent = false,
            type = msgType,
            uri = encryptedUri,
            apiMessageId = apiMsg.id
        )
        try {
            messageDao.insert(entity)
            // Legacy path: send a receipt to trigger delivered status on the other side
            sendReceipt(senderOnion, entity.messageId, "DELIVERED_RECEIPT")
            val displayMsg = generateDisplayMessage(msgText, msgType)
            showPushNotification(senderOnion, "legacy", displayMsg)
        } catch (e: Exception) {}
    }

    private fun handleSocketMessage(message: UnionClient.UnionMessage) {
        serviceScope.launch {
            try {
                val decodedPayload = try {
                    String(Base64.decode(message.content, Base64.NO_WRAP), Charsets.UTF_8)
                } catch (e: Exception) {
                    message.content
                }

                try {
                    val transport = transportAdapter.fromJson(decodedPayload)
                    if (transport != null) {
                        handleTransport(transport, null)
                    } else {
                        handleLegacySocketMessage(message, decodedPayload)
                    }
                } catch (e: Exception) {
                    handleLegacySocketMessage(message, decodedPayload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing global socket message", e)
            }
        }
    }

    private suspend fun handleLegacySocketMessage(
        message: UnionClient.UnionMessage,
        decodedPayload: String
    ) {
        val entity = MessageEntity(
            messageId = UUID.randomUUID().toString(),
            msg = MessageEncryptionManager.encryptLocal(applicationContext, decodedPayload).encryptedBlob,
            senderOnion = message.from,
            time = System.currentTimeMillis().toString(),
            isSent = false,
            type = "TEXT",
            apiMessageId = ""
        )
        messageDao.insert(entity)
        // Legacy path: send a receipt to trigger delivered status on the other side
        sendReceipt(message.from, entity.messageId, "DELIVERED_RECEIPT")
        val contact = contactRepo.getContact(message.from)
        val name = contact?.name ?: "Unknown User"
        showPushNotification(message.from, name, generateDisplayMessage(decodedPayload, "TEXT"))
    }

    private fun generateDisplayMessage(text: String, type: String): String {
        return when (type.uppercase()) {
            "IMAGE" -> "Photo"
            "VIDEO" -> "Video"
            "AUDIO" -> "Voice message"
            else -> "Text message"
        }
    }

    private fun parseWipeResponseAction(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            runCatching {
                return org.json.JSONObject(trimmed).optString("action", "").uppercase(Locale.US)
            }
        }
        return trimmed.uppercase(Locale.US)
    }

    // REMOTE WIPE — Target-side: receive and execute

    private fun handleRemoteWipeRequest(payload: String, senderOnion: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val json             = JSONObject(payload)
                val incomingHash     = json.optString("wipePasswordHash")
                val requestId        = json.optString("requestId")
                val initiatorOnion   = json.optString("initiatorOnion")

                val localWipePwd = Prefs.getWipePassword(applicationContext)
                if (localWipePwd.isNullOrEmpty()) {
                    sendRemoteWipeAck(initiatorOnion, requestId, "NO_WIPE_PASSWORD")
                    return@launch
                }

                val localHash = sha256Remote(localWipePwd)
                if (incomingHash != localHash) {
                    sendRemoteWipeAck(initiatorOnion, requestId, "WRONG_PASSWORD")
                    return@launch
                }

                // Password matches — send ACK first, then silently wipe after 5s
                sendRemoteWipeAck(initiatorOnion, requestId, "SUCCESS")
                delay(5_000L)
                performSilentRemoteWipe()

            } catch (e: Exception) {
                Log.e(TAG, "handleRemoteWipeRequest failed", e)
            }
        }
    }

    private suspend fun sendRemoteWipeAck(initiatorOnion: String, requestId: String, status: String) {
        try {
            val ctx      = applicationContext
            val myOnion  = Prefs.getOnionAddress(ctx) ?: return
            val myName   = Prefs.getName(ctx) ?: ""

            val payload = JSONObject()
                .put("action", "REMOTE_WIPE_ACK")
                .put("status", status)
                .put("requestId", requestId)
                .toString()

            val pubKeyResp = repository.getPublicKey(initiatorOnion)
            if (!pubKeyResp.isSuccessful || pubKeyResp.body() == null) return
            val recipientPublicKey = pubKeyResp.body()!!.publicKey

            val enc = app.secure.kyber.Utils.MessageEncryptionManager.encryptMessage(ctx, recipientPublicKey, payload)

            val moshi = com.squareup.moshi.Moshi.Builder()
                .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory()).build()
            val adapter = moshi.adapter(app.secure.kyber.backend.beans.PrivateMessageTransportDto::class.java)
            val transport = app.secure.kyber.backend.beans.PrivateMessageTransportDto(
                messageId = java.util.UUID.randomUUID().toString(),
                msg = enc.encryptedPayload,
                senderOnion = myOnion,
                senderName = myName,
                timestamp = System.currentTimeMillis().toString(),
                type = "REMOTE_WIPE_ACK",
                iv = enc.iv,
                senderKeyFingerprint = enc.senderKeyFingerprint,
                recipientKeyFingerprint = enc.recipientKeyFingerprint,
                senderPublicKey = enc.senderPublicKeyBase64
            )

            val json   = adapter.toJson(transport)
            val base64 = android.util.Base64.encodeToString(json.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            var circuit = Prefs.getCircuitId(ctx) ?: ""
            if (circuit.isEmpty()) {
                val r = repository.createCircuit()
                if (r.isSuccessful) { circuit = r.body()?.circuitId ?: ""; Prefs.setCircuitId(ctx, circuit) }
            }
            if (circuit.isNotEmpty()) repository.sendMessage(initiatorOnion, base64, circuit)
        } catch (e: Exception) {
            Log.e(TAG, "sendRemoteWipeAck failed", e)
        }
    }

    private fun performSilentRemoteWipe() {
        try {
            AppDb.get(applicationContext).clearAllTables()
        } catch (e: Exception) { Log.e(TAG, "Remote wipe: DB clear failed", e) }
        try { deleteRecursiveRemote(applicationContext.filesDir) } catch (e: Exception) {}
        try { deleteRecursiveRemote(applicationContext.cacheDir) } catch (e: Exception) {}
        try { applicationContext.externalCacheDir?.let { deleteRecursiveRemote(it) } } catch (e: Exception) {}
        try {
            applicationContext.getSharedPreferences("kyber_prefs", Context.MODE_PRIVATE).edit().clear().commit()
            applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().clear().commit()
        } catch (e: Exception) {}

        try {
            val intent = Intent(applicationContext, AppIntroSliderActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            applicationContext.startActivity(intent)
        } catch (e: Exception) { Log.e(TAG, "Remote wipe: navigation failed", e) }
    }

    private fun deleteRecursiveRemote(fileOrDir: File) {
        if (fileOrDir.isDirectory) fileOrDir.listFiles()?.forEach { deleteRecursiveRemote(it) }
        fileOrDir.delete()
    }

    private fun sha256Remote(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // REMOTE WIPE — Initiator-side: receive confirmation ACK

    private fun handleRemoteWipeAck(payload: String) {
        try {
            val json      = JSONObject(payload)
            val status    = json.optString("status", "UNKNOWN")
            val requestId = json.optString("requestId")

            val pendingId = SettingFragment.pendingWipeRequestId
            val callback  = SettingFragment.onWipeAckReceived

            if (pendingId != null && pendingId == requestId && callback != null) {
                // Dialog is open — deliver result on main thread
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    callback.invoke(status)
                    SettingFragment.onWipeAckReceived = null
                    SettingFragment.pendingWipeRequestId = null
                }
            } else {
                // Dialog dismissed or app in background — show push notification
                showRemoteWipeResultNotification(status)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleRemoteWipeAck failed", e)
        }
    }

    private fun showRemoteWipeResultNotification(status: String) {
        val (title, body) = when (status) {
            "SUCCESS"          -> Pair("Remote Wipe Successful", "Target device app data has been wiped.")
            "NO_WIPE_PASSWORD" -> Pair("Wipe Failed", "Target user has not set a wipe password.")
            "WRONG_PASSWORD"   -> Pair("Wipe Failed", "Incorrect wipe password for the target user.")
            else               -> Pair("Wipe Failed", "An unknown error occurred.")
        }
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            val pi = PendingIntent.getActivity(this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notif = NotificationCompat.Builder(this, MSG_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(R.drawable.notification)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify("remote_wipe_ack".hashCode(), notif)
        } catch (e: Exception) {
            Log.e(TAG, "showRemoteWipeResultNotification failed", e)
        }
    }

    private fun formatWipeTimestamp(ts: Long): String {
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
                now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
        return if (sameDay) {
            java.text.SimpleDateFormat("hh:mm a", Locale.getDefault()).format(java.util.Date(ts))
        } else {
            java.text.SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(java.util.Date(ts))
        }
    }

    private fun showPushNotification(sender: String, title: String, messageText: String) {
        try {
            val myApp = applicationContext as app.secure.kyber.MyApp.MyApp
            if (myApp.activeChatOnion == sender) {
                unreadNotifications.remove(sender)
                return 
            }
        } catch (e: Exception) {}

        val unreadList = unreadNotifications.getOrPut(sender) { mutableListOf() }
        unreadList.add(messageText)

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val secureTitle = "You have a new message"
        val secureBody = if (unreadList.size > 1) "${unreadList.size} new messages" else messageText

        val inboxStyle = NotificationCompat.InboxStyle()
            .setBigContentTitle(secureTitle)

        unreadList.takeLast(6).forEach { msg ->
            inboxStyle.addLine(msg)
        }
        if (unreadList.size > 6) {
            inboxStyle.setSummaryText("+${unreadList.size - 6} more")
        } else if (unreadList.size > 1) {
            inboxStyle.setSummaryText("${unreadList.size} new messages")
        }

        val notification = NotificationCompat.Builder(this, MSG_CHANNEL_ID)
            .setContentTitle(secureTitle)
            .setContentText(secureBody)
            .setStyle(inboxStyle)
            .setSmallIcon(R.drawable.notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        notificationManager.notify(sender.hashCode(), notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand: action = $action")

        if (action == "CLEAR_NOTIFICATIONS") {
            val sender = intent.getStringExtra("sender_onion")
            if (sender != null) {
                unreadNotifications.remove(sender)
            }
            return START_STICKY
        }

        startForegroundServiceNotification()

        val serverHost = intent?.getStringExtra(EXTRA_SERVER_HOST) ?: "82.221.100.220"
        val serverPort = intent?.getIntExtra(EXTRA_SERVER_PORT, 8080) ?: 8080

        val myRealOnion = Prefs.getOnionAddress(applicationContext)
        if (!myRealOnion.isNullOrEmpty()) {
            unionClient.setClientIdentity(myRealOnion)
            Log.d(TAG, "Injected real identity: $myRealOnion")
        }

        if (action == ACTION_START_SERVICE) {
             startPersistentConnectionLoop(serverHost, serverPort)
             startGlobalPolling()
             serviceScope.launch { performColdStartBackfill() }
        } else {
             // Default start behavior for sticky restarts
             startPersistentConnectionLoop(serverHost, serverPort)
             startGlobalPolling()
        }

        return START_STICKY
    }

    private fun startPersistentConnectionLoop(host: String, port: Int) {
        serviceScope.launch {
            while (isServiceRunning) {
                val state = unionClient.connectionState.value
                if (state == UnionClient.ConnectionState.DISCONNECTED || state == UnionClient.ConnectionState.ERROR) {
                    unionClient.connect(host, port)
                }
                delay(8000)
            }
        }
    }

    @SuppressLint("ForegroundServiceType")
    private fun startForegroundServiceNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kyber Background Sync")
            .setContentText("Listening for secure messages...")
            .setSmallIcon(R.drawable.notification)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            val downloadChannel = NotificationChannel(
                MediaTransferNotifier.DOWNLOAD_CHANNEL_ID,
                "Media Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }

            val uploadChannel = NotificationChannel(
                MediaTransferNotifier.UPLOAD_CHANNEL_ID,
                "Media Uploads",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }

            val messageChannel = NotificationChannel(
                MSG_CHANNEL_ID,
                "Message Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { setShowBadge(true) }

            notificationManager.createNotificationChannel(downloadChannel)
            notificationManager.createNotificationChannel(uploadChannel)
            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(messageChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        pollingJob?.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        serviceScope.launch {
            unionClient.disconnect()
            unionClient.cleanup()
        }
        serviceScope.cancel()
    }

    private suspend fun performColdStartBackfill() {
        try {
            val myOnion = Prefs.getOnionAddress(applicationContext) ?: return
            Log.d(TAG, "Cold-start backfill starting...")
            val circuitId = refreshCircuitIfNeeded(forceNew = true) ?: return
            val response = repository.getMessages(myOnion, circuitId, since = null)
            if (response.isSuccessful) {
                val messages = response.body()?.messages ?: emptyList()
                Log.d(TAG, "Cold-start backfill: ${messages.size} messages from server")
                processApiMessages(messages)
                Prefs.setLastSyncTime(applicationContext, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cold-start backfill error", e)
        }
    }

    private suspend fun handleChunk(
        transport: PrivateMessageTransportDto,
        apiMsg: app.secure.kyber.backend.beans.ApiMessage?
    ) {
        try {
            var chunkPayload = transport.msg // In new system, payload is in 'msg' if encrypted
            
            // Decrypt chunk payload if encrypted
            if (!transport.iv.isNullOrBlank() && !transport.recipientKeyFingerprint.isNullOrBlank()) {
                val contact = contactRepo.getContact(transport.senderOnion)
                var senderPublicKey = transport.senderPublicKey ?: contact?.publicKey ?: applicationContext.getSharedPreferences("contact_name_cache", Context.MODE_PRIVATE)
                    .getString("pending_key_${transport.senderOnion}", null)
                
                if (senderPublicKey == null) {
                    val resp = repository.getPublicKey(transport.senderOnion)
                    if (resp.isSuccessful) senderPublicKey = resp.body()?.publicKey
                }

                if (senderPublicKey != null) {
                    chunkPayload = MessageEncryptionManager.decryptMessage(
                        applicationContext,
                        senderPublicKey!!,
                        transport.recipientKeyFingerprint!!,
                        transport.msg,
                        transport.iv
                    )
                }
            } else if (transport.uri != null) {
                chunkPayload = transport.uri!! // Fallback for legacy chunks
            }

            val chunk = chunkAdapter.fromJson(chunkPayload) ?: return
            val mediaId = chunk.mediaId
            getChunkMutex(mediaId).withLock {
                handleChunkLocked(chunk, transport, apiMsg)
            }
            val entity = messageDao.getByRemoteMediaId(mediaId)
            if (entity?.downloadState == "done" || entity?.downloadState == "failed") {
                chunkMutexes.remove(mediaId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleChunk error", e)
        }
    }

    private suspend fun handleChunkLocked(
        chunk: MediaChunkDto,
        transport: PrivateMessageTransportDto,
        apiMsg: app.secure.kyber.backend.beans.ApiMessage?
    ) {
        val mediaId = chunk.mediaId
        val originalMessageId = chunk.messageId
        val chunkDir = java.io.File(applicationContext.cacheDir, "chunks_$mediaId")
        val chunkFile = java.io.File(chunkDir, "chunk_${chunk.index.toString().padStart(6, '0')}")
        if (chunkFile.exists() && chunkFile.length() > 0) return
        MediaChunkManager.saveChunkToDisk(applicationContext, mediaId, chunk.index, chunk.data)
        val savedCount = MediaChunkManager.countSavedChunks(applicationContext, mediaId)
        val progress = (savedCount * 100) / chunk.total

        if (chunk.index == 0) {
            val existing = messageDao.getByRemoteMediaId(mediaId)
            if (existing == null) {
                val sentMs = DisappearTime.parseMessageTimestampMs(transport.timestamp)
                val localExpiresAt = DisappearTime.expiresAtFromSent(sentMs, transport.disappear_ttl)

                val entity = MessageEntity(
                    messageId = originalMessageId,
                    apiMessageId = apiMsg?.id,
                    msg = MessageEncryptionManager.encryptLocal(applicationContext, chunk.caption.ifBlank {
                        when (chunk.mimeType) {
                            "AUDIO" -> "Voice Message"
                            "VIDEO" -> "video"
                            else -> "photo"
                        }
                    }).encryptedBlob,
                    senderOnion = transport.senderOnion,
                    time = sentMs.toString(),
                    isSent = false,
                    type = chunk.mimeType,
                    uri = null,
                    ampsJson = if (chunk.ampsJson.isNotBlank())
                        MessageEncryptionManager.encryptLocal(applicationContext, chunk.ampsJson).encryptedBlob else "",
                    downloadState = "downloading",
                    downloadProgress = progress,
                    remoteMediaId = mediaId,
                    mediaDurationMs = chunk.durationMs,
                    mediaSizeBytes = chunk.totalBytes,
                    isRequest = transport.isRequest,
                    keyFingerprint = transport.recipientKeyFingerprint,
                    iv = transport.iv,
                    replyToText = transport.replyToText,
                    expiresAt = localExpiresAt
                )
                messageDao.insert(entity)
                val isAccepted = contactRepo.getContact(transport.senderOnion) != null
                if (isAccepted) {
                    val name = contactRepo.getContact(transport.senderOnion)?.name ?: "Unknown"
                    val displayMsg = when (chunk.mimeType) {
                        "AUDIO" -> "Voice message"
                        "VIDEO" -> "Video"
                        else -> "Photo"
                    }
                    showPushNotification(transport.senderOnion, "media", displayMsg)
                } else {
                    showPushNotification(transport.senderOnion, "Someone", "New message request")
                }
            }

            // Media Delivery Receipt: Send ACK for the original message ID when the first chunk arrives
            Log.d(TAG, "SEND MEDIA DELIVERED_RECEIPT to ${transport.senderOnion} for msg ${chunk.messageId}")
            sendReceipt(transport.senderOnion, chunk.messageId, "DELIVERED_RECEIPT", transport.senderPublicKey)
        } else {
            val existing = messageDao.getByRemoteMediaId(mediaId)
            if (existing != null) {
                messageDao.updateDownloadProgress(existing.messageId, "downloading", progress)
            }
        }

        if (savedCount >= chunk.total) {
            val entity = messageDao.getByRemoteMediaId(mediaId)
            if (entity != null) {
                messageDao.updateDownloadProgress(entity.messageId, "downloading", progress)
                val myApp = try { applicationContext as app.secure.kyber.MyApp.MyApp } catch (e: Exception) { null }
                val isInChat = myApp?.activeChatOnion == entity.senderOnion
                if (!isInChat && progress % 10 == 0) {
                    transferNotifier.showDownloadProgress(entity.messageId, chunk.mimeType, progress)
                }
                val assembledPath = MediaChunkManager.assembleChunks(applicationContext, mediaId, chunk.mimeType) { p ->
                    serviceScope.launch {
                        messageDao.updateDownloadProgress(entity.messageId, "downloading", progress)
                        transferNotifier.showDownloadProgress(entity.messageId, chunk.mimeType, progress)
                    }
                }
                if (assembledPath != null) {
                    val updated = entity.copy(
                        uri = MessageEncryptionManager.encryptLocal(applicationContext, assembledPath).encryptedBlob,
                        downloadState = "done",
                        downloadProgress = 100,
                        localFilePath = assembledPath.removePrefix("file://")
                    )
                    messageDao.update(updated)
                    val myApp2 = try { applicationContext as app.secure.kyber.MyApp.MyApp } catch (e: Exception) { null }
                    if (myApp2?.activeChatOnion != entity.senderOnion) {
                        transferNotifier.showDownloadComplete(entity.messageId, chunk.mimeType)
                    } else {
                        transferNotifier.cancel(entity.messageId)
                    }
                } else {
                    messageDao.updateDownloadProgress(entity.messageId, "failed", 0)
                    val myApp3 = try { applicationContext as app.secure.kyber.MyApp.MyApp } catch (e: Exception) { null }
                    if (myApp3?.activeChatOnion != entity.senderOnion) {
                        transferNotifier.showDownloadFailed(entity.messageId, chunk.mimeType)
                    }
                }
            }
        }
    }

    private fun getChunkMutex(mediaId: String): kotlinx.coroutines.sync.Mutex =
        chunkMutexes.getOrPut(mediaId) { kotlinx.coroutines.sync.Mutex() }

}
