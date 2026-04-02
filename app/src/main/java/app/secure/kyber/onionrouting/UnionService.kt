package app.secure.kyber.onionrouting

import android.annotation.SuppressLint
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
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import app.secure.kyber.R
import app.secure.kyber.Utils.EncryptionUtils
import app.secure.kyber.backend.KyberRepository
import app.secure.kyber.backend.beans.MediaChunkDto
import app.secure.kyber.backend.beans.PrivateMessageTransportDto
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.media.MediaChunkManager
import app.secure.kyber.media.MediaTransferNotifier
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.ContactRepository
import app.secure.kyber.roomdb.MessageDao
import app.secure.kyber.roomdb.MessageEntity
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
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
    private lateinit var contactRepo: ContactRepository
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val chunkMutexes = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    private lateinit var transferNotifier: MediaTransferNotifier

    @Inject
    lateinit var repository: KyberRepository

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val transportAdapter = moshi.adapter(PrivateMessageTransportDto::class.java)

    private val chunkAdapter = moshi.adapter(MediaChunkDto::class.java)

    private var wakeLock: PowerManager.WakeLock? = null
    private var isServiceRunning = false
    private var pollingJob: Job? = null

    // In-memory cache for per-sender grouped notifications
    private val unreadNotifications = mutableMapOf<String, MutableList<String>>()

    inner class UnionBinder : Binder() {
        fun getService(): UnionService = this@UnionService
        fun getUnionClient(): UnionClient = unionClient
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Union Service created")
        isServiceRunning = true

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Kyber::SocketWakeLock")
        wakeLock?.acquire()

        val db = AppDb.get(applicationContext)
        messageDao = db.messageDao()
        contactRepo = ContactRepository(db.contactDao())

        unionClient = UnionClient()
        createNotificationChannels()
        transferNotifier = MediaTransferNotifier(this)

        // 1. GLOBAL SOCKET RECEIVER
        unionClient.setMessageCallback { message ->
            handleSocketMessage(message)
        }

        // 2. START GLOBAL API POLLING
        startGlobalPolling()
        serviceScope.launch { performColdStartBackfill() }
    }

    private fun startGlobalPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            refreshCircuitOnStart()
            // Fast initial polls to catch up quickly after restart
            var pollCount = 0
            while (isActive && isServiceRunning) {
                fetchGlobalMessages()
                // First 10 polls: every 1s (fast catch-up)
                // After that: every 3s (steady state, saves battery)
                val delayMs = if (pollCount < 10) 1000L else 3000L
                pollCount++
                delay(delayMs)
            }
        }
    }

    private suspend fun refreshCircuitOnStart() {
        try {
            val existing = Prefs.getCircuitId(applicationContext)
            if (existing.isNullOrEmpty()) {
                val resp = repository.createCircuit()
                if (resp.isSuccessful) {
                    Prefs.setCircuitId(applicationContext, resp.body()?.circuitId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Circuit refresh on start failed: ${e.message}")
        }
    }

    private suspend fun refreshCircuitIfNeeded(forceNew: Boolean = false): String? {
        var circuitId = if (forceNew) null else Prefs.getCircuitId(applicationContext)
        if (circuitId.isNullOrEmpty()) {
            try {
                val resp = repository.createCircuit()
                if (resp.isSuccessful) {
                    circuitId = resp.body()?.circuitId
                    Prefs.setCircuitId(applicationContext, circuitId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Circuit creation failed: ${e.message}")
            }
        }
        return circuitId
    }

    private suspend fun fetchGlobalMessages() {
        try {
            val myOnion = Prefs.getOnionAddress(applicationContext) ?: return
            var circuitId = refreshCircuitIfNeeded()

            // Pass the last known message timestamp so the server
            // can return messages we missed while the app was closed.
            // If the server ignores this param, we fall back to
            // deduplication via apiMessageId unique index in the DB.
            val since = Prefs.getLastSyncTime(applicationContext)
                .takeIf { it > 0L }

            var response = repository.getMessages(myOnion, circuitId, since)

            if (!response.isSuccessful && response.code() == 400) {
                Log.w(TAG, "Circuit expired during poll, refreshing...")
                circuitId = refreshCircuitIfNeeded(forceNew = true)
                if (circuitId.isNullOrEmpty()) return
                response = repository.getMessages(myOnion, circuitId, since)
            }

            if (response.isSuccessful) {
                val messages = response.body()?.messages ?: emptyList()
                processApiMessages(messages)
                // Always update sync time even if no messages — proves we reached server
                Prefs.setLastSyncTime(applicationContext, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Global polling error", e)
        }
    }

    private fun processApiMessages(messages: List<app.secure.kyber.backend.beans.ApiMessage>) {
        if (messages.isEmpty()) return
        messages.forEach { apiMsg ->
            val decodedPayload = try {
                String(Base64.decode(apiMsg.payload, Base64.NO_WRAP), Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "Base64 decode failed for msg ${apiMsg.id}", e)
                return@forEach
            }

            try {
                val transport = transportAdapter.fromJson(decodedPayload)
                if (transport != null) {
                    serviceScope.launch {
                        try {
                            handleTransport(transport, apiMsg)
                        } catch (e: Exception) {
                            Log.e(TAG, "handleTransport failed for ${apiMsg.id}", e)
                        }
                    }
                } else {
                    serviceScope.launch {
                        try {
                            handleLegacyMessage(apiMsg, decodedPayload)
                        } catch (e: Exception) {
                            Log.e(TAG, "handleLegacyMessage failed for ${apiMsg.id}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                serviceScope.launch {
                    try {
                        handleLegacyMessage(apiMsg, decodedPayload)
                    } catch (ex: Exception) {
                        Log.e(TAG, "Legacy fallback failed for ${apiMsg.id}", ex)
                    }
                }
            }
        }
    }

    private suspend fun handleTransport(
        transport: PrivateMessageTransportDto,
        apiMsg: app.secure.kyber.backend.beans.ApiMessage?
    ) {
        if (transport.isAcceptance) {
            handleAcceptanceAck(transport)
            return
        }

        // Handle chunked media transfer
        if (transport.type == "CHUNK" && !transport.uri.isNullOrBlank()) {
            handleChunk(transport, apiMsg)
            return
        }

        if (transport.isRequest && transport.senderName.isNotBlank()) {
            cachePendingContactName(transport.senderOnion, transport.senderName)
        }

        val existing = messageDao.getMessageByMessageId(transport.messageId)
        if (existing == null) {
            val encryptedUri = if (!transport.uri.isNullOrBlank())
                EncryptionUtils.encrypt(transport.uri)
            else null

            val entity = MessageEntity(
                messageId = transport.messageId,
                msg = EncryptionUtils.encrypt(transport.msg),
                senderOnion = transport.senderOnion,
                time = System.currentTimeMillis().toString(),
                isSent = false,
                type = transport.type ?: "TEXT",
                uri = encryptedUri,
                ampsJson = transport.ampsJson ?: "",
                apiMessageId = apiMsg?.id ?: "",
                reaction = transport.reaction ?: "",
                isRequest = transport.isRequest
            )
            messageDao.insert(entity)

            val isAccepted = contactRepo.getContact(transport.senderOnion) != null
            if (transport.isRequest || !isAccepted) {
                val msgCount = messageDao.getBySender(transport.senderOnion).size
                if (msgCount == 1) {
                    val name = transport.senderName.ifBlank { "Unknown User" }
                    showPushNotification(transport.senderOnion, name, "New message request")
                }
            } else {
                val displayMsg = generateDisplayMessage(transport.msg, transport.type ?: "TEXT")
                val name = contactRepo.getContact(transport.senderOnion)?.name ?: "Unknown User"
                showPushNotification(transport.senderOnion, name, displayMsg)
            }

        } else {
            if (existing.reaction != transport.reaction) {
                existing.reaction = transport.reaction ?: ""
                val isRemoval =
                    transport.reaction.isNullOrEmpty() || transport.reaction.endsWith("|")
                existing.updatedAt = if (isRemoval) "" else System.currentTimeMillis().toString()
                messageDao.update(existing)

                if (!isRemoval && existing.reaction.isNotBlank()) {
                    val contact = contactRepo.getContact(transport.senderOnion)
                    val senderName = contact?.name ?: "Unknown User"
                    val actualEmoji = existing.reaction.substringAfter("|")
                    val notificationMsg = when (existing.type.uppercase(java.util.Locale.US)) {
                        "IMAGE" -> "Reacted $actualEmoji to a photo"
                        "VIDEO" -> "Reacted $actualEmoji to a video"
                        "AUDIO" -> "Reacted $actualEmoji to a voice message"
                        else -> {
                            val decryptedOriginalMsg = EncryptionUtils.decrypt(existing.msg)
                            val preview =
                                if (decryptedOriginalMsg.length > 30) decryptedOriginalMsg.take(27) + "..." else decryptedOriginalMsg
                            "Reacted $actualEmoji to: \"$preview\""
                        }
                    }
                    showPushNotification(transport.senderOnion, senderName, notificationMsg)
                }
            }
        }
    }

    private suspend fun handleAcceptanceAck(transport: PrivateMessageTransportDto) {
        val existing = contactRepo.getContact(transport.senderOnion)
        if (existing == null) {
            val name = transport.senderName.ifBlank { transport.senderOnion }
            contactRepo.saveContact(onionAddress = transport.senderOnion, name = name)
        }
    }

    private fun cachePendingContactName(senderOnion: String, senderName: String) {
        applicationContext.getSharedPreferences("contact_name_cache", Context.MODE_PRIVATE)
            .edit()
            .putString("pending_name_$senderOnion", senderName)
            .apply()
    }

    private suspend fun handleLegacyMessage(
        apiMsg: app.secure.kyber.backend.beans.ApiMessage,
        decodedPayload: String
    ) {
        val senderOnion = apiMsg.senderOnion ?: return
        if (senderOnion.isEmpty()) return

        var msgText = decodedPayload
        var msgType = "TEXT"
        var msgUri: String? = null

        when {
            decodedPayload.startsWith("[IMAGE] ") -> {
                msgType = "IMAGE"
                msgUri = decodedPayload.removePrefix("[IMAGE] ")
                msgText = "photo"
            }

            decodedPayload.startsWith("[VIDEO] ") -> {
                msgType = "VIDEO"
                msgUri = decodedPayload.removePrefix("[VIDEO] ")
                msgText = "video"
            }

            decodedPayload.startsWith("[AUDIO] ") -> {
                msgType = "AUDIO"
                msgUri = decodedPayload.removePrefix("[AUDIO] ")
                msgText = "Voice Message"
            }
        }

        val encryptedUri = if (!msgUri.isNullOrBlank()) EncryptionUtils.encrypt(msgUri) else null

        val entity = MessageEntity(
            messageId = UUID.randomUUID().toString(),
            msg = EncryptionUtils.encrypt(msgText),
            senderOnion = senderOnion,
            time = System.currentTimeMillis().toString(),
            isSent = false,
            type = msgType,
            uri = encryptedUri,
            apiMessageId = apiMsg.id
        )
        // Ensure not duplicate
        try {
            messageDao.insert(entity)
            val displayMsg = generateDisplayMessage(msgText, msgType)
            val contact = contactRepo.getContact(senderOnion)
            val name = contact?.name ?: "Unknown User"
            showPushNotification(senderOnion, name, displayMsg)
        } catch (e: Exception) {
            // Might be duplicate apiMessageId
        }
    }

    private fun parseApiTimestamp(timestamp: String): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val date = sdf.parse(timestamp)
            date?.time?.toString() ?: System.currentTimeMillis().toString()
        } catch (e: Exception) {
            System.currentTimeMillis().toString()
        }
    }

    private fun handleSocketMessage(message: UnionClient.UnionMessage) {
        serviceScope.launch {
            try {
                val decodedPayload = try {
                    String(Base64.decode(message.content, Base64.NO_WRAP), Charsets.UTF_8)
                } catch (e: Exception) {
                    message.content
                }

                try {
                    val transport = transportAdapter.fromJson(decodedPayload)
                    if (transport != null) {
                        handleTransport(transport, null)
                    } else {
                        handleLegacySocketMessage(message, decodedPayload)
                    }
                } catch (e: Exception) {
                    handleLegacySocketMessage(message, decodedPayload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing global socket message", e)
            }
        }
    }

    private suspend fun handleLegacySocketMessage(
        message: UnionClient.UnionMessage,
        decodedPayload: String
    ) {
        val entity = MessageEntity(
            messageId = UUID.randomUUID().toString(),
            msg = EncryptionUtils.encrypt(decodedPayload),
            senderOnion = message.from,
            time = System.currentTimeMillis().toString(),
            isSent = false,
            type = "TEXT",
            apiMessageId = ""
        )
        messageDao.insert(entity)
        val contact = contactRepo.getContact(message.from)
        val name = contact?.name ?: "Unknown User"
        showPushNotification(message.from, name, decodedPayload)
    }

    private fun generateDisplayMessage(text: String, type: String): String {
        return when (type) {
            "IMAGE" -> "📷 Photo"
            "VIDEO" -> "🎥 Video"
            "AUDIO" -> "🎵 Voice Message"
            else -> if (text.startsWith("Voice Message")) "🎵 Voice Message" else text
        }
    }

    private fun showPushNotification(sender: String, title: String, messageText: String) {
        try {
            val myApp = applicationContext as app.secure.kyber.MyApp.MyApp
            if (myApp.activeChatOnion == sender) {
                unreadNotifications.remove(sender)
                return // Suppress notification if currently in this exact chat
            }
        } catch (e: Exception) {
        }

        val unreadList = unreadNotifications.getOrPut(sender) { mutableListOf() }
        unreadList.add(messageText)

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val inboxStyle = NotificationCompat.InboxStyle()
            .setBigContentTitle(title)

        unreadList.takeLast(6).forEach { msg ->
            inboxStyle.addLine(msg)
        }
        if (unreadList.size > 6) {
            inboxStyle.setSummaryText("+${unreadList.size - 6} more")
        } else if (unreadList.size > 1) {
            inboxStyle.setSummaryText("${unreadList.size} new messages")
        }

        val notification = NotificationCompat.Builder(this, MSG_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (unreadList.size > 1) "${unreadList.size} new messages" else messageText)
            .setStyle(inboxStyle)
            .setSmallIcon(R.drawable.notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        notificationManager.notify(sender.hashCode(), notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "CLEAR_NOTIFICATIONS") {
            val sender = intent.getStringExtra("sender_onion")
            if (sender != null) {
                unreadNotifications.remove(sender)
            }
            return START_STICKY
        }

        startForegroundServiceNotification()

        val serverHost = intent?.getStringExtra(EXTRA_SERVER_HOST) ?: "82.221.100.220"
        val serverPort = intent?.getIntExtra(EXTRA_SERVER_PORT, 8080) ?: 8080

        val myRealOnion = Prefs.getOnionAddress(applicationContext)
        if (!myRealOnion.isNullOrEmpty()) {
            unionClient.setClientIdentity(myRealOnion)
            Log.d(TAG, "Injected real identity: \$myRealOnion")
        }

        startPersistentConnectionLoop(serverHost, serverPort)

        return START_STICKY
    }

    private fun startPersistentConnectionLoop(host: String, port: Int) {
        serviceScope.launch {
            while (isServiceRunning) {
                val state = unionClient.connectionState.value
                if (state == UnionClient.ConnectionState.DISCONNECTED || state == UnionClient.ConnectionState.ERROR) {
                    unionClient.connect(host, port)
                }
                delay(8000)
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
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            val downloadChannel = NotificationChannel(
                MediaTransferNotifier.DOWNLOAD_CHANNEL_ID,
                "Media Downloads",
                NotificationManager.IMPORTANCE_LOW  // low = no sound, but shows in bar
            ).apply { setShowBadge(false) }

            val uploadChannel = NotificationChannel(
                MediaTransferNotifier.UPLOAD_CHANNEL_ID,
                "Media Uploads",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }

            val messageChannel = NotificationChannel(
                MSG_CHANNEL_ID,
                "Message Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { setShowBadge(true) }

            notificationManager.createNotificationChannel(downloadChannel)
            notificationManager.createNotificationChannel(uploadChannel)
            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(messageChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        pollingJob?.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        serviceScope.launch {
            unionClient.disconnect()
            unionClient.cleanup()
        }
        serviceScope.cancel()
    }


    /**
     * On cold start, fetch messages with no cursor restriction to catch
     * everything the server still has queued for us.
     * Uses the apiMessageId unique index in the DB to silently discard
     * messages we already stored, so this is always safe to call.
     */
    private suspend fun performColdStartBackfill() {
        try {
            val myOnion = Prefs.getOnionAddress(applicationContext) ?: return
            Log.d(TAG, "Cold-start backfill starting...")

            // Force a fresh circuit so we get a clean server session
            val circuitId = refreshCircuitIfNeeded(forceNew = true) ?: return

            // Fetch with NO since filter — get everything server still has
            val response = repository.getMessages(myOnion, circuitId, since = null)
            if (response.isSuccessful) {
                val messages = response.body()?.messages ?: emptyList()
                Log.d(TAG, "Cold-start backfill: ${messages.size} messages from server")
                processApiMessages(messages)
                Prefs.setLastSyncTime(applicationContext, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cold-start backfill error", e)
        }
    }



    private suspend fun handleChunk(
        transport: PrivateMessageTransportDto,
        apiMsg: app.secure.kyber.backend.beans.ApiMessage?
    ) {
        try {
            val chunk = chunkAdapter.fromJson(transport.uri ?: return) ?: return
            val mediaId = chunk.mediaId

            // Serialize all chunk processing for this mediaId — prevents race condition
            getChunkMutex(mediaId).withLock {
                handleChunkLocked(chunk, transport, apiMsg)
            }

            // Clean up mutex when transfer is complete
            val entity = messageDao.getByRemoteMediaId(mediaId)
            if (entity?.downloadState == "done" || entity?.downloadState == "failed") {
                chunkMutexes.remove(mediaId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleChunk error", e)
        }
    }


    private suspend fun handleChunkLocked(
        chunk: MediaChunkDto,
        transport: PrivateMessageTransportDto,
        apiMsg: app.secure.kyber.backend.beans.ApiMessage?
    ) {
        val mediaId = chunk.mediaId
        val originalMessageId = chunk.messageId

        // Dedup: skip if this exact chunk index is already saved
        val chunkDir = java.io.File(applicationContext.cacheDir, "chunks_$mediaId")
        val chunkFile = java.io.File(chunkDir, "chunk_${chunk.index.toString().padStart(6, '0')}")
        if (chunkFile.exists() && chunkFile.length() > 0) {
            Log.d(TAG, "Chunk ${chunk.index} already saved, skipping duplicate")
            return
        }

        // Save chunk to disk
        MediaChunkManager.saveChunkToDisk(applicationContext, mediaId, chunk.index, chunk.data)

        val savedCount = MediaChunkManager.countSavedChunks(applicationContext, mediaId)
        val progress = (savedCount * 100) / chunk.total

        // ... rest of existing handleChunk body unchanged ...


        // First chunk: create the placeholder message row immediately
        if (chunk.index == 0) {
            val existing = messageDao.getByRemoteMediaId(mediaId)
            if (existing == null) {
                val entity = MessageEntity(
                    messageId = originalMessageId,
                    apiMessageId = apiMsg?.id,
                    msg = EncryptionUtils.encrypt(chunk.caption.ifBlank {
                        when (chunk.mimeType) {
                            "AUDIO" -> "Voice Message"
                            "VIDEO" -> "video"
                            else -> "photo"
                        }
                    }),
                    senderOnion = transport.senderOnion,
                    time = System.currentTimeMillis().toString(),
                    isSent = false,
                    type = chunk.mimeType,
                    uri = null,
                    ampsJson = if (chunk.ampsJson.isNotBlank())
                        EncryptionUtils.encrypt(chunk.ampsJson) else "",
                    downloadState = "downloading",
                    downloadProgress = progress,
                    remoteMediaId = mediaId,
                    mediaDurationMs = chunk.durationMs,
                    mediaSizeBytes = chunk.totalBytes,
                    isRequest = transport.isRequest
                )
                messageDao.insert(entity)

                // Show immediate notification for first chunk
                val isAccepted = contactRepo.getContact(transport.senderOnion) != null
                if (!transport.isRequest && isAccepted) {
                    val name = contactRepo.getContact(transport.senderOnion)?.name ?: "Unknown"
                    val displayMsg = when (chunk.mimeType) {
                        "AUDIO" -> "🎵 Voice Message"
                        "VIDEO" -> "🎥 Video"
                        else -> "📷 Photo"
                    }
                    showPushNotification(transport.senderOnion, name, displayMsg)
                }
            }
        } else {
            // Update download progress for subsequent chunks
            val existing = messageDao.getByRemoteMediaId(mediaId)
            if (existing != null) {
                messageDao.updateDownloadProgress(existing.messageId, "downloading", progress)
            }
        }

        // All chunks received — assemble
        if (savedCount >= chunk.total) {
            val entity = messageDao.getByRemoteMediaId(mediaId)
            if (entity != null) {
                messageDao.updateDownloadProgress(entity.messageId, "downloading", progress)
// Only post notification every ~10% to avoid Android notification rate-limiting
                val myApp = try {
                    applicationContext as app.secure.kyber.MyApp.MyApp
                } catch (e: Exception) {
                    null
                }
                val isInChat = myApp?.activeChatOnion == entity.senderOnion
                if (!isInChat && progress % 10 == 0) {
                    transferNotifier.showDownloadProgress(
                        entity.messageId,
                        chunk.mimeType,
                        progress
                    )
                }
                val assembledPath = MediaChunkManager.assembleChunks(
                    applicationContext, mediaId, chunk.mimeType
                ) { p ->
                    serviceScope.launch {
                        messageDao.updateDownloadProgress(
                            entity.messageId,
                            "downloading",
                            progress
                        )
                        transferNotifier.showDownloadProgress(
                            entity.messageId,
                            chunk.mimeType,
                            progress
                        )
                    }
                }
                if (assembledPath != null) {
                    val updated = entity.copy(
                        uri = EncryptionUtils.encrypt(assembledPath),
                        downloadState = "done",
                        downloadProgress = 100,
                        localFilePath = assembledPath.removePrefix("file://")
                    )
                    messageDao.update(updated)
                    val myApp2 = try { applicationContext as app.secure.kyber.MyApp.MyApp } catch (e: Exception) { null }
                    if (myApp2?.activeChatOnion != entity.senderOnion) {
                        // Only show "received" notification if user is NOT already in that chat
                        transferNotifier.showDownloadComplete(entity.messageId, chunk.mimeType)
                    } else {
                        transferNotifier.cancel(entity.messageId)
                    }
                } else {
                    messageDao.updateDownloadProgress(entity.messageId, "failed", 0)
                    val myApp3 = try { applicationContext as app.secure.kyber.MyApp.MyApp } catch (e: Exception) { null }
                    if (myApp3?.activeChatOnion != entity.senderOnion) {
                        transferNotifier.showDownloadFailed(entity.messageId, chunk.mimeType)
                    }
                }
            }
        }

    }

    private fun getChunkMutex(mediaId: String): kotlinx.coroutines.sync.Mutex =
        chunkMutexes.getOrPut(mediaId) { kotlinx.coroutines.sync.Mutex() }

}
