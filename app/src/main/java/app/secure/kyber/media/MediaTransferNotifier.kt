package app.secure.kyber.media

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import app.secure.kyber.R

class MediaTransferNotifier(private val context: Context) {

    companion object {
        const val UPLOAD_CHANNEL_ID   = "media_upload_channel"
        const val DOWNLOAD_CHANNEL_ID = "media_download_channel"
        private const val UPLOAD_BASE_ID   = 90000
        private const val DOWNLOAD_BASE_ID = 91000
    }

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init { createChannels() }

    private fun createChannels() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    UPLOAD_CHANNEL_ID, "Media Uploads",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    DOWNLOAD_CHANNEL_ID, "Media Downloads",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }
    }

    private fun launchIntent(): PendingIntent {
        val i = context.packageManager.getLaunchIntentForPackage(context.packageName)
        return PendingIntent.getActivity(
            context, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun typeLabel(mimeType: String) = when (mimeType.uppercase()) {
        "AUDIO" -> "Voice message"
        "VIDEO" -> "Video"
        else    -> "Image"
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    fun buildUploadForegroundInfo(
        messageId: String,
        mimeType: String,
        progress: Int,
        stateLabel: String = ""   // ADD THIS PARAMETER
    ): ForegroundInfo {
        val notifId = UPLOAD_BASE_ID + messageId.hashCode().and(0xFFFF)
        val label = typeLabel(mimeType)
        val contentText = when {
            stateLabel.isNotBlank() -> "$stateLabel $progress%"
            progress == 0 -> "Starting…"
            else -> "$progress%"
        }
        val notification = NotificationCompat.Builder(context, UPLOAD_CHANNEL_ID)
            .setContentTitle("Sending $label")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.app_ic)
            .setProgress(100, progress, progress == 0 && stateLabel.isBlank())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launchIntent())
            .build()
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, notification)
        }
    }

    fun showCompressionProgress(messageId: String, progress: Int) {
        val notifId = UPLOAD_BASE_ID + messageId.hashCode().and(0xFFFF)
        val n = NotificationCompat.Builder(context, UPLOAD_CHANNEL_ID)
            .setContentTitle("Compressing video")
            .setContentText("$progress%")
            .setSmallIcon(R.drawable.app_ic)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launchIntent())
            .build()
        nm.notify(notifId, n)
    }

    fun showUploadComplete(messageId: String, mimeType: String) {
        val notifId = UPLOAD_BASE_ID + messageId.hashCode().and(0xFFFF)
        val n = NotificationCompat.Builder(context, UPLOAD_CHANNEL_ID)
            .setContentTitle("${typeLabel(mimeType)} sent")
            .setContentText("Delivered successfully")
            .setSmallIcon(R.drawable.app_ic)
            .setAutoCancel(true)
            .setContentIntent(launchIntent())
            .build()
        nm.notify(notifId, n)
    }

    fun showUploadFailed(messageId: String, mimeType: String) {
        val notifId = UPLOAD_BASE_ID + messageId.hashCode().and(0xFFFF)
        val n = NotificationCompat.Builder(context, UPLOAD_CHANNEL_ID)
            .setContentTitle("${typeLabel(mimeType)} failed to send")
            .setContentText("Tap to retry")
            .setSmallIcon(R.drawable.app_ic)
            .setAutoCancel(true)
            .setContentIntent(launchIntent())
            .build()
        nm.notify(notifId, n)
    }

    // ── Download ──────────────────────────────────────────────────────────────

    fun showDownloadProgress(messageId: String, mimeType: String, progress: Int) {
        val notifId = DOWNLOAD_BASE_ID + messageId.hashCode().and(0xFFFF)
        val n = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setContentTitle("Receiving ${typeLabel(mimeType)}")
            .setContentText("$progress%")
            .setSmallIcon(R.drawable.app_ic)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launchIntent())
            .build()
        nm.notify(notifId, n)
    }

    fun showDownloadComplete(messageId: String, mimeType: String) {
        val notifId = DOWNLOAD_BASE_ID + messageId.hashCode().and(0xFFFF)
        val n = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setContentTitle("${typeLabel(mimeType)} received")
            .setContentText("Tap to open")
            .setSmallIcon(R.drawable.app_ic)
            .setAutoCancel(true)
            .setContentIntent(launchIntent())
            .build()
        nm.notify(notifId, n)
    }

    fun showDownloadFailed(messageId: String, mimeType: String) {
        val notifId = DOWNLOAD_BASE_ID + messageId.hashCode().and(0xFFFF)
        val n = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setContentTitle("${typeLabel(mimeType)} download failed")
            .setContentText("Open chat to retry")
            .setSmallIcon(R.drawable.app_ic)
            .setAutoCancel(true)
            .setContentIntent(launchIntent())
            .build()
        nm.notify(notifId, n)
    }

    fun cancel(messageId: String) {
        nm.cancel(UPLOAD_BASE_ID + messageId.hashCode().and(0xFFFF))
        nm.cancel(DOWNLOAD_BASE_ID + messageId.hashCode().and(0xFFFF))
    }

    fun buildDownloadForegroundInfo(
        messageId: String,
        mimeType: String,
        progress: Int,
        stateLabel: String = ""
    ): ForegroundInfo {
        val notifId = DOWNLOAD_BASE_ID + messageId.hashCode().and(0xFFFF)
        val label = typeLabel(mimeType)
        val isIndeterminate = progress == 0 && stateLabel.isBlank()
        
        val contentText = when {
            stateLabel.isNotBlank() -> "$stateLabel $progress%"
            isIndeterminate -> "Starting…"
            else -> "$progress%"
        }

        val notification = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
            .setContentTitle("Receiving $label")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.app_ic)
            .setProgress(100, progress, isIndeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(launchIntent())
            .build()
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notifId, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notifId, notification)
        }
    }


}