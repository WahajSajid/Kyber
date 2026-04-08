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
import app.secure.kyber.backend.beans.MediaChunkDto
import app.secure.kyber.backend.beans.PrivateMessageTransportDto
import app.secure.kyber.backend.common.DisappearTime
import app.secure.kyber.backend.common.Prefs
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

        startGlobalPolling()
        serviceScope.launch { performColdStartBackfill() }
    }

    private fun startGlobalPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            val myOnion = Prefs.getOnionAddress(applicationContext) ?: ""
            if (myOnion.isNotEmpty()) {
                app.secure.kyber.GroupCreationBackend.GlobalGroupSync.startGlobalSync(applicationContext, myOnion)
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

                // If we got a NEW public key in the transport, and this is a trusted contact,
                // we should update their key to ensure future messages work.
                if (transport.senderPublicKey != null) {
                    if (contact != null && transport.senderPublicKey != contact.publicKey) {
                        contactRepo.saveContact(contact.onionAddress, contact.name, transport.senderPublicKey)
                    } else if (contact == null) {
                        cachePendingContactInfo(transport.senderOnion, transport.senderName, transport.senderPublicKey!!)
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
                type = transport.type ?: "TEXT",
                uri = encryptedUri,
                ampsJson = if (transport.ampsJson.isNotBlank())
                    MessageEncryptionManager.encryptLocal(applicationContext, transport.ampsJson).encryptedBlob else "",
                apiMessageId = apiMsg?.id ?: "",
                reaction = transport.reaction ?: "",
                isRequest = transport.isRequest,
                keyFingerprint = transport.recipientKeyFingerprint, // Targeted my key
                iv = transport.iv,
                expiresAt = localExpiresAt
            )
            messageDao.insert(entity)

            val isAccepted = contactRepo.getContact(transport.senderOnion) != null
            if (transport.isRequest || !isAccepted) {
                val msgCount = messageDao.getBySender(transport.senderOnion).size
                if (msgCount == 1) {
                    showPushNotification(transport.senderOnion, "Someone", "New message request")
                }
            } else {
                val displayMsg = generateDisplayMessage(decryptedPayloadText, transport.type ?: "TEXT")
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
        if (existing == null) {
            val name = transport.senderName.ifBlank { transport.senderOnion }
            var pk: String? = transport.senderPublicKey
            if (pk == null) {
                try {
                    val resp = repository.getPublicKey(transport.senderOnion)
                    if (resp.isSuccessful) pk = resp.body()?.publicKey
                } catch (e: Exception) {}
            }
            
            contactRepo.saveContact(onionAddress = transport.senderOnion, name = name, publicKey = pk)
        } else if (transport.senderPublicKey != null && transport.senderPublicKey != existing.publicKey) {
            contactRepo.saveContact(existing.onionAddress, existing.name, transport.senderPublicKey)
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
        if (intent?.action == "CLEAR_NOTIFICATIONS") {
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

        startPersistentConnectionLoop(serverHost, serverPort)

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
                    time = System.currentTimeMillis().toString(),
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
                    iv = transport.iv
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
                    val msgCount = messageDao.getBySender(transport.senderOnion).size
                    if (msgCount == 1) {
                        showPushNotification(transport.senderOnion, "Someone", "New message request")
                    }
                }
            }
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
