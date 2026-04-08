package app.secure.kyber.workers

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.*
import app.secure.kyber.Utils.MessageEncryptionManager
import app.secure.kyber.Utils.SecureKeyManager
import app.secure.kyber.backend.beans.PrivateMessageTransportDto
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.roomdb.AppDb
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TextUploadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "TextUploadWorker"
        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_TEXT = "text"
        const val KEY_CONTACT_ONION = "contact_onion"
        const val KEY_SENDER_ONION = "sender_onion"
        const val KEY_SENDER_NAME = "sender_name"
        const val KEY_TIMESTAMP = "timestamp"
        const val KEY_IS_REQUEST = "is_request"
        const val KEY_DISAPPEAR_TTL = "disappear_ttl"

        fun buildRequest(
            messageId: String, text: String, contactOnion: String,
            senderOnion: String, senderName: String, timestamp: String,
            isRequest: Boolean, disappearTtl: Long = 0L
        ): OneTimeWorkRequest {
            val data = workDataOf(
                KEY_MESSAGE_ID to messageId,
                KEY_TEXT to text,
                KEY_CONTACT_ONION to contactOnion,
                KEY_SENDER_ONION to senderOnion,
                KEY_SENDER_NAME to senderName,
                KEY_TIMESTAMP to timestamp,
                KEY_IS_REQUEST to isRequest,
                KEY_DISAPPEAR_TTL to disappearTtl
            )

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            return OneTimeWorkRequestBuilder<TextUploadWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .addTag(TAG)
                .addTag("text_upload_$messageId")
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                .build()
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val messageId = inputData.getString(KEY_MESSAGE_ID) ?: return@withContext Result.failure()
        val text = inputData.getString(KEY_TEXT) ?: return@withContext Result.failure()
        val contactOnion = inputData.getString(KEY_CONTACT_ONION) ?: return@withContext Result.failure()
        val senderOnion = inputData.getString(KEY_SENDER_ONION) ?: return@withContext Result.failure()
        val senderName = inputData.getString(KEY_SENDER_NAME) ?: return@withContext Result.failure()
        val timestamp = inputData.getString(KEY_TIMESTAMP) ?: return@withContext Result.failure()
        val isRequest = inputData.getBoolean(KEY_IS_REQUEST, false)
        val disappearTtl = inputData.getLong(KEY_DISAPPEAR_TTL, 0L)

        val db = AppDb.get(context)
        val messageDao = db.messageDao()
        val contactDao = db.contactDao()

        // Set state to uploading visually
        messageDao.updateUploadProgress(messageId, "uploading", 0)

        // Hilt accessor for KyberRepository
        val repository = try {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                SyncWorkerEntryPoint::class.java
            ).repository()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get repository", e)
            return@withContext Result.retry()
        }

        try {
            // 1. Get recipient public key
                // Fetch from backend
                val resp = repository.getPublicKey(contactOnion)
                if (resp.isSuccessful && resp.body() != null) {
                   val recipientPublicKey = resp.body()!!.publicKey

                    // 2. Encrypt the message
                    val encryptionResult = MessageEncryptionManager.encryptMessage(
                        context, recipientPublicKey, text
                    )

                    // 3. Update local message with fingerprint
                    val localMsg = messageDao.getMessageByMessageId(messageId)
                    localMsg?.let {
                        val updated = it.copy(keyFingerprint = encryptionResult.senderKeyFingerprint)
                        messageDao.update(updated)
                    }


                    val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
                    val transportAdapter = moshi.adapter(PrivateMessageTransportDto::class.java)



                    val transport = PrivateMessageTransportDto(
                        messageId = messageId,
                        msg = encryptionResult.encryptedPayload,
                        senderOnion = senderOnion,
                        senderName = senderName,
                        timestamp = timestamp,
                        isRequest = isRequest,
                        iv = encryptionResult.iv,
                        senderKeyFingerprint = encryptionResult.senderKeyFingerprint,
                        recipientKeyFingerprint = encryptionResult.recipientKeyFingerprint,
                        senderPublicKey = encryptionResult.senderPublicKeyBase64,
                        ampsJson = localMsg?.ampsJson ?: "",
                        disappear_ttl = disappearTtl
                    )


                    val jsonPayload = transportAdapter.toJson(transport)
                    val base64Payload = Base64.encodeToString(jsonPayload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

                    var circuitId = Prefs.getCircuitId(context) ?: ""
                    if (circuitId.isEmpty()) {
                        val r = repository.createCircuit()
                        if (r.isSuccessful) {
                            circuitId = r.body()?.circuitId ?: ""
                            Prefs.setCircuitId(context, circuitId)
                        }
                    }


                    if (circuitId.isNotEmpty()) {
                        var response = repository.sendMessage(contactOnion, base64Payload, circuitId)
                        if (!response.isSuccessful && (response.code() == 404 || response.code() == 400)) {
                            val retry = repository.createCircuit()
                            if (retry.isSuccessful) {
                                circuitId = retry.body()?.circuitId ?: ""
                                Prefs.setCircuitId(context, circuitId)
                                response = repository.sendMessage(contactOnion, base64Payload, circuitId)
                            }
                        }

                        if (response.isSuccessful) {
                            // Sent successfully
                            messageDao.setUploadDone(messageId, "done", "")
                            return@withContext Result.success()
                        }
                    }



                } else {
                    Log.e(TAG, "Could not fetch public key for $contactOnion")
                    return@withContext Result.retry()
                }


            // Check if we reached 24 hour timeout
            val currentMillis = System.currentTimeMillis()
            val startMillis = timestamp.toLongOrNull() ?: currentMillis
            if (currentMillis - startMillis > 24 * 60 * 60 * 1000L) {
                // Fail completely after 24 hrs
                messageDao.updateUploadProgress(messageId, "failed", 0)
                return@withContext Result.failure()
            }

            // Retry for any error during the 24h
            return@withContext Result.retry()

        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            val currentMillis = System.currentTimeMillis()
            val startMillis = timestamp.toLongOrNull() ?: currentMillis
            if (currentMillis - startMillis > 24 * 60 * 60 * 1000L) {
                messageDao.updateUploadProgress(messageId, "failed", 0)
                return@withContext Result.failure()
            }

            return@withContext Result.retry()
        }
    }
}
