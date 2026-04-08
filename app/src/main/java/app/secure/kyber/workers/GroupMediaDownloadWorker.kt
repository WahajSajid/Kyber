package app.secure.kyber.workers

import android.content.Context
import android.util.Log
import androidx.work.*
import app.secure.kyber.media.MediaChunkManager
import app.secure.kyber.media.MediaTransferNotifier
import app.secure.kyber.roomdb.AppDb
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

class GroupMediaDownloadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "GroupMediaDownloadWorker"
        const val DB_URL = "https://kyber-b144e-default-rtdb.asia-southeast1.firebasedatabase.app/"

        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_GROUP_ID = "group_id"
        const val KEY_MEDIA_ID = "media_id"
        const val KEY_TOTAL_CHUNKS = "total_chunks"
        const val KEY_MIME_TYPE = "mime_type"

        fun buildRequest(
            messageId: String,
            groupId: String,
            mediaId: String,
            totalChunks: Int,
            mimeType: String
        ): OneTimeWorkRequest {
            val data = workDataOf(
                KEY_MESSAGE_ID to messageId,
                KEY_GROUP_ID to groupId,
                KEY_MEDIA_ID to mediaId,
                KEY_TOTAL_CHUNKS to totalChunks,
                KEY_MIME_TYPE to mimeType
            )
            return OneTimeWorkRequestBuilder<GroupMediaDownloadWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG)
                .addTag("group_download_$messageId")
                .build()
        }
    }

    override suspend fun doWork(): Result {
        val messageId = inputData.getString(KEY_MESSAGE_ID) ?: return Result.failure()
        val groupId = inputData.getString(KEY_GROUP_ID) ?: return Result.failure()
        val mediaId = inputData.getString(KEY_MEDIA_ID) ?: return Result.failure()
        val totalChunks = inputData.getInt(KEY_TOTAL_CHUNKS, 0)
        val mimeType = inputData.getString(KEY_MIME_TYPE) ?: "IMAGE"

        val db = AppDb.get(context)
        val dao = db.groupsMessagesDao()
        val notifier = MediaTransferNotifier(context)

        return try {
            val fgInfo = notifier.buildDownloadForegroundInfo(messageId, mimeType, 0)
            setForeground(fgInfo)

            dao.updateMessageFields(messageId, null, "downloading", 0)

            val database = FirebaseDatabase.getInstance(DB_URL)
            val chunkRef = database.getReference("group_media_chunks").child(groupId).child(messageId)

            val snapshot = chunkRef.get().await()
            if (!snapshot.exists()) return Result.failure()

            var downloadedCount = 0
            for (child in snapshot.children) {
                if (isStopped) return Result.retry()

                val index = child.key?.toIntOrNull() ?: continue
                val base64Data = child.getValue(String::class.java) ?: continue

                MediaChunkManager.saveChunkToDisk(context, mediaId, index, base64Data)
                downloadedCount++

                val progress = (downloadedCount * 100) / totalChunks
                dao.updateMessageFields(messageId, null, "downloading", progress)
                setForeground(notifier.buildDownloadForegroundInfo(messageId, mimeType, progress))
            }

            // Assembly 
            val assembledPath = MediaChunkManager.assembleChunks(context, mediaId, mimeType)
            if (assembledPath == null) {
                dao.updateMessageFields(messageId, null, "failed", 0)
                return Result.failure()
            }

            val finalLocalPath = assembledPath.removePrefix("file://")
            dao.updateMessageFields(messageId, finalLocalPath, "done", 100)
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Group download worker failed", e)
            dao.updateMessageFields(messageId, null, "failed", 0)
            Result.retry()
        }
    }
}
