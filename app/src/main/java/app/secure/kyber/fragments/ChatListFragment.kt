package app.secure.kyber.fragments

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
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.databinding.FragmentChatListBinding
import app.secure.kyber.onionrouting.UnionClient
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.MessageRepository
import app.secure.kyber.roomdb.roomViewModel.MessagesViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
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
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private fun startGlobalPolling() {
        pollingJob?.cancel()
        pollingJob = viewLifecycleOwner.lifecycleScope.launch {
            // Eagerly refresh the circuit on app start so a stale/expired circuit
            // from a previous session is replaced before the first poll fires.
            refreshCircuitOnStart()
            while (isActive) {
                fetchGlobalMessages()
                delay(5000)
            }
        }
    }

    /**
     * Creates a new circuit on app start only if one doesn't already exist in Prefs.
     * This avoids hitting the "Circuit expired or inactive" error on the very first poll
     * after the app is reopened.
     */
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

    /**
     * Ensures a valid circuit exists.
     * Pass forceNew = true to discard the cached circuitId and create a fresh one.
     * Returns the circuitId, or null on failure.
     */
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

            // If circuit expired, create a new one and retry the fetch once
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
            val senderOnion = apiMsg.senderOnion ?: return@forEach
            if (senderOnion.isEmpty()) return@forEach

            val decodedPayload = try {
                String(Base64.decode(apiMsg.payload, Base64.NO_WRAP), Charsets.UTF_8)
            } catch (e: Exception) {
                apiMsg.payload
            }

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

            // apiMessageId unique constraint in Room prevents duplicate saves
            vm.saveMessage(
                msg = msgText,
                senderOnion = senderOnion,
                timestamp = parseApiTimestamp(apiMsg.timestamp),
                isSent = false,
                type = msgType,
                uri = msgUri,
                apiMessageId = apiMsg.id
            )
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
            vm.saveMessage(
                message.content,
                senderOnion = message.from,
                timestamp = message.timestamp.toString(),
                isSent = false,
            )
        }
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
                        Log.d("ChatListFragment", "Updating chat list with ${list.size} items")
                        chatListAdapter.submitList(list)
                    }
                }
            }
            layoutManager = LinearLayoutManager(activity)
            adapter = chatListAdapter
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDestroyView() {
        pollingJob?.cancel()
        super.onDestroyView()
    }
}