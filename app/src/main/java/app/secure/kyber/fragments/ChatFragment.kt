package app.secure.kyber.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.TranslateAnimation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.emoji2.emojipicker.RecentEmojiProviderAdapter
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.GroupCreationBackend.GroupManager
import app.secure.kyber.R
import app.secure.kyber.activities.CameraActivity
import app.secure.kyber.activities.FullscreenMediaActivity
import app.secure.kyber.activities.MainActivity
import app.secure.kyber.activities.MediaPreviewActivity
import app.secure.kyber.adapters.GroupMessagesAdapter
import app.secure.kyber.adapters.MessageAdapter
import app.secure.kyber.adapters.SelectedMediaAdapter
import app.secure.kyber.adapters.emoji_adapter.CustomRecentEmojiProvider
import app.secure.kyber.audio.AudioRecordingManager
import app.secure.kyber.backend.KyberRepository
import app.secure.kyber.backend.beans.PrivateMessageTransportDto
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.dataClasses.SelectedMediaParcelable
import app.secure.kyber.databinding.FragmentChatBinding
import app.secure.kyber.onionrouting.UnionClient
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.GroupMessageEntity
import app.secure.kyber.roomdb.GroupMessageRepository
import app.secure.kyber.roomdb.GroupRepository
import app.secure.kyber.roomdb.MessageEntity
import app.secure.kyber.roomdb.MessageRepository
import app.secure.kyber.roomdb.roomViewModel.GroupMessagesViewModel
import app.secure.kyber.roomdb.roomViewModel.GroupsViewModel
import app.secure.kyber.roomdb.roomViewModel.MessagesViewModel
import app.secure.kyber.roomdb.MessageUiModel
import app.secure.kyber.roomdb.toUiModel
import app.secure.kyber.Utils.EncryptionUtils
import com.google.android.material.textfield.TextInputEditText
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@Suppress("UNCHECKED_CAST")
@AndroidEntryPoint
class ChatFragment : Fragment(R.layout.fragment_chat) {

    @Inject
    lateinit var repository: KyberRepository

    private lateinit var unionClient: UnionClient

    private var serverHost by mutableStateOf("82.221.100.220")
    private var serverPort by mutableStateOf("8080")

    private lateinit var binding: FragmentChatBinding
    private lateinit var navController: NavController

    private lateinit var adapterMsg: MessageAdapter
    private lateinit var recyclerview: RecyclerView
    private lateinit var recentEmojiProvider: CustomRecentEmojiProvider
    private lateinit var emojiPickerContainer: FrameLayout
    private lateinit var emojiPickerView: EmojiPickerView
    private lateinit var messageEdit: TextInputEditText
    private lateinit var groupMessageAdapter: GroupMessagesAdapter
    private lateinit var groupManager: GroupManager

    private var isEmojiPickerVisible = false

    private lateinit var selectedMediaAdapter: SelectedMediaAdapter
    private val selectedMedias = mutableListOf<SelectedMedia>()

    // ── Audio recording ───────────────────────────────────────────────────────
    private lateinit var audioRecordingManager: AudioRecordingManager
    private val recordingTimerHandler = Handler(Looper.getMainLooper())
    private var recordingSeconds = 0
    private var pulseAnimation: AlphaAnimation? = null

    // ── Mic touch tracking for lock popup + locked recording bar ─────────────
    private var micDownRawY = 0f
    private var micDownRawX = 0f
    private val lockThreshold = 120f
    private val cancelThreshold = 180f
    private var isRecordingLocked = false
    private var isRecordingPaused = false
    private var lockPopupVisible = false
    private val waveformHandler = Handler(Looper.getMainLooper())
    private val liveAmplititudes = mutableListOf<Float>()

    private lateinit var groupCreationDate: String
    private lateinit var noOfMembers: String

    private var isRecordingStarted = false
    private val longPressHandler = Handler(Looper.getMainLooper())

    private var selectedMsg: MessageUiModel? = null
    private var selectedGroupMsg: GroupMessageEntity? = null

    private var recentEmojisList = mutableListOf("👌", "😊", "😂", "😍", "💜", "🎮")

    private val decryptedMediaCache = mutableMapOf<Long, MessageEntity>()

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val transportAdapter = moshi.adapter(PrivateMessageTransportDto::class.java)

    private val longPressRunnable = Runnable {
        if (hasAudioPermission()) {
            isRecordingStarted = true
            startAudioRecording()
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private val contactOnion by lazy { requireArguments().getString("contact_onion").orEmpty() }
    private val contactName by lazy { requireArguments().getString("contact_name").orEmpty() }
    private val comingFrom by lazy { requireArguments().getString("coming_from").orEmpty() }
    private val groupId by lazy { requireArguments().getString("group_id").orEmpty() }
    private val groupName by lazy { requireArguments().getString("group_name").orEmpty() }

    private val vm: MessagesViewModel by viewModels {
        val db = AppDb.get(requireContext())
        val repo = MessageRepository(db.messageDao())
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MessagesViewModel(repo, contactOnion) as T
        }
    }

    private val vm1: GroupMessagesViewModel by viewModels {
        val db = AppDb.get(requireContext())
        val repo = GroupMessageRepository(db.groupsMessagesDao())
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GroupMessagesViewModel(repo) as T
        }
    }

    private val vm2: GroupsViewModel by viewModels {
        val db = AppDb.get(requireContext())
        val repo = GroupRepository(db.groupsDao())
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GroupsViewModel(repo) as T
        }
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            isRecordingStarted = true
            startAudioRecording()
        }
    }

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult -> handlePickResult(result) }

    private val previewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data!!.getParcelableArrayListExtra(
                    MediaPreviewActivity.EXTRA_RESULT_ITEMS,
                    SelectedMediaParcelable::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                result.data!!.getParcelableArrayListExtra<SelectedMediaParcelable>(
                    MediaPreviewActivity.EXTRA_RESULT_ITEMS
                )
            }
            if (!list.isNullOrEmpty()) {
                val onionAddr = Prefs.getOnionAddress(requireContext()) ?: ""
                val name = Prefs.getName(requireContext()) ?: ""
                for (item in list) {
                    val caption = if (item.caption.isBlank()) {
                        if (item.type == "VIDEO") "video" else "photo"
                    } else item.caption

                    if (comingFrom == "group_chat_list") {
                        lifecycleScope.launch {
                            groupManager.sendMessage(
                                groupId = groupId,
                                senderId = onionAddr,
                                senderName = name,
                                messageText = caption,
                                groupMessagesViewModel = vm1,
                                type = item.type,
                                uri = item.uriString
                            )
                        }
                    } else {
                        val base64Uri = encodeFileToBase64(item.uriString.toString())
                        val messageId = UUID.randomUUID().toString()
                        val timestamp = System.currentTimeMillis().toString()
                        val transport = PrivateMessageTransportDto(
                            messageId = messageId,
                            msg = caption,
                            senderOnion = onionAddr,
                            timestamp = timestamp,
                            type = item.type,
                            uri = base64Uri
                        )

                        vm.saveMessage(
                            messageId = messageId,
                            msg = EncryptionUtils.encrypt(caption),
                            senderOnion = contactOnion,
                            timestamp = timestamp,
                            isSent = true,
                            type = item.type,
                            uri = EncryptionUtils.encrypt(item.uriString)
                        )
                        sendPrivateMessage(transport)
                    }
                }
            }
        }
    }

    private var apiMessageJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentChatBinding.inflate(inflater, container, false)
        groupManager = GroupManager()
        audioRecordingManager = AudioRecordingManager(requireContext())

        selectedMediaAdapter = SelectedMediaAdapter(
            selectedMedias,
            onRemove = { pos ->
                if (pos in selectedMedias.indices) {
                    selectedMedias.removeAt(pos)
                    selectedMediaAdapter.notifyItemRemoved(pos)
                    updatePreviewVisibility()
                }
            },
            onCaptionChanged = { pos, caption ->
                if (pos in selectedMedias.indices) selectedMedias[pos].caption = caption
            }
        )
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val name = Prefs.getName(requireContext()) ?: ""
        val onionAddr = Prefs.getOnionAddress(requireContext()).toString()

        messageEdit = binding.etMsg
        emojiPickerContainer = binding.emojiPickerContainer
        recentEmojiProvider = CustomRecentEmojiProvider(requireContext())

        loadRecentEmojis()

        if (comingFrom == "group_chat_list") {
            vm2.getCreationDate(onResult = { result ->
                groupCreationDate = formatTimestamp(result)
                if (::noOfMembers.isInitialized) {
                    (activity as? MainActivity)?.onGroupChatDetailsClick(
                        groupName, groupCreationDate, noOfMembers
                    )
                }
            }, groupId)

            vm2.getNoOfMembers(onResult = { result ->
                noOfMembers = result.toString()
                if (::groupCreationDate.isInitialized) {
                    (activity as? MainActivity)?.onGroupChatDetailsClick(
                        groupName, groupCreationDate, noOfMembers
                    )
                }
            }, groupId)
        }

        // ── Emoji picker ──────────────────────────────────────────────────────
        emojiPickerView = EmojiPickerView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setRecentEmojiProvider(RecentEmojiProviderAdapter(recentEmojiProvider))
            setOnEmojiPickedListener { emoji ->
                if (selectedMsg != null || selectedGroupMsg != null) {
                    val msgId = selectedMsg?.id
                    selectedMsg?.let {
                        adapterMsg.showReactionImmediately(it.id.toString(), emoji.emoji)
                    }
                    handleReaction(emoji.emoji)
                    updateRecentEmojiList(emoji.emoji, msgId.toString())
                    hideEmojiPicker()
                } else {
                    val editable = messageEdit.text ?: return@setOnEmojiPickedListener
                    val start = messageEdit.selectionStart.coerceAtLeast(0)
                    val end = messageEdit.selectionEnd.coerceAtLeast(start)
                    editable.replace(start, end, emoji.emoji)
                    recentEmojiProvider.addRecentEmoji(emoji.emoji)
                    updateRecentEmojiList(emoji.emoji)
                }
            }
        }
        emojiPickerContainer.addView(emojiPickerView)

        binding.tilMsg.setEndIconOnClickListener {
            selectedMsg = null
            selectedGroupMsg = null
            if (isEmojiPickerVisible) {
                hideEmojiPicker()
            } else {
                hideKeyboard(messageEdit)
                messageEdit.postDelayed({ showEmojiPicker() }, 150)
            }
        }
        messageEdit.setOnClickListener {
            if (isEmojiPickerVisible) {
                hideEmojiPicker()
                showKeyboard(messageEdit)
            }
        }
        messageEdit.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && isEmojiPickerVisible) hideEmojiPicker()
        }

        // ── Keyboard visibility → button visibility ───────────────────────────
        binding.tilMsg.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            binding.tilMsg.getWindowVisibleDisplayFrame(r)
            val screenH = binding.tilMsg.rootView.height
            val kbHeight = screenH - r.bottom
            val kbVisible = kbHeight > screenH * 0.15

            when {
                kbVisible -> {
                    if (isEmojiPickerVisible) hideEmojiPicker()
                    binding.ivSend.isVisible = true
                    binding.ivCamera.isVisible = false
                    binding.ivMic.isVisible = false
                }

                isEmojiPickerVisible -> {
                    binding.ivSend.isVisible = true
                    binding.ivCamera.isVisible = false
                    binding.ivMic.isVisible = false
                }

                else -> {
                    binding.ivSend.isVisible = false
                    binding.ivCamera.isVisible = true
                    binding.ivMic.isVisible = true
                }
            }
        }

        // ── Global tap handler to close emoji bars / action menus ─────────────
        binding.root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (::adapterMsg.isInitialized) adapterMsg.closeMenu()
                if (::groupMessageAdapter.isInitialized) groupMessageAdapter.closeMenu()
            }
            false
        }

        // ── Back press ────────────────────────────────────────────────────────
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        isRecordingLocked -> cancelAudioRecording()
                        isEmojiPickerVisible -> hideEmojiPicker()
                        else -> {
                            isEnabled = false
                            requireActivity().onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            })

        // ── Camera ────────────────────────────────────────────────────────────
        binding.ivCamera.setOnClickListener {
            previewLauncher.launch(Intent(requireContext(), CameraActivity::class.java))
        }

        // ── Mic touch listener ────────────────────────────────────────────────
        binding.ivMic.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    micDownRawY = event.rawY
                    micDownRawX = event.rawX
                    isRecordingLocked = false
                    isRecordingPaused = false
                    isRecordingStarted = false
                    longPressHandler.postDelayed(longPressRunnable, 400)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isRecordingStarted) return@setOnTouchListener true
                    if (isRecordingLocked) return@setOnTouchListener true

                    val deltaY = micDownRawY - event.rawY
                    val deltaX = micDownRawX - event.rawX

                    if (deltaX > cancelThreshold) {
                        cancelAudioRecording()
                        return@setOnTouchListener true
                    }
                    if (deltaY > lockThreshold) {
                        lockRecording()
                        return@setOnTouchListener true
                    }
                    if (deltaY > 20f && lockPopupVisible) {
                        val progress = (deltaY / lockThreshold).coerceIn(0f, 1f)
                        binding.ivLockIcon.scaleX = 1f + 0.4f * progress
                        binding.ivLockIcon.scaleY = 1f + 0.4f * progress
                        binding.tvLockChevrons.alpha = 0.3f + 0.7f * progress
                        binding.tvLockChevrons.translationY = -20f * progress
                        binding.lockPopup.translationY = -deltaY * 0.25f
                    }
                    if (deltaX > 20f) {
                        val progress = (deltaX / cancelThreshold).coerceIn(0f, 1f)
                        binding.tvSlideToCancel.alpha = 1f - progress
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (!isRecordingStarted) return@setOnTouchListener true
                    if (isRecordingLocked) return@setOnTouchListener true
                    if (audioRecordingManager.isCurrentlyRecording()) {
                        stopAndSendAudioRecording(onionAddr, name)
                    }
                    true
                }

                else -> false
            }
        }

        // ── Locked recording bar listeners ────────────────────────────────────
        binding.ivRecordDelete.setOnClickListener { cancelAudioRecording() }

        binding.ivRecordPause.setOnClickListener {
            if (!isRecordingLocked) return@setOnClickListener
            isRecordingPaused = !isRecordingPaused
            if (isRecordingPaused) {
                recordingTimerHandler.removeCallbacks(recordingTimerRunnable)
                waveformHandler.removeCallbacks(waveformUpdateRunnable)
                binding.ivRecordPause.setImageResource(R.drawable.pause_icon_1)
                stopMicPulse()
            } else {
                recordingTimerHandler.post(recordingTimerRunnable)
                waveformHandler.post(waveformUpdateRunnable)
                binding.ivRecordPause.setImageResource(R.drawable.pause_icon_0)
                startMicPulse()
            }
        }

        binding.ivRecordSend.setOnClickListener {
            if (isRecordingLocked) stopAndSendAudioRecording(onionAddr, name)
        }

        // ── Menu / Add ────────────────────────────────────────────────────────
        binding.ivAdd.setOnClickListener {
            if (binding.contentMenu.isVisible) {
                binding.contentMenu.isVisible = false
                binding.bottomSheet.setBackgroundResource(R.drawable.bottom_nav_bar_bg_png)
            } else {
                binding.bottomSheet.setBackgroundResource(R.drawable.bottom_sheet)
                binding.contentMenu.isVisible = true
            }
        }

        binding.ivGallery.setOnClickListener {
            openPicker()
            binding.contentMenu.isVisible = false
            binding.bottomSheet.setBackgroundResource(R.drawable.bottom_nav_bar_bg_png)
        }

        binding.etMsg.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                if (selectedMedias.isNotEmpty()) performSendForSelectedMedia()
                else {
                    val text = binding.etMsg.text.toString().trim()
                    if (text.isNotEmpty()) {
                        if (comingFrom == "group_chat_list") {
                            lifecycleScope.launch {
                                groupManager.sendMessage(groupId, onionAddr, name, text, vm1)
                                binding.etMsg.setText("")
                            }
                        } else {
                            val messageId = UUID.randomUUID().toString()
                            val timestamp = System.currentTimeMillis().toString()
                            val transport = PrivateMessageTransportDto(
                                messageId = messageId,
                                msg = text,
                                senderOnion = onionAddr,
                                timestamp = timestamp
                            )
                            vm.saveMessage(
                                messageId = messageId,
                                msg = EncryptionUtils.encrypt(text),
                                senderOnion = contactOnion,
                                timestamp = timestamp,
                                isSent = true
                            )
                            sendPrivateMessage(transport)
                            binding.etMsg.setText("")
                        }
                    }
                }
                true
            } else false
        }

        // ── Initial Setup ─────────────────────────────────────────────────────
        navController = view.findNavController()
        unionClient = UnionClient()
        onionAppConnection()

        when (comingFrom) {
            "chat_list" -> {
                (requireActivity() as MainActivity).setAppChatUser(
                    if (contactName.length > 15) contactName.substring(0, 14)
                    else contactName.takeUnless { it.isBlank() } ?: contactOnion
                )
                binding.ivSend.setOnClickListener {
                    val text = binding.etMsg.text.toString().trim()
                    if (text.isNotEmpty()) {
                        Log.d("SENDER ONION ADDRESS", contactOnion)
                        val messageId = UUID.randomUUID().toString()
                        val timestamp = System.currentTimeMillis().toString()
                        val transport = PrivateMessageTransportDto(
                            messageId = messageId,
                            msg = text,
                            senderOnion = onionAddr,
                            timestamp = timestamp
                        )
                        vm.saveMessage(
                            messageId = messageId,
                            msg = EncryptionUtils.encrypt(text),
                            senderOnion = contactOnion,
                            timestamp = timestamp,
                            isSent = true
                        )
                        sendPrivateMessage(transport)
                        binding.etMsg.setText("")
                    }
                }
                (requireActivity() as MainActivity).onChatDetailsClick(contactOnion, contactName)
                setListMessageAdapter()
                startApiMessagePolling()
            }

            "group_chat_list" -> {
                groupManager.listenForMessages(groupId, onionAddr, vm1)
                (requireActivity() as MainActivity).setAppChatUser(groupName)
                binding.ivSend.setOnClickListener {
                    val text = binding.etMsg.text.toString().trim()
                    if (text.isNotEmpty()) {
                        Log.d("SENDER ONION ADDRESS", onionAddr)
                        lifecycleScope.launch {
                            groupManager.sendMessage(groupId, onionAddr, name, text, vm1)
                            binding.etMsg.setText("")
                        }
                    }
                }
                setListGroupMessageAdapter(onionAddr)
            }
        }

        binding.rvPreview.apply {
            layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = selectedMediaAdapter
        }
        updatePreviewVisibility()
    }

    // ── API Polling ───────────────────────────────────────────────────────────

    private fun startApiMessagePolling() {
        apiMessageJob?.cancel()
        apiMessageJob = lifecycleScope.launch {
            while (isActive) {
                fetchMessagesFromApi()
                delay(5000)
            }
        }
    }

    private suspend fun fetchMessagesFromApi() {
        try {
            val myOnion = Prefs.getOnionAddress(requireContext()) ?: return
            var circuitId = Prefs.getCircuitId(requireContext())

            if (circuitId.isNullOrEmpty()) {
                val circuitResp = repository.createCircuit()
                if (circuitResp.isSuccessful) {
                    circuitId = circuitResp.body()?.circuitId ?: ""
                    Prefs.setCircuitId(requireContext(), circuitId)
                }
            }

            if (circuitId.isNullOrEmpty()) return

            val response = repository.getMessages(myOnion, circuitId)

            if (response.isSuccessful) {
                val apiResponse = response.body()
                apiResponse?.messages?.forEach { apiMsg ->
                    val decodedPayload = try {
                        String(Base64.decode(apiMsg.payload, Base64.NO_WRAP), Charsets.UTF_8)
                    } catch (e: Exception) {
                        Log.e("ChatFragment", "Base64 decode failed", e)
                        return@forEach
                    }

                    try {
                        val transport = transportAdapter.fromJson(decodedPayload)
                        if (transport != null && transport.senderOnion.equals(
                                contactOnion,
                                ignoreCase = true
                            )
                        ) {
                            // Check if already exists
                            val existing = vm.getMessageByMessageId(transport.messageId)
                            val encryptedUri =
                                if (!transport.uri.isNullOrBlank()) EncryptionUtils.encrypt(
                                    transport.uri
                                ) else null
                            if (existing == null) {
                                vm.saveMessage(
                                    messageId = transport.messageId,
                                    msg = EncryptionUtils.encrypt(transport.msg),
                                    senderOnion = contactOnion,
                                    timestamp = transport.timestamp,
                                    isSent = false,
                                    type = transport.type,
                                    uri = encryptedUri,
                                    ampsJson = transport.ampsJson,
                                    apiMessageId = apiMsg.id,
                                    reaction = transport.reaction
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ChatFragment", "JSON parse failed: $decodedPayload", e)
                    }
                }
            } else if (response.code() == 404 || response.code() == 400) {
                Prefs.setCircuitId(requireContext(), "")
            }
        } catch (e: Exception) {
            Log.e("ChatFragment", "Error fetching messages from API", e)
        }
    }

    private fun recieveMessage() {
        unionClient.setMessageCallback(contactOnion) { socketMsg ->
            try {
                val decodedPayload =
                    String(Base64.decode(socketMsg.content, Base64.NO_WRAP), Charsets.UTF_8)
                val transport = transportAdapter.fromJson(decodedPayload)
                if (transport != null && transport.senderOnion.equals(
                        contactOnion,
                        ignoreCase = true
                    )
                ) {

                    val encryptedUri =
                        if (!transport.uri.isNullOrBlank()) EncryptionUtils.encrypt(transport.uri) else null

                    lifecycleScope.launch {
                        val existing = vm.getMessageByMessageId(transport.messageId)
                        if (existing == null) {
                            vm.saveMessage(
                                messageId = transport.messageId,
                                msg = EncryptionUtils.encrypt(transport.msg),
                                senderOnion = contactOnion,
                                timestamp = transport.timestamp,
                                isSent = false,
                                type = transport.type,
                                uri = encryptedUri,
                                ampsJson = transport.ampsJson,
                                reaction = transport.reaction
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatFragment", "Socket receive error: ${e.message}")
            }
        }
    }

    private fun parseApiTimestamp(timestamp: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                java.time.Instant.parse(timestamp).toEpochMilli().toString()
            } else {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault())
                val formattedTimestamp = timestamp.replace("Z", "+0000")
                val date = sdf.parse(formattedTimestamp)
                date?.time?.toString() ?: System.currentTimeMillis().toString()
            }
        } catch (e: Exception) {
            Log.e("ChatFragment", "Failed to parse timestamp: $timestamp", e)
            System.currentTimeMillis().toString()
        }
    }

    // ── Message sending ───────────────────────────────────────────────────────

    private fun sendPrivateMessage(transport: PrivateMessageTransportDto) {
        lifecycleScope.launch {
            try {
                val jsonPayload = transportAdapter.toJson(transport)
                val base64Payload =
                    Base64.encodeToString(jsonPayload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

                var circuitId = Prefs.getCircuitId(requireContext()) ?: ""
                var apiSuccess = false

                try {
                    if (circuitId.isEmpty()) {
                        val circuitResp = repository.createCircuit()
                        if (circuitResp.isSuccessful) {
                            circuitId = circuitResp.body()?.circuitId ?: ""
                            Prefs.setCircuitId(requireContext(), circuitId)
                        }
                    }

                    if (circuitId.isNotEmpty()) {
                        var response =
                            repository.sendMessage(contactOnion, base64Payload, circuitId)
                        if (!response.isSuccessful && (response.code() == 404 || response.code() == 400)) {
                            val retryCircuitResp = repository.createCircuit()
                            if (retryCircuitResp.isSuccessful) {
                                circuitId = retryCircuitResp.body()?.circuitId ?: ""
                                Prefs.setCircuitId(requireContext(), circuitId)
                                response =
                                    repository.sendMessage(contactOnion, base64Payload, circuitId)
                            }
                        }
                        if (response.isSuccessful) apiSuccess = true
                    }
                } catch (e: Exception) {
                    Log.e("ChatFragment", "API call failed: ${e.message}")
                }

                unionClient.sendMessage(contactOnion, base64Payload)
            } catch (e: Exception) {
                Log.e("ChatFragment", "Final delivery failure: ${e.message}")
            }
        }
    }

    // ── Reactions / Reply / Forward / Info / Delete ───────────────────────────

    private fun handleReaction(emoji: String) {
        lifecycleScope.launch {
            if (comingFrom == "group_chat_list") {
                selectedGroupMsg?.let { msg ->
                    msg.reaction = emoji
                    vm1.updateMessage(msg)
                    groupManager.updateReaction(groupId, msg.messageId, emoji)
                }
            } else {
                selectedMsg?.let { msg ->
                    msg.reaction = emoji
                    vm.updateMessage(msg.entity)

                    val myOnion = Prefs.getOnionAddress(requireContext()) ?: ""
                    val transport = PrivateMessageTransportDto(
                        messageId = msg.messageId,
                        msg = msg.decryptedMsg,
                        senderOnion = myOnion,
                        timestamp = msg.time,
                        type = msg.type,
                        uri = msg.decryptedUri,
                        ampsJson = msg.ampsJson,
                        reaction = emoji
                    )
                    sendPrivateMessage(transport)
                }
            }
            selectedMsg = null
            selectedGroupMsg = null
        }
    }

    private fun handleReply(text: String) {
        lifecycleScope.launch {
            messageEdit.setText(String.format(">> \"%s\"\n", text))
            messageEdit.requestFocus()
            showKeyboard(messageEdit)
        }
    }

    private fun handleForward() {
        Toast.makeText(requireContext(), "Forward feature - select contacts", Toast.LENGTH_SHORT)
            .show()
    }

    private fun handleInfo(msg: Any) {
        val time = if (msg is MessageUiModel) convertDatetime(msg.time)
        else convertDatetime((msg as GroupMessageEntity).time)
        val senderInfo = if (msg is GroupMessageEntity)
            String.format("Sender: %s\nOnion: %s\n", msg.senderName, msg.senderOnion)
        else ""
        val infoMessage = String.format("%sTime: %s\nStatus: Delivered", senderInfo, time)
        Toast.makeText(requireContext(), infoMessage, Toast.LENGTH_LONG).show()
    }

    private fun handleDelete(msg: Any) {
        lifecycleScope.launch {
            if (msg is GroupMessageEntity) {
                vm1.deleteMessage(msg)
                groupManager.deleteMessage(groupId, msg.messageId)
                updateLastMessageForGroup(groupId)
            } else if (msg is MessageUiModel) {
                vm.deleteMessage(msg.entity)
            }
            Toast.makeText(requireContext(), "Message deleted", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun updateLastMessageForGroup(groupId: String) {
        val latest = vm1.getLatestMessage(groupId)
        val group = vm2.getGroupById(groupId)
        if (group != null) {
            val updatedGroup = group.copy(
                lastMessage = latest?.msg ?: "",
                timeSpan = latest?.time?.toLongOrNull() ?: System.currentTimeMillis()
            )
            vm2.saveGroup(updatedGroup)
            groupManager.updateLastMessage(
                groupId,
                updatedGroup.lastMessage,
                updatedGroup.timeSpan.toString(),
                latest?.senderOnion ?: ""
            )
        }
    }

    // ── Adapter setup ─────────────────────────────────────────────────────────

    private fun setListMessageAdapter() {
        recyclerview = binding.rvMsg

        // 1. Fetch the last seen local message ID for this specific contact
        val sharedPrefs = requireContext().getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
        val lastSeenIdKey = "last_seen_id_${contactOnion}"
        val lastSeenId = sharedPrefs.getLong(lastSeenIdKey, 0L)
        adapterMsg = MessageAdapter(
            myId = "me123",
            lastSeenMessageId = lastSeenId,
            onClick = { msg ->
                if (msg.type.uppercase(Locale.getDefault()) != "AUDIO") {
                    var actualUri = msg.decryptedUri ?: msg.decryptedMsg

                    if (actualUri.isNotBlank()) {

                        // 1. Strip legacy network prefixes just in case
                        if (actualUri.startsWith("[IMAGE] ")) actualUri = actualUri.removePrefix("[IMAGE] ")
                        else if (actualUri.startsWith("[VIDEO] ")) actualUri = actualUri.removePrefix("[VIDEO] ")

                        // 2. CRITICAL FIX: The payload in the UI model is raw Base64.
                        // We must decode it to a valid physical file path before opening the viewer!
                        // (Uses ChatFragment's existing helper function)
                        actualUri = decodeBase64ToFile(actualUri, msg.type) ?: actualUri

                        // 3. Fix Android MediaServer permissions for Videos
                        // MediaPlayer cannot legally read internal cache file:// URIs.
                        // We securely convert it to a content:// URI using FileProvider.
                        var finalUri = actualUri
                        if (actualUri.startsWith("file://")) {
                            try {
                                val file = java.io.File(java.net.URI(actualUri))
                                if (file.exists()) {
                                    val authority = "${requireContext().packageName}.provider"
                                    finalUri = androidx.core.content.FileProvider.getUriForFile(
                                        requireContext(),
                                        authority,
                                        file
                                    ).toString()
                                }
                            } catch (e: Exception) {
                                // Fallback if FileProvider is named differently in Manifest
                                try {
                                    val authorityAlt = "${requireContext().packageName}.fileprovider"
                                    val file = java.io.File(java.net.URI(actualUri))
                                    finalUri = androidx.core.content.FileProvider.getUriForFile(
                                        requireContext(),
                                        authorityAlt,
                                        file
                                    ).toString()
                                } catch (e2: Exception) {
                                    finalUri = actualUri // Absolute fallback
                                }
                            }
                        }

                        // 4. Pass the secure, resolved URI to the viewer
                        startActivity(
                            Intent(requireContext(), FullscreenMediaActivity::class.java).apply {
                                putExtra("uri", finalUri)
                                putExtra("type", msg.type)
                                // Grant temporary read permission to the target activity
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            })
                    }
                }
            },
            onLongClick = { view, msg ->
                val textToUse = msg.decryptedMsg
                when (view.id) {
                    R.id.btnReplySent -> handleReply(textToUse)
                    R.id.btnReplyRcv -> handleReply(textToUse)
                    R.id.btnDeleteSent -> handleDelete(msg)
                    R.id.btnDeleteRcv -> handleDelete(msg)
                    R.id.btnCopySent -> (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("msg", textToUse))

                    R.id.btnCopyRcv -> (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("msg", textToUse))

                    R.id.btnForwardSent -> handleForward()
                    R.id.btnForwardRcv -> handleForward()
                    R.id.btnInfoSent -> handleInfo(msg)
                    R.id.btnInfoRcv -> handleInfo(msg)
                }
            },
            onEmojiSelected = { msg, emoji ->
                selectedMsg = msg
                adapterMsg.showReactionImmediately(msg.id.toString(), emoji)
                handleReaction(emoji)
                updateRecentEmojiList(emoji, msg.id.toString())
            },
            onMoreEmojisClicked = { msg ->
                selectedMsg = msg
                selectedGroupMsg = null
                if (!isEmojiPickerVisible) {
                    hideKeyboard(messageEdit)
                    messageEdit.postDelayed({ showEmojiPicker() }, 150)
                }
            },
            recentEmojis = recentEmojisList
        )

        adapterMsg.messageDecryptor = { encryptedText ->
            EncryptionUtils.decrypt(encryptedText)
        }

        recyclerview.apply {
            viewLifecycleOwner.lifecycleScope.launch {
                vm.messagesFlow.collect { list ->
                    val uiModels = list.map { it.toUiModel() }
                    adapterMsg.submitList(uiModels) {
                        scrollToPosition(adapterMsg.itemCount - 1)
                        // 3. Immediately update the shared prefs to the highest ID in this list.
                        // This guarantees that the NEXT time you open the chat, these messages
                        // will be safely treated as historical and won't animate again.
                        val maxId = uiModels.maxOfOrNull { it.id } ?: 0L
                        if (maxId > sharedPrefs.getLong(lastSeenIdKey, 0L)) {
                            sharedPrefs.edit().putLong(lastSeenIdKey, maxId).apply()
                        }
                    }
                }
            }
            layoutManager = object : LinearLayoutManager(requireContext()) {
                override fun supportsPredictiveItemAnimations() = false
            }.apply { stackFromEnd = false }
            adapter = adapterMsg
            clipChildren = false
            clipToPadding = false
        }
    }

    private fun setListGroupMessageAdapter(onionAddr: String) {
        recyclerview = binding.rvMsg
        groupMessageAdapter = GroupMessagesAdapter(
            onClick = { msg ->
                if (msg.type.uppercase(Locale.getDefault()) != "AUDIO") {
                    val uriStr = msg.uri ?: msg.msg
                    if (uriStr.isNotBlank()) {
                        startActivity(
                            Intent(requireContext(), FullscreenMediaActivity::class.java).apply {
                                putExtra("uri", uriStr); putExtra("type", msg.type)
                            })
                    }
                }
            },
            onLongClick = { view, msg ->
                when (view.id) {
                    R.id.btnReplySent -> handleReply(msg.msg)
                    R.id.btnReplyRcv -> handleReply(msg.msg)
                    R.id.btnDeleteSent -> handleDelete(msg)
                    R.id.btnDeleteRcv -> handleDelete(msg)
                    R.id.btnCopySent -> (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("msg", msg.msg))

                    R.id.btnCopyRcv -> (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("msg", msg.msg))

                    R.id.btnForwardSent -> handleForward()
                    R.id.btnForwardRcv -> handleForward()
                    R.id.btnInfoSent -> handleInfo(msg)
                    R.id.btnInfoRcv -> handleInfo(msg)
                }
            },
            onEmojiSelected = { msg, emoji ->
                selectedGroupMsg = msg
                groupMessageAdapter.showReactionImmediately(msg.messageId, emoji)
                handleReaction(emoji)
                updateRecentEmojiList(emoji, msg.messageId)
            },
            onMoreEmojisClicked = { msg ->
                selectedGroupMsg = msg
                selectedMsg = null
                if (!isEmojiPickerVisible) {
                    hideKeyboard(messageEdit)
                    messageEdit.postDelayed({ showEmojiPicker() }, 150)
                }
            },
            myId = onionAddr,
            recentEmojis = recentEmojisList
        )

        groupMessageAdapter.messageDecryptor = { encryptedText ->
            delay((300L..700L).random())
            encryptedText
        }

        recyclerview.layoutManager = object : LinearLayoutManager(requireContext()) {
            override fun supportsPredictiveItemAnimations() = false
        }.apply { stackFromEnd = false }
        recyclerview.adapter = groupMessageAdapter
        recyclerview.clipChildren = false
        recyclerview.clipToPadding = false

        vm1.getMessagesForGroupChat(groupId) { liveData ->
            liveData.observe(viewLifecycleOwner) { messages ->
                groupMessageAdapter.submitList(messages) {
                    recyclerview.scrollToPosition(groupMessageAdapter.itemCount - 1)
                }
            }
        }
    }

    // ── Audio recording ───────────────────────────────────────────────────────

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun startAudioRecording() {
        if (!audioRecordingManager.startRecording()) return
        recordingSeconds = 0
        isRecordingLocked = false
        isRecordingPaused = false
        liveAmplititudes.clear()

        binding.ivLockIcon.scaleX = 1f
        binding.ivLockIcon.scaleY = 1f
        binding.lockPopup.translationY = 0f
        binding.lockPopup.alpha = 0f
        binding.tvLockChevrons.translationY = 0f
        binding.tvLockChevrons.alpha = 0.3f

        binding.bottomSheet.isVisible = false
        binding.lockedRecordingBar.isVisible = false
        binding.unLockedRecordingBar.isVisible = true
        binding.tvSlideToCancel.alpha = 1f

        binding.lockPopup.isVisible = true
        binding.lockPopup.animate().alpha(1f).setDuration(200).start()
        lockPopupVisible = true

        binding.tvRecordingTimerUnlocked.text = "0:00"
        binding.tvRecordingTimer.text = "0:00"
        startMicPulse()
        recordingTimerHandler.post(recordingTimerRunnable)
        waveformHandler.post(waveformUpdateRunnable)
    }

    private fun lockRecording() {
        isRecordingLocked = true
        lockPopupVisible = false

        binding.ivLockIcon.animate()
            .scaleX(1.6f).scaleY(1.6f).setDuration(120)
            .withEndAction {
                binding.lockPopup.animate()
                    .translationY(-300f).alpha(0f).setDuration(250)
                    .withEndAction {
                        binding.lockPopup.isVisible = false
                        binding.lockPopup.translationY = 0f
                        binding.lockPopup.alpha = 1f
                        binding.ivLockIcon.scaleX = 1f
                        binding.ivLockIcon.scaleY = 1f
                        binding.tvLockChevrons.translationY = 0f
                        binding.tvLockChevrons.alpha = 0.3f
                        binding.unLockedRecordingBar.isVisible = false
                        binding.lockedRecordingBar.isVisible = true
                        binding.ivRecordPause.setImageResource(R.drawable.pause_icon_0)
                        binding.waveformRecording.setAmplitudes(liveAmplititudes.toList())
                    }.start()
            }.start()
    }

    private fun stopAndSendAudioRecording(onionAddr: String, senderName: String) {
        val filePath = audioRecordingManager.stopRecording()
        val amplitudes = audioRecordingManager.amplitudeSamples

        recordingTimerHandler.removeCallbacks(recordingTimerRunnable)
        waveformHandler.removeCallbacks(waveformUpdateRunnable)
        stopMicPulse()

        isRecordingStarted = false
        isRecordingLocked = false
        isRecordingPaused = false
        lockPopupVisible = false

        binding.lockedRecordingBar.isVisible = false
        binding.unLockedRecordingBar.isVisible = false
        binding.lockPopup.isVisible = false
        binding.bottomSheet.isVisible = true

        if (filePath.isNullOrBlank()) return
        if (recordingSeconds < 1) {
            java.io.File(filePath).delete(); return
        }

        val fileUri = Uri.fromFile(java.io.File(filePath)).toString()
        val ampsJson = encodeAmplitudes(amplitudes)
        val duration = getTotalDuration(requireContext(), fileUri)
        val durationStr = formatDuration(duration)
        val messageText = "Voice Message ($durationStr)"

        // FIX: Encode the actual file to Base64 for the network payload
        val base64Uri = encodeFileToBase64(fileUri)

        if (comingFrom == "group_chat_list") {
            lifecycleScope.launch {
                groupManager.sendMessage(
                    groupId, onionAddr, senderName,
                    messageText, vm1,
                    "AUDIO", fileUri, ampsJson
                )
            }
        } else {
            val messageId = UUID.randomUUID().toString()
            val timestamp = System.currentTimeMillis().toString()

            // Network payload gets the Base64 encoded file
            val transport = PrivateMessageTransportDto(
                messageId = messageId,
                msg = messageText,
                senderOnion = onionAddr,
                timestamp = timestamp,
                type = "AUDIO",
                uri = base64Uri, // <--- CHANGED FROM fileUri
                ampsJson = ampsJson
            )

            // Local Room DB keeps the local file path (encrypted)
            vm.saveMessage(
                messageId = messageId,
                msg = EncryptionUtils.encrypt(messageText),
                senderOnion = contactOnion,
                timestamp = timestamp,
                isSent = true,
                type = "AUDIO",
                uri = EncryptionUtils.encrypt(fileUri), // Keeps local file path!
                ampsJson = ampsJson
            )
            sendPrivateMessage(transport)
        }
    }

    private fun cancelAudioRecording() {
        audioRecordingManager.cancelRecording()
        recordingTimerHandler.removeCallbacks(recordingTimerRunnable)
        waveformHandler.removeCallbacks(waveformUpdateRunnable)
        stopMicPulse()
        isRecordingStarted = false
        isRecordingPaused = false
        lockPopupVisible = false

        if (isRecordingLocked) {
            val anim = TranslateAnimation(0f, -200f, 0f, 0f).apply {
                duration = 300
                setAnimationListener(object : Animation.AnimationListener {
                    override fun onAnimationStart(a: Animation?) {}
                    override fun onAnimationRepeat(a: Animation?) {}
                    override fun onAnimationEnd(a: Animation?) {
                        isRecordingLocked = false
                        binding.lockedRecordingBar.isVisible = false
                        binding.unLockedRecordingBar.isVisible = false
                        resetLockPopupState()
                        binding.bottomSheet.isVisible = true
                    }
                })
            }
            binding.lockedRecordingBar.startAnimation(anim)
        } else {
            isRecordingLocked = false
            binding.lockedRecordingBar.isVisible = false
            binding.unLockedRecordingBar.isVisible = false
            resetLockPopupState()
            binding.bottomSheet.isVisible = true
        }
    }

    private fun resetLockPopupState() {
        binding.lockPopup.isVisible = false
        binding.lockPopup.translationY = 0f
        binding.lockPopup.alpha = 1f
        binding.ivLockIcon.scaleX = 1f
        binding.ivLockIcon.scaleY = 1f
        binding.tvLockChevrons.translationY = 0f
        binding.tvLockChevrons.alpha = 0.3f
    }

    // ── Timer / Waveform ──────────────────────────────────────────────────────

    private val recordingTimerRunnable = object : Runnable {
        override fun run() {
            if (isRecordingPaused) return
            recordingSeconds++
            val formatted = String.format(
                Locale.getDefault(), "%02d:%02d",
                recordingSeconds / 60, recordingSeconds % 60
            )
            binding.tvRecordingTimer.text = formatted
            binding.tvRecordingTimerUnlocked.text = formatted
            recordingTimerHandler.postDelayed(this, 1000)
        }
    }

    private val waveformUpdateRunnable = object : Runnable {
        override fun run() {
            if (isRecordingPaused || !audioRecordingManager.isCurrentlyRecording()) return
            val samples = audioRecordingManager.amplitudeSamples
            if (samples.isNotEmpty()) {
                liveAmplititudes.clear()
                liveAmplititudes.addAll(samples)
                if (isRecordingLocked) {
                    binding.waveformRecording.setAmplitudes(
                        if (samples.size > 60) samples.takeLast(60) else samples
                    )
                }
            }
            waveformHandler.postDelayed(this, 100)
        }
    }

    private fun startMicPulse() {
        pulseAnimation = AlphaAnimation(1.0f, 0.2f).apply {
            duration = 600
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        binding.ivRecordingMic.startAnimation(pulseAnimation)
        binding.ivRecordingMicUnlocked.startAnimation(pulseAnimation)
    }

    private fun stopMicPulse() {
        pulseAnimation?.cancel()
        binding.ivRecordingMic.clearAnimation()
        binding.ivRecordingMicUnlocked.clearAnimation()
        pulseAnimation = null
    }

    private fun encodeAmplitudes(amps: List<Float>): String {
        val target = 100
        val sampled = if (amps.size <= target) amps
        else List(target) { i ->
            amps[(i * (amps.size.toFloat() / target)).toInt().coerceIn(0, amps.size - 1)]
        }
        val array = JSONArray()
        sampled.forEach { array.put(it.toDouble()) }
        return array.toString()
    }

    // ── Emoji Picker ──────────────────────────────────────────────────────────

    private fun showEmojiPicker() {
        isEmojiPickerVisible = true
        emojiPickerContainer.isVisible = true
        binding.ivSend.isVisible = true
        binding.ivCamera.isVisible = true
        binding.ivMic.isVisible = false
    }

    private fun hideEmojiPicker() {
        isEmojiPickerVisible = false
        emojiPickerContainer.isVisible = false
        binding.ivSend.isVisible = false
        binding.ivCamera.isVisible = true
        binding.ivMic.isVisible = true
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onPause() {
        if (::adapterMsg.isInitialized) adapterMsg.releasePlayer()
        if (::groupMessageAdapter.isInitialized) groupMessageAdapter.releasePlayer()
        super.onPause()
    }

    override fun onDestroyView() {
        if (::adapterMsg.isInitialized) adapterMsg.releasePlayer()
        if (::groupMessageAdapter.isInitialized) groupMessageAdapter.releasePlayer()
        super.onDestroyView()
    }

    override fun onDestroy() {
        recordingTimerHandler.removeCallbacks(recordingTimerRunnable)
        waveformHandler.removeCallbacks(waveformUpdateRunnable)
        longPressHandler.removeCallbacks(longPressRunnable)
        audioRecordingManager.cancelRecording()
        apiMessageJob?.cancel()
        disconnectFromServer()
        super.onDestroy()
    }

    private fun disconnectFromServer() {
        lifecycleScope.launch { unionClient.disconnect() }
    }

    // ── Recent Emojis ─────────────────────────────────────────────────────────

    private fun loadRecentEmojis() {
        val saved = Prefs.getRecentEmojis(requireContext())
        if (saved != null) {
            recentEmojisList.clear()
            recentEmojisList.addAll(saved)
        }
    }

    private fun updateRecentEmojiList(emoji: String, msgId: String? = null) {
        recentEmojisList.remove(emoji)
        recentEmojisList.add(0, emoji)
        if (recentEmojisList.size > 8) {
            recentEmojisList = recentEmojisList.take(8).toMutableList()
        }
        Prefs.setRecentEmojis(requireContext(), recentEmojisList)
        if (::adapterMsg.isInitialized) adapterMsg.updateRecentEmojis(recentEmojisList, msgId)
        if (::groupMessageAdapter.isInitialized) groupMessageAdapter.updateRecentEmojis(
            recentEmojisList
        )
    }

    // ── Media Picker ──────────────────────────────────────────────────────────

    private fun openPicker() {
        pickMediaLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        })
    }

    @SuppressLint("WrongConstant")
    private fun handlePickResult(result: ActivityResult) {
        val data = result.data ?: return
        val clip = data.clipData
        val uris = mutableListOf<Uri>()
        if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
        else data.data?.let { uris.add(it) }
        if (uris.isEmpty()) return

        val takeFlags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val parcelables = ArrayList<SelectedMediaParcelable>(uris.size)
        for (uri in uris) {
            try {
                requireContext().contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Exception) {
            }
            val mime = try {
                requireContext().contentResolver.getType(uri)
            } catch (_: Exception) {
                null
            }
            val type = if (mime?.startsWith("video") == true ||
                uri.toString().endsWith(".mp4", true)
            ) "VIDEO" else "IMAGE"
            parcelables.add(SelectedMediaParcelable(uri.toString(), type, ""))
        }
        previewLauncher.launch(Intent(requireContext(), MediaPreviewActivity::class.java).apply {
            putParcelableArrayListExtra(MediaPreviewActivity.EXTRA_ITEMS, parcelables)
        })
        binding.contentMenu.isVisible = false
        binding.bottomSheet.setBackgroundResource(R.drawable.bottom_nav_bar_bg_png)
    }

    private fun updatePreviewVisibility() {
        binding.previewContainer.isVisible = selectedMedias.isNotEmpty()
    }

    private fun performSendForSelectedMedia() {
        val onionAddr = Prefs.getOnionAddress(requireContext()) ?: ""
        val name = Prefs.getName(requireContext()) ?: ""
        for (m in selectedMedias) {
            val caption = if (m.caption.isNullOrBlank())
                (if (m.type == "VIDEO") "video" else "photo") else m.caption

            // FIX: Encode the actual image/video to Base64 for the network
            val base64Uri = encodeFileToBase64(m.uri.toString())
            if (comingFrom == "group_chat_list") {
                lifecycleScope.launch {
                    groupManager.sendMessage(
                        groupId, onionAddr, name, caption!!, vm1, m.type, m.uri.toString()
                    )
                }
            } else {
                val messageId = UUID.randomUUID().toString()
                val timestamp = System.currentTimeMillis().toString()

                // Network payload gets the Base64 encoded file
                val transport = PrivateMessageTransportDto(
                    messageId = messageId,
                    msg = caption!!,
                    senderOnion = onionAddr,
                    timestamp = timestamp,
                    type = m.type,
                    uri = base64Uri // <--- CHANGED FROM m.uri.toString()
                )

                // Local Room DB keeps the local file path (encrypted)
                vm.saveMessage(
                    messageId = messageId,
                    msg = EncryptionUtils.encrypt(caption),
                    senderOnion = contactOnion,
                    timestamp = timestamp,
                    isSent = true,
                    type = m.type,
                    uri = EncryptionUtils.encrypt(m.uri.toString()) // Keeps local file path!
                )
                sendPrivateMessage(transport)
            }
        }
        val count = selectedMedias.size
        selectedMedias.clear()
        selectedMediaAdapter.notifyItemRangeRemoved(0, count)
        updatePreviewVisibility()
        binding.etMsg.setText("")
    }

    // ── Union / Socket Connection ─────────────────────────────────────────────

    fun onionAppConnection() {
        if (Prefs.getPublicKey(requireContext()).isNullOrEmpty()) {
            val exported = unionClient.exportIdentity()
            Prefs.setOnionAddress(requireContext(), exported["onionAddress"])
            Prefs.setPublicKey(requireContext(), exported["publicKey"])
        } else {
            unionClient.importIdentity(
                mapOf(
                    "onionAddress" to (Prefs.getOnionAddress(requireContext()) ?: ""),
                    "publicKey" to (Prefs.getPublicKey(requireContext()) ?: "")
                )
            )
        }
        connectToServer()
    }

    private fun connectToServer() {
        lifecycleScope.launch {
            unionClient.connect(serverHost, serverPort.toIntOrNull() ?: 8080) {
                if (it == UnionClient.ConnectionState.CONNECTED) recieveMessage()
            }
        }
    }

    // ── Keyboard Helpers ──────────────────────────────────────────────────────

    private fun hideKeyboard(view: View) {
        (requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun showKeyboard(view: View) {
        view.requestFocus()
        (requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun formatDuration(ms: Int): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.getDefault(), "%d:%02d", totalSec / 60, totalSec % 60)
    }

    private fun getTotalDuration(context: Context, uriStr: String): Int {
        if (uriStr.isBlank()) return 0
        val uri = try {
            uriStr.toUri()
        } catch (_: Exception) {
            return 0
        }
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file") retriever.setDataSource(uri.path)
            else retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt() ?: 0
        } catch (_: Exception) {
            0
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun convertDatetime(time: String): String {
        return try {
            val formatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
            formatter.format(java.util.Date(time.toLong()))
        } catch (_: Exception) {
            ""
        }
    }

    private fun formatTimestamp(timeInMillis: Long): String {
        val formatter = SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)
        return formatter.format(java.util.Date(timeInMillis))
    }

    // ── ADD THIS HELPER FUNCTION ──────────────────────────────────────────────
    private fun encodeFileToBase64(uriStr: String): String {
        return try {
            val uri = Uri.parse(uriStr)
            val inputStream = if (uri.scheme == "content" || uri.scheme == "file") {
                requireContext().contentResolver.openInputStream(uri)
            } else {
                java.io.File(uriStr).inputStream()
            }
            val bytes = inputStream?.readBytes()
            inputStream?.close()
            if (bytes != null) {
                android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
            } else {
                uriStr
            }
        } catch (e: Exception) {
            Log.e("ChatFragment", "Failed to encode file to base64", e)
            uriStr // fallback to original string if it fails
        }
    }

    private fun decodeBase64ToFile(base64Data: String?, type: String): String? {
        if (base64Data.isNullOrBlank()) return null

        // If it's already a valid path (e.g. from local testing or HTTP), leave it alone
        if (base64Data.startsWith("content://") || base64Data.startsWith("file://") || base64Data.startsWith("http")) {
            return base64Data
        }

        return try {
            val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            val ext = when(type.uppercase(java.util.Locale.US)) {
                "AUDIO" -> "mp3"
                "VIDEO" -> "mp4"
                else -> "jpg"
            }
            // Create a local file on the receiver's device so external viewers can read it
            val file = java.io.File(requireContext().cacheDir, "kyber_media_${System.currentTimeMillis()}.$ext")
            file.writeBytes(bytes)

            // Return the receiver's local valid file path
            "file://${file.absolutePath}"
        } catch (e: Exception) {
            base64Data // Fallback
        }
    }


    data class SelectedMedia(val uri: Uri, var caption: String?, val type: String)
}