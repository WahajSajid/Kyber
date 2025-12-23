package app.secure.kyber.onionrouting

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Union Network Client for Android
 * Provides secure messaging through Union servers with Tor-like anonymity
 */
class UnionClient {
    
    companion object {
        private const val TAG = "UnionClient"
        private const val CONNECTION_TIMEOUT = 30000 // 30 seconds
        private const val READ_TIMEOUT = 60000 // 1 minute
        private const val HEARTBEAT_INTERVAL = 30000L // 30 seconds
    }

    // Connection state
    private var socket: Socket? = null
    private var outputStream: PrintWriter? = null
    private var inputStream: BufferedReader? = null
    private var isConnected = false
    
    // Client identity
    var unionId: String = ""
        private set
    private var publicKey: ByteArray = ByteArray(32)
    
    // Coroutine management
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionJob: Job? = null
    private var heartbeatJob: Job? = null
    
    // State flows for reactive UI
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    private val _messages = MutableStateFlow<List<UnionMessage>>(emptyList())
    val messages: StateFlow<List<UnionMessage>> = _messages
    
    // Callbacks
    private val messageCallbacks = ConcurrentHashMap<String, (UnionMessage) -> Unit>()
    private var connectionCallback: ((ConnectionState) -> Unit)? = null

    private var globalMessageCallback: ((UnionClient.UnionMessage) -> Unit)? = null

    /**
     * Connection states
     */
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR,
        RECONNECTING
    }

    /**
     * Union message data class
     */
    data class UnionMessage(
        val id: String,
        val from: String,
        val to: String,
        val content: String,
        val timestamp: Long,
        val messageType: MessageType = MessageType.DIRECT,
        val metadata: Map<String, String> = emptyMap()
    )

    /**
     * Message types
     */
    enum class MessageType {
        DIRECT,
        BROADCAST,
        SYSTEM
    }

    /**
     * Initialize the Union client
     */
    init {
        generateClientIdentity()
    }

    /**
     * Generate client identity and Union ID
     */
    private fun generateClientIdentity() {
        val random = SecureRandom()
        random.nextBytes(publicKey)
        
        // Generate Union ID (simulating deterministic generation from public key)
        val hash = publicKey.fold(0) { acc, byte -> acc * 31 + byte.toInt() }
        unionId = "union_${Math.abs(hash).toString(16).padStart(16, '0')}"
        
        Log.d(TAG, "Generated Union ID: $unionId")
    }

    /**
     * Connect to Union server
     */
    suspend fun connect(
        serverHost: String, 
        serverPort: Int = 8080,
        onConnectionChanged: ((ConnectionState) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            connectionCallback = onConnectionChanged
            _connectionState.value = ConnectionState.CONNECTING
            
            Log.d(TAG, "Connecting to Union server at $serverHost:$serverPort")
            
            // Create socket connection
            socket = Socket().apply {
                soTimeout = READ_TIMEOUT
                connect(InetSocketAddress(serverHost, serverPort), CONNECTION_TIMEOUT)
            }
            
            // Setup streams
            outputStream = PrintWriter(socket!!.getOutputStream(), true)
            inputStream = BufferedReader(InputStreamReader(socket!!.getInputStream()))
            
            // Register with server
            val registrationResult = registerWithServer()
            if (!registrationResult.isSuccess) {
                disconnect()
                return@withContext Result.failure(
                    Exception("Registration failed: ${registrationResult.exceptionOrNull()?.message}")
                )
            }
            
            isConnected = true
            _connectionState.value = ConnectionState.CONNECTED
            connectionCallback?.invoke(ConnectionState.CONNECTED)
            
            // Start message listening
            startMessageListener()
            
            // Start heartbeat
            startHeartbeat()
            
            Log.d(TAG, "Successfully connected to Union server as $unionId")
            Result.success("Connected as $unionId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            _connectionState.value = ConnectionState.ERROR
            connectionCallback?.invoke(ConnectionState.ERROR)
            Result.failure(e)
        }
    }

    /**
     * Register with Union server
     */
    private suspend fun registerWithServer(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val publicKeyHex = publicKey.joinToString("") { "%02x".format(it) }
            val metadata = """{"client_type":"android","algorithm":"Kyber1024-fallback","platform":"Android"}"""
            
            val connectCommand = "CONNECT $unionId $publicKeyHex $metadata"
            
            Log.d(TAG, "Sending registration: $connectCommand")
            outputStream?.println(connectCommand)
            
            // Read response
            val response = inputStream?.readLine()
                ?: return@withContext Result.failure(Exception("No registration response"))
            
            Log.d(TAG, "Registration response: $response")
            
            if (response.startsWith("OK:")) {
                Result.success(response)
            } else {
                Result.failure(Exception("Registration failed: $response"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Registration error", e)
            Result.failure(e)
        }
    }

    /**
     * Start listening for incoming messages
     */
    private fun startMessageListener() {
        connectionJob = clientScope.launch {
            try {
                while (isConnected && !isActive.not()) {
                    val line = inputStream?.readLine()
                    if (line != null) {
                        handleServerMessage(line)
                    } else {
                        Log.w(TAG, "Received null message, connection may be closed")
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Message listener error", e)
                handleConnectionError(e)
            }
        }
    }

    /**
     * Handle incoming server messages
     */
    private suspend fun handleServerMessage(message: String) {
        try {
            Log.d(TAG, "Received: $message")
            var msg = message.replaceFirst("MESSAGE ","")
            when {
                msg.startsWith("{") -> {
                    // JSON message from another client
                    handleIncomingMessage(msg)
                }
                msg.startsWith("OK:") -> {
                    // Command response
                    handleCommandResponse(msg)
                }
                msg.startsWith("ERROR:") -> {
                    Log.w(TAG, "Server error: $msg")
                }
                else -> {
                    Log.d(TAG, "Server message: $msg")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling server message", e)
        }
    }

    /**
     * Handle incoming JSON messages
     */
    private suspend fun handleIncomingMessage(jsonMessage: String) {
        // Parse JSON and create UnionMessage
        // For simplicity, using basic parsing - in production use a proper JSON library
        try {
            val message = parseJsonMessage(jsonMessage)

            if (message != null) {
                val currentMessages = _messages.value.toMutableList()
                currentMessages.add(message)
                _messages.value = currentMessages

                if (message.to == unionId || message.to == "*" || message.messageType == MessageType.BROADCAST) {
                    globalMessageCallback?.invoke(message)
                }
                // Notify callback
                messageCallbacks[message.from]?.invoke(message)
            }
        }
        catch (e: Exception) {
            Log.e(TAG, "Error parsing message", e)
        }

    }

    /**
     * Simple JSON message parser (use proper JSON library in production)
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun parseJsonMessage(json: String): UnionMessage? {
        return try {
            // Basic parsing - replace with proper JSON library like Gson or Moshi
            val fromMatch = Regex("\"from\":\"([^\"]+)\"").find(json)
            val contentMatch = Regex("\"payload\":\"([^\"]+)\"").find(json)
            val idMatch = Regex("\"id\":\"([^\"]+)\"").find(json)

            val decodedString = Base64.decode(
                contentMatch!!.groupValues[1],
                startIndex = 0,
                endIndex = contentMatch!!.groupValues[1].length
            ).decodeToString()


            if (fromMatch != null && contentMatch != null) {
                UnionMessage(
                    id = idMatch?.groupValues?.get(1) ?: "unknown",
                    from = fromMatch.groupValues[1],
                    to = unionId,
                    content = decodedString,//contentMatch.groupValues[1],
                    timestamp = System.currentTimeMillis(),
                    messageType = MessageType.DIRECT
                )
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message", e)
            null
        }
    }

    /**
     * Handle command responses
     */
    private fun handleCommandResponse(response: String) {
        Log.d(TAG, "Command response: $response")
        // Handle specific command responses as needed
    }

    /**
     * Send message to specific Union ID
     */
    suspend fun sendMessage(
        targetUnionId: String, 
        message: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!isConnected) {
                return@withContext Result.failure(Exception("Not connected to Union server"))
            }
            
            val command = "SEND $unionId $targetUnionId $message"
            Log.d(TAG, "Sending: $command")
            
            outputStream?.println(command)
            
            // Wait for response
            val response = inputStream?.readLine()
            if (response?.startsWith("OK:") == true) {
                Log.d(TAG, "Message sent successfully: $response")
                Result.success(response)
            } else {
                Log.w(TAG, "Send failed: $response")
                Result.failure(Exception("Send failed: $response"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            Result.failure(e)
        }
    }

    /**
     * Broadcast message to all connected clients
     */
    suspend fun broadcastMessage(message: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!isConnected) {
                return@withContext Result.failure(Exception("Not connected to Union server"))
            }
            
            val command = "BROADCAST $message"
            Log.d(TAG, "Broadcasting: $command")
            
            outputStream?.println(command)
            
            val response = inputStream?.readLine()
            if (response?.startsWith("OK:") == true) {
                Log.d(TAG, "Broadcast successful: $response")
                Result.success(response)
            } else {
                Log.w(TAG, "Broadcast failed: $response")
                Result.failure(Exception("Broadcast failed: $response"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error broadcasting message", e)
            Result.failure(e)
        }
    }

    /**
     * Get list of connected clients
     */
    suspend fun getConnectedClients(): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!isConnected) {
                return@withContext Result.failure(Exception("Not connected to Union server"))
            }
            
            outputStream?.println("LIST")
            
            val response = inputStream?.readLine()
            if (response != null) {
                Log.d(TAG, "Client list: $response")
                Result.success(response)
            } else {
                Result.failure(Exception("No response from server"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting client list", e)
            Result.failure(e)
        }
    }

    /**
     * Get message history
     */
    suspend fun getMessageHistory(): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!isConnected) {
                return@withContext Result.failure(Exception("Not connected to Union server"))
            }
            
            outputStream?.println("HISTORY")
            
            val response = inputStream?.readLine()
            if (response != null) {
                Log.d(TAG, "Message history: $response")
                Result.success(response)
            } else {
                Result.failure(Exception("No response from server"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting message history", e)
            Result.failure(e)
        }
    }

    /**
     * Set callback for messages from specific Union ID
     */
    fun setMessageCallback(fromUnionId: String, callback: (UnionMessage) -> Unit) {
        messageCallbacks[fromUnionId] = callback
    }

    /**
     * Remove message callback
     */
    fun removeMessageCallback(fromUnionId: String) {
        messageCallbacks.remove(fromUnionId)
    }

    fun setMessageCallback(callback: (UnionMessage) -> Unit) {
        globalMessageCallback = callback
    }


    /**
     * Remove global message callback
     */
    fun removeGlobalMessageCallback() {
        globalMessageCallback = null
    }

    /**
     * Start heartbeat to keep connection alive
     */
    private fun startHeartbeat() {
        heartbeatJob = clientScope.launch {
            while (isConnected && isActive) {
                delay(HEARTBEAT_INTERVAL)
                try {
                    outputStream?.println("PING")
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat failed", e)
                    handleConnectionError(e)
                    break
                }
            }
        }
    }

    /**
     * Handle connection errors
     */
    private suspend fun handleConnectionError(error: Exception) {
        Log.e(TAG, "Connection error", error)
        _connectionState.value = ConnectionState.ERROR
        connectionCallback?.invoke(ConnectionState.ERROR)
        
        // Optionally implement reconnection logic
        // reconnect()
    }

    /**
     * Disconnect from Union server
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            isConnected = false
            
            // Cancel jobs
            connectionJob?.cancel()
            heartbeatJob?.cancel()
            
            // Close streams and socket
            outputStream?.close()
            inputStream?.close()
            socket?.close()
            
            _connectionState.value = ConnectionState.DISCONNECTED
            connectionCallback?.invoke(ConnectionState.DISCONNECTED)
            
            Log.d(TAG, "Disconnected from Union server")
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        clientScope.cancel()
        messageCallbacks.clear()
        connectionCallback = null
        globalMessageCallback = null
    }

    /**
     * Get connection info
     */
    fun getConnectionInfo(): Map<String, Any> {
        return mapOf(
            "unionId" to unionId,
            "connected" to isConnected,
            "connectionState" to _connectionState.value,
            "messageCount" to _messages.value.size
        )
    }

    /**
     * Set client identity from external source
     * @param customUnionId Custom Union ID to use
     * @param customPublicKey Optional custom public key (if null, generates new one)
     * @return Result indicating success or failure
     */
    fun setClientIdentity(
        customUnionId: String,
        customPublicKey: ByteArray? = null
    ): Result<String> {
        return try {
            // Validate Union ID format
            if (!customUnionId.startsWith("union_") || customUnionId.length < 10) {
                return Result.failure(Exception("Invalid Union ID format. Must start with 'union_' and be at least 10 characters"))
            }

            // Check if already connected - should set identity before connecting
            if (isConnected) {
                return Result.failure(Exception("Cannot change identity while connected. Please disconnect first."))
            }

            // Set the Union ID
            unionId = customUnionId

            // Set or generate public key
            if (customPublicKey != null) {
                if (customPublicKey.size != 32) {
                    return Result.failure(Exception("Public key must be exactly 32 bytes"))
                }
                publicKey = customPublicKey.copyOf()
            } else {
                // Generate new public key
                val random = SecureRandom()
                random.nextBytes(publicKey)
            }

            Log.d(TAG, "Set custom Union ID: $unionId")
            Result.success("Identity set successfully: $unionId")

        } catch (e: Exception) {
            Log.e(TAG, "Error setting client identity", e)
            Result.failure(e)
        }
    }

    /**
     * Set client identity from hex strings (convenient for importing)
     * @param customUnionId Custom Union ID to use
     * @param publicKeyHex Optional public key as hex string (64 characters for 32 bytes)
     * @return Result indicating success or failure
     */
    fun setClientIdentityFromHex(
        customUnionId: String,
        publicKeyHex: String? = null
    ): Result<String> {
        return try {
            val publicKeyBytes = publicKeyHex?.let { hex ->
                if (hex.length != 64) {
                    return Result.failure(Exception("Public key hex must be exactly 64 characters (32 bytes)"))
                }
                try {
                    hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                } catch (e: Exception) {
                    return Result.failure(Exception("Invalid hex format in public key"))
                }
            }

            setClientIdentity(customUnionId, publicKeyBytes)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Export current identity for backup/transfer
     * @return Map containing union ID and public key (as hex)
     */
    fun exportIdentity(): Map<String, String> {
        return mapOf(
            "unionId" to unionId,
            "publicKey" to publicKey.joinToString("") { "%02x".format(it) },
            "exportTime" to System.currentTimeMillis().toString()
        )
    }

    /**
     * Import identity from exported data
     * @param identityData Map containing exported identity data
     * @return Result indicating success or failure
     */
    fun importIdentity(identityData: Map<String, String>): Result<String> {
        return try {
            val importedUnionId = identityData["unionId"]
                ?: return Result.failure(Exception("Missing unionId in identity data"))

            val importedPublicKeyHex = identityData["publicKey"]
                ?: return Result.failure(Exception("Missing publicKey in identity data"))

            setClientIdentityFromHex(importedUnionId, importedPublicKeyHex)
        } catch (e: Exception) {
            Result.failure(Exception("Failed to import identity: ${e.message}"))
        }
    }
}