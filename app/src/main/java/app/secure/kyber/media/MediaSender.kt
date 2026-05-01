package app.secure.kyber.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.core.net.toUri
import androidx.work.WorkManager
import app.secure.kyber.Utils.MessageEncryptionManager
import app.secure.kyber.roomdb.MessageDao
import app.secure.kyber.roomdb.MessageEntity
import app.secure.kyber.workers.MediaUploadWorker
import java.util.UUID

class MediaSender(
    private val context: Context,
    private val messageDao: MessageDao
) {
    suspend fun sendMedia(
        contactOnion: String,
        senderOnion: String,
        senderName: String,
        filePath: String,
        mimeType: String,
        caption: String,
        ampsJson: String = "",
        isContact: Boolean = true,
        replyToText: String = "",
        onLocalMessageSaved: ((messageId: String) -> Unit)? = null
    ) {
        val messageId  = UUID.randomUUID().toString()
        val mediaId    = UUID.randomUUID().toString()
        val sentTimeMs = System.currentTimeMillis()
        val timestamp  = sentTimeMs.toString()
        
        val durableFilePath = when (mimeType) {
            "IMAGE" -> prepareImageForUpload(context, filePath)
            "AUDIO" -> copyToAppStorage(context, filePath, mimeType)
            else    -> copyToAppStorage(context, filePath, mimeType) // Copy video too for persistence
        }

        val fileSize   = resolveFileSize(durableFilePath)
        val durationMs = if (mimeType != "IMAGE") getDurationMs(durableFilePath) else 0L

        // Generate thumbnail immediately so the bubble shows a real frame/preview
        // from the very first DB write — before the upload worker even starts.
        val earlyThumbnailPath: String? = when (mimeType) {
            "VIDEO" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                VideoCompressor.generateThumbnailForPath(context, durableFilePath)
            }
            "IMAGE" -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                generateImageThumbnail(context, durableFilePath)
            }
            else -> null
        }

        val disappearTimerMs = app.secure.kyber.backend.common.Prefs.getEffectiveDisappearingTimerMs(context, contactOnion)
        var localExpiresAt = 0L
        if (disappearTimerMs > 0L) {
            localExpiresAt = sentTimeMs + disappearTimerMs
        }

        // 1. Insert local pending row immediately
        messageDao.insert(
            MessageEntity(
                messageId            = messageId,
                msg                  = MessageEncryptionManager.encryptLocal(context, caption).encryptedBlob,
                senderOnion          = contactOnion,
                time                 = timestamp,
                isSent               = true,
                type                 = mimeType,
                uri                  = MessageEncryptionManager.encryptLocal(context, durableFilePath).encryptedBlob,
                ampsJson             = if (ampsJson.isNotBlank()) MessageEncryptionManager.encryptLocal(context, ampsJson).encryptedBlob else "",
                uploadState          = "pending",
                uploadProgress       = 0,
                remoteMediaId        = mediaId,
                localFilePath        = durableFilePath.removePrefix("file://"),
                mediaSizeBytes       = fileSize,
                thumbnailPath        = earlyThumbnailPath,
                expiresAt            = 0L, // Timer starts after successful upload
                replyToText          = replyToText,
                uploadedChunkIndices = "",       // Will be updated as chunks are sent
                downloadedChunkIndices = "",     // N/A for sender
                totalChunksExpected  = 0,        // Will be set by worker after building chunks
                uploadAttemptCount   = 0,        // Retry counter
                lastUploadAttemptTime = 0L       // Exponential backoff timestamp
            )
        )
        onLocalMessageSaved?.invoke(messageId)

        // 2. Enqueue durable WorkManager upload
        val request = MediaUploadWorker.buildRequest(
            messageId    = messageId,
            mediaId      = mediaId,
            filePath     = durableFilePath,
            mimeType     = mimeType,
            caption      = caption,
            ampsJson     = ampsJson,
            contactOnion = contactOnion,
            senderOnion  = senderOnion,
            senderName   = senderName,
            isContact    = isContact,
            durationMs   = durationMs,
            disappearTtl = disappearTimerMs,
            replyToText  = replyToText
        )
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "upload_$messageId",
                androidx.work.ExistingWorkPolicy.KEEP,
                request
            )
    }

    suspend fun retryUpload(
        messageId: String,
        contactOnion: String,
        senderOnion: String,
        senderName: String,
        isContact: Boolean
    ) {
        val entity = messageDao.getByMessageId(messageId) ?: return
        val filePath   = entity.localFilePath ?: return
        val mediaId    = entity.remoteMediaId ?: UUID.randomUUID().toString()
        val mimeType   = entity.type
        val caption    = try { MessageEncryptionManager.decryptLocal(context, entity.msg) } catch (e: Exception) { "" }
        val ampsJson   = try {
            if (entity.ampsJson.isNotBlank()) MessageEncryptionManager.decryptLocal(context, entity.ampsJson) else ""
        } catch (e: Exception) { "" }

        // Reset chunk-tracking fields for retry
        messageDao.update(
            entity.copy(
                uploadState = "pending",
                uploadProgress = 0,
                uploadAttemptCount = 0,
                uploadedChunkIndices = "",
                lastUploadAttemptTime = 0L
            )
        )

        val request = MediaUploadWorker.buildRequest(
            messageId    = messageId,
            mediaId      = mediaId,
            filePath     = "file://$filePath",
            mimeType     = mimeType,
            caption      = caption,
            ampsJson     = ampsJson,
            contactOnion = contactOnion,
            senderOnion  = senderOnion,
            senderName   = senderName,
            isContact    = isContact,
            durationMs   = entity.mediaDurationMs
        )
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "upload_$messageId",
                androidx.work.ExistingWorkPolicy.REPLACE,
                request
            )
    }

    private fun resolveFileSize(path: String): Long =
        try { java.io.File(path.removePrefix("file://")).length() } catch (e: Exception) { 0L }

    private fun getDurationMs(path: String): Long {
        return try {
            val r = MediaMetadataRetriever()
            r.setDataSource(context, path.toUri())
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            r.release()
            d
        } catch (e: Exception) { 0L }
    }

    private suspend fun prepareImageForUpload(context: Context, uriString: String): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val uri = android.net.Uri.parse(uriString)
                val inputStream = when {
                    uriString.startsWith("file://") || uriString.startsWith("/") -> {
                        java.io.File(uriString.removePrefix("file://")).inputStream()
                    }
                    else -> context.contentResolver.openInputStream(uri)
                } ?: return@withContext uriString

                val bytes = inputStream.readBytes()
                inputStream.close()
                
                val boundsOpts = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)

                val origW = boundsOpts.outWidth
                val origH = boundsOpts.outHeight

                val maxDim = 1920
                var sampleSize = 1
                var tmpW = origW; var tmpH = origH
                while (tmpW > maxDim * 2 || tmpH > maxDim * 2) {
                    sampleSize *= 2; tmpW /= 2; tmpH /= 2
                }

                val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                }
                var bitmap = android.graphics.BitmapFactory.decodeByteArray(
                    bytes, 0, bytes.size, decodeOpts
                ) ?: return@withContext uriString

                val longerSide = maxOf(bitmap.width, bitmap.height)
                if (longerSide > maxDim) {
                    val scale = maxDim.toFloat() / longerSide
                    val scaled = android.graphics.Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt(),
                        (bitmap.height * scale).toInt(),
                        true
                    )
                    bitmap.recycle()
                    bitmap = scaled
                }

                try {
                    val exif = androidx.exifinterface.media.ExifInterface(
                        java.io.ByteArrayInputStream(bytes)
                    )
                    val orientation = exif.getAttributeInt(
                        androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                    )
                    val matrix = android.graphics.Matrix()
                    when (orientation) {
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90  -> matrix.postRotate(90f)
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                        androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                    }
                    if (!matrix.isIdentity) {
                        val rotated = android.graphics.Bitmap.createBitmap(
                            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                        )
                        bitmap.recycle()
                        bitmap = rotated
                    }
                } catch (e: Exception) {}

                val destDir = java.io.File(context.filesDir, "sent_media").apply { mkdirs() }
                val destFile = java.io.File(destDir, "img_${System.currentTimeMillis()}.jpg")
                destFile.outputStream().use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
                }
                bitmap.recycle()

                "file://${destFile.absolutePath}"
            } catch (e: Exception) {
                copyToAppStorage(context, uriString, "IMAGE")
            }
        }
    }

    private fun copyToAppStorage(context: Context, uriString: String, mimeType: String): String {
        if (uriString.startsWith("file://") || uriString.startsWith("/")) {
            val f = java.io.File(uriString.removePrefix("file://"))
            if (f.exists()) {
                val destDir = java.io.File(context.filesDir, "sent_media").apply { mkdirs() }
                val destFile = java.io.File(destDir, "sent_${System.currentTimeMillis()}_${f.name}")
                f.copyTo(destFile, overwrite = true)
                return "file://${destFile.absolutePath}"
            }
        }
        return try {
            val uri = android.net.Uri.parse(uriString)
            val ext = when (mimeType.uppercase()) { "VIDEO" -> "mp4"; "AUDIO" -> "m4a"; else -> "jpg" }
            val destDir = java.io.File(context.filesDir, "sent_media").apply { mkdirs() }
            val destFile = java.io.File(destDir, "sent_${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(uri)?.use { i ->
                destFile.outputStream().use { o -> i.copyTo(o) }
            }
            if (destFile.exists() && destFile.length() > 0) "file://${destFile.absolutePath}"
            else uriString
        } catch (e: Exception) { uriString }
    }
    private fun generateImageThumbnail(context: Context, path: String): String? {
        return try {
            val file = java.io.File(path.removePrefix("file://"))
            if (!file.exists()) return null
            
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val thumb = android.media.ThumbnailUtils.extractThumbnail(bitmap, 320, 320)
            bitmap.recycle()
            
            val destDir = java.io.File(context.cacheDir, "thumbnails").apply { mkdirs() }
            val destFile = java.io.File(destDir, "thumb_${System.currentTimeMillis()}.jpg")
            destFile.outputStream().use { out ->
                thumb.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
            }
            thumb.recycle()
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e("MediaSender", "Error generating image thumbnail: ${e.message}")
            null
        }
    }
}
