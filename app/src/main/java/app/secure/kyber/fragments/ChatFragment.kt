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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
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
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.textfield.TextInputEditText
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Locale

@Suppress("UNCHECKED_CAST")
@AndroidEntryPoint
class ChatFragment : Fragment(R.layout.fragment_chat) {

    private lateinit var unionClient: UnionClient
    private var serverHost by mutableStateOf("139.59.96.43")
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
    private var micDownRawY       = 0f
    private var micDownRawX       = 0f
    private val LOCK_THRESHOLD    = 120f   // px — swipe UP this far to lock
    private val CANCEL_THRESHOLD  = 180f   // px — swipe LEFT this far to cancel
    private var isRecordingLocked = false  // true after user swipes up to lock
    private var isRecordingPaused = false  // true when pause tapped in locked bar
    private var lockPopupVisible  = false
    private val waveformHandler   = Handler(Looper.getMainLooper())
    private val liveAmplititudes    = mutableListOf<Float>()

    private lateinit var groupCreationDate: String
    private lateinit var noOfMembers: String

    private var isRecordingStarted = false
    private val longPressHandler = Handler(Looper.getMainLooper())

    private var selectedMsg: MessageEntity? = null
    private var selectedGroupMsg: GroupMessageEntity? = null

    // ── Long press runnable: fires after 400ms hold to actually start recording ──
    private val longPressRunnable = Runnable {
        if (hasAudioPermission()) {
            isRecordingStarted = true
            startAudioRecording()
        } else {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private val targetUnionId by lazy { requireArguments().getString("contact_id").orEmpty() }
    private val contactName   by lazy { requireArguments().getString("contact_name").orEmpty() }
    private val comingFrom    by lazy { requireArguments().getString("coming_from").orEmpty() }
    private val groupId       by lazy { requireArguments().getString("group_id").orEmpty() }
    private val groupName     by lazy { requireArguments().getString("group_name").orEmpty() }

    private val vm: MessagesViewModel by viewModels {
        val db   = AppDb.get(requireContext())
        val repo = MessageRepository(db.messageDao())
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MessagesViewModel(repo, targetUnionId) as T
        }
    }

    private val vm1: GroupMessagesViewModel by viewModels {
        val db   = AppDb.get(requireContext())
        val repo = GroupMessageRepository(db.groupsMessagesDao())
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GroupMessagesViewModel(repo) as T
        }
    }

    private val vm2: GroupsViewModel by viewModels {
        val db   = AppDb.get(requireContext())
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
            val list = result.data!!.getParcelableArrayListExtra<SelectedMediaParcelable>(
                MediaPreviewActivity.EXTRA_RESULT_ITEMS
            )
            if (!list.isNullOrEmpty()) {
                val unionId = Prefs.getUnionId(requireContext()).toString()
                val name    = Prefs.getName(requireContext()).toString()
                for (item in list) {
                    val caption = if (item.caption.isNullOrBlank()) {
                        if (item.type == "VIDEO") "video" else "photo"
                    } else item.caption

                    if (comingFrom == "group_chat_list") {
                        lifecycleScope.launch {
                            groupManager.sendMessage(
                                groupId = groupId,
                                senderId = unionId,
                                senderName = name,
                                messageText = caption,
                                groupMessagesViewModel = vm1,
                                type = item.type,
                                uri = item.uriString
                            )
                        }
                    } else {
                        vm.saveMessage(
                            msg = caption,
                            senderId = targetUnionId,
                            timestamp = System.currentTimeMillis().toString(),
                            isSent = true,
                            type = item.type,
                            uri = item.uriString
                        )
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding      = FragmentChatBinding.inflate(inflater, container, false)
        groupManager = GroupManager()
        audioRecordingManager = AudioRecordingManager(requireContext())

        selectedMediaAdapter = SelectedMediaAdapter(selectedMedias,
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
        val name    = Prefs.getName(requireContext()).toString()
        val unionId = Prefs.getUnionId(requireContext()).toString()

        messageEdit          = binding.etMsg
        emojiPickerContainer = binding.emojiPickerContainer
        recentEmojiProvider  = CustomRecentEmojiProvider(requireContext())


        if (comingFrom == "group_chat_list") {
            //Get the group creation date
            vm2.getCreationDate(onResult = { result ->
                groupCreationDate = formatTimestamp(result)
                if (::noOfMembers.isInitialized) {
                    (activity as? MainActivity)?.onGroupChatDetailsClick(groupName, groupCreationDate, noOfMembers)
                }
            }, groupId)

            vm2.getNoOfMembers(onResult = { result ->
                noOfMembers = result.toString()
                if (::groupCreationDate.isInitialized) {
                    (activity as? MainActivity)?.onGroupChatDetailsClick(groupName, groupCreationDate, noOfMembers)
                }
            }, groupId)
        }


        setupActionMenu()


        // ── Emoji picker ──────────────────────────────────────────────────────
        emojiPickerView = EmojiPickerView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setRecentEmojiProvider(RecentEmojiProviderAdapter(recentEmojiProvider))
            setOnEmojiPickedListener { emoji ->
                val editable = messageEdit.text ?: return@setOnEmojiPickedListener
                val start = messageEdit.selectionStart.coerceAtLeast(0)
                val end   = messageEdit.selectionEnd.coerceAtLeast(start)
                editable.replace(start, end, emoji.emoji)
                recentEmojiProvider.addRecentEmoji(emoji.emoji)
            }
        }
        emojiPickerContainer.addView(emojiPickerView)

        binding.tilMsg.setEndIconOnClickListener {
            if (isEmojiPickerVisible) {
                hideEmojiPicker()
                showKeyboard(messageEdit)
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
            val screenH  = binding.tilMsg.rootView.height
            val kbHeight = screenH - r.bottom
            val kbVisible = kbHeight > screenH * 0.15

            when {
                kbVisible -> {
                    if (isEmojiPickerVisible) hideEmojiPicker()
                    binding.ivSend.visibility   = View.VISIBLE
                    binding.ivCamera.visibility = View.GONE
                    binding.ivMic.visibility    = View.GONE
                }
                isEmojiPickerVisible -> {
                    binding.ivSend.visibility   = View.VISIBLE
                    binding.ivCamera.visibility = View.GONE
                    binding.ivMic.visibility    = View.GONE
                }
                else -> {
                    binding.ivSend.visibility   = View.GONE
                    binding.ivCamera.visibility = View.VISIBLE
                    binding.ivMic.visibility    = View.VISIBLE
                }
            }
        }

        // ── Back press ────────────────────────────────────────────────────────
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        binding.actionMenu.actionMenuRoot.visibility == View.VISIBLE -> hideActionMenu()
                        isRecordingLocked -> cancelAudioRecording()
                        isEmojiPickerVisible -> hideEmojiPicker()
                        else -> { isEnabled = false; requireActivity().onBackPressedDispatcher.onBackPressed() }
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
                    micDownRawY        = event.rawY
                    micDownRawX        = event.rawX
                    isRecordingLocked  = false
                    isRecordingPaused  = false
                    isRecordingStarted = false
                    longPressHandler.postDelayed(longPressRunnable, 400)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isRecordingStarted) return@setOnTouchListener true
                    if (isRecordingLocked)   return@setOnTouchListener true

                    val deltaY = micDownRawY - event.rawY  // positive = moved up
                    val deltaX = micDownRawX - event.rawX  // positive = moved left

                    // 1. Cancel on swipe-left
                    if (deltaX > CANCEL_THRESHOLD) {
                        cancelAudioRecording()
                        return@setOnTouchListener true
                    }

                    // 2. Lock on swipe-up
                    if (deltaY > LOCK_THRESHOLD) {
                        lockRecording(unionId, name)
                        return@setOnTouchListener true
                    }

                    // 3. Animate lock popup as user swipes up
                    if (deltaY > 20f && lockPopupVisible) {
                        val progress = (deltaY / LOCK_THRESHOLD).coerceIn(0f, 1f)

                        val scale = 1f + 0.4f * progress
                        binding.ivLockIcon.scaleX = scale
                        binding.ivLockIcon.scaleY = scale

                        binding.tvLockChevrons.alpha        = 0.3f + 0.7f * progress
                        binding.tvLockChevrons.translationY = -20f * progress

                        binding.lockPopup.translationY = -deltaY * 0.25f
                    }

                    // 4. Dim the "slide to cancel" hint as user swipes left
                    if (deltaX > 20f) {
                        val progress = (deltaX / CANCEL_THRESHOLD).coerceIn(0f, 1f)
                        binding.tvSlideToCancel.alpha = 1f - progress
                    }

                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)

                    if (!isRecordingStarted) return@setOnTouchListener true
                    if (isRecordingLocked)   return@setOnTouchListener true

                    if (audioRecordingManager.isCurrentlyRecording()) {
                        stopAndSendAudioRecording(unionId, name)
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
            if (isRecordingLocked) stopAndSendAudioRecording(unionId, name)
        }

        // ── Menu / Add ────────────────────────────────────────────────────────
        binding.ivAdd.setOnClickListener {
            if (binding.contentMenu.visibility == View.VISIBLE) {
                binding.contentMenu.visibility = View.GONE
                binding.bottomSheet.setBackgroundResource(R.drawable.bottom_nav_bar_bg_png)
            } else {
                binding.bottomSheet.setBackgroundResource(R.drawable.bottom_sheet)
                binding.contentMenu.visibility = View.VISIBLE
            }
        }

        binding.ivGallery.setOnClickListener {
            openPicker()
            binding.contentMenu.visibility = View.GONE
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
                                groupManager.sendMessage(groupId, unionId, name, text, vm1)
                                binding.etMsg.setText("")
                            }
                        } else {
                            sendMessage(text)
                            vm.saveMessage(text, targetUnionId, System.currentTimeMillis().toString(), true)
                            binding.etMsg.setText("")
                        }
                    }
                }
                true
            } else false
        }

        // ── Initial Setup ─────────────────────────────────────────────────────
        navController = view.findNavController()
        unionClient   = UnionClient()
        onionAppConnection()

        when (comingFrom) {
            "chat_list" -> {
                (requireActivity() as MainActivity).setAppChatUser(
                    if (contactName.length > 15) contactName.substring(0, 14)
                    else contactName.takeUnless { it.isBlank() } ?: targetUnionId
                )
                binding.ivSend.setOnClickListener {
                    val text = binding.etMsg.text.toString().trim()
                    if (text.isNotEmpty()) {
                        sendMessage(text)
                        vm.saveMessage(text, targetUnionId, System.currentTimeMillis().toString(), true)
                        binding.etMsg.setText("")
                    }
                }
                (requireActivity() as MainActivity).onChatDetailsClick(targetUnionId, contactName)
                setListMessageAdapter()
            }
            "group_chat_list" -> {
                groupManager.listenForMessages(groupId, unionId, vm1)
                (requireActivity() as MainActivity).setAppChatUser(groupName)
                binding.ivSend.setOnClickListener {
                    val text = binding.etMsg.text.toString().trim()
                    if (text.isNotEmpty()) {
                        lifecycleScope.launch {
                            groupManager.sendMessage(groupId, unionId, name, text, vm1)
                            binding.etMsg.setText("")
                        }
                    }
                }
                setListGroupMessageAdapter(unionId)
            }
        }

        binding.rvPreview.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = selectedMediaAdapter
        }
        updatePreviewVisibility()
    }

    private fun setupActionMenu() {
        binding.blurOverlay.setOnClickListener { hideActionMenu() }

        val menu = binding.actionMenu
        val emojis = listOf(menu.emoji1, menu.emoji2, menu.emoji3, menu.emoji4, menu.emoji5, menu.emoji6)

        // Setup emoji reactions
        emojis.forEach { emojiTv ->
            emojiTv.setOnClickListener {
                val reaction = emojiTv.text.toString()
                handleReaction(reaction)
                hideActionMenu()
            }
        }

        // More emojis button - shows emoji picker
        menu.emojiMore.setOnClickListener {
            hideActionMenu()
            hideKeyboard(messageEdit)
            messageEdit.postDelayed({ showEmojiPicker() }, 150)
        }

        // Reply button
        menu.btnReply.setOnClickListener {
            handleReply()
            hideActionMenu()
        }

        // Forward button
        menu.btnForward.setOnClickListener {
            handleForward()
            hideActionMenu()
        }

        // Copy button
        menu.btnCopy.setOnClickListener {
            val text = selectedMsg?.msg ?: selectedGroupMsg?.msg ?: ""
            if (text.isNotEmpty() && text != "photo" && text != "video" && !text.startsWith("Voice Message")) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("KyberMsg", text))
                Toast.makeText(requireContext(), "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            hideActionMenu()
        }

        // Info button
        menu.btnInfo.setOnClickListener {
            handleInfo()
            hideActionMenu()
        }

        // Delete button
        menu.btnDelete.setOnClickListener {
            handleDelete()
            hideActionMenu()
        }
    }

    private fun handleReaction(emoji: String) {
        lifecycleScope.launch {
            if (comingFrom == "group_chat_list") {
                selectedGroupMsg?.let { msg ->
                    msg.reaction = emoji
                    vm1.updateMessage(msg)
                    // Explicitly update Firebase reaction through GroupManager
                    groupManager.updateReaction(groupId, msg.messageId, emoji)
                }
            } else {
                selectedMsg?.let { msg ->
                    msg.reaction = emoji
                    vm.updateMessage(msg)
                }
            }
        }
    }

    private fun handleReply() {
        val text = selectedMsg?.msg ?: selectedGroupMsg?.msg ?: return
        lifecycleScope.launch {
            messageEdit.setText(">> \"$text\"\n")
            messageEdit.requestFocus()
            showKeyboard(messageEdit)
        }
    }

    private fun handleForward() {
        Toast.makeText(requireContext(), "Forward feature - select contacts", Toast.LENGTH_SHORT).show()
    }

    private fun handleInfo() {
        val msg = selectedMsg ?: selectedGroupMsg ?: return
        val time = if (msg is MessageEntity) {
            convertDatetime(msg.time)
        } else {
            convertDatetime((msg as GroupMessageEntity).time)
        }

        val senderInfo = if (msg is GroupMessageEntity) {
            "Sender: ${msg.senderName}\nID: ${msg.senderId}\n"
        } else {
            ""
        }

//        val infoMessage = "${senderInfo}Time: $time\nType: ${msg.type}\nStatus: Delivered"
//        Toast.makeText(requireContext(), infoMessage, Toast.LENGTH_LONG).show()
    }

    private fun handleDelete() {
        lifecycleScope.launch {
            if (comingFrom == "group_chat_list") {
                selectedGroupMsg?.let { msg ->
                    vm1.deleteMessage(msg)
                    groupManager.deleteMessage(groupId, msg.messageId)
                    updateLastMessageForGroup(groupId)
                    Toast.makeText(requireContext(), "Message deleted", Toast.LENGTH_SHORT).show()
                }
            } else {
                selectedMsg?.let { msg ->
                    vm.deleteMessage(msg)
                    Toast.makeText(requireContext(), "Message deleted", Toast.LENGTH_SHORT).show()
                }
            }
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
            // Update Firebase
            groupManager.updateLastMessage(
                groupId,
                updatedGroup.lastMessage,
                updatedGroup.timeSpan.toString(),
                latest?.senderId ?: ""
            )
        }
    }

    private fun showActionMenu(view: View, msg: MessageEntity?, groupMsg: GroupMessageEntity?) {
        selectedMsg = msg
        selectedGroupMsg = groupMsg

        // Show blur overlay and menu
        binding.blurOverlay.visibility = View.VISIBLE
        binding.actionMenu.actionMenuRoot.visibility = View.VISIBLE

        // ═══════════════════════════════════════════════════════════════════════
        // DETERMINE IF MESSAGE IS SENT OR RECEIVED
        // ═══════════════════════════════════════════════════════════════════════

        val messageText = msg?.msg ?: groupMsg?.msg ?: ""
        val messageType = (msg?.type ?: groupMsg?.type ?: "TEXT").uppercase()
        val messageUri = msg?.uri ?: groupMsg?.uri
        val messageTime = msg?.time ?: groupMsg?.time ?: ""
        val messageAmpsJson = msg?.ampsJson ?: groupMsg?.ampsJson

        // Check if message is sent or received
        val isSent = if (msg != null) {
            msg.isSent
        } else if (groupMsg != null) {
            groupMsg.senderId == Prefs.getUnionId(requireContext()).toString()
        } else {
            false
        }

        // Always show message preview container
        binding.actionMenu.messagePreviewContainer.visibility = View.VISIBLE

        // Hide all preview bubbles first
        binding.actionMenu.previewRcvdMsgBubble.visibility = View.GONE
        binding.actionMenu.previewSentMsgBubble.visibility = View.GONE

        // ═══════════════════════════════════════════════════════════════════════
        // POPULATE RECEIVED MESSAGE PREVIEW
        // ═══════════════════════════════════════════════════════════════════════
        if (!isSent) {
            binding.actionMenu.previewRcvdMsgBubble.visibility = View.VISIBLE

            // Hide all content first
            binding.actionMenu.previewRcvdText.visibility = View.GONE
            binding.actionMenu.previewRcvdMediaFrame.visibility = View.GONE
            binding.actionMenu.previewRcvdMediaCaption.visibility = View.GONE
            binding.actionMenu.previewRcvdAudioContainer.visibility = View.GONE

            when (messageType) {
                "TEXT" -> {
                    binding.actionMenu.previewRcvdText.text = messageText
                    binding.actionMenu.previewRcvdText.visibility = View.VISIBLE
                }

                "IMAGE" -> {
                    binding.actionMenu.previewRcvdVideoIcon.visibility = View.GONE
                    binding.actionMenu.previewRcvdMediaFrame.visibility = View.VISIBLE

                    if (!messageUri.isNullOrBlank()) {
                        try {
                            Glide.with(binding.root.context)
                                .load(messageUri.toUri())
                                .apply(RequestOptions.centerCropTransform())
                                .into(binding.actionMenu.previewRcvdMedia)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    if (messageText.isNotBlank() && messageText != "photo") {
                        binding.actionMenu.previewRcvdMediaCaption.text = messageText
                        binding.actionMenu.previewRcvdMediaCaption.visibility = View.VISIBLE
                    }
                }

                "VIDEO" -> {
                    binding.actionMenu.previewRcvdVideoIcon.visibility = View.VISIBLE
                    binding.actionMenu.previewRcvdMediaFrame.visibility = View.VISIBLE

                    if (!messageUri.isNullOrBlank()) {
                        try {
                            Glide.with(binding.root.context)
                                .load(messageUri.toUri())
                                .apply(RequestOptions.centerCropTransform())
                                .into(binding.actionMenu.previewRcvdMedia)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    if (messageText.isNotBlank() && messageText != "video") {
                        binding.actionMenu.previewRcvdMediaCaption.text = messageText
                        binding.actionMenu.previewRcvdMediaCaption.visibility = View.VISIBLE
                    }
                }

                "AUDIO" -> {
                    binding.actionMenu.previewRcvdAudioContainer.visibility = View.VISIBLE

                    val amplitudes = decodeAmplitudes(messageAmpsJson)
                    binding.actionMenu.previewRcvdWaveform.setAmplitudes(amplitudes)

                    val duration = getTotalDuration(requireContext(), messageUri ?: "")
                    binding.actionMenu.previewRcvdAudioDuration.text = formatDuration(duration)
                    binding.actionMenu.previewRcvdAudioTime.text = convertDatetime(messageTime)
                    binding.actionMenu.previewRcvdSpeed.text = "1x"
                }

                else -> {
                    binding.actionMenu.previewRcvdText.text = messageText
                    binding.actionMenu.previewRcvdText.visibility = View.VISIBLE
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // POPULATE SENT MESSAGE PREVIEW
        // ═══════════════════════════════════════════════════════════════════════
        else {
            binding.actionMenu.previewSentMsgBubble.visibility = View.VISIBLE

            // Hide all content first
            binding.actionMenu.previewSentText.visibility = View.GONE
            binding.actionMenu.previewSentMediaFrame.visibility = View.GONE
            binding.actionMenu.previewSentMediaCaption.visibility = View.GONE
            binding.actionMenu.previewSentAudioContainer.visibility = View.GONE

            when (messageType) {
                "TEXT" -> {
                    binding.actionMenu.previewSentText.text = messageText
                    binding.actionMenu.previewSentText.visibility = View.VISIBLE
                }

                "IMAGE" -> {
                    binding.actionMenu.previewSentVideoIcon.visibility = View.GONE
                    binding.actionMenu.previewSentMediaFrame.visibility = View.VISIBLE

                    if (!messageUri.isNullOrBlank()) {
                        try {
                            Glide.with(binding.root.context)
                                .load(messageUri.toUri())
                                .apply(RequestOptions.centerCropTransform())
                                .into(binding.actionMenu.previewSentMedia)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    if (messageText.isNotBlank() && messageText != "photo") {
                        binding.actionMenu.previewSentMediaCaption.text = messageText
                        binding.actionMenu.previewSentMediaCaption.visibility = View.VISIBLE
                    }
                }

                "VIDEO" -> {
                    binding.actionMenu.previewSentVideoIcon.visibility = View.VISIBLE
                    binding.actionMenu.previewSentMediaFrame.visibility = View.VISIBLE

                    if (!messageUri.isNullOrBlank()) {
                        try {
                            Glide.with(binding.root.context)
                                .load(messageUri.toUri())
                                .apply(RequestOptions.centerCropTransform())
                                .into(binding.actionMenu.previewSentMedia)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    if (messageText.isNotBlank() && messageText != "video") {
                        binding.actionMenu.previewSentMediaCaption.text = messageText
                        binding.actionMenu.previewSentMediaCaption.visibility = View.VISIBLE
                    }
                }

                "AUDIO" -> {
                    binding.actionMenu.previewSentAudioContainer.visibility = View.VISIBLE

                    val amplitudes = decodeAmplitudes(messageAmpsJson)
                    binding.actionMenu.previewSentWaveform.setAmplitudes(amplitudes)

                    val duration = getTotalDuration(requireContext(), messageUri ?: "")
                    binding.actionMenu.previewSentAudioDuration.text = formatDuration(duration)
                    binding.actionMenu.previewSentAudioTime.text = convertDatetime(messageTime)
                    binding.actionMenu.previewSentSpeed.text = "1x"
                }

                else -> {
                    binding.actionMenu.previewSentText.text = messageText
                    binding.actionMenu.previewSentText.visibility = View.VISIBLE
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // END MESSAGE PREVIEW SETUP
        // ═══════════════════════════════════════════════════════════════════════

        // Position the menu near the message
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val viewY = location[1].toFloat()

        val menu = binding.actionMenu.actionMenuRoot
        menu.post {
            val menuHeight = menu.height
            val menuWidth = menu.width
            val screenHeight = resources.displayMetrics.heightPixels
            val screenWidth = resources.displayMetrics.widthPixels

            // Position vertically - centered on screen
            val posY = (screenHeight / 2.5f).coerceAtLeast(100f)

            // Position horizontally - centered on screen
            val posX = ((screenWidth - menuWidth) / 2).toFloat().coerceIn(20f, (screenWidth - menuWidth - 20).toFloat())

            menu.y = posY
            menu.x = posX

            // Animate menu appearance
            menu.alpha = 0f
            menu.scaleX = 0.8f
            menu.scaleY = 0.8f
            menu.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .start()
        }
    }

    private fun hideActionMenu() {
        val menu = binding.actionMenu.actionMenuRoot
        menu.animate()
            .alpha(0f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .setDuration(150)
            .withEndAction {
                binding.blurOverlay.visibility = View.GONE
                binding.actionMenu.root.visibility = View.GONE
                selectedMsg = null
                selectedGroupMsg = null

                // Reset menu properties for next time
                menu.alpha = 1f
                menu.scaleX = 1f
                menu.scaleY = 1f
            }
            .start()
    }

    // ── Audio recording ───────────────────────────────────────────────────────

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun startAudioRecording() {
        if (!audioRecordingManager.startRecording()) return
        recordingSeconds  = 0
        isRecordingLocked = false
        isRecordingPaused = false
        liveAmplititudes.clear()

        // Reset every animated property so each session starts clean
        binding.ivLockIcon.scaleX           = 1f
        binding.ivLockIcon.scaleY           = 1f
        binding.lockPopup.translationY      = 0f
        binding.lockPopup.alpha             = 0f
        binding.tvLockChevrons.translationY = 0f
        binding.tvLockChevrons.alpha        = 0.3f

        binding.bottomSheet.visibility          = View.GONE
        binding.lockedRecordingBar.visibility   = View.GONE
        binding.unLockedRecordingBar.visibility = View.VISIBLE
        binding.tvSlideToCancel.alpha           = 1f

        // Fade the lock popup in
        binding.lockPopup.visibility = View.VISIBLE
        binding.lockPopup.animate().alpha(1f).setDuration(200).start()
        lockPopupVisible = true

        binding.tvRecordingTimerUnlocked.text = "0:00"
        binding.tvRecordingTimer.text         = "0:00"
        startMicPulse()
        recordingTimerHandler.post(recordingTimerRunnable)
        waveformHandler.post(waveformUpdateRunnable)
    }

    private fun lockRecording(unionId: String, senderName: String) {
        isRecordingLocked = true
        lockPopupVisible  = false

        // Step 1: snap the lock icon up (picks up seamlessly from drag scale)
        binding.ivLockIcon.animate()
            .scaleX(1.6f)
            .scaleY(1.6f)
            .setDuration(120)
            .withEndAction {

                // Step 2: fly the popup upward and fade it out.
                binding.lockPopup.animate()
                    .translationY(-300f)
                    .alpha(0f)
                    .setDuration(250)
                    .withEndAction {

                        // Step 3: popup is now fully off-screen and invisible.
                        binding.lockPopup.visibility            = View.GONE
                        binding.lockPopup.translationY          = 0f
                        binding.lockPopup.alpha                 = 1f
                        binding.ivLockIcon.scaleX               = 1f
                        binding.ivLockIcon.scaleY               = 1f
                        binding.tvLockChevrons.translationY     = 0f
                        binding.tvLockChevrons.alpha            = 0.3f

                        // NOW hide the unlocked bar and show the locked bar
                        binding.unLockedRecordingBar.visibility = View.GONE
                        binding.lockedRecordingBar.visibility   = View.VISIBLE
                        binding.ivRecordPause.setImageResource(R.drawable.pause_icon_0)
                        binding.waveformRecording.setAmplitudes(liveAmplititudes.toList())
                    }
                    .start()
            }
            .start()
    }

    private fun stopAndSendAudioRecording(unionId: String, senderName: String) {
        val filePath   = audioRecordingManager.stopRecording()
        val amplitudes = audioRecordingManager.amplitudeSamples

        recordingTimerHandler.removeCallbacks(recordingTimerRunnable)
        waveformHandler.removeCallbacks(waveformUpdateRunnable)
        stopMicPulse()

        isRecordingStarted = false
        isRecordingLocked  = false
        isRecordingPaused  = false
        lockPopupVisible   = false

        binding.lockedRecordingBar.visibility   = View.GONE
        binding.unLockedRecordingBar.visibility = View.GONE
        binding.lockPopup.visibility            = View.GONE
        binding.bottomSheet.visibility          = View.VISIBLE

        if (filePath.isNullOrBlank()) return
        if (recordingSeconds < 1) { java.io.File(filePath).delete(); return }

        val fileUri     = Uri.fromFile(java.io.File(filePath)).toString()
        val ampsJson    = encodeAmplitudes(amplitudes)
        val duration    = getTotalDuration(requireContext(), fileUri)
        val durationStr = formatDuration(duration)

        if (comingFrom == "group_chat_list") {
            lifecycleScope.launch {
                groupManager.sendMessage(groupId, unionId, senderName, "Voice Message ($durationStr)", vm1, "AUDIO", fileUri, ampsJson)
            }
        } else {
            vm.saveMessage("Voice Message ($durationStr)", targetUnionId, System.currentTimeMillis().toString(), true, "AUDIO", fileUri, ampsJson)
        }
    }

    private fun cancelAudioRecording() {
        audioRecordingManager.cancelRecording()

        recordingTimerHandler.removeCallbacks(recordingTimerRunnable)
        waveformHandler.removeCallbacks(waveformUpdateRunnable)
        stopMicPulse()

        isRecordingStarted = false
        isRecordingPaused  = false
        lockPopupVisible   = false

        if (isRecordingLocked) {
            val anim = TranslateAnimation(0f, -200f, 0f, 0f).apply {
                duration = 300
                setAnimationListener(object : Animation.AnimationListener {
                    override fun onAnimationStart(a: Animation?) {}
                    override fun onAnimationRepeat(a: Animation?) {}
                    override fun onAnimationEnd(a: Animation?) {
                        isRecordingLocked                       = false
                        binding.lockedRecordingBar.visibility   = View.GONE
                        binding.unLockedRecordingBar.visibility = View.GONE
                        resetLockPopupState()
                        binding.bottomSheet.visibility          = View.VISIBLE
                    }
                })
            }
            binding.lockedRecordingBar.startAnimation(anim)
        } else {
            isRecordingLocked                       = false
            binding.lockedRecordingBar.visibility   = View.GONE
            binding.unLockedRecordingBar.visibility = View.GONE
            resetLockPopupState()
            binding.bottomSheet.visibility          = View.VISIBLE
        }
    }

    /** Resets every animated property on the lock popup to its initial state. */
    private fun resetLockPopupState() {
        binding.lockPopup.visibility        = View.GONE
        binding.lockPopup.translationY      = 0f
        binding.lockPopup.alpha             = 1f
        binding.ivLockIcon.scaleX           = 1f
        binding.ivLockIcon.scaleY           = 1f
        binding.tvLockChevrons.translationY = 0f
        binding.tvLockChevrons.alpha        = 0.3f
    }

    // ── Timer / Waveform ──────────────────────────────────────────────────────

    private val recordingTimerRunnable = object : Runnable {
        override fun run() {
            if (isRecordingPaused) return
            recordingSeconds++
            val formatted = String.format(Locale.getDefault(), "%02d:%02d", recordingSeconds / 60, recordingSeconds % 60)
            binding.tvRecordingTimer.text         = formatted
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
            duration    = 600
            repeatMode  = Animation.REVERSE
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
        val target  = 100
        val sampled = if (amps.size <= target) amps
        else List(target) { i -> amps[(i * (amps.size.toFloat() / target)).toInt().coerceIn(0, amps.size - 1)] }
        return JSONArray().apply { sampled.forEach { put(Math.round(it * 100) / 100.0) } }.toString()
    }

    // ── Standard Chat Helpers ─────────────────────────────────────────────────

    private fun showEmojiPicker() {
        isEmojiPickerVisible = true
        emojiPickerContainer.visibility = View.VISIBLE
        binding.ivSend.visibility   = View.VISIBLE
        binding.ivCamera.visibility = View.VISIBLE
        binding.ivMic.visibility    = View.GONE
    }

    private fun hideEmojiPicker() {
        isEmojiPickerVisible = false
        emojiPickerContainer.visibility = View.GONE
        binding.ivSend.visibility   = View.GONE
        binding.ivCamera.visibility = View.VISIBLE
        binding.ivMic.visibility    = View.VISIBLE
    }

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
        disconnectFromServer()
        super.onDestroy()
    }

    private fun disconnectFromServer() { lifecycleScope.launch { unionClient.disconnect() } }

    private fun setListGroupMessageAdapter(unionId: String) {
        recyclerview = binding.rvMsg
        groupMessageAdapter = GroupMessagesAdapter(onClick = { messageEntity ->
            if (messageEntity.type.uppercase(Locale.getDefault()) != "AUDIO") {
                val uriStr = messageEntity.uri ?: messageEntity.msg
                if (!uriStr.isNullOrBlank()) {
                    startActivity(Intent(requireContext(), FullscreenMediaActivity::class.java).apply {
                        putExtra("uri", uriStr); putExtra("type", messageEntity.type)
                    })
                }
            }
        }, onLongClick = { view, messageEntity ->
            showActionMenu(view, null, messageEntity)
        }, myId = unionId)

        vm1.getMessagesForGroupChat(groupId) { liveData ->
            liveData.observe(viewLifecycleOwner) { messages ->
                groupMessageAdapter.submitList(messages) {
                    recyclerview.scrollToPosition(groupMessageAdapter.itemCount - 1)
                }
                recyclerview.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
                recyclerview.adapter = groupMessageAdapter
            }
        }
    }

    private fun setListMessageAdapter() {
        recyclerview = binding.rvMsg
        adapterMsg = MessageAdapter(myId = "me123", onClick = { messageEntity ->
            if (messageEntity.type?.uppercase(Locale.getDefault()) != "AUDIO") {
                val uriStr = messageEntity.uri ?: messageEntity.msg
                if (!uriStr.isNullOrBlank()) {
                    startActivity(Intent(requireContext(), FullscreenMediaActivity::class.java).apply {
                        putExtra("uri", uriStr); putExtra("type", messageEntity.type ?: "TEXT")
                    })
                }
            }
        }, onLongClick = { view, messageEntity ->
            showActionMenu(view, messageEntity, null)
        })
        recyclerview.apply {
            viewLifecycleOwner.lifecycleScope.launch {
                vm.messagesFlow.collect { list ->
                    adapterMsg.submitList(list) { scrollToPosition(adapterMsg.itemCount - 1) }
                }
            }
            layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
            adapter = adapterMsg
        }
    }

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

        val takeFlags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val parcelables = ArrayList<SelectedMediaParcelable>(uris.size)
        for (uri in uris) {
            try { requireContext().contentResolver.takePersistableUriPermission(uri, takeFlags) } catch (e: Exception) {}
            val mime = try { requireContext().contentResolver.getType(uri) } catch (e: Exception) { null }
            val type = if (mime?.startsWith("video") == true || uri.toString().endsWith(".mp4", true)) "VIDEO" else "IMAGE"
            parcelables.add(SelectedMediaParcelable(uri.toString(), type, ""))
        }
        previewLauncher.launch(Intent(requireContext(), MediaPreviewActivity::class.java).apply {
            putParcelableArrayListExtra(MediaPreviewActivity.EXTRA_ITEMS, parcelables)
        })
        binding.contentMenu.visibility = View.GONE
        binding.bottomSheet.setBackgroundResource(R.drawable.bottom_nav_bar_bg_png)
    }

    private fun updatePreviewVisibility() {
        binding.previewContainer.visibility = if (selectedMedias.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun performSendForSelectedMedia() {
        val unionId = Prefs.getUnionId(requireContext()).toString()
        val name    = Prefs.getName(requireContext()).toString()
        for (m in selectedMedias) {
            val caption = if (m.caption.isNullOrBlank()) (if (m.type == "VIDEO") "video" else "photo") else m.caption
            if (comingFrom == "group_chat_list") {
                lifecycleScope.launch {
                    groupManager.sendMessage(groupId, unionId, name, caption!!, vm1, m.type, m.uri.toString())
                }
            } else {
                vm.saveMessage(caption!!, targetUnionId, System.currentTimeMillis().toString(), true, m.type, m.uri.toString())
            }
        }
        val count = selectedMedias.size
        selectedMedias.clear()
        selectedMediaAdapter.notifyItemRangeRemoved(0, count)
        updatePreviewVisibility()
        binding.etMsg.setText("")
    }

    private fun sendMessage(text: String) {
        if (targetUnionId.isNotEmpty()) lifecycleScope.launch { unionClient.sendMessage(targetUnionId, text) }
    }

    fun onionAppConnection() {
        if (Prefs.getPublicKey(requireContext()).isNullOrEmpty()) {
            val exported = unionClient.exportIdentity()
            Prefs.setUnionId(requireContext(), exported["unionId"])
            Prefs.setPublicKey(requireContext(), exported["publicKey"])
        } else {
            unionClient.importIdentity(
                mapOf(
                    "unionId"   to Prefs.getUnionId(requireContext())!!,
                    "publicKey" to Prefs.getPublicKey(requireContext())!!
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

    private fun recieveMessage() {
        unionClient.setMessageCallback(targetUnionId) {
            vm.saveMessage(it.content, targetUnionId, it.timestamp.toString(), false)
        }
    }

    private fun hideKeyboard(view: View) {
        (requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun showKeyboard(view: View) {
        view.requestFocus()
        (requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    // ── Helper Functions for Message Preview ──────────────────────────────────

    private fun decodeAmplitudes(json: String?): List<Float> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i -> arr.getDouble(i).toFloat() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun formatDuration(ms: Int): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.getDefault(), "%d:%02d", totalSec / 60, totalSec % 60)
    }

    private fun getTotalDuration(context: Context, uriStr: String): Int {
        if (uriStr.isBlank()) return 0
        val uri = try { uriStr.toUri() } catch (_: Exception) { return 0 }
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file") retriever.setDataSource(uri.path)
            else retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt() ?: 0
        } catch (e: Exception) {
            0
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun convertDatetime(time: String): String {
        return try {
            val formatter = java.text.SimpleDateFormat("hh:mm a", Locale.getDefault())
            formatter.format(java.util.Date(time.toLong()))
        } catch (e: Exception) {
            ""
        }
    }

    private fun formatTimestamp(timeInMillis: Long): String {
        val formatter = SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH)
        return formatter.format(java.util.Date(timeInMillis))
    }

    data class SelectedMedia(val uri: Uri, var caption: String?, val type: String)
}
