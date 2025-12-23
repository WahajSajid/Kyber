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
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import app.secure.kyber.R
import app.secure.kyber.adapters.ChatListAdapter
import app.secure.kyber.adapters.ContactListAdapter
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.backend.models.ChatModel
import app.secure.kyber.databinding.FragmentChatListBinding
import app.secure.kyber.onionrouting.UnionClient
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.MessageRepository
import app.secure.kyber.roomdb.roomViewModel.MessagesViewModel
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlin.getValue

@AndroidEntryPoint
class ChatListFragment : Fragment(R.layout.fragment_chat_list) {

    private lateinit var unionClient: UnionClient
    private var serverHost by mutableStateOf("139.59.96.43")  // Replace with your server IP
    private var serverPort by mutableStateOf("8080")

    private lateinit var binding: FragmentChatListBinding
    private lateinit var navController : NavController
    private lateinit var chatListAdapter: ChatListAdapter

    private val vm: MessagesViewModel by viewModels {
        // quick factory — wire up Room
        val db = AppDb.get(requireContext())
        val repo = MessageRepository(db.messageDao())
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MessagesViewModel(repo,"") as T
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentChatListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        navController = view.findNavController()
        unionClient = UnionClient()


        onionAppConnection()

        setListAdapter()
    }


    fun onionAppConnection() {
        if(Prefs.getPublicKey(requireContext())==null || Prefs.getPublicKey(requireContext())!!.isEmpty()){
            val exportedIdentity = unionClient.exportIdentity()
            Prefs.setUnionId(requireContext(),exportedIdentity["unionId"])
            Prefs.setPublicKey(requireContext(),exportedIdentity["publicKey"])
        }else{
            val m: Map<String, String> = mapOf(
                "unionId" to Prefs.getUnionId(requireContext())!!,
                "publicKey" to Prefs.getPublicKey(requireContext())!!
            )
            val importedIdentity = unionClient.importIdentity(m)
            Log.d("TAG", "onionAppConnection: $importedIdentity")
        }
        connectToServer()

    }
    private fun connectToServer() {
        lifecycleScope.launch {
            val result = unionClient.connect(
                serverHost = serverHost,
                serverPort = serverPort.toIntOrNull() ?: 8080
            ) { state ->
                // Connection state callback
                when (state) {
                    UnionClient.ConnectionState.CONNECTED -> {
                        // Successfully connected

                     //   Snackbar.make(binding.root, "Server Connected Successfully", Snackbar.LENGTH_SHORT).show()
                        recieveMessage()
                    }
                    UnionClient.ConnectionState.ERROR -> {
                        // Handle connection error
                       // Snackbar.make(binding.root, "Error Connecting Server", Snackbar.LENGTH_SHORT).show()
                    }
                    else -> { /* Handle other states */
                       // Snackbar.make(binding.root, "Something went wrong", Snackbar.LENGTH_SHORT).show()
                    }
                }
            }

            result.fold(
                onSuccess = { message ->
                    // Connection successful
                },
                onFailure = { error ->
                    // Handle connection failure
                }
            )
        }
    }

    private fun recieveMessage(){

        unionClient.setMessageCallback { message ->

            vm.saveMessage(
                message.content,
                senderId = message.from,
                timestamp = message.timestamp.toString(),
                isSent = false,
            )
            //Log.d("Union", "Message from specific sender: ${message.content}")
        }
    }

    private fun setListAdapter() {
        val recyclerview = binding.rv
        recyclerview.setHasFixedSize(false)
        chatListAdapter = ChatListAdapter(requireContext(),onItemClick={
            chatModel ->
            val args = bundleOf(
                "contact_id" to chatModel.id,
                "contact_name" to chatModel.name
            )
            navController.navigate(R.id.action_chatListFragment_to_chatFragment,args)
        })


        chatListAdapter.notifyDataSetChanged()
        recyclerview.apply {
            viewLifecycleOwner.lifecycleScope.launchWhenStarted {
                vm.lastMessagesFlow.collect { list ->
                    chatListAdapter.submitList(list)
                }
            }
            layoutManager = LinearLayoutManager(activity)
            adapter = chatListAdapter
        }



//        val emptyDataObserver = EmptyDataObserver(recyclerview, empty_data_parent)
//        chatListAdapter.registerAdapterDataObserver(emptyDataObserver)
    }


    }