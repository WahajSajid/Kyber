package app.secure.kyber.workers

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.*
import app.secure.kyber.Utils.MessageEncryptionManager
import app.secure.kyber.backend.beans.MediaChunkDto
import app.secure.kyber.backend.beans.PrivateMessageTransportDto
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.media.MediaChunkManager
import app.secure.kyber.media.MediaTransferNotifier
import app.secure.kyber.media.VideoCompressor
import app.secure.kyber.roomdb.AppDb
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow

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
        const val KEY_DISAPPEAR_TTL = "disappear_ttl"
        const val KEY_REPLY_TO_TEXT = "reply_to_text"

        // Retry and timeout constants
        private const val PER_CHUNK_TIMEOUT_MS = 15000L  // 15 seconds per chunk
        private const val MAX_RETRIES_PER_CHUNK = 5
        private const val INTER_CHUNK_DELAY_MS = 80L

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
            durationMs: Long,
            disappearTtl: Long = 0L,
            replyToText: String = ""
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
                KEY_DURATION_MS to durationMs,
                KEY_DISAPPEAR_TTL to disappearTtl,
                KEY_REPLY_TO_TEXT to replyToText
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
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                .build()
        }
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val chunkAdapter = moshi.adapter(MediaChunkDto::class.java)
    private val transportAdapter = moshi.adapter(PrivateMessageTransportDto::class.java)

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
        val disappearTtl = inputData.getLong(KEY_DISAPPEAR_TTL, 0L)
        val replyToText = inputData.getString(KEY_REPLY_TO_TEXT) ?: ""

        val db = AppDb.get(context)
        val messageDao = db.messageDao()
        val notifier = MediaTransferNotifier(context)

        val repository = EntryPointAccessors.fromApplication(
            context.applicationContext,
            SyncWorkerEntryPoint::class.java
        ).repository()

        return try {
            Log.d(TAG, "Starting upload for messageId=$messageId, mediaId=$mediaId")
            setForeground(notifier.buildUploadForegroundInfo(messageId, mimeType, 0))

            // Fetch saved state for resumption
            val savedMsg = messageDao.getMessageByMessageId(messageId)
            val uploadedIndices = parseSavedChunks(savedMsg?.uploadedChunkIndices ?: "")
            val attemptCount = savedMsg?.uploadAttemptCount ?: 0

            if (attemptCount >= MAX_RETRIES_PER_CHUNK) {
                Log.e(TAG, "Max retries reached for messageId=$messageId")
                messageDao.updateUploadProgress(messageId, "failed", 0)
                notifier.showUploadFailed(messageId, mimeType)
                return Result.failure()
            }

            // Prepare file for upload (compress video if needed)
            val uploadFilePath: String
            val thumbnailPath: String?

            if (mimeType == "VIDEO") {
                val existingLocalPath = savedMsg?.localFilePath ?: filePath
                val hasCompressed = existingLocalPath.contains("video_comp_") && java.io.File(existingLocalPath.removePrefix("file://")).exists()

                if (hasCompressed) {
                    uploadFilePath = existingLocalPath
                    thumbnailPath = savedMsg?.thumbnailPath
                    Log.d(TAG, "Using existing compressed video: $uploadFilePath")
                } else {
                    Log.d(TAG, "Compressing video...")
                    messageDao.updateUploadProgress(messageId, "compressing", 0)
                    setForeground(
                        notifier.buildUploadForegroundInfo(messageId, mimeType, 0, stateLabel = "Compressing…")
                    )

                    val result = VideoCompressor.compress(
                        context = context,
                        inputPath = filePath,
                        onProgress = { pct ->
                            if (pct % 5 == 0) {
                                notifier.showCompressionProgress(messageId, pct)
                                runBlocking {
                                    messageDao.updateUploadProgress(messageId, "compressing", pct)
                                }
                            }
                        }
                    ) ?: run {
                        Log.e(TAG, "Video compression failed")
                        messageDao.updateUploadProgress(messageId, "failed", 0)
                        notifier.showUploadFailed(messageId, mimeType)
                        return Result.failure()
                    }

                    uploadFilePath = result.outputPath
                    thumbnailPath = result.thumbnailPath

                    messageDao.setLocalFilePath(messageId, uploadFilePath)
                    if (thumbnailPath != null) {
                        messageDao.setThumbnailPath(messageId, thumbnailPath)
                    }
                }
            } else {
                uploadFilePath = filePath
                // For IMAGE: read the early thumbnail that MediaSender already saved to the DB.
                // This is transmitted in Chunk 0 so the receiver can show it during download.
                thumbnailPath = savedMsg?.thumbnailPath
                messageDao.setLocalFilePath(messageId, uploadFilePath)
            }

            // Build chunks
            Log.d(TAG, "Building chunks for uploadFilePath=$uploadFilePath")
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
                Log.e(TAG, "No chunks built")
                messageDao.updateUploadProgress(messageId, "failed", 0)
                notifier.showUploadFailed(messageId, mimeType)
                return Result.failure()
            }

            Log.d(TAG, "Built ${chunks.size} chunks, already uploaded: ${uploadedIndices.size}")

            // Get recipient public key
            val resp = repository.getPublicKey(contactOnion)
            if (!resp.isSuccessful) {
                Log.e(TAG, "Failed to get recipient public key")
                return Result.retry()
            }
            val recipientPublicKey = resp.body()?.publicKey ?: return Result.retry()

            // Identify chunks to send
            val failedChunks = mutableSetOf<Int>()
            for (i in chunks.indices) {
                if (i !in uploadedIndices) {
                    failedChunks.add(i)
                }
            }

            Log.d(TAG, "Chunks to send: ${failedChunks.sorted()}")

            // Send chunks with retry logic
            var successCount = uploadedIndices.size
            messageDao.updateUploadProgress(messageId, "uploading", 0)
            setForeground(notifier.buildUploadForegroundInfo(messageId, mimeType, 0, stateLabel = "Uploading…"))

            for (chunkIndex in failedChunks.sorted()) {
                if (isStopped) {
                    Log.w(TAG, "Worker stopped")
                    messageDao.updateUploadProgress(messageId, "failed", (successCount * 100) / chunks.size)
                    return Result.retry()
                }

                val chunk = chunks[chunkIndex]
                val sent = sendChunkWithRetry(
                    repository, contactOnion, senderOnion, senderName, chunk, isContact,
                    recipientPublicKey, disappearTtl, replyToText, thumbnailPath
                )

                if (sent) {
                    uploadedIndices.add(chunkIndex)
                    successCount++

                    // Update DB with progress
                    val progress = (successCount * 100) / chunks.size
                    val indices = uploadedIndices.sorted().joinToString(",")
                    messageDao.updateUploadProgress(messageId, "uploading", progress)

                    // Update chunk tracking
                    val entity = messageDao.getMessageByMessageId(messageId)
                    if (entity != null) {
                        db.messageDao().update(
                            entity.copy(
                                uploadedChunkIndices = indices,
                                totalChunksExpected = chunks.size,
                                uploadProgress = progress
                            )
                        )
                    }

                    Log.d(TAG, "Chunk $chunkIndex sent ($successCount/${chunks.size})")
                    setForeground(notifier.buildUploadForegroundInfo(messageId, mimeType, progress))
                } else {
                    Log.e(TAG, "Failed to send chunk $chunkIndex after all retries")
                    messageDao.updateUploadProgress(messageId, "failed", (successCount * 100) / chunks.size)
                    notifier.showUploadFailed(messageId, mimeType)

                    // Increment attempt count and retry
                    val entity = messageDao.getMessageByMessageId(messageId)
                    if (entity != null) {
                        db.messageDao().update(entity.copy(uploadAttemptCount = attemptCount + 1))
                    }

                    return Result.retry()
                }

                if (chunkIndex < chunks.size - 1) {
                    delay(INTER_CHUNK_DELAY_MS)
                }
            }

            // All chunks sent successfully
            val nowMs = System.currentTimeMillis()
            val nowStr = nowMs.toString()
            if (disappearTtl > 0L) {
                val expiresAt = nowMs + disappearTtl
                messageDao.updateSentTime(messageId, nowStr, expiresAt)
            } else {
                messageDao.updateSentTime(messageId, nowStr, 0L)
            }
            val finalLocalPath = uploadFilePath.removePrefix("file://")
            messageDao.setUploadDone(messageId, "done", finalLocalPath)
            notifier.showUploadComplete(messageId, mimeType)

            Log.d(TAG, "Upload complete for messageId=$messageId")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Upload worker error for $messageId: ${e.message}", e)
            messageDao.updateUploadProgress(messageId, "failed", 0)
            notifier.showUploadFailed(messageId, mimeType)
            Result.retry()
        }
    }

    /**
     * Send a single chunk with exponential backoff retry logic.
     * Per-chunk timeout: 15 seconds
     * Max retries: 5
     */
    private suspend fun sendChunkWithRetry(
        repository: app.secure.kyber.backend.KyberRepository,
        contactOnion: String,
        senderOnion: String,
        senderName: String,
        chunk: MediaChunkDto,
        isContact: Boolean,
        recipientPublicKey: String,
        disappearTtl: Long,
        replyToText: String,
        thumbnailPath: String?
    ): Boolean {
        var attempt = 0

        while (attempt < MAX_RETRIES_PER_CHUNK) {
            try {
                val result = withTimeoutOrNull(PER_CHUNK_TIMEOUT_MS) {
                    sendChunk(
                        repository, contactOnion, senderOnion, senderName, chunk, isContact,
                        recipientPublicKey, disappearTtl, replyToText, thumbnailPath
                    )
                } ?: false

                if (result) {
                    Log.d(TAG, "Chunk ${chunk.index} sent successfully")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Chunk ${chunk.index} attempt $attempt error: ${e.message}")
            }

            // Exponential backoff
            if (attempt < MAX_RETRIES_PER_CHUNK - 1) {
                val backoffMs = (1000L * (2.0.pow(attempt.toDouble()))).toLong().coerceAtMost(30000L)
                Log.d(TAG, "Chunk ${chunk.index} backoff: ${backoffMs}ms")
                delay(backoffMs)
            }

            attempt++
        }

        Log.e(TAG, "Chunk ${chunk.index} failed after $MAX_RETRIES_PER_CHUNK attempts")
        return false
    }

    /**
     * Send a single chunk to the server.
     * Returns true if successful, false otherwise.
     */
    private suspend fun sendChunk(
        repository: app.secure.kyber.backend.KyberRepository,
        contactOnion: String,
        senderOnion: String,
        senderName: String,
        chunk: MediaChunkDto,
        isContact: Boolean,
        recipientPublicKey: String,
        disappearTtl: Long,
        replyToText: String,
        thumbnailPath: String?
    ): Boolean {
        return try {
            val chunkJson = chunkAdapter.toJson(chunk)

            // Encrypt chunk
            val encryptionResult = MessageEncryptionManager.encryptMessage(
                context, recipientPublicKey, chunkJson
            )

            val transport = PrivateMessageTransportDto(
                messageId = UUID.randomUUID().toString(),
                msg = encryptionResult.encryptedPayload,
                senderOnion = senderOnion,
                senderName = senderName,
                timestamp = System.currentTimeMillis().toString(),
                type = "CHUNK",
                uri = null,
                isRequest = !isContact,
                iv = encryptionResult.iv,
                senderKeyFingerprint = encryptionResult.senderKeyFingerprint,
                recipientKeyFingerprint = encryptionResult.recipientKeyFingerprint,
                senderPublicKey = encryptionResult.senderPublicKeyBase64,
                disappear_ttl = disappearTtl,
                thumbnail = if (chunk.index == 0) thumbnailPath?.let { encodeThumbnail(it) } else null,
                replyToText = replyToText
            )
            val transportJson = transportAdapter.toJson(transport)
            val payload = Base64.encodeToString(
                transportJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )

            // Get circuit
            var circuitId = Prefs.getCircuitId(context) ?: ""
            if (circuitId.isEmpty()) {
                val r = repository.createCircuit()
                if (r.isSuccessful) {
                    circuitId = r.body()?.circuitId ?: ""
                    Prefs.setCircuitId(context, circuitId)
                }
            }
            if (circuitId.isEmpty()) return false

            // Send message
            var response = repository.sendMessage(contactOnion, payload, circuitId)
            if (!response.isSuccessful && (response.code() == 400 || response.code() == 404)) {
                // Try fresh circuit
                val retry = repository.createCircuit()
                if (retry.isSuccessful) {
                    circuitId = retry.body()?.circuitId ?: ""
                    Prefs.setCircuitId(context, circuitId)
                    response = repository.sendMessage(contactOnion, payload, circuitId)
                }
            }

            response.isSuccessful
        } catch (e: Exception) {
            Log.e(TAG, "sendChunk error: ${e.message}", e)
            false
        }
    }

    /**
     * Parse comma-separated chunk indices from saved state.
     * Example: "0,1,2,5,7" → {0, 1, 2, 5, 7}
     */
    private fun parseSavedChunks(csv: String): MutableSet<Int> {
        if (csv.isBlank()) return mutableSetOf()
        return csv.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toMutableSet()
    }
    private fun encodeThumbnail(path: String): String? {
        return try {
            val file = java.io.File(path)
            if (!file.exists()) return null
            val bytes = file.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }
}

