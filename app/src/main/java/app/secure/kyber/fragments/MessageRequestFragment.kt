package app.secure.kyber.fragments

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import app.secure.kyber.MyApp.MyApp
import app.secure.kyber.R
import app.secure.kyber.adapters.ChatListAdapter
import app.secure.kyber.activities.MainActivity
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.MessageRepository
import app.secure.kyber.roomdb.roomViewModel.MessagesViewModel
import app.secure.kyber.Utils.MessageEncryptionManager
import app.secure.kyber.databinding.FragmentMessageRequestBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import app.secure.kyber.backend.KyberRepository
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.onionrouting.UnionClient
import app.secure.kyber.backend.beans.PrivateMessageTransportDto
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * Shows all incoming pending message requests — conversations from unknown senders
 * (senderOnion not in the contacts table and never replied to).
 */
@AndroidEntryPoint
class MessageRequestsFragment : Fragment(R.layout.fragment_message_request) {
    @Inject lateinit var unionClient: UnionClient
    @Inject lateinit var repository: KyberRepository

    private lateinit var binding: FragmentMessageRequestBinding
    private lateinit var chatListAdapter: ChatListAdapter
    private lateinit var navController: NavController
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
        binding = FragmentMessageRequestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        navController = findNavController()
        myApp = requireActivity().application as MyApp


        binding.btnGroupChat.setOnClickListener {
            myApp.tabBtnState = "group_chat"
            navController.navigate(R.id.action_messageRequestsFragment_to_groupChatListFragment)
        }
        binding.btnIndividualChat.setOnClickListener {
            myApp.tabBtnState = "individual_chat"
            navController.navigate(R.id.action_messageRequestsFragment_to_chatListFragment)
        }


        setupAdapter()
    }

    private fun setupAdapter() {
        val nameCache = requireContext()
            .getSharedPreferences("contact_name_cache", Context.MODE_PRIVATE)

        chatListAdapter = ChatListAdapter(requireContext(), onDeleteChat = { chatModel ->
            viewLifecycleOwner.lifecycleScope.launch {
                val db = AppDb.get(requireContext())
                db.messageDao().deleteAllBySender(chatModel.onionAddress ?: "")
            }
        }) { chatModel ->
            val onion = chatModel.onionAddress ?: ""
            val resolvedName = nameCache.getString("pending_name_$onion", "") ?: ""

            val args = bundleOf(
                "contact_onion" to onion,
                "contact_name" to resolvedName,
                "coming_from" to "message_request"
            )
            navController.navigate(R.id.action_messageRequestsFragment_to_chatFragment, args)
        }

        binding.rvRequests.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = chatListAdapter
            setHasFixedSize(false)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.incomingRequestsFlow.collectLatest { requests ->

                    val sharedPrefs = requireContext().getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
                    val dao = AppDb.get(requireContext()).messageDao()
                    val myRealOnion = app.secure.kyber.backend.common.Prefs.getOnionAddress(requireContext()) ?: ""

                    val myOnion = app.secure.kyber.backend.common.Prefs.getOnionAddress(requireContext()) ?: ""
                    val displayList = requests.map { chat ->
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
                                "Someone reacted to a message"
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

                        val displayName = "Unknown User"

                        chat.copy(
                            name = displayName,
                            lastMessage = formattedMessage,
                            unreadCount = unread
                        )
                    }

                    chatListAdapter.submitList(displayList)

                    binding.tvEmpty.visibility = if (displayList.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvRequests.visibility = if (displayList.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }



    override fun onDestroyView() {
        super.onDestroyView()
    }
}
