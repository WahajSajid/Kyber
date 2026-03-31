package app.secure.kyber.media

import android.R.attr.mimeType
import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.core.net.toUri
import androidx.work.WorkManager
import app.secure.kyber.Utils.EncryptionUtils
import app.secure.kyber.roomdb.MessageDao
import app.secure.kyber.roomdb.MessageEntity
import app.secure.kyber.workers.MediaUploadWorker
import java.util.UUID

class MediaSender(
    private val context: Context,
    private val messageDao: MessageDao
) {
    // Repository removed from constructor — worker handles network via Hilt entry point

    suspend fun sendMedia(
        contactOnion: String,
        senderOnion: String,
        senderName: String,
        filePath: String,
        mimeType: String,
        caption: String,
        ampsJson: String = "",
        isContact: Boolean = true,
        onLocalMessageSaved: ((messageId: String) -> Unit)? = null
    ) {
        val messageId  = UUID.randomUUID().toString()
        val mediaId    = UUID.randomUUID().toString()
        val timestamp  = System.currentTimeMillis().toString()
        val fileSize   = resolveFileSize(filePath)
        val durationMs = if (mimeType != "IMAGE") getDurationMs(filePath) else 0L


        val durableFilePath = when (mimeType) {
            "IMAGE" -> prepareImageForUpload(context, filePath)   // compress + copy
            "AUDIO" -> copyToAppStorage(context, filePath, mimeType)
            else    -> filePath  // VIDEO handled by MediaUploadWorker
        }

        // 1. Insert local pending row immediately — UI sees bubble right away
        messageDao.insert(
            MessageEntity(
                messageId      = messageId,
                msg            = EncryptionUtils.encrypt(caption),
                senderOnion    = contactOnion,
                time           = timestamp,
                isSent         = true,
                type           = mimeType,
                uri            = EncryptionUtils.encrypt(filePath),
                ampsJson       = if (ampsJson.isNotBlank()) EncryptionUtils.encrypt(ampsJson) else "",
                uploadState    = "pending",
                uploadProgress = 0,
                remoteMediaId  = mediaId,
                localFilePath  = filePath.removePrefix("file://"),
                mediaDurationMs = durationMs,
                mediaSizeBytes = fileSize
            )
        )
        onLocalMessageSaved?.invoke(messageId)

        // 2. Enqueue durable WorkManager upload — survives fragment death, app background
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
            durationMs   = durationMs
        )
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "upload_$messageId",
                androidx.work.ExistingWorkPolicy.KEEP,
                request
            )
    }

    // ── Retry a previously failed upload ─────────────────────────────────────
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
        val caption    = try { EncryptionUtils.decrypt(entity.msg) } catch (e: Exception) { "" }
        val ampsJson   = try {
            if (entity.ampsJson.isNotBlank()) EncryptionUtils.decrypt(entity.ampsJson) else ""
        } catch (e: Exception) { "" }

        // Reset state to pending
        messageDao.updateUploadProgress(messageId, "pending", 0)

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

    // Add this private function to MediaSender:
    private fun compressImageIfNeeded(context: Context, filePath: String): String {
        return try {
            val file = java.io.File(filePath.removePrefix("file://"))
            if (!file.exists()) return filePath
            // Only compress images over 500 KB
            if (file.length() < 500 * 1024) return filePath

            val bitmap =
                android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return filePath
            // Cap at 1920px on longest side
            val maxDim = 1920
            val scale = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height, 1f)
            val scaled = if (scale < 1f) {
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                )
            } else bitmap

            val outFile =
                java.io.File(context.cacheDir, "compressed_${System.currentTimeMillis()}.jpg")
            outFile.outputStream().use { out ->
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
            }
            if (scaled != bitmap) scaled.recycle()
            bitmap.recycle()
            "file://${outFile.absolutePath}"
        } catch (e: Exception) {
            Log.e("MediaSender", "Image compression failed, using original", e)
            filePath
        }
    }


    // Replace copyToAppStorage + add compressAndCopyImage:

    private suspend fun prepareImageForUpload(context: Context, uriString: String): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Step 1: Resolve to a readable InputStream
                val uri = android.net.Uri.parse(uriString)
                val inputStream = when {
                    uriString.startsWith("file://") || uriString.startsWith("/") -> {
                        java.io.File(uriString.removePrefix("file://")).inputStream()
                    }
                    else -> context.contentResolver.openInputStream(uri)
                } ?: return@withContext uriString

                // Step 2: Decode with inJustDecodeBounds first to get dimensions
                val boundsOpts = android.graphics.BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                val boundsBytes = inputStream.readBytes()
                inputStream.close()
                android.graphics.BitmapFactory.decodeByteArray(boundsBytes, 0, boundsBytes.size, boundsOpts)

                val origW = boundsOpts.outWidth
                val origH = boundsOpts.outHeight

                // Step 3: Calculate sample size to avoid loading huge bitmap into memory
                val maxDim = 1920
                var sampleSize = 1
                var tmpW = origW; var tmpH = origH
                while (tmpW > maxDim * 2 || tmpH > maxDim * 2) {
                    sampleSize *= 2; tmpW /= 2; tmpH /= 2
                }

                // Step 4: Decode with sampling
                val decodeOpts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                }
                var bitmap = android.graphics.BitmapFactory.decodeByteArray(
                    boundsBytes, 0, boundsBytes.size, decodeOpts
                ) ?: return@withContext uriString

                // Step 5: Scale down if still over maxDim
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

                // Step 6: Preserve EXIF orientation
                try {
                    val exif = androidx.exifinterface.media.ExifInterface(
                        java.io.ByteArrayInputStream(boundsBytes)
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
                } catch (e: Exception) { /* EXIF read failed, continue without rotation fix */ }

                // Step 7: Compress to JPEG at 82% quality into app-owned storage
                val destDir = java.io.File(context.filesDir, "sent_media").apply { mkdirs() }
                val destFile = java.io.File(destDir, "img_${System.currentTimeMillis()}.jpg")
                destFile.outputStream().use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
                }
                bitmap.recycle()

                android.util.Log.d("MediaSender",
                    "Image compressed: ${boundsBytes.size / 1024}KB → ${destFile.length() / 1024}KB")

                "file://${destFile.absolutePath}"
            } catch (e: Exception) {
                android.util.Log.e("MediaSender", "Image compression failed, using original", e)
                // Fallback: just copy to durable storage without compression
                copyToAppStorage(context, uriString, "IMAGE")
            }
        }
    }

    private fun copyToAppStorage(context: Context, uriString: String, mimeType: String): String {
        if (uriString.startsWith("file://") || uriString.startsWith("/")) {
            val f = java.io.File(uriString.removePrefix("file://"))
            if (f.exists()) return "file://${f.absolutePath}"
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


}