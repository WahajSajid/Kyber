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
import app.secure.kyber.Utils.MessageEncryptionManager
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
import org.json.JSONObject

@Suppress("DEPRECATION")
@AndroidEntryPoint
class ChatListFragment : Fragment(R.layout.fragment_chat_list) {
    private fun parseWipePreview(raw: String, type: String): String? {
        val upperType = type.uppercase(Locale.US)
        if (upperType != "WIPE_REQUEST" && upperType != "WIPE_RESPONSE" && upperType != "WIPE_SYSTEM") {
            return null
        }
        if (upperType == "WIPE_SYSTEM") return "Chat Cleared"
        val trimmed = raw.trim()
        if (upperType == "WIPE_REQUEST") return "Wipe Chat Request"
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            runCatching {
                val action = JSONObject(trimmed).optString("action", "").uppercase(Locale.US)
                return if (action == "REJECTED") "Wipe Request Rejected" else "Chat Cleared"
            }
        }
        return if (trimmed.uppercase(Locale.US) == "REJECTED") "Wipe Request Rejected" else "Chat Cleared"
    }

    @Inject
    lateinit var unionClient: UnionClient

    @Inject
    lateinit var repository: KyberRepository

    private lateinit var binding: FragmentChatListBinding
    private lateinit var navController: NavController
    private lateinit var chatListAdapter: ChatListAdapter
    private lateinit var myApp: MyApp

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

        setListAdapter()

        binding.btnGroupChat.setOnClickListener {
            myApp.tabBtnState = "group_chat"
            navController.navigate(R.id.action_chatListFragment_to_groupChatListFragment)
        }

        binding.btnRequest.setOnClickListener {
            myApp.tabBtnState = "request_chat"
            navController.navigate(R.id.action_chatListFragment_to_messageRequestsFragment)
        }
    }

    private fun setListAdapter() {
        val recyclerview = binding.rv
        recyclerview.setHasFixedSize(false)
        chatListAdapter = ChatListAdapter(requireContext(), onDeleteChat = { chatModel ->
            viewLifecycleOwner.lifecycleScope.launch {
                val db = AppDb.get(requireContext())
                db.messageDao().deleteAllBySender(chatModel.onionAddress ?: "")
                // The list will update automatically via Flow
            }
        }, onItemClick = { chatModel ->
            val args = bundleOf(
                "contact_onion" to chatModel.onionAddress,
                "contact_name" to chatModel.name,
                "coming_from" to "chat_list",
                "shortId" to chatModel.contactId
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

                        val myOnion = app.secure.kyber.backend.common.Prefs.getOnionAddress(requireContext()) ?: ""
                        val displayList = list.map { chat ->
                            val rawMsg = chat.lastMessage ?: ""
                            val rawReaction = chat.reaction ?: ""
                            val msgType = chat.type ?: "TEXT"

                            // Determine if message is sent by me
                            // Sent messages are stored as local blobs (IV column is empty)
                            // Received messages have the IV stored in the dedicated column
                            val isSent = chat.iv.isNullOrBlank()
                            
                            var formattedMessage = ""

                            if (rawReaction.isNotEmpty()) {
                                // Reaction logic
                                val parts = rawReaction.split("|", limit = 2)
                                val isMyReaction = if (parts.size == 2) parts[0] == myOnion else false
                                
                                formattedMessage = if (isMyReaction) {
                                    "You reacted to a message"
                                } else {
                                    val displayName = chat.name ?: "Someone"
                                    "$displayName reacted to a message"
                                }
                            } else {
                                // Standard message logic
                                formattedMessage = when (msgType.uppercase(Locale.US)) {
                                    "IMAGE" -> if (isSent) "You sent a photo" else "You received a photo"
                                    "VIDEO" -> if (isSent) "You sent a video" else "You received a video"
                                    "AUDIO" -> if (isSent) "You sent a voice message" else "You received a voice message"
                                    "WIPE_REQUEST" -> if (isSent) "You sent a wipe request" else "You received a wipe request"
                                    "WIPE_RESPONSE", "WIPE_SYSTEM" -> "Chat Cleared"
                                    else -> if (isSent) "You sent a text message" else "You received a text message"
                                }
                            }

                            val onion = chat.onionAddress ?: ""
                            val lastSeenId = sharedPrefs.getLong("last_seen_id_$onion", 0L)
                            val unread = dao.getUnreadCount(onion, lastSeenId)

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
        super.onDestroyView()
    }
}
