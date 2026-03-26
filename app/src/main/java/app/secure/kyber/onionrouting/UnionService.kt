package app.secure.kyber.onionrouting

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import app.secure.kyber.R
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.MessageDao
import app.secure.kyber.roomdb.MessageEntity
import kotlinx.coroutines.*

class UnionService : Service() {

    companion object {
        private const val TAG = "UnionService"
        private const val NOTIFICATION_ID = 1001
        private const val MSG_CHANNEL_ID = "union_messages_channel"
        private const val CHANNEL_ID = "union_service_channel"

        const val ACTION_START_SERVICE = "START_UNION_SERVICE"
        const val ACTION_STOP_SERVICE = "STOP_UNION_SERVICE"

        const val EXTRA_SERVER_HOST = "server_host"
        const val EXTRA_SERVER_PORT = "server_port"
    }

    private val binder = UnionBinder()
    private lateinit var unionClient: UnionClient
    private lateinit var messageDao: MessageDao
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Keeps CPU awake for socket networking when screen is off
    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceRunning = false

    inner class UnionBinder : Binder() {
        fun getService(): UnionService = this@UnionService
        fun getUnionClient(): UnionClient = unionClient
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Union Service created")
        isServiceRunning = true

        // 1. Acquire Partial WakeLock to ensure messages arrive when phone is locked
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kyber::SocketWakeLock")
        wakeLock?.acquire()

        unionClient = UnionClient()
        messageDao = AppDb.get(applicationContext).messageDao()
        createNotificationChannels()

        // 2. GLOBAL MESSAGE RECEIVER
        unionClient.setMessageCallback { message ->
            handleGlobalMessage(message)
        }
    }

    private fun handleGlobalMessage(message: UnionClient.UnionMessage) {
        serviceScope.launch {
            try {
                // Decode payload SENDER::MESSAGE
                val parts = message.content.split("::", limit = 2)
                var actualSender = message.from
                var actualText = message.content

                if (parts.size == 2) {
                    actualSender = parts[0]
                    actualText = parts[1]
                }

                var msgType = "TEXT"
                var msgUri: String? = null
                var displayText = actualText

                if (actualText.startsWith("[IMAGE] ")) {
                    msgType = "IMAGE"
                    msgUri = actualText.removePrefix("[IMAGE] ")
                    displayText = "📷 Photo"
                } else if (actualText.startsWith("[VIDEO] ")) {
                    msgType = "VIDEO"
                    msgUri = actualText.removePrefix("[VIDEO] ")
                    displayText = "🎥 Video"
                } else if (actualText.startsWith("[AUDIO] ")) {
                    msgType = "AUDIO"
                    msgUri = actualText.removePrefix("[AUDIO] ")
                    displayText = "🎵 Voice Message"
                }

                // Save to Room DB globally (Chat UI will auto-update if open)
                val entity = MessageEntity(
                    messageId = message.id,
                    msg = actualText,
                    senderOnion = actualSender,
                    time = System.currentTimeMillis().toString(),
                    isSent = false,
                    type = msgType,
                    uri = msgUri
                )
                messageDao.insert(entity)
                Log.d(TAG, "Message saved to DB globally from $actualSender")

                // Show push notification
                showPushNotification(actualSender, displayText)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing global message", e)
            }
        }
    }

    private fun showPushNotification(sender: String, messageText: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, MSG_CHANNEL_ID)
            .setContentTitle("New message")
            .setContentText(messageText)
            .setSmallIcon(R.drawable.notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        notificationManager.notify(sender.hashCode(), notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceNotification()

        // Fallback properties for when the OS restarts the service automatically (intent is null)
        val serverHost = intent?.getStringExtra(EXTRA_SERVER_HOST) ?: "82.221.100.220"
        val serverPort = intent?.getIntExtra(EXTRA_SERVER_PORT, 8080) ?: 8080

        // 3. Inject REAL identity before connecting!
        val myRealOnion = Prefs.getOnionAddress(applicationContext)
        if (!myRealOnion.isNullOrEmpty()) {
            unionClient.setClientIdentity(myRealOnion)
            Log.d(TAG, "Injected real identity: $myRealOnion")
        }

        // 4. Start infinite reconnection loop
        startPersistentConnectionLoop(serverHost, serverPort)

        return START_STICKY // Tells OS to recreate service if killed
    }

    private fun startPersistentConnectionLoop(host: String, port: Int) {
        serviceScope.launch {
            while (isServiceRunning) {
                val state = unionClient.connectionState.value
                if (state == UnionClient.ConnectionState.DISCONNECTED || state == UnionClient.ConnectionState.ERROR) {
                    Log.d(TAG, "Socket not connected. Reconnecting...")
                    unionClient.connect(host, port)
                }
                delay(8000) // Check health every 8 seconds forever
            }
        }
    }

    @SuppressLint("ForegroundServiceType")
    private fun startForegroundServiceNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kyber Background Sync")
            .setContentText("Listening for secure messages...")
            .setSmallIcon(R.drawable.notification)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun createNotificationChannels() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Background Service", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
            val messageChannel = NotificationChannel(MSG_CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(messageChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        if (wakeLock?.isHeld == true) wakeLock?.release()
        serviceScope.launch {
            unionClient.disconnect()
            unionClient.cleanup()
        }
        serviceScope.cancel()
    }
}
