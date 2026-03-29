package app.secure.kyber.media

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
            filePath     = filePath,
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
}