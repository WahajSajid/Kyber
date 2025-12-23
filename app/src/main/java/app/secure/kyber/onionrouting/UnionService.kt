package app.secure.kyber.onionrouting

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import app.secure.kyber.R
import kotlinx.coroutines.*

/**
 * Background service for maintaining Union connection
 */
class UnionService : Service() {
    
    companion object {
        private const val TAG = "UnionService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "union_service_channel"
        
        const val ACTION_START_SERVICE = "START_UNION_SERVICE"
        const val ACTION_STOP_SERVICE = "STOP_UNION_SERVICE"
        
        const val EXTRA_SERVER_HOST = "server_host"
        const val EXTRA_SERVER_PORT = "server_port"
    }
    
    private val binder = UnionBinder()
    private lateinit var unionClient: UnionClient
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    inner class UnionBinder : Binder() {
        fun getService(): UnionService = this@UnionService
        fun getUnionClient(): UnionClient = unionClient
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Union Service created")
        
        unionClient = UnionClient()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                val serverHost = intent.getStringExtra(EXTRA_SERVER_HOST) ?: "localhost"
                val serverPort = intent.getIntExtra(EXTRA_SERVER_PORT, 8080)
                
                startForegroundService()
                connectToUnionServer(serverHost, serverPort)
            }
            ACTION_STOP_SERVICE -> {
                stopUnionService()
            }
        }
        
        return START_STICKY // Restart if killed
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    @SuppressLint("ForegroundServiceType")
    private fun startForegroundService() {
        val notification = createNotification("Connecting to Union network...")
        startForeground(NOTIFICATION_ID, notification)
    }
    
    private fun connectToUnionServer(serverHost: String, serverPort: Int) {
        serviceScope.launch {
            try {
                val result = unionClient.connect(serverHost, serverPort) { state ->
                    when (state) {
                        UnionClient.ConnectionState.CONNECTED -> {
                            updateNotification("Connected to Union network")
                        }
                        UnionClient.ConnectionState.ERROR -> {
                            updateNotification("Union connection error")
                        }
                        UnionClient.ConnectionState.DISCONNECTED -> {
                            updateNotification("Disconnected from Union network")
                        }
                        else -> {
                            updateNotification("Union connection: ${state.name}")
                        }
                    }
                }
                
                result.fold(
                    onSuccess = {
                        Log.d(TAG, "Successfully connected to Union server")
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to connect to Union server", error)
                        updateNotification("Connection failed: ${error.message}")
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error in Union service", e)
                updateNotification("Service error: ${e.message}")
            }
        }
    }
    
    private fun stopUnionService() {
        serviceScope.launch {
            unionClient.disconnect()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Union Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Union messaging service"
            setShowBadge(false)
        }
        
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
    
    private fun createNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Union Messaging")
            .setContentText(message)
            .setSmallIcon(R.drawable.notification) // You'll need to add this icon
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }
    
    private fun updateNotification(message: String) {
        val notification = createNotification(message)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Union Service destroyed")
        
        serviceScope.launch {
            unionClient.disconnect()
            unionClient.cleanup()
        }
        
        serviceScope.cancel()
    }


}