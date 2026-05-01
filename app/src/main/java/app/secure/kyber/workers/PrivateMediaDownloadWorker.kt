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

            // Wait for all chunks to be saved
            val maxWaitTime = 120_000L // 2 minutes max
            val startTime = System.currentTimeMillis()
            var chunksReady = false

            while (!chunksReady && System.currentTimeMillis() - startTime < maxWaitTime) {
                val savedCount = MediaChunkManager.countSavedChunks(context, mediaId)
                Log.d(TAG, "Chunks saved: $savedCount / $totalChunks")

                if (savedCount >= totalChunks) {
                    chunksReady = true
                } else {
                    delay(500) // Check every 500ms
                }
            }

            if (!chunksReady) {
                Log.e(TAG, "Timeout waiting for all chunks to download")
                messageDao.updateDownloadProgress(messageId, "failed", 0)
                notifier.showDownloadFailed(messageId, mimeType)
                return Result.retry()
            }

            Log.d(TAG, "All chunks received, assembling...")
            setForeground(notifier.buildDownloadForegroundInfo(messageId, mimeType, 90, stateLabel = "Assembling…"))

            // Assemble chunks into a complete file
            val finalPath = MediaChunkManager.assembleChunksFromDisk(
                context, mediaId, mimeType
            ) ?: run {
                Log.e(TAG, "Assembly failed for mediaId=$mediaId")
                messageDao.updateDownloadProgress(messageId, "failed", 0)
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
