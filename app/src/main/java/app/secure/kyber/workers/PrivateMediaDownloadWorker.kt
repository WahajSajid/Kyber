package app.secure.kyber.workers

import android.content.Context
import android.util.Log
import androidx.work.*
import app.secure.kyber.media.MediaChunkManager
import app.secure.kyber.media.MediaTransferNotifier
import app.secure.kyber.roomdb.AppDb
import kotlinx.coroutines.delay

/**
 * Worker that assembles downloaded chunks into a complete encrypted file.
 * 
 * Triggered by SyncWorker when all chunks for a media download have been received.
 * Waits for all chunks to appear in filesDir/chunks_{mediaId}, then:
 * 1. Assembles them into filesDir/received_media/{mediaId}
 * 2. Updates DB with finalPath
 * 3. Shows download completion notification
 */
class PrivateMediaDownloadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "PrivateMediaDownloadWorker"

        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_MEDIA_ID = "media_id"
        const val KEY_TOTAL_CHUNKS = "total_chunks"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_DISAPPEAR_TTL = "disappear_ttl"

        fun buildRequest(
            messageId: String,
            mediaId: String,
            totalChunks: Int,
            mimeType: String,
            disappearTtl: Long = 0L
        ): OneTimeWorkRequest {
            val data = workDataOf(
                KEY_MESSAGE_ID to messageId,
                KEY_MEDIA_ID to mediaId,
                KEY_TOTAL_CHUNKS to totalChunks,
                KEY_MIME_TYPE to mimeType,
                KEY_DISAPPEAR_TTL to disappearTtl
            )
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            return OneTimeWorkRequestBuilder<PrivateMediaDownloadWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .addTag(TAG)
                .addTag("download_$messageId")
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    java.util.concurrent.TimeUnit.MILLISECONDS
                )
                .build()
        }
    }

    override suspend fun doWork(): Result {
        val messageId = inputData.getString(KEY_MESSAGE_ID) ?: return Result.failure()
        val mediaId = inputData.getString(KEY_MEDIA_ID) ?: return Result.failure()
        val totalChunks = inputData.getInt(KEY_TOTAL_CHUNKS, 0)
        val mimeType = inputData.getString(KEY_MIME_TYPE) ?: return Result.failure()

        if (totalChunks <= 0) {
            Log.e(TAG, "Invalid totalChunks: $totalChunks")
            return Result.failure()
        }

        val db = AppDb.get(context)
        val messageDao = db.messageDao()
        val notifier = MediaTransferNotifier(context)

        return try {
            Log.d(TAG, "Starting assembly for messageId=$messageId, mediaId=$mediaId, totalChunks=$totalChunks")
            setForeground(notifier.buildDownloadForegroundInfo(messageId, mimeType, 0))

            // Wait for all chunks to be saved with intelligent progress timeout
            val maxWaitTime = 120_000L        // 2 minutes absolute max
            val progressTimeoutMs = 60_000L   // If no progress for 60s, fail
            val checkInterval = 500L
            
            val startTime = System.currentTimeMillis()
            var lastProgressTime = System.currentTimeMillis()
            var lastSavedCount = 0
            var chunksReady = false

            while (!chunksReady && System.currentTimeMillis() - startTime < maxWaitTime) {
                val savedCount = MediaChunkManager.countSavedChunks(context, mediaId)
                val elapsedMs = System.currentTimeMillis() - startTime
                
                // Update progress time if we received new chunks
                if (savedCount > lastSavedCount) {
                    lastProgressTime = System.currentTimeMillis()
                    lastSavedCount = savedCount
                    val progress = (savedCount * 100) / totalChunks
                    Log.d(TAG, "[PROGRESS] $savedCount / $totalChunks chunks ($progress%)")
                    messageDao.updateDownloadProgress(messageId, "downloading", progress)
                    setForeground(notifier.buildDownloadForegroundInfo(messageId, mimeType, progress))
                }

                if (savedCount >= totalChunks) {
                    chunksReady = true
                    Log.d(TAG, "[SUCCESS] All chunks ready!")
                } else {
                    // Check if we've stalled (no new chunks for 60 seconds)
                    val timeSinceLastProgress = System.currentTimeMillis() - lastProgressTime
                    if (timeSinceLastProgress > progressTimeoutMs) {
                        Log.e(TAG, "[STALL] No progress ${timeSinceLastProgress}ms. Have $savedCount/$totalChunks")
                        messageDao.updateDownloadProgress(messageId, "failed", (savedCount * 100) / totalChunks)
                        notifier.showDownloadFailed(messageId, mimeType)
                        return Result.retry()
                    }
                    
                    delay(checkInterval)
                }
            }

            if (!chunksReady) {
                Log.e(TAG, "Timeout waiting for all chunks. Received $lastSavedCount / $totalChunks")
                messageDao.updateDownloadProgress(messageId, "failed", (lastSavedCount * 100) / totalChunks)
                notifier.showDownloadFailed(messageId, mimeType)
                return Result.retry()
            }

            Log.d(TAG, "All chunks received, assembling...")
            setForeground(notifier.buildDownloadForegroundInfo(messageId, mimeType, 90, stateLabel = "Assembling…"))

            // Final validation: ensure all chunks are actually present and not corrupted
            if (!MediaChunkManager.validateAllChunksPresent(context, mediaId, totalChunks)) {
                Log.e(TAG, "Chunk validation failed - some chunks are missing or empty")
                messageDao.updateDownloadProgress(messageId, "failed", 90)
                notifier.showDownloadFailed(messageId, mimeType)
                return Result.failure()
            }

            // Assemble chunks into a complete file
            val finalPath = MediaChunkManager.assembleChunksFromDisk(
                context, mediaId, mimeType, totalChunks
            ) ?: run {
                Log.e(TAG, "Assembly failed for mediaId=$mediaId")
                messageDao.updateDownloadProgress(messageId, "failed", 90)
                notifier.showDownloadFailed(messageId, mimeType)
                return Result.failure()
            }

            Log.d(TAG, "Assembly complete: $finalPath")

            // Update DB with final path and mark as done
            val disappearTtl = inputData.getLong(KEY_DISAPPEAR_TTL, 0L)
            if (disappearTtl > 0L) {
                val expiresAt = System.currentTimeMillis() + disappearTtl
                val time = System.currentTimeMillis().toString()
                messageDao.updateSentTime(messageId, time, expiresAt)
            }
            messageDao.setDownloadDone(messageId, "done", finalPath)
            notifier.showDownloadComplete(messageId, mimeType)

            Log.d(TAG, "Download worker complete for messageId=$messageId")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Download worker error for $messageId: ${e.message}", e)
            messageDao.updateDownloadProgress(messageId, "failed", 0)
            notifier.showDownloadFailed(messageId, mimeType)
            Result.retry()
        }
    }
}
