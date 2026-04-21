package app.secure.kyber.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import app.secure.kyber.R
import app.secure.kyber.activities.AppIntroSliderActivity
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.roomdb.AppDb
import java.io.File
import java.util.concurrent.Executors

/**
 * ═══════════════════════════════════════════════════════════════════════
 * WIPE SERVICE — 10-second countdown foreground service
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Survives app being killed. Posts notification with live countdown.
 * On completion: wipes all app data (DB, prefs, files, cache).
 * Optional UNDO button ("authorized" wipe only).
 */
class WipeService : Service() {

    companion object {
        private const val TAG = "WipeService"

        const val EXTRA_AUTHORIZED = "wipe_authorized"   // true = manual, false = unauthorized
        const val ACTION_CANCEL_WIPE = "app.secure.kyber.ACTION_CANCEL_WIPE"

        private const val NOTIFICATION_ID = 8888
        const val CHANNEL_ID = "kyber_wipe_channel"

        private const val WIPE_COUNTDOWN_SECONDS = 10
    }

    private val handler = Handler(Looper.getMainLooper())
    private var secondsRemaining = WIPE_COUNTDOWN_SECONDS
    private var isAuthorized = true   // true = manual wipe (UNDO allowed)
    private var cancelled = false

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_WIPE) {
            cancelWipe()
            return START_NOT_STICKY
        }

        isAuthorized = intent?.getBooleanExtra(EXTRA_AUTHORIZED, true) ?: true
        cancelled = false
        secondsRemaining = WIPE_COUNTDOWN_SECONDS

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildCountdownNotification(secondsRemaining))

        Prefs.setWipePending(applicationContext, true)

        handler.post(countdownRunnable)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Countdown runnable
    // ─────────────────────────────────────────────────────────────────────

    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (cancelled) return

            updateNotification(secondsRemaining)

            if (secondsRemaining <= 0) {
                performWipe()
                return
            }

            secondsRemaining--
            handler.postDelayed(this, 1_000L)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Cancel wipe
    // ─────────────────────────────────────────────────────────────────────

    private fun cancelWipe() {
        cancelled = true
        handler.removeCallbacksAndMessages(null)
        Prefs.setWipePending(applicationContext, false)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
        Log.d(TAG, "Wipe cancelled by user")
        stopSelf()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Perform wipe (runs on background thread for DB/file I/O)
    // ─────────────────────────────────────────────────────────────────────

    private fun performWipe() {
        Log.d(TAG, "Performing full data wipe")

        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                // 1. Wipe Room DB
                try {
                    AppDb.get(applicationContext).clearAllTables()
                    Log.d(TAG, "Room DB cleared")
                } catch (e: Exception) {
                    Log.e(TAG, "DB clear failed", e)
                }

                // 2. Wipe internal files
                try {
                    deleteRecursive(filesDir)
                    Log.d(TAG, "filesDir cleared")
                } catch (e: Exception) {
                    Log.e(TAG, "filesDir clear failed", e)
                }

                // 3. Wipe cache
                try {
                    deleteRecursive(cacheDir)
                    Log.d(TAG, "cacheDir cleared")
                } catch (e: Exception) {
                    Log.e(TAG, "cacheDir clear failed", e)
                }

                // 4. Wipe external cache
                try {
                    externalCacheDir?.let { deleteRecursive(it) }
                    Log.d(TAG, "externalCacheDir cleared")
                } catch (e: Exception) {
                    Log.e(TAG, "externalCacheDir clear failed", e)
                }

                // 5. Clear all SharedPreferences
                try {
                    // Clear both known prefs files
                    applicationContext.getSharedPreferences("kyber_prefs", Context.MODE_PRIVATE)
                        .edit().clear().commit()
                    applicationContext.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                        .edit().clear().commit()
                    Log.d(TAG, "SharedPreferences cleared")
                } catch (e: Exception) {
                    Log.e(TAG, "SharedPreferences clear failed", e)
                }

                // 6. Reset wipe pending flag (already cleared since prefs are wiped)
                Log.d(TAG, "Full wipe complete")

            } catch (e: Exception) {
                Log.e(TAG, "Wipe failed", e)
            } finally {
                // 7. Show final notification
                showWipeCompleteNotification()

                // 8. Navigate to onboarding on main thread
                handler.post {
                    try {
                        val intent = Intent(applicationContext, AppIntroSliderActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to navigate to onboarding", e)
                    }
                    stopSelf()
                }
            }
        }
    }

    private fun deleteRecursive(fileOrDir: File) {
        if (fileOrDir.isDirectory) {
            fileOrDir.listFiles()?.forEach { deleteRecursive(it) }
        }
        fileOrDir.delete()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Notification helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "KyberChat Security",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Security wipe countdown notification"
                setShowBadge(false)
                enableVibration(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildCountdownNotification(seconds: Int): Notification {
        val title = if (isAuthorized) "Wiping KyberChat data..." else "Wiping KyberChat due to unauthorized access"
        val text = "Wiping in $seconds second${if (seconds != 1) "s" else ""}..."

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.security_ic)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)

        // Only add UNDO action for authorized (manual) wipe
        if (isAuthorized) {
            val cancelIntent = Intent(this, WipeService::class.java).apply {
                action = ACTION_CANCEL_WIPE
            }
            val cancelPending = PendingIntent.getService(
                this, 0, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.security_ic,
                    "UNDO",
                    cancelPending
                ).build()
            )
        }

        return builder.build()
    }

    private fun updateNotification(seconds: Int) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildCountdownNotification(seconds))
        } catch (e: Exception) {
            Log.e(TAG, "updateNotification failed", e)
        }
    }

    private fun showWipeCompleteNotification() {
        try {
            val title = if (isAuthorized)
                "KyberChat has been wiped out successfully."
            else
                "KyberChat has been wiped due to unauthorized access."

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("KyberChat")
                .setContentText(title)
                .setSmallIcon(R.drawable.security_ic)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Use different ID so it stays after stopSelf
            nm.notify(NOTIFICATION_ID + 1, notification)
        } catch (e: Exception) {
            Log.e(TAG, "showWipeCompleteNotification failed", e)
        }
    }
}
