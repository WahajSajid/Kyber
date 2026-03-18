package app.secure.kyber.fragments

import android.os.Bundle
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import app.secure.kyber.MyApp.MyApp
import app.secure.kyber.R
import app.secure.kyber.adapters.ChatListAdapter
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.databinding.FragmentChatListBinding
import app.secure.kyber.onionrouting.UnionClient
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.MessageRepository
import app.secure.kyber.roomdb.roomViewModel.MessagesViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@Suppress("DEPRECATION")
@AndroidEntryPoint
class ChatListFragment : Fragment(R.layout.fragment_chat_list) {

    private lateinit var unionClient: UnionClient
    private var serverHost by mutableStateOf("139.59.96.43")
    private var serverPort by mutableStateOf("8080")

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
        unionClient = UnionClient()
        myApp = requireActivity().application as MyApp

        onionAppConnection()
        setListAdapter()

        binding.btnGroupChat.setOnClickListener {
            myApp.tabBtnState = "group_chat"
            navController.navigate(R.id.action_chatListFragment_to_groupChatListFragment)
        }
    }

    fun onionAppConnection() {
        if (Prefs.getPublicKey(requireContext()).isNullOrEmpty()) {
            val exportedIdentity = unionClient.exportIdentity()
            Prefs.setOnionAddress(requireContext(), exportedIdentity["onionAddress"])
            Prefs.setPublicKey(requireContext(), exportedIdentity["publicKey"])
        } else {
            val m = mapOf(
                "onionAddress" to (Prefs.getOnionAddress(requireContext()) ?: ""),
                "publicKey" to (Prefs.getPublicKey(requireContext()) ?: "")
            )
            unionClient.importIdentity(m)
        }
        connectToServer()
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
            // Note: message.from here is unionId, but we prefer onionAddress for DB.
            // If the socket protocol only gives unionId, we might need a mapping.
            // However, the task is to use onionAddress as primary ID.
            // For now, saving as is, but repository will use senderOnion column.
            vm.saveMessage(
                message.content,
                senderOnion = message.from,
                timestamp = message.timestamp.toString(),
                isSent = false,
            )
        }
    }

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
            viewLifecycleOwner.lifecycleScope.launchWhenStarted {
                vm.lastMessagesFlow.collect { list ->
                    chatListAdapter.submitList(list)
                }
            }
            layoutManager = LinearLayoutManager(activity)
            adapter = chatListAdapter
        }
    }
}
