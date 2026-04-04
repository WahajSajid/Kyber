package app.secure.kyber.workers

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.secure.kyber.Utils.MessageEncryptionManager
import app.secure.kyber.backend.beans.PrivateMessageTransportDto
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.onionrouting.UnionService
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.ContactRepository
import app.secure.kyber.roomdb.MessageEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

                    val encryptedUri = if (!transport.uri.isNullOrBlank())
                        MessageEncryptionManager.encryptLocal(context, transport.uri).encryptedBlob else null

                    val entity = MessageEntity(
                        messageId = transport.messageId,
                        apiMessageId = apiMsg.id,
                        msg = if (isDecryptionSuccessful) MessageEncryptionManager.encryptLocal(context, decryptedPayloadText).encryptedBlob else transport.msg,
                        senderOnion = transport.senderOnion,
                        time = System.currentTimeMillis().toString(),
                        isSent = false,
                        type = transport.type,
                        uri = encryptedUri,
                        ampsJson = if (transport.ampsJson.isNotBlank())
                            MessageEncryptionManager.encryptLocal(context, transport.ampsJson).encryptedBlob else "",
                        reaction = transport.reaction,
                        isRequest = transport.isRequest,
                        keyFingerprint = transport.recipientKeyFingerprint,
                        iv = transport.iv
                    )
                    messageDao.insert(entity)
                    newMessageCount++

                    // Cache sender name for requests
                    if (contact == null && transport.senderName.isNotBlank()) {
                        context.getSharedPreferences("contact_name_cache", Context.MODE_PRIVATE)
                            .edit()
                            .putString("pending_name_${transport.senderOnion}", transport.senderName)
                            .apply()
                    }

                    // Show notification
                    val currentContact = contactRepo.getContact(transport.senderOnion)
                    if (currentContact != null) {
                        showNotification(transport.senderOnion, currentContact.name, decryptedPayloadText, transport.type)
                    } else {
                        val msgCount = messageDao.getBySender(transport.senderOnion).size
                        if (msgCount == 1) {
                            showNotification(transport.senderOnion, "Someone", "New message request", "TEXT")
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

    private fun showNotification(sender: String, title: String, msg: String, type: String) {
        try {
            val displayMsg = when (type.uppercase()) {
                "IMAGE" -> "📷 Photo"
                "VIDEO" -> "🎥 Video"
                "AUDIO" -> "🎵 Voice Message"
                else -> if (msg.length > 60) msg.take(57) + "..." else msg
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
                .Builder(context, "union_messages_channel")
                .setContentTitle(title)
                .setContentText(displayMsg)
                .setSmallIcon(app.secure.kyber.R.drawable.notification)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
                .build()
            notificationManager.notify(sender.hashCode(), notification)
        } catch (e: Exception) {
            Log.e(TAG, "showNotification failed", e)
        }
    }
}
