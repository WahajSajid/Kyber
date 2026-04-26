package app.secure.kyber.fragments

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
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
import app.secure.kyber.Utils.MessageEncryptionManager
import app.secure.kyber.media.MediaSender
import app.secure.kyber.roomdb.roomViewModel.MSG_PAGE_SIZE
import com.google.android.material.textfield.TextInputEditText
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import app.secure.kyber.roomdb.ContactRepository
import app.secure.kyber.workers.TextUploadWorker

@Suppress("UNCHECKED_CAST")
@AndroidEntryPoint
class ChatFragment : Fragment(R.layout.fragment_chat) {
    private companion object {
        private const val WIPE_REQ = "WIPE_REQUEST"
        private const val WIPE_RES = "WIPE_RESPONSE"
        private const val WIPE_ACTION_ACCEPTED = "ACCEPTED"
        private const val WIPE_ACTION_REJECTED = "REJECTED"
    }

    @Inject
    lateinit var repository: KyberRepository

    @Inject
    lateinit var unionClient: UnionClient

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
    private lateinit var mediaSender: MediaSender
    private val recordingTimerHandler = Handler(Looper.getMainLooper())
    private var recordingSeconds = 0
    private var pulseAnimation: AlphaAnimation? = null

    // Mic touch tracking
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

    // Set to true right after a chat wipe so the next Flow emission fully replaces
    // the in-memory displayedList instead of merging with stale cached items.
    private var wipePending = false

    private var selectedMsg: MessageUiModel? = null
    private var selectedGroupMsg: GroupMessageEntity? = null
    private var currentReplyText: String = ""
    private var currentReplyRefRaw: String = ""

    private var recentEmojisList = mutableListOf("👌", "😊", "😂", "😍", "💜", "🎮")
    private var displayedList = mutableListOf<MessageUiModel>()

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val transportAdapter = moshi.adapter(PrivateMessageTransportDto::class.java)

    private data class WipeMeta(
        val action: String,
        val requesterOnion: String?,
        val responderOnion: String?,
        val requestId: String?
    )

    private fun buildWipeRequestPayload(requesterOnion: String, requestId: String): String {
        return JSONObject()
            .put("action", WIPE_REQ)
            .put("requesterOnion", requesterOnion)
            .put("requestId", requestId)
            .toString()
    }

    private fun buildWipeResponsePayload(
        action: String,
        requesterOnion: String,
        responderOnion: String,
        requestId: String
    ): String {
        return JSONObject()
            .put("action", action)
            .put("requesterOnion", requesterOnion)
            .put("responderOnion", responderOnion)
            .put("requestId", requestId)
            .toString()
    }

    private fun parseWipeMeta(raw: String): WipeMeta {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            runCatching {
                val obj = JSONObject(trimmed)
                return WipeMeta(
                    action = obj.optString("action", "").ifBlank { WIPE_REQ },
                    requesterOnion = obj.optString("requesterOnion", null),
                    responderOnion = obj.optString("responderOnion", null),
                    requestId = obj.optString("requestId", null)
                )
            }
        }
        return WipeMeta(
            action = trimmed.uppercase(Locale.US),
            requesterOnion = null,
            responderOnion = null,
            requestId = null
        )
    }

    private suspend fun wipeGroupMessagesForUserPair(groupId: String, userA: String, userB: String, exceptId: String? = null, cutoffTime: String) {
        val dao = AppDb.get(requireContext()).groupsMessagesDao()
        if (exceptId != null) {
            dao.expireByGroupIdAndSenderPairExcept(groupId, userA, userB, exceptId, cutoffTime)
        } else {
            dao.expireByGroupIdAndSenderPair(groupId, userA, userB, cutoffTime)
        }
    }


    private val screenStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                android.content.Intent.ACTION_SCREEN_OFF -> {
                    // Pause recording if currently active and not already paused
                    if (isRecordingStarted && audioRecordingManager.isCurrentlyRecording() && !isRecordingPaused) {
                        isRecordingPaused = true
                        audioRecordingManager.pauseRecording() // Pause the hardware stream
                        recordingTimerHandler.removeCallbacks(recordingTimerRunnable)
                        waveformHandler.removeCallbacks(waveformUpdateRunnable)
                        stopMicPulse()
                        if (isRecordingLocked) {
                            binding.ivRecordPause.setImageResource(R.drawable.pause_icon_1)
                        }
                    }
                }

                android.content.Intent.ACTION_SCREEN_ON -> {
                    // Do NOT auto-resume — user must manually tap ivRecordPause to resume.
                    // Nothing to do here. The recording remains paused and ivRecordPause
                    // is already showing pause_icon_1 (play icon), ready for the user to tap.
                }
            }
        }
    }


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

    private var isRequestMode: Boolean = false

    private val vm: MessagesViewModel by viewModels {
        val db = AppDb.get(requireContext())
        val repo = MessageRepository(db.messageDao())
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MessagesViewModel(repo, contactOnion) as T
        }
    }

    private val contactsVm: app.secure.kyber.roomdb.roomViewModel.ContactsViewModel by viewModels {
        val db = AppDb.get(requireContext())
        val repo = app.secure.kyber.roomdb.ContactRepository(db.contactDao())
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                app.secure.kyber.roomdb.roomViewModel.ContactsViewModel(repo) as T
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
            isRecordingStarted = true; startAudioRecording()
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
                    MediaPreviewActivity.EXTRA_RESULT_ITEMS, SelectedMediaParcelable::class.java
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
                    val caption =
                        if (item.caption.isBlank()) (if (item.type == "VIDEO") "video" else "photo") else item.caption
                    if (comingFrom == "group_chat_list") {
                        lifecycleScope.launch {
                            val ext = if (item.type == "VIDEO") "mp4" else "jpg"

                            // 1. Process media entry immediately in Room (placeholder)
                            // We pass firebasePayload = null here, GroupManager.sendMessage 
                            // will now trigger GroupMediaUploadWorker instead of blocking.
                            groupManager.sendMessage(
                                groupId = groupId, senderId = onionAddr,
                                senderName = name, messageText = caption,
                                groupMessagesViewModel = vm1, type = item.type,
                                uri = item.uriString,         // Local URI
                                firebasePayload = null,       // Chunked upload starts in worker
                                replyToText = currentReplyRefRaw,
                                context = requireContext()
                            )
                            clearReply()
                        }
                    } else {
                        lifecycleScope.launch {
                            val db = AppDb.get(requireContext())
                            val contactRepo =
                                app.secure.kyber.roomdb.ContactRepository(db.contactDao())
                            val isContact = contactRepo.getContact(contactOnion) != null

                            // Removed hardcoded size limits to allow universal compression & chunking
                            mediaSender.sendMedia(
                                contactOnion = contactOnion,
                                senderOnion = onionAddr,
                                senderName = name,
                                filePath = item.uriString,
                                mimeType = item.type,
                                caption = caption,
                                isContact = isContact,
                                replyToText = currentReplyRefRaw
                            )
                            clearReply()
                        }
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentChatBinding.inflate(inflater, container, false)
        groupManager = GroupManager()
        audioRecordingManager = AudioRecordingManager(requireContext())

        mediaSender = MediaSender(
            context = requireContext(),
            messageDao = AppDb.get(requireContext()).messageDao()
        )


        selectedMediaAdapter = SelectedMediaAdapter(
            selectedMedias,
            onRemove = { pos ->
                if (pos in selectedMedias.indices) {
                    selectedMedias.removeAt(pos); selectedMediaAdapter.notifyItemRemoved(pos); updatePreviewVisibility()
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

        isRequestMode = (comingFrom == "message_request")

        messageEdit = binding.etMsg
        emojiPickerContainer = binding.emojiPickerContainer
        recentEmojiProvider = CustomRecentEmojiProvider(requireContext())
        loadRecentEmojis()

        if (comingFrom == "group_chat_list") {
            vm2.getCreationDate(onResult = { result ->
                groupCreationDate = formatTimestamp(result)
                if (::noOfMembers.isInitialized)
                    (activity as? MainActivity)?.onGroupChatDetailsClick(
                        groupId,
                        groupName,
                        groupCreationDate,
                        noOfMembers
                    )
            }, groupId)
            vm2.getNoOfMembers(onResult = { result ->
                noOfMembers = result.toString()
                if (::groupCreationDate.isInitialized)
                    (activity as? MainActivity)?.onGroupChatDetailsClick(
                        groupId,
                        groupName,
                        groupCreationDate,
                        noOfMembers
                    )
            }, groupId)
        }

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
                        adapterMsg.showReactionImmediately(
                            it.id.toString(),
                            emoji.emoji
                        )
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

            binding.bottomSheet.setBackgroundResource(R.drawable.bottom_nav_bar_bg_png)

            binding.contentMenu.isVisible = false
            selectedMsg = null; selectedGroupMsg = null
            if (isEmojiPickerVisible) hideEmojiPicker()
            else {
                hideKeyboard(messageEdit); messageEdit.postDelayed({ showEmojiPicker() }, 150)
            }
        }
        messageEdit.setOnClickListener {
            binding.contentMenu.isVisible = false
            binding.bottomSheet.setBackgroundResource(R.drawable.bottom_nav_bar_bg_png)
            if (isEmojiPickerVisible) {
                hideEmojiPicker(); showKeyboard(messageEdit)
            }

        }
        messageEdit.setOnFocusChangeListener { _, hasFocus ->

            binding.contentMenu.isVisible = false
            binding.bottomSheet.setBackgroundResource(R.drawable.bottom_nav_bar_bg_png)
            if (hasFocus && isEmojiPickerVisible) {
                hideEmojiPicker()
            }
        }

        binding.tilMsg.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect(); binding.tilMsg.getWindowVisibleDisplayFrame(r)
            val screenH = binding.tilMsg.rootView.height
            val kbVisible = (screenH - r.bottom) > screenH * 0.15
            when {
                kbVisible -> {
                    if (isEmojiPickerVisible) hideEmojiPicker()
                    binding.ivSend.isVisible = true; binding.ivCamera.isVisible =
                        false; binding.ivMic.isVisible = false
                }

                isEmojiPickerVisible -> {
                    binding.ivSend.isVisible = true; binding.ivCamera.isVisible =
                        false; binding.ivMic.isVisible = false
                }

                else -> {
                    binding.ivSend.isVisible = false; binding.ivCamera.isVisible =
                        true; binding.ivMic.isVisible = true
                }
            }
        }

        binding.root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (::adapterMsg.isInitialized) adapterMsg.closeMenu()
                if (::groupMessageAdapter.isInitialized) groupMessageAdapter.closeMenu()
            }
            false
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        isRecordingLocked -> cancelAudioRecording()
                        isEmojiPickerVisible -> hideEmojiPicker()
                        else -> {
                            isEnabled =
                                false; requireActivity().onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            })

        binding.ivCamera.setOnClickListener {
            binding.contentMenu.isVisible = false
            binding.bottomSheet.setBackgroundResource(R.drawable.bottom_nav_bar_bg_png)
            previewLauncher.launch(
                Intent(
                    requireContext(),
                    CameraActivity::class.java
                )
            )
        }

        binding.ivMic.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    binding.contentMenu.isVisible = false
                    binding.bottomSheet.setBackgroundResource(R.drawable.bottom_nav_bar_bg_png)

                    // Scale-up animation feedback on tap
                    binding.ivMic.animate()
                        .scaleX(2.0f).scaleY(2.0f)
                        .setDuration(120)
                        .start()

                    micDownRawY = event.rawY; micDownRawX = event.rawX
                    isRecordingLocked = false; isRecordingPaused = false; isRecordingStarted = false
                    longPressHandler.postDelayed(longPressRunnable, 400); true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isRecordingStarted || isRecordingLocked) return@setOnTouchListener true
                    val deltaY = micDownRawY - event.rawY;
                    val deltaX = micDownRawX - event.rawX
                    if (deltaX > cancelThreshold) {
                        cancelAudioRecording(); return@setOnTouchListener true
                    }
                    if (deltaY > lockThreshold) {
                        lockRecording(); return@setOnTouchListener true
                    }
                    if (deltaY > 20f && lockPopupVisible) {
                        val p = (deltaY / lockThreshold).coerceIn(0f, 1f)
                        binding.ivLockIcon.scaleX = 1f + 0.4f * p; binding.ivLockIcon.scaleY =
                            1f + 0.4f * p
                        binding.tvLockChevrons.alpha = 0.3f + 0.7f * p
                        binding.tvLockChevrons.translationY = -20f * p
                        binding.lockPopup.translationY = -deltaY * 0.25f
                    }
                    if (deltaX > 20f) binding.tvSlideToCancel.alpha =
                        1f - (deltaX / cancelThreshold).coerceIn(0f, 1f)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {

                    // Animate back to original size regardless of recording state
                    binding.ivMic.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(120)
                        .start()

                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (!isRecordingStarted || isRecordingLocked) return@setOnTouchListener true
                    if (audioRecordingManager.isCurrentlyRecording()) stopAndSendAudioRecording(
                        onionAddr,
                        name
                    )
                    true
                }

                else -> false
            }
        }

        binding.ivRecordDelete.setOnClickListener { cancelAudioRecording() }
        binding.ivRecordPause.setOnClickListener {
            if (!isRecordingLocked) return@setOnClickListener
            isRecordingPaused = !isRecordingPaused
            if (isRecordingPaused) {
                audioRecordingManager.pauseRecording()
                recordingTimerHandler.removeCallbacks(recordingTimerRunnable)
                waveformHandler.removeCallbacks(waveformUpdateRunnable)
                binding.ivRecordPause.setImageResource(R.drawable.pause_icon_1); stopMicPulse()
            } else {
                audioRecordingManager.resumeRecording()
                recordingTimerHandler.post(recordingTimerRunnable); waveformHandler.post(
                    waveformUpdateRunnable
                )
                binding.ivRecordPause.setImageResource(R.drawable.pause_icon_0); startMicPulse()
            }
        }
        binding.ivRecordSend.setOnClickListener {
            if (isRecordingLocked) stopAndSendAudioRecording(
                onionAddr,
                name
            )
        }

        binding.ivAdd.setOnClickListener {
            if (binding.contentMenu.isVisible) {
                binding.contentMenu.isVisible =
                    false; binding.bottomSheet.setBackgroundResource(R.drawable.bottom_nav_bar_bg_png)
            } else {
                if (isEmojiPickerVisible) hideEmojiPicker()
                hideKeyboard(messageEdit)
                binding.bottomSheet.setBackgroundResource(R.drawable.bottom_sheet); binding.contentMenu.isVisible =
                    true
            }
        }
        
        binding.llWipeRequest.setOnClickListener {
            binding.contentMenu.isVisible = false
            binding.bottomSheet.setBackgroundResource(R.drawable.bottom_nav_bar_bg_png)
            val requestId = UUID.randomUUID().toString()
            val text = buildWipeRequestPayload(onionAddr, requestId)
            if (comingFrom == "group_chat_list") {
                lifecycleScope.launch {
                    groupManager.sendMessage(
                        groupId,
                        onionAddr,
                        name,
                        text,
                        vm1,
                        replyToText = "",
                        context = requireContext(),
                        type = WIPE_REQ
                    )
                }
            } else {
                sendTextMessage(text, onionAddr, name, WIPE_REQ)
            }
        }
        binding.ivGallery.setOnClickListener {
            openPicker(); binding.contentMenu.isVisible = false
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
                                groupManager.sendMessage(
                                    groupId,
                                    onionAddr,
                                    name,
                                    text,
                                    vm1,
                                    replyToText = currentReplyRefRaw,
                                    context = requireContext()
                                ); binding.etMsg.setText(""); clearReply()
                            }
                        } else {
                            sendTextMessage(text, onionAddr, name)
                            clearReply()
                        }
                    }
                }
                true
            } else false
        }

        navController = view.findNavController()

        if (isRequestMode) {
            binding.bottomItemsLayout.visibility = View.GONE
            binding.requestActionBar.visibility = View.VISIBLE
            (requireActivity() as MainActivity).setAppChatUser(resolveDisplayName())
            binding.btnAcceptRequest.setOnClickListener { acceptRequest() }
            binding.btnRejectRequest.setOnClickListener { rejectRequest() }
        }

        when (comingFrom) {
            "chat_list" -> {
                (requireActivity() as MainActivity).setAppChatUser(
                    contactName.takeIf { it.isNotBlank() }
                        ?.let { if (it.length > 15) it.substring(0, 14) else it }
                        ?: "Unknown User")
                binding.ivSend.setOnClickListener {
                    val text = binding.etMsg.text.toString().trim()
                    if (text.isNotEmpty()) {
                        sendTextMessage(text, onionAddr, name); clearReply()
                    }
                }
                (requireActivity() as MainActivity).onChatDetailsClick(contactOnion, contactName)
                setListMessageAdapter()
            }

            "group_chat_list" -> {
                // Fetch group metadata to get isAnonymous + creatorId before attaching listener
                lifecycleScope.launch {
                    // Sync group metadata from Firebase (updates anonymousAliases in local DB)
                    groupManager.syncGroupMetadataFromFirebase(groupId, vm2)

                    val groupEntity = vm2.getGroupById(groupId)
                    val isAnonymous = groupEntity?.isAnonymous ?: false
                    val creatorId = groupEntity?.createdBy ?: ""
                    groupManager.listenForMessages(
                        groupId = groupId,
                        mySenderId = onionAddr,
                        groupMessageViewModel = vm1,
                        groupsViewModel = vm2,
                        isGroupAnonymous = isAnonymous,
                        groupCreatorId = creatorId,
                        context = requireContext()   // needed to decode incoming Base64 to local file
                    )

                    // Initialize adapter AFTER metadata is synced (ensures anonymousAliases are available)
                    setupGroupMessageAdapter(
                        onionAddr,
                        isAnonymous,
                        creatorId,
                        groupEntity?.anonymousAliases ?: "{}"
                    )
                }
                (requireActivity() as MainActivity).setAppChatUser(groupName)
                binding.ivSend.setOnClickListener {
                    val text = binding.etMsg.text.toString().trim()
                    if (text.isNotEmpty()) lifecycleScope.launch {
                        groupManager.sendMessage(
                            groupId,
                            onionAddr,
                            name,
                            text,
                            vm1,
                            replyToText = currentReplyRefRaw,
                            context = requireContext()
                        ); binding.etMsg.setText(""); clearReply()
                    }
                }
                (requireActivity().application as? app.secure.kyber.MyApp.MyApp)?.activeGroupId =
                    groupId
                vm2.resetUnread(groupId)
            }

            "message_request" -> {
                (requireActivity() as MainActivity).setAppChatUser(resolveDisplayName())
                setListMessageAdapter()
            }
        }

        binding.rvPreview.apply {
            layoutManager =
                LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = selectedMediaAdapter
        }
        updatePreviewVisibility()

        binding.ivCancelReply.setOnClickListener {
            clearReply()
        }
        binding.replyPreviewBar.setOnClickListener {
            jumpToReplyTarget(currentReplyRefRaw)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                contactsVm.observeContact(contactOnion).collect { contact ->
                    if (contact != null && !isRequestMode) {
                        val newDisplayName = if (contact.name.length > 15) contact.name.substring(
                            0,
                            14
                        ) else contact.name
                        (requireActivity() as MainActivity).setAppChatUser(newDisplayName)
                    }
                }
            }
        }
    }

    private fun resolveDisplayName(): String =
        contactName.takeIf { it.isNotBlank() && it != contactOnion } ?: "Unknown User"

    private fun sendTextMessage(text: String, onionAddr: String, name: String, msgType: String = "TEXT") {
        val messageId = UUID.randomUUID().toString()
        val sentTimeMs = System.currentTimeMillis()
        val timestamp = sentTimeMs.toString()
        // ⚠️ Capture BEFORE the coroutine launches — clearReply() is called synchronously right
        // after sendTextMessage() returns, which would race with the coroutine body and clear
        // currentReplyText before the DB insert reads it.
        val capturedReplyText = currentReplyRefRaw
        lifecycleScope.launch {
            val db = AppDb.get(requireContext())
            val contactRepo = ContactRepository(db.contactDao())
            val isContact = contactRepo.getContact(contactOnion) != null
            val isRequest = !isContact
            val disappearTimerMs = if (comingFrom == "group_chat_list") {
                Prefs.getEffectiveDisappearingTimerMs(requireContext(), groupId)
            } else {
                Prefs.getEffectiveDisappearingTimerMs(requireContext(), contactOnion)
            }
            var localExpiresAt = 0L
            if (disappearTimerMs > 0L) {
                localExpiresAt = sentTimeMs + disappearTimerMs
            }

            db.messageDao().insert(
                MessageEntity(
                    messageId = messageId,
                    msg = MessageEncryptionManager.encryptLocal(
                        requireContext(),
                        text
                    ).encryptedBlob,
                    senderOnion = contactOnion,
                    time = timestamp,
                    isSent = true,
                    type = msgType,
                    uploadState = "pending",
                    uploadProgress = 0,
                    expiresAt = localExpiresAt,
                    replyToText = capturedReplyText
                )
            )

            val request = TextUploadWorker.buildRequest(
                messageId = messageId,
                text = text,
                contactOnion = contactOnion,
                senderOnion = onionAddr,
                senderName = name,
                timestamp = timestamp,
                isRequest = isRequest,
                disappearTtl = disappearTimerMs
            )
            WorkManager.getInstance(requireContext())
                .enqueueUniqueWork(
                    "text_upload_$messageId",
                    ExistingWorkPolicy.REPLACE,
                    request
                )

            binding.etMsg.setText("")
        }
    }

    private fun sendPrivateMessage(transport: PrivateMessageTransportDto) {
        lifecycleScope.launch {
            try {
                val jsonPayload = transportAdapter.toJson(transport)
                val base64Payload =
                    Base64.encodeToString(jsonPayload.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                var circuitId = Prefs.getCircuitId(requireContext()) ?: ""
                try {
                    if (circuitId.isEmpty()) {
                        val r = repository.createCircuit()
                        if (r.isSuccessful) {
                            circuitId = r.body()?.circuitId ?: ""; Prefs.setCircuitId(
                                requireContext(),
                                circuitId
                            )
                        }
                    }
                    if (circuitId.isNotEmpty()) {
                        var response =
                            repository.sendMessage(contactOnion, base64Payload, circuitId)
                        if (!response.isSuccessful && (response.code() == 404 || response.code() == 400)) {
                            val retry = repository.createCircuit()
                            if (retry.isSuccessful) {
                                circuitId = retry.body()?.circuitId ?: ""; Prefs.setCircuitId(
                                    requireContext(),
                                    circuitId
                                )
                                response =
                                    repository.sendMessage(contactOnion, base64Payload, circuitId)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ChatFragment", "API send failed: ${e.message}")
                }
                unionClient.sendMessage(contactOnion, base64Payload)
            } catch (e: Exception) {
                Log.e("ChatFragment", "Final delivery failure: ${e.message}")
            }
        }
    }

    private fun handleReaction(emoji: String) {
        lifecycleScope.launch {
            if (comingFrom == "group_chat_list") {
                selectedGroupMsg?.let { msg ->
                    msg.reaction = emoji; vm1.updateMessage(msg); groupManager.updateReaction(
                    groupId,
                    msg.messageId,
                    emoji
                )
                }
            } else {
                selectedMsg?.let { msg ->
                    val myOnion = Prefs.getOnionAddress(requireContext()) ?: ""
                    val formattedReaction = if (emoji.isEmpty()) "" else "$myOnion|$emoji"
                    msg.reaction = formattedReaction
                    msg.updatedAt =
                        if (emoji.isEmpty()) "" else System.currentTimeMillis().toString()
                    vm.updateMessage(msg.entity)
                    sendPrivateMessage(
                        PrivateMessageTransportDto(
                            messageId = msg.messageId,
                            msg = msg.decryptedMsg,
                            senderOnion = myOnion,
                            senderName = Prefs.getName(requireContext()) ?: "",
                            timestamp = msg.time,
                            type = msg.type,
                            uri = msg.decryptedUri,
                            ampsJson = msg.ampsJson,
                            reaction = formattedReaction,
                            senderPublicKey = Prefs.getPublicKey(requireContext())
                        )
                    )
                }
            }
            selectedMsg = null; selectedGroupMsg = null
        }
    }

    private fun handleReply(text: String) {
        currentReplyText = extractReplyPreviewText(text)
        currentReplyRefRaw = text
        binding.replyPreviewBar.isVisible = true
        binding.tvReplyText.text = currentReplyText.replace("\n", " ")
        messageEdit.requestFocus()
        showKeyboard(messageEdit)
    }

    private fun clearReply() {
        currentReplyText = ""
        currentReplyRefRaw = ""
        binding.replyPreviewBar.isVisible = false
        binding.tvReplyText.text = ""
    }

    private fun buildReplyPreviewForPrivate(msg: MessageUiModel): String {
        val preview = when (msg.type.uppercase(Locale.getDefault())) {
            "IMAGE" -> "Photo"
            "VIDEO" -> "Video"
            "AUDIO" -> "Voice message"
            // Never expose raw wipe JSON in the reply preview bar or quote blocks
            "WIPE_REQUEST"  -> "Wipe Chat Request"
            "WIPE_RESPONSE" -> "Wipe Response"
            "WIPE_SYSTEM"   -> "System Message"
            else -> msg.decryptedMsg.ifBlank { "Message" }
        }
        return encodeReplyRef(msg.messageId, preview)
    }

    private fun buildReplyPreviewForGroup(msg: GroupMessageEntity): String {
        val preview = when (msg.type.uppercase(Locale.getDefault())) {
            "IMAGE" -> "Photo"
            "VIDEO" -> "Video"
            "AUDIO" -> "Voice message"
            // Never expose raw wipe JSON in the reply preview bar or quote blocks
            "WIPE_REQUEST"  -> "Wipe Chat Request"
            "WIPE_RESPONSE" -> "Wipe Response"
            "WIPE_SYSTEM"   -> "System Message"
            else -> msg.msg.ifBlank { "Message" }
        }
        return encodeReplyRef(msg.messageId, preview)
    }

    private fun encodeReplyRef(messageId: String, preview: String): String {
        return "__reply__${messageId}::${preview}"
    }

    private fun extractReplyMessageId(raw: String): String? {
        if (!raw.startsWith("__reply__")) return null
        val content = raw.removePrefix("__reply__")
        val sep = content.indexOf("::")
        if (sep <= 0) return null
        return content.substring(0, sep)
    }

    private fun extractReplyPreviewText(raw: String): String {
        if (!raw.startsWith("__reply__")) return raw
        val content = raw.removePrefix("__reply__")
        val sep = content.indexOf("::")
        if (sep < 0 || sep + 2 > content.length) return raw
        return content.substring(sep + 2)
    }

    private fun findPrivateReplyPosition(replyRaw: String): Int {
        val targetId = extractReplyMessageId(replyRaw)
        if (!targetId.isNullOrBlank()) {
            val byId = adapterMsg.currentList.indexOfFirst { it.messageId == targetId }
            if (byId != -1) return byId
        }
        val preview = extractReplyPreviewText(replyRaw)
        return adapterMsg.currentList.indexOfFirst {
            val candidate = when (it.type.uppercase(Locale.getDefault())) {
                "IMAGE"         -> "Photo"
                "VIDEO"         -> "Video"
                "AUDIO"         -> "Voice message"
                "WIPE_REQUEST"  -> "Wipe Chat Request"
                "WIPE_RESPONSE" -> "Wipe Response"
                "WIPE_SYSTEM"   -> "System Message"
                else -> it.decryptedMsg
            }
            candidate == preview
        }
    }

    private fun findGroupReplyPosition(replyRaw: String): Int {
        val targetId = extractReplyMessageId(replyRaw)
        if (!targetId.isNullOrBlank()) {
            val byId = groupMessageAdapter.currentList.indexOfFirst { it.messageId == targetId }
            if (byId != -1) return byId
        }
        val preview = extractReplyPreviewText(replyRaw)
        return groupMessageAdapter.currentList.indexOfFirst {
            val candidate = when (it.type.uppercase(Locale.getDefault())) {
                "IMAGE"         -> "Photo"
                "VIDEO"         -> "Video"
                "AUDIO"         -> "Voice message"
                "WIPE_REQUEST"  -> "Wipe Chat Request"
                "WIPE_RESPONSE" -> "Wipe Response"
                "WIPE_SYSTEM"   -> "System Message"
                else -> it.msg
            }
            candidate == preview
        }
    }

    private fun jumpToReplyTarget(replyRaw: String) {
        if (replyRaw.isBlank()) return
        if (comingFrom == "group_chat_list") {
            if (!::groupMessageAdapter.isInitialized) return
            val idx = findGroupReplyPosition(replyRaw)
            if (idx != -1) scrollToAndHighlight(
                recyclerview,
                idx
            ) { groupMessageAdapter.highlightItem(idx) }
        } else {
            if (!::adapterMsg.isInitialized) return
            val idx = findPrivateReplyPosition(replyRaw)
            if (idx != -1) scrollToAndHighlight(recyclerview, idx) { adapterMsg.highlightItem(idx) }
        }
    }

    private fun handleForward() {
        Toast.makeText(requireContext(), "Forward feature - select contacts", Toast.LENGTH_SHORT)
            .show()
    }

    private fun handleInfo(msg: Any) {
        val time =
            if (msg is MessageUiModel) convertDatetime(msg.time) else convertDatetime((msg as GroupMessageEntity).time)
        val senderInfo =
            if (msg is GroupMessageEntity) "Sender: ${msg.senderName}\nOnion: ${msg.senderOnion}\n" else ""
        Toast.makeText(
            requireContext(),
            "${senderInfo}Time: $time\nStatus: Delivered",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun handleDelete(msg: Any) {
        lifecycleScope.launch {
            if (msg is GroupMessageEntity) {
                vm1.deleteMessage(msg); groupManager.deleteMessage(
                    groupId,
                    msg.messageId
                ); updateLastMessageForGroup(groupId)
            } else if (msg is MessageUiModel) {
                vm.deleteMessage(msg.entity)
                // Manual removal for immediate feedback
                displayedList.removeAll { it.id == msg.id }
                if (::adapterMsg.isInitialized) adapterMsg.submitList(displayedList.toList())
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

    private suspend fun insertSystemMessage(text: String) {
        val db = AppDb.get(requireContext())
        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis().toString()
        if (comingFrom == "group_chat_list") {
             val sysMsg = GroupMessageEntity(
                 messageId = messageId,
                 group_id = groupId,
                 msg = text,
                 senderOnion = "system",
                 senderName = "System",
                 time = timestamp,
                 type = "WIPE_SYSTEM",
                 isSent = false
             )
             db.groupsMessagesDao().insertGroupMessage(sysMsg)
        } else {
             val sysMsg = app.secure.kyber.roomdb.MessageEntity(
                 messageId = messageId,
                 msg = MessageEncryptionManager.encryptLocal(requireContext(), text).encryptedBlob,
                 senderOnion = contactOnion,
                 time = timestamp,
                 isSent = false,
                 type = "WIPE_SYSTEM",
                 uploadState = "done",
                 uploadProgress = 100
             )
             db.messageDao().insert(sysMsg)
        }
    }

    private fun handleWipeRequestAction(messageId: String, wipeRequesterOnion: String, isAccepted: Boolean) {
        if (!isAccepted) {
            val myOnion = Prefs.getOnionAddress(requireContext()) ?: ""
            val payload = buildWipeResponsePayload(
                action = WIPE_ACTION_REJECTED,
                requesterOnion = wipeRequesterOnion,
                responderOnion = myOnion,
                requestId = messageId
            )
            if (comingFrom == "group_chat_list") {
                lifecycleScope.launch {
                    groupManager.sendMessage(
                        groupId,
                        myOnion,
                        Prefs.getName(requireContext()) ?: "",
                        payload,
                        vm1,
                        replyToText = "",
                        context = requireContext(),
                        type = WIPE_RES
                    )
                }
            } else {
                sendPrivateMessage(
                    PrivateMessageTransportDto(
                        messageId = UUID.randomUUID().toString(),
                        msg = payload,
                        senderOnion = myOnion,
                        senderName = Prefs.getName(requireContext()) ?: "",
                        timestamp = System.currentTimeMillis().toString(),
                        type = WIPE_RES,
                        senderPublicKey = Prefs.getPublicKey(requireContext())
                    )
                )
            }
            // Update local message reaction so action buttons are hidden after response.
            lifecycleScope.launch {
                val db = AppDb.get(requireContext())
                if (comingFrom == "group_chat_list") {
                    db.groupsMessagesDao().updateReaction(messageId, WIPE_ACTION_REJECTED)
                } else {
                    val entity = db.messageDao().getMessageByMessageId(messageId)
                    if (entity != null) {
                        db.messageDao().update(entity.copy(reaction = WIPE_ACTION_REJECTED))
                    }
                }
            }
        } else {
            // Show confirmation dialog
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_wipe_request_confirm, null)
            val dialog = android.app.AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create()
            
            dialogView.findViewById<android.widget.TextView>(R.id.tvCancel).setOnClickListener {
                dialog.dismiss()
            }
            
            dialogView.findViewById<android.widget.TextView>(R.id.tvAccept).setOnClickListener {
                dialog.dismiss()
                performWipeChat(messageId, wipeRequesterOnion)
            }
            dialog.show()
        }
    }

    private fun performWipeChat(requestId: String, wipeRequesterOnion: String) {
        lifecycleScope.launch {
            val myOnion = Prefs.getOnionAddress(requireContext()) ?: ""
            val myName = Prefs.getName(requireContext()) ?: ""
            val payload = buildWipeResponsePayload(
                action = WIPE_ACTION_ACCEPTED,
                requesterOnion = wipeRequesterOnion,
                responderOnion = myOnion,
                requestId = requestId
            )
            if (comingFrom == "group_chat_list") {
                groupManager.sendMessage(
                    groupId,
                    myOnion,
                    myName,
                    payload,
                    vm1,
                    replyToText = "",
                    context = requireContext(),
                    type = WIPE_RES
                )
                // For groups: only wipe requester<->responder messages for this accepter.
                wipePending = true
                val now = System.currentTimeMillis().toString()
                wipeGroupMessagesForUserPair(groupId, wipeRequesterOnion, myOnion, requestId, now)
            } else {
                // Send the acceptance signal over the network ONLY — do NOT insert it into
                // the local DB via sendTextMessage, which would create a spurious
                // "Chat cleared" WIPE_RES bubble alongside the WIPE_SYSTEM bubble.
                sendPrivateMessage(
                    PrivateMessageTransportDto(
                        messageId = UUID.randomUUID().toString(),
                        msg = payload,
                        senderOnion = myOnion,
                        senderName = myName,
                        timestamp = System.currentTimeMillis().toString(),
                        type = WIPE_RES,
                        senderPublicKey = Prefs.getPublicKey(requireContext())
                    )
                )
                // Mark wipe pending so the next Flow emission fully replaces
                // the in-memory displayedList instead of merging with stale items.
                wipePending = true
                val db = AppDb.get(requireContext())
                val now = System.currentTimeMillis().toString()
                db.messageDao().expireAllBySender(contactOnion, now)
                insertSystemMessage("Chat history cleared on ${formatWipeTimestamp(System.currentTimeMillis())}")
            }
            
            // Update local message reaction so action buttons are hidden after response.
            val db = AppDb.get(requireContext())
            if (comingFrom == "group_chat_list") {
                db.groupsMessagesDao().updateReaction(requestId, WIPE_ACTION_ACCEPTED)
            } else {
                val entity = db.messageDao().getMessageByMessageId(requestId)
                if (entity != null) {
                    db.messageDao().update(entity.copy(reaction = WIPE_ACTION_ACCEPTED))
                }
            }

            Toast.makeText(requireContext(), "Chat history wiped", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatWipeTimestamp(ts: Long): String {
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        val sameDay = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
                now.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
        return if (sameDay) {
            java.text.SimpleDateFormat("hh:mm a", Locale.getDefault()).format(java.util.Date(ts))
        } else {
            java.text.SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(java.util.Date(ts))
        }
    }

    private fun setListMessageAdapter() {
        recyclerview = binding.rvMsg
        val sharedPrefs = requireContext().getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
        val lastSeenIdKey = "last_seen_id_${contactOnion}"
        val lastSeenId = sharedPrefs.getLong(lastSeenIdKey, 0L)
        val myRealOnion = Prefs.getOnionAddress(requireContext()) ?: ""

        adapterMsg = MessageAdapter(
            myId = myRealOnion, lastSeenMessageId = lastSeenId,
            onClick = { msg ->
                val type = msg.type.uppercase(Locale.getDefault())

                if (type == "IMAGE" || type == "VIDEO") {
                    when {
                        msg.downloadState == "downloading" || msg.downloadState == "pending" -> {
                            Toast.makeText(
                                requireContext(),
                                "Still downloading… ${msg.downloadProgress}%",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@MessageAdapter
                        }

                        msg.uploadState == "uploading" || msg.uploadState == "pending" -> {
                            Toast.makeText(
                                requireContext(),
                                "Still uploading… ${msg.uploadProgress}%",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@MessageAdapter
                        }

                        msg.downloadState == "failed" -> {
                            Toast.makeText(
                                requireContext(),
                                "Download failed. Tap retry.",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@MessageAdapter
                        }
                    }
                }

                if (type == "AUDIO") return@MessageAdapter

                val resolvedUri: String? = when {
                    !msg.localFilePath.isNullOrBlank() -> "file://${msg.localFilePath}"
                    !msg.decryptedUri.isNullOrBlank() -> {
                        val stripped = msg.decryptedUri!!
                            .removePrefix("[IMAGE] ")
                            .removePrefix("[VIDEO] ")
                        decodeBase64ToFile(stripped, type) ?: stripped
                    }

                    else -> null
                }

                if (resolvedUri.isNullOrBlank()) {
                    Toast.makeText(requireContext(), "Media not available", Toast.LENGTH_SHORT)
                        .show()
                    return@MessageAdapter
                }

                val fileExists = try {
                    if (resolvedUri.startsWith("file://")) {
                        java.io.File(java.net.URI(resolvedUri)).exists()
                    } else {
                        true
                    }
                } catch (e: Exception) {
                    false
                }

                if (!fileExists) {
                    Toast.makeText(requireContext(), "Media file not found", Toast.LENGTH_SHORT)
                        .show()
                    return@MessageAdapter
                }

                val finalUri = try {
                    if (resolvedUri.startsWith("file://")) {
                        val file = java.io.File(java.net.URI(resolvedUri))
                        androidx.core.content.FileProvider.getUriForFile(
                            requireContext(),
                            "${requireContext().packageName}.provider",
                            file
                        ).toString()
                    } else resolvedUri
                } catch (e: Exception) {
                    resolvedUri
                }

                startActivity(
                    Intent(requireContext(), FullscreenMediaActivity::class.java).apply {
                        putExtra("uri", finalUri)
                        putExtra("type", type)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
            },
            onLongClick = { view, msg ->
                val clipboard =
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                when (view.id) {
                    R.id.btnReplySent, R.id.btnReplyRcv -> handleReply(
                        buildReplyPreviewForPrivate(
                            msg
                        )
                    )

                    R.id.btnDeleteSent, R.id.btnDeleteRcv -> handleDelete(msg)
                    R.id.btnCopySent, R.id.btnCopyRcv -> clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            "msg",
                            msg.decryptedMsg
                        )
                    )

                    R.id.btnForwardSent, R.id.btnForwardRcv -> handleForward()
                    R.id.btnInfoSent, R.id.btnInfoRcv -> handleInfo(msg)
                }
            },
            onEmojiSelected = { msg, emoji ->
                selectedMsg = msg; adapterMsg.showReactionImmediately(msg.id.toString(), emoji)
                handleReaction(emoji); updateRecentEmojiList(emoji, msg.id.toString())
            },
            onMoreEmojisClicked = { msg ->
                selectedMsg = msg; selectedGroupMsg = null
                if (!isEmojiPickerVisible) {
                    hideKeyboard(messageEdit); messageEdit.postDelayed({ showEmojiPicker() }, 150)
                }
            },
            recentEmojis = recentEmojisList,
            onRetryUpload = { msg ->
                lifecycleScope.launch {
                    val myOnion = Prefs.getOnionAddress(requireContext()) ?: ""
                    val myName = Prefs.getName(requireContext()) ?: ""
                    val db = AppDb.get(requireContext())
                    val contactRepo = app.secure.kyber.roomdb.ContactRepository(db.contactDao())
                    val isContact = contactRepo.getContact(contactOnion) != null

                    val normalizedType = msg.type.uppercase(Locale.US)
                    if (normalizedType != "IMAGE" && normalizedType != "VIDEO" && normalizedType != "AUDIO") {
                        val request = TextUploadWorker.buildRequest(
                            messageId = msg.messageId,
                            text = msg.decryptedMsg,
                            contactOnion = contactOnion,
                            senderOnion = myOnion,
                            senderName = myName,
                            timestamp = msg.time,
                            isRequest = !isContact
                        )
                        WorkManager.getInstance(requireContext())
                            .enqueueUniqueWork(
                                "text_upload_${msg.messageId}",
                                ExistingWorkPolicy.REPLACE,
                                request
                            )
                    } else {
                        mediaSender.retryUpload(
                            messageId = msg.messageId,
                            contactOnion = contactOnion,
                            senderOnion = myOnion,
                            senderName = myName,
                            isContact = isContact
                        )
                    }
                }
            },
            onRetryDownload = { msg ->
                lifecycleScope.launch {
                    val db = AppDb.get(requireContext())
                    db.messageDao().updateDownloadProgress(msg.messageId, "pending", 0)
                    val entity = db.messageDao().getByMessageId(msg.messageId) ?: return@launch
                    val mediaId = entity.remoteMediaId ?: return@launch
                    val assembled = app.secure.kyber.media.MediaChunkManager.assembleChunks(
                        requireContext(), mediaId, entity.type
                    )
                    if (assembled != null) {
                        val updated = entity.copy(
                            uri = MessageEncryptionManager.encryptLocal(
                                requireContext(),
                                assembled
                            ).encryptedBlob,
                            downloadState = "done",
                            downloadProgress = 100,
                            localFilePath = assembled.removePrefix("file://")
                        )
                        db.messageDao().update(updated)
                    } else {
                        db.messageDao().updateDownloadProgress(msg.messageId, "failed", 0)
                    }
                }
            },
            onReplyQuoteClicked = { replyText ->
                val idx = findPrivateReplyPosition(replyText)
                if (idx != -1) scrollToAndHighlight(recyclerview, idx) {
                    adapterMsg.highlightItem(
                        idx
                    )
                }
            },
            onWipeRequestAction = { item, isAccepted ->
                val reqMeta = parseWipeMeta(item.decryptedMsg)
                val requester = reqMeta.requesterOnion ?: contactOnion
                handleWipeRequestAction(item.messageId, requester, isAccepted)
            }
        )
        adapterMsg.messageDecryptor =
            { MessageEncryptionManager.decryptLocal(requireContext(), it) }

        // ── Pagination state ─────────────────────────────────────────────────
        // Tracks all messages currently displayed (recent + older batches prepended).
        var isLoadingOlder = false
        var hasMoreOlder = true  // assume there may be older until a batch returns empty

        val llm = object : LinearLayoutManager(requireContext()) {
            override fun supportsPredictiveItemAnimations() = false
        }.apply { stackFromEnd = false }

        recyclerview.apply {
            layoutManager = llm
            adapter = adapterMsg
            clipChildren = false
            clipToPadding = false
        }

        // ── Collect the recent window (live-updates on new messages) ─────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.recentMessagesFlow.collect { recent ->
                    // Decrypt on IO so Main thread is never blocked
                    val newModels =
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            recent.map { it.toUiModel(requireContext()) }
                        }

                    // After a wipe, discard the stale in-memory cache entirely so the
                    // adapter clears in real-time without requiring a nav back/forward.
                    // Also discard when a NEW WIPE_SYSTEM message arrives from the network
                    // (meaning the other user accepted the wipe).
                    val hasNewWipeSystem = newModels.any { newMsg ->
                        newMsg.type.uppercase() == "WIPE_SYSTEM" && displayedList.none { it.id == newMsg.id }
                    }
                    
                    val freshStart = wipePending || hasNewWipeSystem ||
                        (displayedList.isNotEmpty() && newModels.isEmpty() &&
                         displayedList.any { it.type.uppercase() !in setOf("WIPE_SYSTEM", "WIPE_REQUEST", "WIPE_RESPONSE") })

                    val merged = if (displayedList.isEmpty() || freshStart) {
                        wipePending = false
                        newModels.toMutableList()
                    } else {
                        // The recent window always covers the newest MSG_PAGE_SIZE messages.
                        val recentIds = newModels.map { it.id }.toSet()
                        val oldestRecentTime = newModels.firstOrNull()?.time?.toLongOrNull() ?: Long.MAX_VALUE
                        
                        if (newModels.size < MSG_PAGE_SIZE) {
                            // If the recent window is not full, it means the entire chat history fits in it.
                            // Fully replace to handle deletions accurately.
                            newModels.toMutableList()
                        } else {
                            // Keep older items that are NOT in the recent window.
                            // Use timestamp to ensure we don't keep items that SHOULD be in the recent window but aren't (meaning they were deleted).
                            val filteredOlder = displayedList.filter { 
                                it.id !in recentIds && (it.time.toLongOrNull() ?: 0L) < oldestRecentTime 
                            }
                            (filteredOlder + newModels).toMutableList()
                        }
                    }
                    displayedList = merged

                    adapterMsg.submitList(displayedList.toList()) {
                        // Only auto-scroll to bottom if user is near bottom (new message) or first load
                        val lastVisible = llm.findLastVisibleItemPosition()
                        val total = adapterMsg.itemCount
                        if (total > 0 && (lastVisible >= total - 3 || displayedList.size <= MSG_PAGE_SIZE)) {
                            recyclerview.scrollToPosition(total - 1)
                        }
                        // Update last-seen bookmark
                        val maxId = displayedList.maxOfOrNull { it.id } ?: 0L
                        if (maxId > sharedPrefs.getLong(lastSeenIdKey, 0L)) {
                            sharedPrefs.edit().putLong(lastSeenIdKey, maxId).apply()
                        }
                    }
                }
            }
        }

        // ── Scroll-up listener: load older messages ──────────────────────────
        recyclerview.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!hasMoreOlder || isLoadingOlder) return
                // Trigger when top 4 items are visible
                if (llm.findFirstVisibleItemPosition() <= 3 && displayedList.isNotEmpty()) {
                    isLoadingOlder = true
                    val oldestTime = displayedList.first().time.toLongOrNull() ?: return
                    viewLifecycleOwner.lifecycleScope.launch {
                        val olderEntities =
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                vm.loadOlderMessages(oldestTime)
                            }
                        if (olderEntities.isEmpty()) {
                            hasMoreOlder = false
                            isLoadingOlder = false
                            return@launch
                        }
                        val olderModels =
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                olderEntities.map { it.toUiModel(requireContext()) }
                            }
                        // Prepend older messages, preserving scroll position
                        val firstVisiblePos = llm.findFirstVisibleItemPosition()
                        val firstVisibleView = llm.findViewByPosition(firstVisiblePos)
                        val topOffset = firstVisibleView?.top ?: 0

                        displayedList = (olderModels + displayedList).toMutableList()
                        adapterMsg.submitList(displayedList.toList()) {
                            // Restore scroll so user doesn't jump to top
                            llm.scrollToPositionWithOffset(
                                firstVisiblePos + olderModels.size, topOffset
                            )
                            isLoadingOlder = false
                        }
                    }
                }
            }
        })

        // Attach swipe-to-reply for private messages
        attachSwipeToReply(
            rv = recyclerview,
            onReply = { position ->
                val item = adapterMsg.currentList.getOrNull(position)
                if (item != null) handleReply(buildReplyPreviewForPrivate(item))
            }
        )
    }

    private fun setupGroupMessageAdapter(
        onionAddr: String,
        isAnonymous: Boolean,
        groupCreatorId: String,
        anonymousAliasesJson: String
    ) {
        recyclerview = binding.rvMsg
        groupMessageAdapter = GroupMessagesAdapter(
            onClick = { msg ->
                val type = msg.type.uppercase(Locale.getDefault())
                if (type != "AUDIO") {
                    val raw = msg.uri ?: msg.msg
                    if (raw.isNotBlank()) {
                        // Resolve the URI: if it is a raw Base64 payload (not a file:// path),
                        // decode it to a local file first so FullscreenMediaActivity can render it.
                        lifecycleScope.launch {
                            val resolvedUri: String? = when {
                                raw.startsWith("file://") || raw.startsWith("/") -> raw
                                raw.startsWith("http") -> raw
                                else -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    val ext = when (type) {
                                        "VIDEO" -> "mp4"; else -> "jpg"
                                    }
                                    groupManager.decodeBase64ToFile(requireContext(), raw, ext)
                                }
                            }
                            if (!resolvedUri.isNullOrBlank()) {
                                val file = if (resolvedUri.startsWith("file://")) {
                                    java.io.File(resolvedUri.removePrefix("file://"))
                                } else null
                                val finalUri = if (file != null && file.exists()) {
                                    try {
                                        androidx.core.content.FileProvider.getUriForFile(
                                            requireContext(),
                                            "${requireContext().packageName}.provider",
                                            file
                                        ).toString()
                                    } catch (e: Exception) {
                                        resolvedUri
                                    }
                                } else resolvedUri
                                startActivity(
                                    Intent(
                                        requireContext(),
                                        FullscreenMediaActivity::class.java
                                    ).apply {
                                        putExtra("uri", finalUri)
                                        putExtra("type", type)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                )
                            }
                        }
                    }
                }
            },
            onLongClick = { view, msg ->
                val clipboard =
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                when (view.id) {
                    R.id.btnReplySent, R.id.btnReplyRcv -> handleReply(buildReplyPreviewForGroup(msg))
                    R.id.btnDeleteSent, R.id.btnDeleteRcv -> handleDelete(msg)
                    R.id.btnCopySent, R.id.btnCopyRcv -> clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            "msg",
                            msg.msg
                        )
                    )

                    R.id.btnForwardSent, R.id.btnForwardRcv -> handleForward()
                    R.id.btnInfoSent, R.id.btnInfoRcv -> handleInfo(msg)
                }
            },
            onEmojiSelected = { msg, emoji ->
                selectedGroupMsg = msg; groupMessageAdapter.showReactionImmediately(
                msg.messageId,
                emoji
            )
                handleReaction(emoji); updateRecentEmojiList(emoji, msg.messageId)
            },
            onMoreEmojisClicked = { msg ->
                selectedGroupMsg = msg; selectedMsg = null
                if (!isEmojiPickerVisible) {
                    hideKeyboard(messageEdit); messageEdit.postDelayed({ showEmojiPicker() }, 150)
                }
            },
            myId = onionAddr,
            recentEmojis = recentEmojisList,
            isAnonymous = isAnonymous,
            groupCreatorId = groupCreatorId,
            anonymousAliasesJson = anonymousAliasesJson,
            onReplyQuoteClicked = { replyText ->
                val idx = findGroupReplyPosition(replyText)
                if (idx != -1) scrollToAndHighlight(
                    recyclerview,
                    idx
                ) { groupMessageAdapter.highlightItem(idx) }
            },
            onWipeRequestAction = { item, isAccepted ->
                val reqMeta = parseWipeMeta(item.msg)
                val requester = reqMeta.requesterOnion ?: item.senderOnion
                handleWipeRequestAction(item.messageId, requester, isAccepted)
            }
        )
        val groupLlm = object : LinearLayoutManager(requireContext()) {
            override fun supportsPredictiveItemAnimations() = false
        }.apply { stackFromEnd = false }
        recyclerview.layoutManager = groupLlm
        recyclerview.adapter = groupMessageAdapter
        recyclerview.clipChildren = false
        recyclerview.clipToPadding = false

        // ── Collect group messages via Flow on IO, submit on Main ─────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // GroupMessageEntity is plaintext — no crypto overhead, but still move DB
                // collection off Main to avoid jank on large histories.
                val repo = app.secure.kyber.roomdb.GroupMessageRepository(
                    AppDb.get(requireContext()).groupsMessagesDao()
                )
                repo.observeFlow(groupId).collect { messages ->
                    groupMessageAdapter.submitList(messages) {
                        val lastVisible = groupLlm.findLastVisibleItemPosition()
                        val total = groupMessageAdapter.itemCount
                        if (total > 0 && (lastVisible >= total - 3 || total <= 30)) {
                            recyclerview.scrollToPosition(total - 1)
                        }
                    }
                }
            }
        }
        // Reset unread count when user opens the group chat
        vm2.resetUnread(groupId)

        // Attach swipe-to-reply for group messages
        attachSwipeToReply(
            rv = recyclerview,
            onReply = { position ->
                val item = groupMessageAdapter.currentList.getOrNull(position)
                if (item != null) handleReply(buildReplyPreviewForGroup(item))
            }
        )
    }

    /**
     * Smoothly scrolls [rv] to [idx] then invokes [highlight] once the item is
     * fully on screen.  If the item is already visible the highlight fires
     * immediately on the next layout pass.
     */
    private fun scrollToAndHighlight(rv: RecyclerView, idx: Int, highlight: () -> Unit) {
        val lm = rv.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstCompletelyVisibleItemPosition()
        val last = lm.findLastCompletelyVisibleItemPosition()
        if (idx in first..last) {
            rv.post { highlight() }
        } else {
            rv.smoothScrollToPosition(idx)
            rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        recyclerView.removeOnScrollListener(this)
                        recyclerView.post { highlight() }
                    }
                }
            })
        }
    }

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Attaches a swipe-to-reply gesture to [rv].
     *
     * Direction is consistent:
     *  - Received messages swipe RIGHT → reply icon appears on the LEFT.
     *  - Sent messages swipe RIGHT     → reply icon appears on the LEFT.
     *
     * The gesture is purely cosmetic — the item always springs back to its
     * original position. The reply callback fires once at 30 % width.
     */
    private fun attachSwipeToReply(
        rv: RecyclerView,
        onReply: (position: Int) -> Unit
    ) {
        val replyIcon = ContextCompat.getDrawable(requireContext(), R.drawable.reply)
        val triggerFraction = 0.30f   // 30 % of item width fires the reply
        val maxSwipeFraction = 0.40f   // cap translation at 40 % of item width
        var triggered = false

        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                // Impossible threshold keeps this from ever firing; reset just in case.
                rv.adapter?.notifyItemChanged(vh.bindingAdapterPosition)
                triggered = false
            }

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 2.0f
            override fun getSwipeEscapeVelocity(defaultValue: Float) = Float.MAX_VALUE
            override fun getSwipeVelocityThreshold(defaultValue: Float) = 0f

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE) {
                    super.onChildDraw(
                        c,
                        recyclerView,
                        viewHolder,
                        dX,
                        dY,
                        actionState,
                        isCurrentlyActive
                    )
                    return
                }

                val pos = viewHolder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return

                val itemView = viewHolder.itemView
                val canSwipeReply = canSwipeReplyAt(pos)
                if (!canSwipeReply) {
                    itemView.translationX = 0f
                    return
                }

                val maxDx = itemView.width * maxSwipeFraction

                // Support right-swipe on both sent and received messages.
                if (dX < 0f) {
                    itemView.translationX = 0f; return
                }

                // Clamp to a smooth right-swipe distance.
                val effectiveDx = dX.coerceAtMost(maxDx)
                val fraction = effectiveDx / itemView.width

                // Fire callback once at threshold; reset when finger lifts.
                if (fraction >= triggerFraction && !triggered && isCurrentlyActive) {
                    triggered = true
                    requireView().performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    if (pos != RecyclerView.NO_POSITION) onReply(pos)
                }
                if (!isCurrentlyActive) triggered = false

                // Draw reply icon on the left side.
                replyIcon?.let { icon ->
                    val alpha = ((fraction / triggerFraction) * 255).toInt().coerceIn(0, 255)
                    val iconSize = (itemView.height * 0.35f).toInt()
                    val margin = (itemView.height - iconSize) / 2
                    val iconTop = itemView.top + margin
                    val iconBottom = iconTop + iconSize
                    val iconLeft = itemView.left + margin
                    val iconRight = iconLeft + iconSize
                    icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                    icon.alpha = alpha
                    icon.draw(c)
                }

                itemView.translationX = effectiveDx
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.translationX = 0f
                triggered = false
            }
        }

        ItemTouchHelper(callback).attachToRecyclerView(rv)
    }

    private fun canSwipeReplyAt(position: Int): Boolean {
        return if (comingFrom == "group_chat_list") {
            val item = groupMessageAdapter.currentList.getOrNull(position) ?: return false
            val isWipe = item.type.uppercase(Locale.US).startsWith("WIPE_")
            if (!isWipe) return true
            val actionable = item.type.uppercase(Locale.US) == WIPE_REQ &&
                    item.senderOnion != (Prefs.getOnionAddress(requireContext()) ?: "") &&
                    item.reaction.uppercase(Locale.US) !in setOf(WIPE_ACTION_ACCEPTED, WIPE_ACTION_REJECTED)
            actionable
        } else {
            val item = adapterMsg.currentList.getOrNull(position) ?: return false
            val isWipe = item.type.uppercase(Locale.US).startsWith("WIPE_")
            if (!isWipe) return true
            val actionable = item.type.uppercase(Locale.US) == WIPE_REQ &&
                    !item.isSent &&
                    item.reaction.uppercase(Locale.US) !in setOf(WIPE_ACTION_ACCEPTED, WIPE_ACTION_REJECTED)
            actionable
        }
    }

    private fun startAudioRecording() {
        if (!audioRecordingManager.startRecording()) return
        recordingSeconds = 0; isRecordingLocked = false; isRecordingPaused =
            false; liveAmplititudes.clear()
        binding.ivLockIcon.scaleX = 1f; binding.ivLockIcon.scaleY = 1f
        binding.lockPopup.translationY = 0f; binding.lockPopup.alpha = 0f
        binding.tvLockChevrons.translationY = 0f; binding.tvLockChevrons.alpha = 0.3f
        binding.bottomSheet.isVisible = false; binding.lockedRecordingBar.isVisible = false
        binding.unLockedRecordingBar.isVisible = true; binding.tvSlideToCancel.alpha = 1f
        binding.lockPopup.isVisible = true; binding.lockPopup.animate().alpha(1f).setDuration(200)
            .start(); lockPopupVisible = true
        binding.tvRecordingTimerUnlocked.text = "0:00"; binding.tvRecordingTimer.text = "0:00"
        startMicPulse(); recordingTimerHandler.post(recordingTimerRunnable); waveformHandler.post(
            waveformUpdateRunnable
        )
    }

    private fun lockRecording() {
        isRecordingLocked = true; lockPopupVisible = false
        binding.ivLockIcon.animate().scaleX(1.6f).scaleY(1.6f).setDuration(120).withEndAction {
            binding.lockPopup.animate().translationY(-300f).alpha(0f).setDuration(250)
                .withEndAction {
                    binding.lockPopup.isVisible = false; binding.lockPopup.translationY =
                    0f; binding.lockPopup.alpha = 1f
                    binding.ivLockIcon.scaleX = 1f; binding.ivLockIcon.scaleY = 1f
                    binding.tvLockChevrons.translationY = 0f; binding.tvLockChevrons.alpha = 0.3f
                    binding.unLockedRecordingBar.isVisible =
                        false; binding.lockedRecordingBar.isVisible = true
                    binding.ivRecordPause.setImageResource(R.drawable.pause_icon_0)
                    binding.waveformRecording.setAmplitudes(liveAmplititudes.toList())
                }.start()
        }.start()
    }

    private fun stopAndSendAudioRecording(onionAddr: String, senderName: String) {
        val filePath = audioRecordingManager.stopRecording()
        val amplitudes = audioRecordingManager.amplitudeSamples
        recordingTimerHandler.removeCallbacks(recordingTimerRunnable); waveformHandler.removeCallbacks(
            waveformUpdateRunnable
        ); stopMicPulse()
        isRecordingStarted = false; isRecordingLocked = false; isRecordingPaused =
            false; lockPopupVisible = false
        binding.lockedRecordingBar.isVisible = false; binding.unLockedRecordingBar.isVisible =
            false; binding.lockPopup.isVisible = false; binding.bottomSheet.isVisible = true
        if (filePath.isNullOrBlank()) return
        if (recordingSeconds < 1) {
            java.io.File(filePath).delete(); return
        }
        val fileUri = Uri.fromFile(java.io.File(filePath)).toString()
        val ampsJson = encodeAmplitudes(amplitudes)
        val messageText =
            "Voice Message (${formatDuration(getTotalDuration(requireContext(), fileUri))})"
        if (comingFrom == "group_chat_list") {
            lifecycleScope.launch {
                // fileUri is already a local file:// path from AudioRecordingManager —
                // copy to group_media_cache for a stable path that survives the temp dir
                val localPath = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    groupManager.saveMediaToLocalCache(requireContext(), fileUri, "m4a")
                } ?: fileUri  // fallback to original path if copy fails

                groupManager.sendMessage(
                    groupId = groupId,
                    senderId = onionAddr,
                    senderName = senderName,
                    messageText = messageText,
                    groupMessagesViewModel = vm1,
                    type = "AUDIO",
                    uri = localPath,              // file:// path → Room
                    ampsJson = ampsJson,
                    firebasePayload = null,       // Will be encoded async in GroupManager
                    replyToText = currentReplyRefRaw,
                    context = requireContext()
                )
                clearReply()
            }
        } else {
            lifecycleScope.launch {
                val db = AppDb.get(requireContext())
                val contactRepo = app.secure.kyber.roomdb.ContactRepository(db.contactDao())
                val isContact = contactRepo.getContact(contactOnion) != null
                mediaSender.sendMedia(
                    contactOnion = contactOnion,
                    senderOnion = onionAddr,
                    senderName = senderName,
                    filePath = fileUri,
                    mimeType = "AUDIO",
                    caption = messageText,
                    ampsJson = ampsJson,
                    isContact = isContact,
                    replyToText = currentReplyRefRaw
                )
                clearReply()
            }
        }
    }

    private fun cancelAudioRecording() {
        audioRecordingManager.cancelRecording()
        recordingTimerHandler.removeCallbacks(recordingTimerRunnable); waveformHandler.removeCallbacks(
            waveformUpdateRunnable
        ); stopMicPulse()
        isRecordingStarted = false; isRecordingPaused = false; lockPopupVisible = false
        if (isRecordingLocked) {
            binding.lockedRecordingBar.startAnimation(TranslateAnimation(0f, -200f, 0f, 0f).apply {
                duration = 300
                setAnimationListener(object : Animation.AnimationListener {
                    override fun onAnimationStart(a: Animation?) {};
                    override fun onAnimationRepeat(a: Animation?) {}
                    override fun onAnimationEnd(a: Animation?) {
                        isRecordingLocked = false; binding.lockedRecordingBar.isVisible = false
                        binding.unLockedRecordingBar.isVisible =
                            false; resetLockPopupState(); binding.bottomSheet.isVisible = true
                    }
                })
            })
        } else {
            isRecordingLocked = false; binding.lockedRecordingBar.isVisible = false
            binding.unLockedRecordingBar.isVisible =
                false; resetLockPopupState(); binding.bottomSheet.isVisible = true
        }
    }

    private fun resetLockPopupState() {
        binding.lockPopup.isVisible = false; binding.lockPopup.translationY =
            0f; binding.lockPopup.alpha = 1f
        binding.ivLockIcon.scaleX = 1f; binding.ivLockIcon.scaleY = 1f
        binding.tvLockChevrons.translationY = 0f; binding.tvLockChevrons.alpha = 0.3f
    }

    private val recordingTimerRunnable = object : Runnable {
        override fun run() {
            if (isRecordingPaused) return
            recordingSeconds++
            val f = String.format(
                Locale.getDefault(),
                "%02d:%02d",
                recordingSeconds / 60,
                recordingSeconds % 60
            )
            binding.tvRecordingTimer.text = f; binding.tvRecordingTimerUnlocked.text = f
            recordingTimerHandler.postDelayed(this, 1000)
        }
    }

    private val waveformUpdateRunnable = object : Runnable {
        override fun run() {
            if (isRecordingPaused || !audioRecordingManager.isCurrentlyRecording()) return
            val samples = audioRecordingManager.amplitudeSamples
            if (samples.isNotEmpty()) {
                liveAmplititudes.clear(); liveAmplititudes.addAll(samples)
                if (isRecordingLocked) binding.waveformRecording.setAmplitudes(
                    if (samples.size > 60) samples.takeLast(
                        60
                    ) else samples
                )
            }
            waveformHandler.postDelayed(this, 100)
        }
    }

    private fun startMicPulse() {
        pulseAnimation = AlphaAnimation(1.0f, 0.2f).apply {
            duration = 600; repeatMode = Animation.REVERSE; repeatCount = Animation.INFINITE
        }
        binding.ivRecordingMic.startAnimation(pulseAnimation); binding.ivRecordingMicUnlocked.startAnimation(
            pulseAnimation
        )
    }

    private fun stopMicPulse() {
        pulseAnimation?.cancel(); binding.ivRecordingMic.clearAnimation(); binding.ivRecordingMicUnlocked.clearAnimation(); pulseAnimation =
            null
    }

    private fun encodeAmplitudes(amps: List<Float>): String {
        val target = 100
        val sampled = if (amps.size <= target) amps else List(target) { i ->
            amps[(i * (amps.size.toFloat() / target)).toInt().coerceIn(0, amps.size - 1)]
        }
        val array =
            JSONArray(); sampled.forEach { array.put(it.toDouble()) }; return array.toString()
    }

    private fun showEmojiPicker() {
        isEmojiPickerVisible = true; emojiPickerContainer.isVisible = true
        binding.ivSend.isVisible = true; binding.ivCamera.isVisible =
            true; binding.ivMic.isVisible = false
    }

    private fun hideEmojiPicker() {
        isEmojiPickerVisible = false; emojiPickerContainer.isVisible = false
        binding.ivSend.isVisible = false; binding.ivCamera.isVisible =
            true; binding.ivMic.isVisible = true
    }

    override fun onResume() {
        super.onResume()
        // Register screen state receiver for recording pause/resume
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
        }
        requireContext().registerReceiver(screenStateReceiver, filter)


        try {
            val myApp = requireActivity().application as app.secure.kyber.MyApp.MyApp
            val targetId = if (comingFrom == "group_chat_list") groupId else contactOnion

            if (comingFrom == "group_chat_list") {
                myApp.activeGroupId = targetId
                myApp.activeChatOnion = null
            } else {
                myApp.activeChatOnion = targetId
                myApp.activeGroupId = null
            }

            val clearIntent = android.content.Intent(
                requireContext(),
                app.secure.kyber.onionrouting.UnionService::class.java
            ).apply {
                action = "CLEAR_NOTIFICATIONS"
                putExtra("sender_onion", targetId)
            }
            requireContext().startService(clearIntent)

            val notificationManager =
                requireContext().getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.cancel(targetId.hashCode())
        } catch (e: Exception) {
        }
    }

    override fun onPause() {

        try {
            requireContext().unregisterReceiver(screenStateReceiver)
        } catch (e: Exception) { /* already unregistered */
        }

        try {
            val myApp = requireActivity().application as app.secure.kyber.MyApp.MyApp
            myApp.activeChatOnion = null
            myApp.activeGroupId = null
        } catch (e: Exception) {
        }
        if (::adapterMsg.isInitialized) adapterMsg.releasePlayer(); if (::groupMessageAdapter.isInitialized) groupMessageAdapter.releasePlayer(); super.onPause()
    }

    override fun onDestroyView() {
        if (::adapterMsg.isInitialized) adapterMsg.releasePlayer();
        if (::groupMessageAdapter.isInitialized) groupMessageAdapter.releasePlayer();
        (requireActivity().application as? app.secure.kyber.MyApp.MyApp)?.activeGroupId = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        recordingTimerHandler.removeCallbacks(recordingTimerRunnable); waveformHandler.removeCallbacks(
            waveformUpdateRunnable
        )
        longPressHandler.removeCallbacks(longPressRunnable); audioRecordingManager.cancelRecording()
        super.onDestroy()
    }


    private fun loadRecentEmojis() {
        val saved = Prefs.getRecentEmojis(requireContext())
        if (saved != null) {
            recentEmojisList.clear(); recentEmojisList.addAll(saved)
        }
    }

    private fun updateRecentEmojiList(emoji: String, msgId: String? = null) {
        recentEmojisList.remove(emoji); recentEmojisList.add(0, emoji)
        if (recentEmojisList.size > 8) recentEmojisList = recentEmojisList.take(8).toMutableList()
        Prefs.setRecentEmojis(requireContext(), recentEmojisList)
        if (::adapterMsg.isInitialized) adapterMsg.updateRecentEmojis(recentEmojisList, msgId)
        if (::groupMessageAdapter.isInitialized) groupMessageAdapter.updateRecentEmojis(
            recentEmojisList
        )
    }

    private fun openPicker() {
        pickMediaLauncher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        })
    }

    @SuppressLint("WrongConstant")
    private fun handlePickResult(result: ActivityResult) {
        val data = result.data ?: return
        val clip = data.clipData;
        val uris = mutableListOf<Uri>()
        if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) } else data.data?.let {
            uris.add(
                it
            )
        }
        if (uris.isEmpty()) return
        val takeFlags =
            data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
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
            val type = if (mime?.startsWith("video") == true || uri.toString()
                    .endsWith(".mp4", true)
            ) "VIDEO" else "IMAGE"
            parcelables.add(SelectedMediaParcelable(uri.toString(), type, ""))
        }
        previewLauncher.launch(Intent(requireContext(), MediaPreviewActivity::class.java).apply {
            putParcelableArrayListExtra(MediaPreviewActivity.EXTRA_ITEMS, parcelables)
        })
        binding.contentMenu.isVisible =
            false; binding.bottomSheet.setBackgroundResource(R.drawable.bottom_nav_bar_bg_png)
    }

    private fun updatePreviewVisibility() {
        binding.previewContainer.isVisible = selectedMedias.isNotEmpty()
    }

    private fun performSendForSelectedMedia() {
        val onionAddr = Prefs.getOnionAddress(requireContext()) ?: "";
        val name = Prefs.getName(requireContext()) ?: ""
        for (m in selectedMedias) {
            val caption =
                if (m.caption.isNullOrBlank()) (if (m.type == "VIDEO") "video" else "photo") else m.caption
            if (comingFrom == "group_chat_list") {
                lifecycleScope.launch {
                    groupManager.sendMessage(
                        groupId,
                        onionAddr,
                        name,
                        caption!!,
                        vm1,
                        m.type,
                        m.uri.toString(),
                        null,
                        null,
                        currentReplyText,
                        requireContext()
                    )
                }
            } else {
                lifecycleScope.launch {
                    val db = AppDb.get(requireContext())
                    val contactRepo = app.secure.kyber.roomdb.ContactRepository(db.contactDao())
                    val isContact = contactRepo.getContact(contactOnion) != null

                    mediaSender.sendMedia(
                        contactOnion = contactOnion,
                        senderOnion = onionAddr,
                        senderName = name,
                        filePath = m.uri.toString(),
                        mimeType = m.type,
                        caption = caption!!,
                        isContact = isContact
                    )
                }
            }
        }
        val count = selectedMedias.size; selectedMedias.clear()
        selectedMediaAdapter.notifyItemRangeRemoved(
            0,
            count
        ); updatePreviewVisibility(); binding.etMsg.setText("")
    }

    private fun hideKeyboard(view: View) =
        (requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(
            view.windowToken,
            0
        )

    private fun showKeyboard(view: View) {
        view.requestFocus(); (requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(
            view,
            InputMethodManager.SHOW_IMPLICIT
        )
    }

    private fun formatDuration(ms: Int): String {
        val s = (ms / 1000).coerceAtLeast(0); return String.format(
            Locale.getDefault(),
            "%d:%02d",
            s / 60,
            s % 60
        )
    }

    private fun getTotalDuration(context: Context, uriStr: String): Int {
        if (uriStr.isBlank()) return 0
        val uri = try {
            uriStr.toUri()
        } catch (_: Exception) {
            return 0
        }
        val r = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file") r.setDataSource(uri.path) else r.setDataSource(
                context,
                uri
            ); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt() ?: 0
        } catch (_: Exception) {
            0
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun convertDatetime(time: String) = try {
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(java.util.Date(time.toLong()))
    } catch (_: Exception) {
        ""
    }

    private fun formatTimestamp(ms: Long): String =
        SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH).format(java.util.Date(ms))

    private fun decodeBase64ToFile(base64Data: String?, type: String): String? {
        if (base64Data.isNullOrBlank()) return null
        if (base64Data.startsWith("content://") || base64Data.startsWith("file://") || base64Data.startsWith(
                "http"
            )
        ) return base64Data
        return try {
            val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            val ext = when (type.uppercase(java.util.Locale.US)) {
                "AUDIO" -> "mp3"; "VIDEO" -> "mp4"; else -> "jpg"
            }
            val file = java.io.File(
                requireContext().cacheDir,
                "kyber_media_${System.currentTimeMillis()}.$ext"
            )
            file.writeBytes(bytes); "file://${file.absolutePath}"
        } catch (e: Exception) {
            base64Data
        }
    }

    private fun acceptRequest() {
        lifecycleScope.launch {
            val db = AppDb.get(requireContext())
            val contactRepo = app.secure.kyber.roomdb.ContactRepository(db.contactDao())
            val myOnion = Prefs.getOnionAddress(requireContext()) ?: ""
            val myName = Prefs.getName(requireContext()) ?: ""

            val cachedName = requireContext()
                .getSharedPreferences("contact_name_cache", Context.MODE_PRIVATE)
                .getString("pending_name_$contactOnion", null)
                ?.takeIf { it.isNotBlank() && it != contactOnion }
            val senderName = cachedName ?: contactOnion

            val publicKey =
                requireContext().getSharedPreferences("contact_name_cache", Context.MODE_PRIVATE)
                    .getString("pending_key_$contactOnion", null)

            contactRepo.saveContact(
                onionAddress = contactOnion,
                name = senderName,
                publicKey = publicKey
            )

            sendPrivateMessage(
                PrivateMessageTransportDto(
                    messageId = UUID.randomUUID().toString(),
                    msg = "",
                    senderOnion = myOnion,
                    senderName = myName,
                    timestamp = System.currentTimeMillis().toString(),
                    isAcceptance = true,
                    senderPublicKey = Prefs.getPublicKey(requireContext())
                )
            )

            requireContext()
                .getSharedPreferences("contact_name_cache", Context.MODE_PRIVATE)
                .edit()
                .remove("pending_name_$contactOnion")
                .remove("pending_key_$contactOnion")
                .apply()

            isRequestMode = false
            binding.requestActionBar.visibility = View.GONE
            binding.bottomItemsLayout.visibility = View.VISIBLE

            val displayName =
                if (senderName.length > 15) senderName.substring(0, 14) else senderName
            (requireActivity() as MainActivity).setAppChatUser(displayName)
            (requireActivity() as MainActivity).onChatDetailsClick(contactOnion, senderName)

            binding.ivSend.setOnClickListener {
                val text = binding.etMsg.text.toString().trim()
                if (text.isNotEmpty()) sendTextMessage(text, myOnion, myName)
            }

            Toast.makeText(requireContext(), "Request accepted", Toast.LENGTH_SHORT).show()
        }
    }

    private fun rejectRequest() {
        lifecycleScope.launch {
            vm.deleteAllBySender(contactOnion)
            requireContext()
                .getSharedPreferences("contact_name_cache", Context.MODE_PRIVATE)
                .edit()
                .remove("pending_name_$contactOnion")
                .remove("pending_key_$contactOnion")
                .apply()
            Toast.makeText(requireContext(), "Request rejected", Toast.LENGTH_SHORT).show()
            navController.popBackStack()
        }
    }


    data class SelectedMedia(val uri: Uri, var caption: String?, val type: String)
}
