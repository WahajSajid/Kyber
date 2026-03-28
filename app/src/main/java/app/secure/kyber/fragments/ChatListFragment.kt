package app.secure.kyber.fragments

import android.content.Context
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import app.secure.kyber.MyApp.MyApp
import app.secure.kyber.R
import app.secure.kyber.adapters.ChatListAdapter
import app.secure.kyber.backend.KyberRepository
import app.secure.kyber.backend.beans.PrivateMessageTransportDto
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.databinding.FragmentChatListBinding
import app.secure.kyber.onionrouting.UnionClient
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.ContactRepository
import app.secure.kyber.roomdb.MessageRepository
import app.secure.kyber.roomdb.roomViewModel.MessagesViewModel
import app.secure.kyber.Utils.EncryptionUtils
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@Suppress("DEPRECATION")
@AndroidEntryPoint
class ChatListFragment : Fragment(R.layout.fragment_chat_list) {

    @Inject
    lateinit var unionClient: UnionClient

    @Inject
    lateinit var repository: KyberRepository

    private var serverHost by mutableStateOf("82.221.100.220")
    private var serverPort by mutableStateOf("8080")

    private lateinit var binding: FragmentChatListBinding
    private lateinit var navController: NavController
    private lateinit var chatListAdapter: ChatListAdapter
    private lateinit var myApp: MyApp

    private var pollingJob: Job? = null

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val transportAdapter = moshi.adapter(PrivateMessageTransportDto::class.java)

    private val vm: MessagesViewModel by viewModels {
        val db = AppDb.get(requireContext())
        val repo = MessageRepository(db.messageDao())
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MessagesViewModel(repo, "") as T
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentChatListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        navController = view.findNavController()
        myApp = requireActivity().application as MyApp

        onionAppConnection()
        setListAdapter()
        startGlobalPolling()

        binding.btnGroupChat.setOnClickListener {
            myApp.tabBtnState = "group_chat"
            navController.navigate(R.id.action_chatListFragment_to_groupChatListFragment)
        }

        binding.btnRequest.setOnClickListener {
            myApp.tabBtnState = "request_chat"
            navController.navigate(R.id.action_chatListFragment_to_messageRequestsFragment)
        }
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private fun startGlobalPolling() {
        pollingJob?.cancel()
        pollingJob = viewLifecycleOwner.lifecycleScope.launch {
            refreshCircuitOnStart()
            while (isActive) {
                fetchGlobalMessages()
                delay(1000)
            }
        }
    }

    private suspend fun refreshCircuitOnStart() {
        try {
            val existing = Prefs.getCircuitId(requireContext())
            if (existing.isNullOrEmpty()) {
                val resp = repository.createCircuit()
                if (resp.isSuccessful) {
                    Prefs.setCircuitId(requireContext(), resp.body()?.circuitId)
                    Log.d("ChatListFragment", "Circuit created on start: ${resp.body()?.circuitId}")
                }
            }
        } catch (e: Exception) {
            Log.e("ChatListFragment", "Circuit refresh on start failed: ${e.message}")
        }
    }

    private suspend fun refreshCircuitIfNeeded(forceNew: Boolean = false): String? {
        var circuitId = if (forceNew) null else Prefs.getCircuitId(requireContext())
        if (circuitId.isNullOrEmpty()) {
            try {
                val resp = repository.createCircuit()
                if (resp.isSuccessful) {
                    circuitId = resp.body()?.circuitId
                    Prefs.setCircuitId(requireContext(), circuitId)
                    Log.d("ChatListFragment", "New circuit created: $circuitId")
                }
            } catch (e: Exception) {
                Log.e("ChatListFragment", "Circuit creation failed: ${e.message}")
            }
        }
        return circuitId
    }

    private suspend fun fetchGlobalMessages() {
        try {
            val myOnion = Prefs.getOnionAddress(requireContext()) ?: return
            var circuitId = refreshCircuitIfNeeded()

            var response = repository.getMessages(myOnion, circuitId)

            if (!response.isSuccessful && response.code() == 400) {
                Log.w("ChatListFragment", "Circuit expired during poll, refreshing...")
                circuitId = refreshCircuitIfNeeded(forceNew = true)
                if (circuitId.isNullOrEmpty()) return
                response = repository.getMessages(myOnion, circuitId)
            }

            if (response.isSuccessful) {
                processApiMessages(response.body()?.messages ?: emptyList())
            }
        } catch (e: Exception) {
            Log.e("ChatListFragment", "Global polling error", e)
        }
    }

    private fun processApiMessages(messages: List<app.secure.kyber.backend.beans.ApiMessage>) {
        messages.forEach { apiMsg ->
            val decodedPayload = try {
                String(Base64.decode(apiMsg.payload, Base64.NO_WRAP), Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e("ChatListFragment", "Base64 decode failed", e)
                return@forEach
            }

            try {
                val transport = transportAdapter.fromJson(decodedPayload)
                if (transport != null) {
                    lifecycleScope.launch {
                        handleTransport(transport, apiMsg)
                    }
                } else {
                    handleLegacyMessage(apiMsg, decodedPayload)
                }
            } catch (e: Exception) {
                handleLegacyMessage(apiMsg, decodedPayload)
            }
        }
    }

    /**
     * Central handler for every decoded transport message.
     *
     * Acceptance flow (two-sided contact creation):
     * ─────────────────────────────────────────────
     * When the receiver accepts a request they send back a special message
     * with isAcceptance = true and their own name in senderName.
     * When the SENDER receives that message here, we:
     *   1. Save the receiver as a contact using senderName from the transport.
     *   2. Do NOT persist the acceptance message itself into the messages table
     *      so it never appears in the conversation thread.
     *
     * Request flow (sender name caching):
     * ────────────────────────────────────
     * When a message with isRequest = true arrives and senderName is provided,
     * we cache the name in SharedPreferences so MessageRequestsFragment can
     * pass it to ChatFragment, and acceptRequest() can save the real name.
     */
    private suspend fun handleTransport(
        transport: PrivateMessageTransportDto,
        apiMsg: app.secure.kyber.backend.beans.ApiMessage
    ) {
        // ── Acceptance acknowledgement received (sender's side) ───────────────
        if (transport.isAcceptance) {
            handleAcceptanceAck(transport)
            return  // Do not persist this message in the thread.
        }

        // ── Cache sender name for incoming requests ───────────────────────────
        if (transport.isRequest && transport.senderName.isNotBlank()) {
            cachePendingContactName(transport.senderOnion, transport.senderName)
        }

        // ── Normal / request message persistence ─────────────────────────────
        val existing = vm.getMessageByMessageId(transport.messageId)
        if (existing == null) {
            val encryptedUri = if (!transport.uri.isNullOrBlank())
                EncryptionUtils.encrypt(transport.uri)
            else null

            vm.saveMessage(
                messageId = transport.messageId,
                msg = EncryptionUtils.encrypt(transport.msg),
                senderOnion = transport.senderOnion,
                timestamp = transport.timestamp,
                isSent = false,
                type = transport.type,
                uri = encryptedUri,
                ampsJson = transport.ampsJson,
                apiMessageId = apiMsg.id,
                reaction = transport.reaction,
                isRequest = transport.isRequest
            )
        } else {
            if (existing.reaction != transport.reaction) {
                existing.reaction = transport.reaction
                val isRemoval = transport.reaction.isEmpty() || transport.reaction.endsWith("|")
                existing.updatedAt = if (isRemoval) "" else System.currentTimeMillis().toString()
                vm.updateMessage(existing)
            }
        }
    }

    /**
     * Called when we receive an acceptance acknowledgement from the other side.
     * Saves the acceptor as a contact on THIS device, completing the two-sided
     * contact relationship without any manual action from this user.
     */
    private suspend fun handleAcceptanceAck(transport: PrivateMessageTransportDto) {
        val db = AppDb.get(requireContext())
        val contactRepo = ContactRepository(db.contactDao())

        // Only save if we don't already have this contact.
        val existing = contactRepo.getContact(transport.senderOnion)
        if (existing == null) {
            val name = transport.senderName.ifBlank { transport.senderOnion }
            contactRepo.saveContact(onionAddress = transport.senderOnion, name = name)
            Log.d(
                "ChatListFragment",
                "Auto-saved contact after acceptance: $name (${transport.senderOnion})"
            )
        }
    }

    /**
     * Stores the pending sender name in SharedPreferences so it survives until
     * the user opens the request and either accepts or rejects it.
     * Key format: "pending_name_<onionAddress>"
     */
    private fun cachePendingContactName(senderOnion: String, senderName: String) {
        requireContext()
            .getSharedPreferences("contact_name_cache", Context.MODE_PRIVATE)
            .edit()
            .putString("pending_name_$senderOnion", senderName)
            .apply()
    }

    private fun handleLegacyMessage(
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

        vm.saveMessage(
            messageId = UUID.randomUUID().toString(),
            msg = EncryptionUtils.encrypt(msgText),
            senderOnion = senderOnion,
            timestamp = parseApiTimestamp(apiMsg.timestamp),
            isSent = false,
            type = msgType,
            uri = encryptedUri,
            apiMessageId = apiMsg.id
        )
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

    // ── Union / Socket Connection ─────────────────────────────────────────────

    fun onionAppConnection() {
        val onion = Prefs.getOnionAddress(requireContext())
        val publicKey = Prefs.getPublicKey(requireContext())

        if (publicKey.isNullOrEmpty()) {
            val exportedIdentity = unionClient.exportIdentity()
            Prefs.setOnionAddress(requireContext(), exportedIdentity["onionAddress"])
            Prefs.setPublicKey(requireContext(), exportedIdentity["publicKey"])
        } else {
            if (unionClient.exportIdentity()["onionAddress"] != onion) {
                val m = mapOf(
                    "onionAddress" to (onion ?: ""),
                    "publicKey" to (publicKey ?: "")
                )
                unionClient.importIdentity(m)
            }
        }

        if (unionClient.connectionState.value != UnionClient.ConnectionState.CONNECTED) {
            connectToServer()
        } else {
            recieveMessage()
        }
    }

    private fun connectToServer() {
        lifecycleScope.launch {
            unionClient.connect(
                serverHost = serverHost,
                serverPort = serverPort.toIntOrNull() ?: 8080
            ) { state ->
                if (state == UnionClient.ConnectionState.CONNECTED) {
                    recieveMessage()
                }
            }
        }
    }

    private fun recieveMessage() {
        unionClient.setMessageCallback { message ->
            Log.d("ChatListFragment", "Global message received from ${message.from}")
            val decodedPayload = try {
                String(Base64.decode(message.content, Base64.NO_WRAP), Charsets.UTF_8)
            } catch (e: Exception) {
                message.content
            }

            try {
                val transport = transportAdapter.fromJson(decodedPayload)
                if (transport != null) {
                    lifecycleScope.launch {
                        // Reuse the same handleTransport logic for socket messages.
                        // Socket messages don't have an apiMsg wrapper, so we create
                        // a minimal stub — apiMessageId will be null, which is fine.
                        handleTransport(
                            transport,
                            app.secure.kyber.backend.beans.ApiMessage(
                                id = "",
                                payload = message.content,
                                senderOnion = message.from,
                                timestamp = message.timestamp.toString()
                            )
                        )
                    }
                } else {
                    saveLegacySocketMessage(message, decodedPayload)
                }
            } catch (e: Exception) {
                saveLegacySocketMessage(message, decodedPayload)
            }
        }
    }

    private fun saveLegacySocketMessage(message: UnionClient.UnionMessage, decodedPayload: String) {
        vm.saveMessage(
            messageId = UUID.randomUUID().toString(),
            msg = EncryptionUtils.encrypt(decodedPayload),
            senderOnion = message.from,
            timestamp = message.timestamp.toString(),
            isSent = false,
        )
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private fun setListAdapter() {
        val recyclerview = binding.rv
        recyclerview.setHasFixedSize(false)
        chatListAdapter = ChatListAdapter(requireContext(), onItemClick = { chatModel ->
            val args = bundleOf(
                "contact_onion" to chatModel.onionAddress,
                "contact_name" to chatModel.name,
                "coming_from" to "chat_list"
            )
            navController.navigate(R.id.action_chatListFragment_to_chatFragment, args)
        })

        recyclerview.apply {
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    vm.lastMessagesFlow.collectLatest { list ->

                        val sharedPrefs = requireContext().getSharedPreferences(
                            "chat_prefs",
                            Context.MODE_PRIVATE
                        )
                        val dao = AppDb.get(requireContext()).messageDao()

                        val displayList = list.map { chat ->
                            val rawMsg = chat.lastMessage ?: ""
                            val rawReaction = chat.reaction ?: ""
                            val msgType = chat.type ?: "TEXT"

                            val decrypted = try {
                                val d = app.secure.kyber.Utils.EncryptionUtils.decrypt(rawMsg)
                                if (d.isNotBlank()) d else rawMsg
                            } catch (e: Exception) {
                                rawMsg
                            }

                            var actualEmoji = ""
                            var isMyReaction = false
                            if (rawReaction.isNotEmpty()) {
                                val parts = rawReaction.split("|", limit = 2)
                                if (parts.size == 2) {
                                    val myRealOnion =
                                        app.secure.kyber.backend.common.Prefs.getOnionAddress(
                                            requireContext()
                                        ) ?: ""
                                    isMyReaction = (parts[0] == myRealOnion)
                                    actualEmoji = parts[1]
                                } else {
                                    actualEmoji = rawReaction
                                }
                            }

                            var formattedMessage = when {
                                decrypted == "photo" -> "📷 Photo"
                                decrypted == "video" -> "🎥 Video"
                                decrypted.startsWith("Voice Message") -> "🎤 $decrypted"
                                else -> decrypted
                            }

                            if (actualEmoji.isNotEmpty()) {
                                val prefix =
                                    if (isMyReaction) "You reacted $actualEmoji to" else "Reacted $actualEmoji to"
                                val suffix = when (msgType.uppercase(java.util.Locale.US)) {
                                    "IMAGE" -> "a photo"
                                    "VIDEO" -> "a video"
                                    "AUDIO" -> "a voice message"
                                    else -> "a message"
                                }
                                formattedMessage = "$prefix $suffix"
                            }

                            val onion = chat.onionAddress ?: ""
                            val lastSeenId = sharedPrefs.getLong("last_seen_id_$onion", 0L)
                            val unread = dao.getUnreadCount(onion, lastSeenId)

                            /// REPLACE only the chat.copy line at the bottom of the displayList map in setListAdapter():
                            val maskedName = chat.name?.let {
                                if (it.endsWith(".onion")) "Unknown User" else it
                            } ?: "Unknown User"

                            chat.copy(
                                lastMessage = formattedMessage,
                                unreadCount = unread,
                                name = maskedName
                            )
                        }

                        Log.d(
                            "ChatListFragment",
                            "Updating chat list with ${displayList.size} items"
                        )
                        chatListAdapter.submitList(displayList)
                    }
                }
            }
            layoutManager = LinearLayoutManager(activity)
            adapter = chatListAdapter
        }
    }

    override fun onDestroyView() {
        pollingJob?.cancel()
        super.onDestroyView()
    }
}