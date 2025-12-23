package app.secure.kyber.fragments

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ToggleButton
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.emoji2.emojipicker.RecentEmojiProviderAdapter
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Slide
import androidx.transition.TransitionManager
import androidx.transition.Visibility
import app.secure.kyber.R
import app.secure.kyber.activities.MainActivity
import app.secure.kyber.adapters.MessageAdapter
import app.secure.kyber.adapters.emoji_adapter.CustomRecentEmojiProvider
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.backend.models.MessageModel
import app.secure.kyber.databinding.FragmentChatBinding
import app.secure.kyber.onionrouting.UnionClient
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.MessageEntity
import app.secure.kyber.roomdb.MessageRepository
import app.secure.kyber.roomdb.roomViewModel.MessagesViewModel
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.UUID

@AndroidEntryPoint
class ChatFragment : Fragment(R.layout.fragment_chat) {

    private lateinit var unionClient: UnionClient
    private var serverHost by mutableStateOf("139.59.96.43")  // Replace with your server IP
    private var serverPort by mutableStateOf("8080")

    private lateinit var binding: FragmentChatBinding
    private lateinit var navController: NavController

    private lateinit var adapterMsg: MessageAdapter

    private lateinit var recyclerview: RecyclerView
    private lateinit var recentEmojiProvider: CustomRecentEmojiProvider
    private lateinit var emojiCard: CardView
    private lateinit var emojiPickerContainer: FrameLayout
    private lateinit var emojiPickerView: EmojiPickerView
    private lateinit var messageEdit: TextInputEditText


    private val targetUnionId by lazy {
        requireArguments().getString("contact_id").orEmpty()
    }
    private val contactName by lazy {
        requireArguments().getString("contact_name").orEmpty()
    }

    private val vm: MessagesViewModel by viewModels {
        // quick factory — wire up Room
        val db = AppDb.get(requireContext())
        val repo = MessageRepository(db.messageDao())
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MessagesViewModel(repo, targetUnionId) as T
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        //Setup the emoji card showing
        emojiPickerView = binding.emojiPicker
        emojiPickerContainer = binding.emojiPickerContainer
        recentEmojiProvider = CustomRecentEmojiProvider(requireContext())
        emojiCard = binding.emojiCard
        messageEdit = binding.etMsg

        // Configure EmojiPickerView with RecentEmojiProvider
        emojiPickerView.setRecentEmojiProvider(RecentEmojiProviderAdapter(recentEmojiProvider))
        emojiPickerView.setOnEmojiPickedListener { emoji ->
            val start = messageEdit.selectionStart.coerceAtLeast(0)
            val end = messageEdit.selectionEnd.coerceAtLeast(0)
            messageEdit.text?.replace(
                start.coerceAtMost(end),
                end.coerceAtLeast(start),
                emoji.emoji,
                0,
                emoji.emoji.length
            )
            recentEmojiProvider.addRecentEmoji(emoji.emoji)
        }

        // Add emojiPickerView to container (if not already added)
        if (emojiPickerView.parent == null) {
            emojiPickerContainer.addView(emojiPickerView)
        }

        var isChecked = false

        // End icon click listener
        binding.tilMsg.setEndIconOnClickListener {
            if (!isChecked) {
                hideKeyboard(messageEdit)
                showEmojiCard()
            } else {
                hideEmojiCard()
                showKeyboard(messageEdit)
            }
            isChecked = !isChecked
        }

        // Focus change listener for the message edit
        messageEdit.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                isChecked = false
                hideEmojiCard()
                showKeyboard(messageEdit)
            }
        }

        // Click listener for the messageEdit
        messageEdit.setOnClickListener {
            isChecked = false
            hideEmojiCard()
            showKeyboard(messageEdit)
        }

        // Handle back press for emoji card visibility
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (emojiCard.isVisible) {
                        hideEmojiCard()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )

        navController = view.findNavController()
        unionClient = UnionClient()

        if (contactName.isNotEmpty() && contactName.length > 15) {
            (requireActivity() as MainActivity).setAppChatUser(contactName.substring(0, 14))
        } else {
            (requireActivity() as MainActivity).setAppChatUser(contactName?.takeUnless { it.isBlank() }
                ?: targetUnionId)
        }

        //Setup click listener for the chat details
        (requireActivity() as MainActivity).onChatDetailsClick(targetUnionId, contactName)


        onionAppConnection()

        binding.ivAdd.setOnClickListener {
            if (binding.contentMenu.isShown) {
                binding.contentMenu.visibility = View.GONE
            } else {
                binding.bottomSheet.setBackgroundResource(R.drawable.bottom_sheet)
                binding.contentMenu.visibility = View.VISIBLE
            }

        }


//        binding.etMsg.setOnEditorActionListener { v, actionId, event ->
//            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
//                val text = binding.etMsg.text.toString().trim()
//                if (text.isNotEmpty()) {
//                    sendMessage(text)
//                    vm.saveMessage(
//                        text,
//                        senderId = targetUnionId,//unionClient.unionId,
//                        timestamp = System.currentTimeMillis().toString(),
//                        isSent = true,
//                    ) // your function to update adapter/db
//                    binding.etMsg.setText("")
//                }
//                true  // consumed
//            } else {
//                false
//            }
//        }


        binding.ivSend.setOnClickListener {
            val text = binding.etMsg.text.toString().trim()
            if (text.isNotEmpty()) {
                sendMessage(text)
                vm.saveMessage(
                    text,
                    senderId = targetUnionId,//unionClient.unionId,
                    timestamp = System.currentTimeMillis().toString(),
                    isSent = true,
                ) // your function to update adapter/db
                binding.etMsg.setText("")
            }
        }


//            binding.tilMsg.viewTreeObserver.addOnGlobalLayoutListener {
//                val r = Rect()
//                binding.tilMsg.getWindowVisibleDisplayFrame(r)
//                val screenHeight = binding.tilMsg.rootView.height
//                val keypadHeight = screenHeight - r.bottom
//                Log.d("TESTING", "Camera button observer = keypad $keypadHeight : screen $screenHeight")
//                // Check if the keyboard is shown
//                if (keypadHeight > screenHeight * 0.15) {
//                    // Keyboard is visible
//                    binding.ivSend.visibility = View.VISIBLE // Show camera button
//                    binding.ivCamera.visibility = View.GONE
//                    binding.ivMic.visibility = View.GONE // Hide camera button
//                    Log.d("TESTING", "Camera button view gone")
//                } else {
//                    // Keyboard is hidden
//                    binding.ivSend.visibility = View.GONE // Show camera button
//                    binding.ivCamera.visibility = View.VISIBLE
//                    binding.ivMic.visibility = View.VISIBLE // Hide camera button
//
//                    Log.d("TESTING", "Camera button view view")
//                }
//            }

        setListAdapter()

    }

    override fun onDestroy() {
        disconnectFromServer()
        super.onDestroy()
    }

    private fun disconnectFromServer() {
        lifecycleScope.launch {
            unionClient.disconnect()
        }
    }

    private fun setListAdapter() {
        recyclerview = binding.rvMsg
        recyclerview.setHasFixedSize(false)

        adapterMsg = MessageAdapter("me123", onClick = {

        })

        recyclerview.apply {
            viewLifecycleOwner.lifecycleScope.launchWhenStarted {
                vm.messagesFlow.collect { list ->
                    adapterMsg.submitList(list) {
                        recyclerview.scrollToPosition(adapterMsg.itemCount - 1)
                    }
                }
            }
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true // ✅ start from bottom
            }
            adapter = adapterMsg
        }

    }

    private fun sendMessage(text: String) {
        sendMessageOnServer(text)

//        viewLifecycleOwner.lifecycleScope.launchWhenStarted {
//            vm.messagesFlow.collect { list ->
//                adapterMsg.submitList(list){
//                    recyclerview.scrollToPosition(adapterMsg.itemCount - 1)
//                }
//            }
//        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    fun onionAppConnection() {
        if (Prefs.getPublicKey(requireContext()) == null || Prefs.getPublicKey(requireContext())!!
                .isEmpty()
        ) {
            val exportedIdentity = unionClient.exportIdentity()
            Prefs.setUnionId(requireContext(), exportedIdentity["unionId"])
            Prefs.setPublicKey(requireContext(), exportedIdentity["publicKey"])
        } else {
            val m: Map<String, String> = mapOf(
                "unionId" to Prefs.getUnionId(requireContext())!!,
                "publicKey" to Prefs.getPublicKey(requireContext())!!
            )
            val importedIdentity = unionClient.importIdentity(m)
            Log.d("TAG", "onionAppConnection: $importedIdentity")
        }
        // Collect connection state
        //val connectionState by unionClient.connectionState.collectAsState()

        connectToServer()


//        if (connectionState == UnionClient.ConnectionState.DISCONNECTED) {
//
//        }
//
//         if (connectionState == UnionClient.ConnectionState.CONNECTED) {
//             Text("Union ID: ${unionClient.unionId}")
//        }

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

                        // Snackbar.make(binding.root, "Server Connected Successfully", Snackbar.LENGTH_SHORT).show()
                        recieveMessage()
                    }

                    UnionClient.ConnectionState.ERROR -> {
                        // Handle connection error
                        //  Snackbar.make(binding.root, "Error Connecting Server", Snackbar.LENGTH_SHORT).show()
                    }

                    else -> { /* Handle other states */
                        //  Snackbar.make(binding.root, "Something went wrong", Snackbar.LENGTH_SHORT).show()
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

    private fun sendMessageOnServer(text: String) {
        if (targetUnionId.isNotEmpty() && text.isNotEmpty()) {
            lifecycleScope.launch {
                val result = unionClient.sendMessage(targetUnionId, text)
                result.fold(
                    onSuccess = {
                        //  Snackbar.make(binding.root, "Message Sent", Snackbar.LENGTH_SHORT).show()
                    },
                    onFailure = { error ->
                        //  Snackbar.make(binding.root, "Error sending message", Snackbar.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    private fun recieveMessage() {
        unionClient.setMessageCallback(targetUnionId) { message ->

            vm.saveMessage(
                message.content,
                senderId = targetUnionId,
                timestamp = message.timestamp.toString(),
                isSent = false,
            )
            //Log.d("Union", "Message from specific sender: ${message.content}")
        }
    }


    private fun showEmojiCard() {
        if (emojiCard.visibility != View.VISIBLE) {
            TransitionManager.beginDelayedTransition(
                emojiCard.parent as ConstraintLayout,
                Slide(Gravity.BOTTOM).apply {
                    duration = 200
                    mode = Visibility.MODE_IN
                })
            emojiCard.visibility = View.VISIBLE
        }
    }

    private fun hideEmojiCard() {
        if (emojiCard.visibility != View.GONE) {
            TransitionManager.beginDelayedTransition(
                emojiCard.parent as ConstraintLayout,
                Slide(Gravity.BOTTOM).apply {
                    duration = 200
                    mode = Visibility.MODE_OUT
                })
            emojiCard.visibility = View.GONE
        }
    }

    private fun hideKeyboard(view: View) {
        val imm =
            requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun showKeyboard(view: View) {
        view.requestFocus()
        val imm =
            requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }


}