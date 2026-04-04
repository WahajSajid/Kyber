package app.secure.kyber.workers

import android.content.Context
import android.util.Log
import androidx.work.*
import app.secure.kyber.backend.beans.MediaChunkDto
import app.secure.kyber.backend.beans.PrivateMessageTransportDto
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.media.MediaChunkManager
import app.secure.kyber.media.MediaTransferNotifier
import app.secure.kyber.media.VideoCompressor
import app.secure.kyber.roomdb.AppDb
import android.util.Base64
import app.secure.kyber.Utils.MessageEncryptionManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

class MediaUploadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "MediaUploadWorker"

        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_MEDIA_ID = "media_id"
        const val KEY_FILE_PATH = "file_path"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_CAPTION = "caption"
        const val KEY_AMPS_JSON = "amps_json"
        const val KEY_CONTACT_ONION = "contact_onion"
        const val KEY_SENDER_ONION = "sender_onion"
        const val KEY_SENDER_NAME = "sender_name"
        const val KEY_IS_CONTACT = "is_contact"
        const val KEY_DURATION_MS = "duration_ms"

        fun buildRequest(
            messageId: String,
            mediaId: String,
            filePath: String,
            mimeType: String,
            caption: String,
            ampsJson: String,
            contactOnion: String,
            senderOnion: String,
            senderName: String,
            isContact: Boolean,
            durationMs: Long
        ): OneTimeWorkRequest {
            val data = workDataOf(
                KEY_MESSAGE_ID to messageId,
                KEY_MEDIA_ID to mediaId,
                KEY_FILE_PATH to filePath,
                KEY_MIME_TYPE to mimeType,
                KEY_CAPTION to caption,
                KEY_AMPS_JSON to ampsJson,
                KEY_CONTACT_ONION to contactOnion,
                KEY_SENDER_ONION to senderOnion,
                KEY_SENDER_NAME to senderName,
                KEY_IS_CONTACT to isContact,
                KEY_DURATION_MS to durationMs
            )
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            return OneTimeWorkRequestBuilder<MediaUploadWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .addTag(TAG)
                .addTag("upload_$messageId")
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                .build()
        }
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val chunkAdapter = moshi.adapter(MediaChunkDto::class.java)
    private val transportAdapter = moshi.adapter(PrivateMessageTransportDto::class.java)
    private var serviceScope: CoroutineScope? = null

    override suspend fun doWork(): Result {
        val messageId = inputData.getString(KEY_MESSAGE_ID) ?: return Result.failure()
        val mediaId = inputData.getString(KEY_MEDIA_ID) ?: return Result.failure()
        val filePath = inputData.getString(KEY_FILE_PATH) ?: return Result.failure()
        val mimeType = inputData.getString(KEY_MIME_TYPE) ?: return Result.failure()
        val caption = inputData.getString(KEY_CAPTION) ?: ""
        val ampsJson = inputData.getString(KEY_AMPS_JSON) ?: ""
        val contactOnion = inputData.getString(KEY_CONTACT_ONION) ?: return Result.failure()
        val senderOnion = inputData.getString(KEY_SENDER_ONION) ?: return Result.failure()
        val senderName = inputData.getString(KEY_SENDER_NAME) ?: ""
        val isContact = inputData.getBoolean(KEY_IS_CONTACT, true)
        val durationMs = inputData.getLong(KEY_DURATION_MS, 0L)

        val db = AppDb.get(context)
        val messageDao = db.messageDao()
        val notifier = MediaTransferNotifier(context)
        serviceScope = CoroutineScope(Dispatchers.IO)

        val repository = EntryPointAccessors.fromApplication(
            context.applicationContext,
            SyncWorkerEntryPoint::class.java
        ).repository()

        return try {
            val fgInfo = notifier.buildUploadForegroundInfo(messageId, mimeType, 0)
            setForeground(fgInfo)

            val uploadFilePath: String
            val thumbnailPath: String?

            if (mimeType == "VIDEO") {
                messageDao.updateUploadProgress(messageId, "compressing", 0)
                setForeground(
                    notifier.buildUploadForegroundInfo(
                        messageId, mimeType, 0, stateLabel = "Compressing…"
                    )
                )

                val result = VideoCompressor.compress(
                    context = context,
                    inputPath = filePath,
                    onProgress = { pct ->
                        if (pct % 5 == 0) {
                            notifier.showCompressionProgress(messageId, pct)
                            serviceScope?.launch {
                                messageDao.updateUploadProgress(messageId, "compressing", pct)
                            }
                        }
                    }
                )

                if (result == null) {
                    messageDao.updateUploadProgress(messageId, "failed", 0)
                    notifier.showUploadFailed(messageId, mimeType)
                    serviceScope?.cancel()
                    return Result.failure()
                }

                uploadFilePath = result.outputPath
                thumbnailPath = result.thumbnailPath

                if (thumbnailPath != null) {
                    messageDao.setThumbnailPath(messageId, thumbnailPath)
                }

                messageDao.updateUploadProgress(messageId, "uploading", 0)
                setForeground(
                    notifier.buildUploadForegroundInfo(
                        messageId, mimeType, 0, stateLabel = "Uploading…"
                    )
                )

            } else {
                uploadFilePath = filePath
                thumbnailPath = null
            }

            messageDao.updateUploadProgress(messageId, "uploading", 0)

            val chunks = MediaChunkManager.buildChunks(
                context = context,
                filePath = uploadFilePath,
                mediaId = mediaId,
                originalMessageId = messageId,
                mimeType = mimeType,
                ampsJson = ampsJson,
                caption = caption,
                durationMs = durationMs
            )

            if (chunks.isEmpty()) {
                messageDao.updateUploadProgress(messageId, "failed", 0)
                notifier.showUploadFailed(messageId, mimeType)
                serviceScope?.cancel()
                return Result.failure()
            }

            // Get recipient public key
            val contact = db.contactDao().get(contactOnion)
            var recipientPublicKey = contact?.publicKey
            if (recipientPublicKey == null) {
                recipientPublicKey = context.getSharedPreferences("contact_name_cache", Context.MODE_PRIVATE)
                    .getString("pending_key_$contactOnion", null)
            }
            
            if (recipientPublicKey == null) {
                val resp = repository.getPublicKey(contactOnion)
                if (resp.isSuccessful) recipientPublicKey = resp.body()?.publicKey
            }

            if (recipientPublicKey == null) {
                Log.e(TAG, "Recipient public key not found for $contactOnion")
                return Result.retry()
            }


            var successCount = 0
            for (chunk in chunks) {
                if (isStopped) {
                    messageDao.updateUploadProgress(
                        messageId, "failed", (successCount * 100) / chunks.size
                    )
                    notifier.showUploadFailed(messageId, mimeType)
                    serviceScope?.cancel()
                    return Result.retry()
                }

                val chunkJson = chunkAdapter.toJson(chunk)
                val encryptionResult = MessageEncryptionManager.encryptMessage(context, recipientPublicKey, chunkJson)
                
                val sent = sendChunk(
                    repository, contactOnion, senderOnion, senderName, chunk, isContact, recipientPublicKey, encryptionResult.senderPublicKeyBase64
                )
                if (sent) {
                    successCount++
                    val progress = (successCount * 100) / chunks.size
                    messageDao.updateUploadProgress(messageId, "uploading", progress)
                    setForeground(
                        notifier.buildUploadForegroundInfo(messageId, mimeType, progress)
                    )
                } else {
                    messageDao.updateUploadProgress(
                        messageId, "failed", (successCount * 100) / chunks.size
                    )
                    notifier.showUploadFailed(messageId, mimeType)
                    serviceScope?.cancel()
                    return Result.retry()
                }

                if (chunk.index < chunks.size - 1) delay(80)
            }

            val finalLocalPath = if (mimeType == "VIDEO") {
                try {
                    val compressed = java.io.File(uploadFilePath.removePrefix("file://"))
                    if (compressed.exists()) {
                        val destDir =
                            java.io.File(context.filesDir, "sent_media").apply { mkdirs() }
                        val dest = java.io.File(destDir, "video_${messageId}.mp4")
                        if (!dest.exists()) compressed.copyTo(dest)
                        dest.absolutePath
                    } else uploadFilePath.removePrefix("file://")
                } catch (e: Exception) {
                    uploadFilePath.removePrefix("file://")
                }
            } else {
                filePath.removePrefix("file://")
            }

            messageDao.setUploadDone(messageId, "done", finalLocalPath)
            notifier.showUploadComplete(messageId, mimeType)
            serviceScope?.cancel()
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Upload worker error for $messageId", e)
            messageDao.updateUploadProgress(messageId, "failed", 0)
            notifier.showUploadFailed(messageId, mimeType)
            serviceScope?.cancel()
            Result.retry()
        }
    }

    private suspend fun sendChunk(
        repository: app.secure.kyber.backend.KyberRepository,
        contactOnion: String,
        senderOnion: String,
        senderName: String,
        chunk: MediaChunkDto,
        isContact: Boolean,
        recipientPublicKey: String,
        myPublicKey: String?
    ): Boolean {
        return try {
            val chunkJson = chunkAdapter.toJson(chunk)
            
            // Encrypt chunk
            val encryptionResult = MessageEncryptionManager.encryptMessage(context, recipientPublicKey, chunkJson)

            val transport = PrivateMessageTransportDto(
                messageId = UUID.randomUUID().toString(),
                msg = encryptionResult.encryptedPayload,
                senderOnion = senderOnion,
                senderName = senderName,
                timestamp = System.currentTimeMillis().toString(),
                type = "CHUNK",
                uri = null, // In the new system, we put the encrypted payload in 'msg'
                isRequest = !isContact,
                iv = encryptionResult.iv,
                senderKeyFingerprint = encryptionResult.senderKeyFingerprint,
                recipientKeyFingerprint = encryptionResult.recipientKeyFingerprint,
                senderPublicKey = myPublicKey
            )
            val transportJson = transportAdapter.toJson(transport)
            val payload = Base64.encodeToString(
                transportJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )

            var circuitId = Prefs.getCircuitId(context) ?: ""
            if (circuitId.isEmpty()) {
                val r = repository.createCircuit()
                if (r.isSuccessful) {
                    circuitId = r.body()?.circuitId ?: ""
                    Prefs.setCircuitId(context, circuitId)
                }
            }
            if (circuitId.isEmpty()) return false

            var response = repository.sendMessage(contactOnion, payload, circuitId)
            if (!response.isSuccessful && (response.code() == 400 || response.code() == 404)) {
                val retry = repository.createCircuit()
                if (retry.isSuccessful) {
                    circuitId = retry.body()?.circuitId ?: ""
                    Prefs.setCircuitId(context, circuitId)
                    response = repository.sendMessage(contactOnion, payload, circuitId)
                }
            }
            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "sendChunk error: ${e.message}")
            false
        }
    }
}
