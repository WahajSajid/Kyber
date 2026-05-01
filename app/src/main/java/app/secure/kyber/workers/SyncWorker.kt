package app.secure.kyber.workers

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.secure.kyber.Utils.MessageEncryptionManager
import app.secure.kyber.activities.AppIntroSliderActivity
import app.secure.kyber.backend.beans.PrivateMessageTransportDto
import app.secure.kyber.backend.common.DisappearTime
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.fragments.SettingFragment
import app.secure.kyber.onionrouting.UnionService
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.ContactRepository
import app.secure.kyber.roomdb.MessageEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Periodic WorkManager worker that runs even when the app process is dead.
 * Fetches missed messages and stores them in the local DB.
 * Serves as the fallback for when UnionService has been killed.
 */
class SyncWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "SyncWorker"
        const val WORK_NAME = "kyber_background_sync"
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val transportAdapter = moshi.adapter(PrivateMessageTransportDto::class.java)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val myOnion = Prefs.getOnionAddress(context)
            if (myOnion.isNullOrEmpty()) {
                Log.d(TAG, "No onion address, skipping sync")
                return@withContext Result.success()
            }

            Log.d(TAG, "SyncWorker running for $myOnion")

            // Get repository via Hilt entry point
            val entryPoint = EntryPointAccessors.fromApplication(
                context.applicationContext,
                SyncWorkerEntryPoint::class.java
            )
            val repository = entryPoint.repository()

            val db = AppDb.get(context)
            val messageDao = db.messageDao()
            val contactRepo = ContactRepository(db.contactDao())

            // Get or create circuit
            var circuitId = Prefs.getCircuitId(context)
            if (circuitId.isNullOrEmpty()) {
                val r = repository.createCircuit()
                if (r.isSuccessful) {
                    circuitId = r.body()?.circuitId ?: ""
                    Prefs.setCircuitId(context, circuitId)
                }
            }
            if (circuitId.isNullOrEmpty()) return@withContext Result.retry()


            


            // Fetch all available messages (no since filter — DB dedup handles duplicates)
            val response = repository.getMessages(myOnion, circuitId, since = null)
            if (!response.isSuccessful) {
                // Try with new circuit
                val retry = repository.createCircuit()
                if (retry.isSuccessful) {
                    circuitId = retry.body()?.circuitId ?: ""
                    Prefs.setCircuitId(context, circuitId)
                }
                return@withContext Result.retry()
            }

            val messages = response.body()?.messages ?: emptyList()
            Log.d(TAG, "SyncWorker fetched ${messages.size} messages")

            var newMessageCount = 0
            messages.forEach { apiMsg ->
                try {
                    val decoded = String(
                        Base64.decode(apiMsg.payload, Base64.NO_WRAP),
                        Charsets.UTF_8
                    )
                    val transport = try {
                        transportAdapter.fromJson(decoded)
                    } catch (e: Exception) { null }

                    if (transport == null || transport.isAcceptance || transport.type == "CHUNK") {
                        return@forEach
                    }

                    // ── SECURITY: Strict type whitelist ─────────────────────────────────
                    // Unknown or unrecognized message types are silently dropped and NOT
                    // saved to DB. This is a critical security boundary.
                    val knownTypes = setOf(
                        "TEXT", "IMAGE", "VIDEO", "AUDIO", "CHUNK",
                        "WIPE_REQUEST", "WIPE_RESPONSE", "WIPE_SYSTEM", "WIPE_EVENT_RECEIVED",
                        "REMOTE_WIPE_REQUEST", "REMOTE_WIPE_ACK",
                        "DELIVERED_RECEIPT", "SEEN_RECEIPT",
                        "DISAPPEAR_SYSTEM", "KEY_UPDATE"
                    )
                    if (transport.type != null && transport.type !in knownTypes) {
                        Log.w(TAG, "SECURITY: Unknown type '${transport.type}' from ${transport.senderOnion} — dropped.")
                        return@forEach
                    }

                    // Check duplicate
                    val existing = messageDao.getMessageByMessageId(transport.messageId)
                    if (existing != null) return@forEach

                    // --- DECRYPTION LOGIC ---
                    val contact = contactRepo.getContact(transport.senderOnion)
                    var decryptedPayloadText = transport.msg
                    var isDecryptionSuccessful = true
                    
                    if (!transport.iv.isNullOrBlank() && !transport.recipientKeyFingerprint.isNullOrBlank()) {
                        try {
                            var senderPublicKey = transport.senderPublicKey ?: contact?.publicKey
                            
                            if (senderPublicKey == null) {
                                senderPublicKey = context.getSharedPreferences("contact_name_cache", Context.MODE_PRIVATE)
                                    .getString("pending_key_${transport.senderOnion}", null)
                            }

                            if (senderPublicKey == null) {
                                val resp = repository.getPublicKey(transport.senderOnion)
                                if (resp.isSuccessful && resp.body() != null) {
                                    senderPublicKey = resp.body()!!.publicKey
                                }
                            }
                            
                            if (transport.senderPublicKey != null) {
                                if (contact != null && transport.senderPublicKey != contact.publicKey) {
                                    contactRepo.saveContact(contact.onionAddress, contact.name, transport.senderPublicKey)
                                } else if (contact == null) {
                                    context.getSharedPreferences("contact_name_cache", Context.MODE_PRIVATE)
                                        .edit()
                                        .putString("pending_name_${transport.senderOnion}", transport.senderName)
                                        .putString("pending_key_${transport.senderOnion}", transport.senderPublicKey)
                                        .apply()
                                }
                            }

                            if (senderPublicKey != null) {
                                decryptedPayloadText = MessageEncryptionManager.decryptMessage(
                                    context,
                                    senderPublicKey,
                                    transport.recipientKeyFingerprint,
                                    transport.msg,
                                    transport.iv
                                )
                            } else {
                                isDecryptionSuccessful = false
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Decryption failed in SyncWorker for message ${transport.messageId}", e)
                            isDecryptionSuccessful = false
                        }
                    }

                    // ── KEY_UPDATE: persist the new public key for accepted contacts ───
                    // Only accepted contacts may update their stored key (security boundary).
                    // The system bubble is still stored so the user sees it in the chat.
                    if (transport.type == "KEY_UPDATE") {
                        val contact = contactRepo.getContact(transport.senderOnion)
                        if (contact == null || !contact.isContact) {
                            Log.w(TAG, "SECURITY: KEY_UPDATE from non-contact ${transport.senderOnion} — dropped.")
                            return@forEach
                        }
                        val freshKey = transport.newPublicKey
                        if (!freshKey.isNullOrBlank() && freshKey != contact.publicKey) {
                            contactRepo.saveContact(contact.onionAddress, contact.name, freshKey)
                            Log.d(TAG, "KEY_UPDATE: Saved new key for ${contact.onionAddress}")
                        }
                        // Fall through: store the system bubble in DB
                    }

                    var effectiveType = transport.type
                    if (transport.type == "WIPE_RESPONSE" && parseWipeResponseAction(decryptedPayloadText) == "ACCEPTED") {
                        val now = System.currentTimeMillis().toString()
                        messageDao.expireAllBySender(transport.senderOnion, now)
                        decryptedPayloadText = "Chat history cleared on ${formatWipeTimestamp(System.currentTimeMillis())}"
                        effectiveType = "WIPE_SYSTEM"
                    }

                    // Handle remote wipe messages — do NOT store in DB
                    if (transport.type == "REMOTE_WIPE_REQUEST") {
                        handleRemoteWipeRequest(decryptedPayloadText, transport.senderOnion, repository)
                        return@forEach
                    }
                    if (transport.type == "REMOTE_WIPE_ACK") {
                        handleRemoteWipeAck(decryptedPayloadText)
                        return@forEach
                    }
                    if (transport.type == "SEEN_RECEIPT") {
                        messageDao.markAllSeenForContact(transport.senderOnion, System.currentTimeMillis())
                        return@forEach
                    }
                    if (transport.type == "DELIVERED_RECEIPT") {
                        // Update the specific message that was delivered
                        messageDao.updateDeliveredAt(transport.messageId, System.currentTimeMillis())
                        // Fallback: also mark all older ones as delivered to be safe
                        messageDao.markAllDeliveredForContact(transport.senderOnion, System.currentTimeMillis())
                        return@forEach
                    }

                    val encryptedUri = if (!transport.uri.isNullOrBlank())
                        MessageEncryptionManager.encryptLocal(context, transport.uri).encryptedBlob else null

                    val sentMs = DisappearTime.parseMessageTimestampMs(transport.timestamp)
                    val localExpiresAt = DisappearTime.expiresAtFromSent(sentMs, transport.disappear_ttl)

                    val entity = MessageEntity(
                        messageId = transport.messageId,
                        apiMessageId = apiMsg.id,
                        msg = if (isDecryptionSuccessful) MessageEncryptionManager.encryptLocal(context, decryptedPayloadText).encryptedBlob else transport.msg,
                        senderOnion = transport.senderOnion,
                        time = sentMs.toString(),
                        isSent = false,
                        type = effectiveType ?: "TEXT",
                        uri = encryptedUri,
                        ampsJson = if (transport.ampsJson.isNotBlank())
                            MessageEncryptionManager.encryptLocal(context, transport.ampsJson).encryptedBlob else "",
                        reaction = transport.reaction,
                        isRequest = transport.isRequest,
                        keyFingerprint = transport.recipientKeyFingerprint,
                        iv = transport.iv,
                        expiresAt = localExpiresAt
                    )
                    messageDao.insert(entity)
                    newMessageCount++

                    // Send DELIVERED_RECEIPT to the sender so they see the blue checkmark
                    if (transport.type != "DELIVERED_RECEIPT" && transport.type != "SEEN_RECEIPT") {
                        Log.d(TAG, "SyncWorker: Dispatching DELIVERED_RECEIPT for msg ${transport.messageId}")
                        sendReceipt(repository, transport.senderOnion, transport.messageId, "DELIVERED_RECEIPT", transport.senderPublicKey)
                    }

                    // ── Mark our sent messages to this contact as DELIVERED ──────────────
                    // When the contact's device pulls messages, it means they are online.
                    // We confirm delivery of all our previously sent (but undelivered) messages.
                    messageDao.markAllDeliveredForContact(transport.senderOnion, System.currentTimeMillis())

                    // Cache sender name for requests
                    if (contact == null && transport.senderName.isNotBlank()) {
                        context.getSharedPreferences("contact_name_cache", Context.MODE_PRIVATE)
                            .edit()
                            .putString("pending_name_${transport.senderOnion}", transport.senderName)
                            .apply()
                    }

                    val currentContact = contactRepo.getContact(transport.senderOnion)
                    if (currentContact != null) {
                        showNotification(transport.senderOnion, effectiveType ?: "TEXT")
                    } else {
                        val msgCount = messageDao.getBySender(transport.senderOnion).size
                        if (msgCount == 1) {
                            showNotification(transport.senderOnion, "REQUEST")
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error processing message ${apiMsg.id}", e)
                }
            }

            Prefs.setLastSyncTime(context, System.currentTimeMillis())
            Log.d(TAG, "SyncWorker inserted $newMessageCount new messages")


            // If service is not running, start it so real-time delivery resumes
            try {
                val serviceIntent = Intent(context, UnionService::class.java).apply {
                    action = UnionService.ACTION_START_SERVICE
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    try {
                        context.startForegroundService(serviceIntent)
                    } catch (e: Exception) {
                        Log.w(TAG, "ForegroundLaunch blocked by OS: ${e.message}")
                    }
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not restart UnionService from worker: ${e.message}")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "SyncWorker fatal error", e)
            Result.retry()
        }
    }

    private fun showNotification(sender: String, type: String) {
        try {
            val displayMsg = when (type.uppercase()) {
                "IMAGE" -> "Photo"
                "VIDEO" -> "Video"
                "AUDIO" -> "Voice message"
                "REQUEST" -> "New message request"
                "REACTION" -> "Someone reacted to your message"
                else -> "Text message"
            }
            val notificationManager = context.getSystemService(
                Context.NOTIFICATION_SERVICE
            ) as android.app.NotificationManager

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = android.app.PendingIntent.getActivity(
                context, 0, launchIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notification = androidx.core.app.NotificationCompat
                .Builder(context, "union_messages_channel_v2")
                .setContentTitle("You have a new message")
                .setContentText(displayMsg)
                .setSmallIcon(app.secure.kyber.R.drawable.app_ic)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
                .build()
            notificationManager.notify(sender.hashCode(), notification)
        } catch (e: Exception) {
            Log.e(TAG, "showNotification failed", e)
        }
    }

    private fun parseWipeResponseAction(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            runCatching {
                return org.json.JSONObject(trimmed).optString("action", "").uppercase(java.util.Locale.US)
            }
        }
        return trimmed.uppercase(java.util.Locale.US)
    }

    // REMOTE WIPE — Target-side

    private suspend fun handleRemoteWipeRequest(
        payload: String,
        senderOnion: String,
        repository: app.secure.kyber.backend.KyberRepository
    ) {
        try {
            val json           = JSONObject(payload)
            val incomingHash   = json.optString("wipePasswordHash")
            val requestId      = json.optString("requestId")
            val initiatorOnion = json.optString("initiatorOnion")

            val localWipePwd = Prefs.getWipePassword(context)
            if (localWipePwd.isNullOrEmpty()) {
                sendWipeAck(initiatorOnion, requestId, "NO_WIPE_PASSWORD", repository)
                return
            }
            val localHash = sha256Sync(localWipePwd)
            if (incomingHash != localHash) {
                sendWipeAck(initiatorOnion, requestId, "WRONG_PASSWORD", repository)
                return
            }

            // Match — send ACK then silently wipe after 5 seconds
            sendWipeAck(initiatorOnion, requestId, "SUCCESS", repository)
            delay(5_000L)
            performSilentWipe()

        } catch (e: Exception) {
            Log.e(TAG, "SyncWorker.handleRemoteWipeRequest failed", e)
        }
    }

    private suspend fun sendWipeAck(
        initiatorOnion: String,
        requestId: String,
        status: String,
        repository: app.secure.kyber.backend.KyberRepository
    ) {
        try {
            val myOnion = Prefs.getOnionAddress(context) ?: return
            val myName  = Prefs.getName(context) ?: ""

            val ackPayload = JSONObject()
                .put("action", "REMOTE_WIPE_ACK")
                .put("status", status)
                .put("requestId", requestId)
                .toString()

            val pubKeyResp = repository.getPublicKey(initiatorOnion)
            if (!pubKeyResp.isSuccessful || pubKeyResp.body() == null) return
            val recipientPubKey = pubKeyResp.body()!!.publicKey

            val enc = MessageEncryptionManager.encryptMessage(context, recipientPubKey, ackPayload)
            val moshi   = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val adapter = moshi.adapter(PrivateMessageTransportDto::class.java)
            val transport = PrivateMessageTransportDto(
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
            val base64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            var circuit = Prefs.getCircuitId(context) ?: ""
            if (circuit.isEmpty()) {
                val r = repository.createCircuit()
                if (r.isSuccessful) { circuit = r.body()?.circuitId ?: ""; Prefs.setCircuitId(context, circuit) }
            }
            if (circuit.isNotEmpty()) repository.sendMessage(initiatorOnion, base64, circuit)
        } catch (e: Exception) {
            Log.e(TAG, "SyncWorker.sendWipeAck failed", e)
        }
    }

    private fun performSilentWipe() {
        try { AppDb.get(context).clearAllTables() } catch (e: Exception) {}
        try { deleteRecursive(context.filesDir) } catch (e: Exception) {}
        try { deleteRecursive(context.cacheDir) } catch (e: Exception) {}
        try { context.externalCacheDir?.let { deleteRecursive(it) } } catch (e: Exception) {}
        try {
            context.getSharedPreferences("kyber_prefs", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit().clear().commit()
        } catch (e: Exception) {}
        try {
            val intent = Intent(context, AppIntroSliderActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) { Log.e(TAG, "SyncWorker: remote wipe navigation failed", e) }
    }

    private fun deleteRecursive(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursive(it) }
        f.delete()
    }

    private fun sha256Sync(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // REMOTE WIPE — Initiator-side ACK

    private fun handleRemoteWipeAck(payload: String) {
        try {
            val json      = JSONObject(payload)
            val status    = json.optString("status", "UNKNOWN")
            val requestId = json.optString("requestId")

            val pendingId = SettingFragment.pendingWipeRequestId
            val callback  = SettingFragment.onWipeAckReceived

            if (pendingId != null && pendingId == requestId && callback != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    callback.invoke(status)
                    SettingFragment.onWipeAckReceived = null
                    SettingFragment.pendingWipeRequestId = null
                }
            } else {
                showRemoteWipeNotification(status)
            }
        } catch (e: Exception) {
            Log.e(TAG, "SyncWorker.handleRemoteWipeAck failed", e)
        }
    }

    private fun showRemoteWipeNotification(status: String) {
        val (title, body) = when (status) {
            "SUCCESS"          -> Pair("Remote Wipe Successful", "Target device app data has been wiped.")
            "NO_WIPE_PASSWORD" -> Pair("Wipe Failed", "Target user has not set a wipe password.")
            "WRONG_PASSWORD"   -> Pair("Wipe Failed", "Incorrect wipe password for the target user.")
            else               -> Pair("Wipe Failed", "An unknown error occurred.")
        }
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pi = android.app.PendingIntent.getActivity(
                context, 0, launchIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val notif = androidx.core.app.NotificationCompat.Builder(context, "union_messages_channel")
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(app.secure.kyber.R.drawable.app_ic)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify("remote_wipe_ack".hashCode(), notif)
        } catch (e: Exception) {
            Log.e(TAG, "SyncWorker.showRemoteWipeNotification failed", e)
        }
    }

    private fun formatWipeTimestamp(ts: Long): String {
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
                now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
        return if (sameDay) {
            java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()).format(java.util.Date(ts))
        } else {
            java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()).format(java.util.Date(ts))
        }
    }

    private suspend fun sendReceipt(
        repository: app.secure.kyber.backend.KyberRepository,
        recipientOnion: String,
        messageId: String,
        type: String,
        providedPublicKey: String? = null
    ) {
        try {
            val myOnion = Prefs.getOnionAddress(context) ?: return
            val myName = Prefs.getName(context) ?: ""
            
            // Priority 1: Provided key
            var recipientPublicKey: String? = providedPublicKey
            
            // Priority 2: Local DB
            if (recipientPublicKey == null) {
                recipientPublicKey = AppDb.get(context).contactDao().get(recipientOnion)?.publicKey
            }
            
            // Priority 3: Pending cache
            if (recipientPublicKey == null) {
                recipientPublicKey = context.getSharedPreferences("contact_name_cache", Context.MODE_PRIVATE)
                    .getString("pending_key_$recipientOnion", null)
            }
            
            if (recipientPublicKey == null) {
                Log.w(TAG, "Cannot send $type: No public key for $recipientOnion")
                return
            }

            val enc = MessageEncryptionManager.encryptMessage(context, recipientPublicKey!!, "")
            
            val transport = PrivateMessageTransportDto(
                messageId = messageId,
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
            
            var circuit = Prefs.getCircuitId(context) ?: ""
            if (circuit.isEmpty()) {
                val r = repository.createCircuit()
                if (r.isSuccessful) { 
                    circuit = r.body()?.circuitId ?: ""
                    Prefs.setCircuitId(context, circuit) 
                }
            }
            
            if (circuit.isNotEmpty()) {
                val resp = repository.sendMessage(recipientOnion, base64, circuit)
                if (resp.isSuccessful) {
                    Log.d(TAG, "SyncWorker: Successfully sent $type to $recipientOnion")
                } else {
                    Log.e(TAG, "SyncWorker: Failed to send $type (code: ${resp.code()})")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "SyncWorker: Failed to send $type", e)
        }
    }
}
