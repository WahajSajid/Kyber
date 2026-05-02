package app.secure.kyber.workers

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.*
import app.secure.kyber.media.MediaChunkManager
import app.secure.kyber.media.MediaTransferNotifier
import app.secure.kyber.media.VideoCompressor
import app.secure.kyber.roomdb.AppDb
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.*

class GroupMediaUploadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "GroupMediaUploadWorker"
        const val DB_URL = "https://kyber-b144e-default-rtdb.asia-southeast1.firebasedatabase.app/"

        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_GROUP_ID = "group_id"
        const val KEY_FILE_PATH = "file_path"
        const val KEY_MIME_TYPE = "mime_type"
        const val KEY_CAPTION = "caption"
        const val KEY_SENDER_ID = "sender_id"
        const val KEY_SENDER_NAME = "sender_name"
        const val KEY_DISAPPEAR_TTL = "disappear_ttl"
        const val KEY_REPLY_TO_TEXT = "reply_to_text"

        fun buildRequest(
            messageId: String,
            groupId: String,
            filePath: String,
            mimeType: String,
            caption: String,
            senderId: String,
            senderName: String,
            disappearTtl: Long = 0L,
            replyToText: String = ""
        ): OneTimeWorkRequest {
            val data = workDataOf(
                KEY_MESSAGE_ID to messageId,
                KEY_GROUP_ID to groupId,
                KEY_FILE_PATH to filePath,
                KEY_MIME_TYPE to mimeType,
                KEY_CAPTION to caption,
                KEY_SENDER_ID to senderId,
                KEY_SENDER_NAME to senderName,
                KEY_DISAPPEAR_TTL to disappearTtl,
                KEY_REPLY_TO_TEXT to replyToText
            )
            return OneTimeWorkRequestBuilder<GroupMediaUploadWorker>()
                .setInputData(data)
                .addTag(TAG)
                .addTag("group_upload_$messageId")
                .build()
        }
    }

    override suspend fun doWork(): Result {
        val messageId = inputData.getString(KEY_MESSAGE_ID) ?: return Result.failure()
        val groupId = inputData.getString(KEY_GROUP_ID) ?: return Result.failure()
        val filePath = inputData.getString(KEY_FILE_PATH) ?: return Result.failure()
        val mimeType = inputData.getString(KEY_MIME_TYPE) ?: return Result.failure()
        val caption = inputData.getString(KEY_CAPTION) ?: ""
        val senderId = inputData.getString(KEY_SENDER_ID) ?: ""
        val senderName = inputData.getString(KEY_SENDER_NAME) ?: ""
        val disappearTtl = inputData.getLong(KEY_DISAPPEAR_TTL, 0L)
        val replyToText = inputData.getString(KEY_REPLY_TO_TEXT) ?: ""

        val db = AppDb.get(context)
        val dao = db.groupsMessagesDao()
        val notifier = MediaTransferNotifier(context)
        val mediaId = UUID.randomUUID().toString()

        return try {
            val fgInfo = notifier.buildUploadForegroundInfo(messageId, mimeType, 0)
            setForeground(fgInfo)

            val localMessage = dao.getMessageById(messageId)
            val savedProgress = localMessage?.uploadProgress ?: 0
            val isUploading = localMessage?.uploadState == "uploading"

            // Resolve content:// URIs immediately to real file bounds
            val resolvedFile = app.secure.kyber.media.MediaChunkManager.resolveFile(context, filePath)
            var uploadFilePath = resolvedFile?.absolutePath ?: filePath
            var thumbnailPath: String? = localMessage?.thumbnailPath

            val existingLocalPath = localMessage?.localFilePath ?: uploadFilePath
            val hasCompressed = existingLocalPath.contains("sent_media") && isUploading

            // 1. Compression
            if (mimeType == "VIDEO") {
                if (hasCompressed) {
                    uploadFilePath = existingLocalPath
                    dao.updateUploadState(messageId, "uploading", savedProgress)
                } else {
                    dao.updateUploadState(messageId, "compressing", 0)
                    // Pass the resolved active path to compressor
                    val result = app.secure.kyber.media.VideoCompressor.compress(context, uploadFilePath) { pct ->
                        CoroutineScope(Dispatchers.IO).launch {
                            dao.updateUploadState(messageId, "compressing", pct)
                        }
                    }
                    if (result == null) {
                        dao.updateUploadState(messageId, "failed", 0)
                        return Result.failure()
                    }
                    uploadFilePath = result.outputPath
                    thumbnailPath = result.thumbnailPath
                    dao.setLocalFilePath(messageId, uploadFilePath)
                    if (thumbnailPath != null) {
                        dao.setThumbnailPath(messageId, thumbnailPath)
                    }
                }
            } else if (mimeType == "IMAGE") {
                if (!hasCompressed) {
                    dao.updateUploadState(messageId, "compressing", 50)
                    // Extract lightweight representation thumbnail for IMAGE
                    try {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(uploadFilePath)
                        if (bitmap != null) {
                            val thumb = android.media.ThumbnailUtils.extractThumbnail(bitmap, 300, 300)
                            val thumbFile = java.io.File(context.cacheDir, "thumb_${mediaId}.jpg")
                            thumbFile.outputStream().use { out ->
                                thumb.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
                            }
                            thumbnailPath = thumbFile.absolutePath
                        }
                    } catch (e: Exception) { Log.e(TAG, "Failed resolving gallery image thumbnail", e) }
                    dao.setLocalFilePath(messageId, uploadFilePath)
                }
            }

            dao.updateUploadState(messageId, "uploading", savedProgress)

            // 2. Chunking
            val chunks = MediaChunkManager.buildChunks(
                context = context,
                filePath = uploadFilePath,
                mediaId = mediaId,
                originalMessageId = messageId,
                mimeType = mimeType,
                caption = caption
            )

            if (chunks.isEmpty()) {
                dao.updateUploadState(messageId, "failed", 0)
                return Result.failure()
            }

            val database = FirebaseDatabase.getInstance(DB_URL)
            val msgRef = database.getReference("group_messages").child(groupId).child(messageId)
            val chunkRef = database.getReference("group_media_chunks").child(groupId).child(messageId)

            val startIndex = if (savedProgress > 0 && isUploading) {
                (savedProgress * chunks.size) / 100
            } else 0

            val chunksToSend = if (startIndex > 0 && startIndex < chunks.size) {
                chunks.subList(startIndex, chunks.size)
            } else chunks

            // 3. Upload Chunks
            for (chunk in chunksToSend) {
                if (isStopped) return Result.retry()

                chunkRef.child(chunk.index.toString()).setValue(chunk.data).await()
                
                val progress = ((chunk.index + 1) * 100) / chunks.size
                dao.updateUploadState(messageId, "uploading", progress)
                setForeground(notifier.buildUploadForegroundInfo(messageId, mimeType, progress))
            }

            // 4. Send Header
            val refreshedLocalMsg = dao.getMessageById(messageId)
            val header = mapOf(
                "messageId" to messageId,
                "group_id" to groupId,
                "senderOnion" to senderId,
                "senderName" to senderName,
                "msg" to caption,
                "time" to System.currentTimeMillis().toString(),
                "type" to "CHUNKED_MEDIA",
                "mediaType" to mimeType,
                "mediaId" to mediaId,
                "ampsJson" to (localMessage?.ampsJson ?: ""),
                "isSent" to true, // Set to true here because the payload signifies sender's upload
                "totalChunks" to chunks.size,
                "thumbnail" to (thumbnailPath?.let { encodeThumbnail(it) } ?: ""),
                "disappear_ttl" to disappearTtl,
                "replyToText" to replyToText
            )
            msgRef.setValue(header).await()

            // 5. Finalize local state
            val nowMs = System.currentTimeMillis()
            val nowStr = nowMs.toString()
            if (disappearTtl > 0L) {
                val expiresAt = nowMs + disappearTtl
                dao.updateSentTime(messageId, nowStr, expiresAt)
            } else {
                dao.updateSentTime(messageId, nowStr, 0L)
            }
            val finalLocalPath = uploadFilePath.removePrefix("file://")
            dao.updateMessageFields(messageId, finalLocalPath, "done", 100)
            
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Group Upload failed", e)
            dao.updateUploadState(messageId, "failed", 0)
            Result.retry()
        }
    }

    private fun encodeThumbnail(path: String): String {
        return try {
            val file = File(path)
            if (!file.exists()) return ""
            val bytes = file.readBytes()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) { "" }
    }
}
