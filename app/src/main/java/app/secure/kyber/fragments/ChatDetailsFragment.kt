package app.secure.kyber.fragments

import android.app.AlertDialog
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import app.secure.kyber.R
import app.secure.kyber.Utils.SystemUpdateManager
import app.secure.kyber.activities.MainActivity
import app.secure.kyber.Utils.DateUtils
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.databinding.FragmentChatDetailsBinding
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.ContactRepository
import app.secure.kyber.roomdb.roomViewModel.ContactsViewModel
import app.secure.kyber.roomdb.MessageEntity
import java.io.File
import java.net.URI
import java.util.*
import org.json.JSONObject
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import app.secure.kyber.workers.TextUploadWorker
import app.secure.kyber.Utils.MessageEncryptionManager
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat

class ChatDetailsFragment : Fragment() {
    private lateinit var binding: FragmentChatDetailsBinding

    private val contactOnion by lazy {
        requireArguments().getString("contact_onion").orEmpty()
    }
    private val contactName by lazy {
        requireArguments().getString("contact_name").orEmpty()
    }

    private val shortId by lazy {
        requireArguments().getString("shortId").orEmpty()
    }

    private lateinit var navController: NavController



    private val vm: ContactsViewModel by viewModels {
        // quick factory — wire up Room
        val db = AppDb.get(requireContext())
        val repo = ContactRepository(db.contactDao())
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ContactsViewModel(repo) as T
        }
    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentChatDetailsBinding.inflate(inflater, container, false)
        navController = requireActivity().findNavController(R.id.main_fragment)

        (requireActivity() as MainActivity).setAppChatUser("Chat Details")
        binding.tvName.text = contactName
        binding.avatar.text = if (contactName.isNotEmpty()) contactName.first().toString() else "?"

        // Bind shortId to the @handle TextView
        if (shortId.isNotBlank()) {
            binding.c.text = "@$shortId"
            binding.c.visibility = View.VISIBLE
        } else {
            binding.c.visibility = View.GONE
        }


        
        val status = Prefs.getChatSpecificDisappearingStatus(requireContext(), contactOnion) 
            ?: Prefs.getDisappearingMessageStatus(requireContext())
        binding.disappearingMessagesState.text = status
        binding.muteNotificationsStatus.text = Prefs.getMuteNotificationStatus(requireContext())


        binding.messageUser.setOnClickListener {
            navController.navigate(R.id.action_chatDetailsFragment_to_chatFragment)
        }

        binding.disappearingMessagesLayout.setOnClickListener {
            showDisappearingMessagesDialog()
        }

        binding.nicknameLayout.setOnClickListener {
            showNicknameDialog()
        }

        binding.muteNotificationsLayout.setOnClickListener {
            showMuteNotificationsDialog()
        }

        val args = bundleOf("name" to contactName)
        binding.sharedMediaLayout.setOnClickListener {
            navController.navigate(R.id.action_chatDetailsFragment_to_sharedMediaFragment, args)
        }
        binding.sharedMediaNavigateButton.setOnClickListener {
            navController.navigate(R.id.action_chatDetailsFragment_to_sharedMediaFragment, args)
        }

        binding.blockLayout.setOnClickListener { showBlockDialog() }
        binding.blockText.setOnClickListener { showBlockDialog() }

        binding.wipeChatLayout.setOnClickListener { showWipeChatDialog() }
        binding.wipeChatText.setOnClickListener { showWipeChatDialog() }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRealtimeContactObserver()
        startPeriodicTimeRefresh()
    }

    private fun setupRealtimeContactObserver() {
        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDb.get(requireContext())
            db.contactDao().observeContact(contactOnion).collect { contact ->
                if (contact != null) {
                    updatePillTimeDisplay(contact.lastKeyUpdate)
                }
            }
        }
    }

    private fun startPeriodicTimeRefresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            while (isAdded) {
                try {
                    val db = AppDb.get(requireContext())
                    val contact = db.contactDao().get(contactOnion)
                    if (contact != null) {
                        updatePillTimeDisplay(contact.lastKeyUpdate)
                    }
                    kotlinx.coroutines.delay(10000)  // Update every 10 seconds
                } catch (e: Exception) {
                    android.util.Log.w("ChatDetailsFragment", "Error in periodic time refresh", e)
                }
            }
        }
    }

    private fun updatePillTimeDisplay(lastKeyUpdate: Long) {
        val timeStr = DateUtils.getRelativeTimeSpan(lastKeyUpdate)
        binding.tvPill.text = "Updated $timeStr"
    }

    private fun showDisappearingMessagesDialog() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.disappearing_messages_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.disappearing_messages_dialog_bg))
        dialog.window?.decorView?.setPadding(0, 0, 0, 0)
        
        val currentStatus = Prefs.getChatSpecificDisappearingStatus(requireContext(), contactOnion) 
            ?: Prefs.getDisappearingMessageStatus(requireContext()) ?: "5 Minutes"
        
        val radioButtons = listOf(
            dialogView.findViewById<ImageView>(R.id.radio_5m),
            dialogView.findViewById<ImageView>(R.id.radio_15m),
            dialogView.findViewById<ImageView>(R.id.radio_1h),
            dialogView.findViewById<ImageView>(R.id.radio_1d),
            dialogView.findViewById<ImageView>(R.id.radio_2d)
        )
        
        // Helper to update radio UI
        fun refreshRadios(selected: String) {
            val checked = ContextCompat.getDrawable(requireContext(), R.drawable.radio_checked)
            val unchecked = ContextCompat.getDrawable(requireContext(), R.drawable.radio_unchecked)
            radioButtons[0].setImageDrawable(if (selected == "5 Minutes") checked else unchecked)
            radioButtons[1].setImageDrawable(if (selected == "15 Minutes") checked else unchecked)
            radioButtons[2].setImageDrawable(if (selected == "1 Hour") checked else unchecked)
            radioButtons[3].setImageDrawable(if (selected == "1 Day") checked else unchecked)
            radioButtons[4].setImageDrawable(if (selected == "2 Days") checked else unchecked)
        }
        
        refreshRadios(currentStatus)
        
        fun select(label: String) {
            val oldLabel = Prefs.getChatSpecificDisappearingStatus(requireContext(), contactOnion)
                ?: Prefs.getDisappearingMessageStatus(requireContext())
            
            if (oldLabel != label) {
                Prefs.setChatSpecificDisappearingStatus(requireContext(), contactOnion, label)
                binding.disappearingMessagesState.text = label
                
                lifecycleScope.launch {
                    SystemUpdateManager.sendDisappearingUpdate(requireContext(), contactOnion, label)
                }
            }
            dialog.dismiss()
        }
        
        dialogView.findViewById<LinearLayout>(R.id.layout_5Minutes).setOnClickListener { select("5 Minutes") }
        dialogView.findViewById<LinearLayout>(R.id.layout_15Minutes).setOnClickListener { select("15 Minutes") }
        dialogView.findViewById<LinearLayout>(R.id.layout_1Hour).setOnClickListener { select("1 Hour") }
        dialogView.findViewById<LinearLayout>(R.id.layout_1Day).setOnClickListener { select("1 Day") }
        dialogView.findViewById<LinearLayout>(R.id.layout_2Days).setOnClickListener { select("2 Days") }
        
        dialog.show()
    }


    fun updateSelectionDisappearingMessages(radioButtons: List<ImageView>) {

        val status = Prefs.getDisappearingMessageStatus(requireContext())
        when (status) {
            "5 Minutes" -> {
                radioButtons[0].setImageResource(R.drawable.radio_checked)
                radioButtons[1].setImageResource(R.drawable.radio_unchecked)
                radioButtons[2].setImageResource(R.drawable.radio_unchecked)
                radioButtons[3].setImageResource(R.drawable.radio_unchecked)
                radioButtons[4].setImageResource(R.drawable.radio_unchecked)
            }

            "15 Minutes" -> {
                radioButtons[0].setImageResource(R.drawable.radio_unchecked)
                radioButtons[1].setImageResource(R.drawable.radio_checked)
                radioButtons[2].setImageResource(R.drawable.radio_unchecked)
                radioButtons[3].setImageResource(R.drawable.radio_unchecked)
                radioButtons[4].setImageResource(R.drawable.radio_unchecked)
            }

            "1 Hour" -> {
                radioButtons[0].setImageResource(R.drawable.radio_unchecked)
                radioButtons[1].setImageResource(R.drawable.radio_unchecked)
                radioButtons[2].setImageResource(R.drawable.radio_checked)
                radioButtons[3].setImageResource(R.drawable.radio_unchecked)
                radioButtons[4].setImageResource(R.drawable.radio_unchecked)
            }

            "1 Day" -> {
                radioButtons[0].setImageResource(R.drawable.radio_unchecked)
                radioButtons[1].setImageResource(R.drawable.radio_unchecked)
                radioButtons[2].setImageResource(R.drawable.radio_unchecked)
                radioButtons[3].setImageResource(R.drawable.radio_checked)
                radioButtons[4].setImageResource(R.drawable.radio_unchecked)
            }

            "2 Days" -> {
                radioButtons[0].setImageResource(R.drawable.radio_unchecked)
                radioButtons[1].setImageResource(R.drawable.radio_unchecked)
                radioButtons[2].setImageResource(R.drawable.radio_unchecked)
                radioButtons[3].setImageResource(R.drawable.radio_unchecked)
                radioButtons[4].setImageResource(R.drawable.radio_checked)
            }

            else -> {
                radioButtons[0].setImageResource(R.drawable.radio_checked)
                radioButtons[1].setImageResource(R.drawable.radio_unchecked)
                radioButtons[2].setImageResource(R.drawable.radio_unchecked)
                radioButtons[3].setImageResource(R.drawable.radio_unchecked)
                radioButtons[4].setImageResource(R.drawable.radio_unchecked)
            }

        }
    }


    fun updateMuteNotificationStatus(radioButtons: List<ImageView>) {

        val status = Prefs.getMuteNotificationStatus(requireContext())
        when (status) {
            "1 Hour" -> {
                radioButtons[0].setImageResource(R.drawable.radio_checked)
                radioButtons[1].setImageResource(R.drawable.radio_unchecked)
                radioButtons[2].setImageResource(R.drawable.radio_unchecked)
                radioButtons[3].setImageResource(R.drawable.radio_unchecked)
            }

            "12 Hours" -> {
                radioButtons[0].setImageResource(R.drawable.radio_unchecked)
                radioButtons[1].setImageResource(R.drawable.radio_checked)
                radioButtons[2].setImageResource(R.drawable.radio_unchecked)
                radioButtons[3].setImageResource(R.drawable.radio_unchecked)
            }

            "7 Days" -> {
                radioButtons[0].setImageResource(R.drawable.radio_unchecked)
                radioButtons[1].setImageResource(R.drawable.radio_unchecked)
                radioButtons[2].setImageResource(R.drawable.radio_checked)
                radioButtons[3].setImageResource(R.drawable.radio_unchecked)
            }

            "Always" -> {
                radioButtons[0].setImageResource(R.drawable.radio_unchecked)
                radioButtons[1].setImageResource(R.drawable.radio_unchecked)
                radioButtons[2].setImageResource(R.drawable.radio_unchecked)
                radioButtons[3].setImageResource(R.drawable.radio_checked)
            }

            else -> {
                radioButtons[0].setImageResource(R.drawable.radio_unchecked)
                radioButtons[1].setImageResource(R.drawable.radio_unchecked)
                radioButtons[2].setImageResource(R.drawable.radio_unchecked)
                radioButtons[3].setImageResource(R.drawable.radio_checked)
            }

        }
    }

    fun updateSelection(
        selectedRadioId: Int,
        radioButtons: List<ImageView>,
        dialog: AlertDialog
    ) {

        radioButtons.forEach {
            if (it.id != selectedRadioId) it.setImageResource(R.drawable.radio_unchecked)
            else it.setImageResource(R.drawable.radio_checked)
        }
        dialog.dismiss()
    }

    private fun showNicknameDialog() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.nick_name_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(
                requireContext(),
                R.drawable.nick_name_dialog_bg
            )
        )
        dialog.window?.decorView?.setPadding(0, 0, 0, 0)
        dialog.show()
    }

    private fun showMuteNotificationsDialog() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.mute_notifications, null)
        val dialog = AlertDialog.Builder(context).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(
                requireContext(),
                R.drawable.mute_notifications_dialog_bg
            )
        )
        dialog.window?.decorView?.setPadding(0, 0, 0, 0)
        dialog.show()

        val options = mapOf(
            R.id.opt_1 to ("1 Hour" to R.id.opt_1_radio),
            R.id.opt_2 to ("12 Hours" to R.id.opt_2_radio),
            R.id.opt_3 to ("7 Days" to R.id.opt_3_radio),
            R.id.opt_4 to ("Always" to R.id.opt_4_radio),
        )


        val radioButtons = options.values.map { dialogView.findViewById<ImageView>(it.second) }

        updateMuteNotificationStatus(radioButtons)

        options.forEach { (layoutId, pair) ->
            dialogView.findViewById<LinearLayout>(layoutId).setOnClickListener {
                Prefs.setMuteNotificationStatus(requireContext(), pair.first)
                updateSelection(pair.second, radioButtons, dialog)
                binding.muteNotificationsStatus.text = pair.first
            }
        }
    }

    private fun showBlockDialog() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.block_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(
                requireContext(),
                R.drawable.block_dialog_bg
            )
        )
        dialog.window?.decorView?.setPadding(0, 0, 0, 0)
        dialog.show()
    }

    private fun showWipeChatDialog() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.wipe_chat_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(
                requireContext(),
                R.drawable.wipe_chat_bg
            )
        )
        dialog.window?.decorView?.setPadding(0, 0, 0, 0)

        dialogView.findViewById<View>(R.id.btnWipe).setOnClickListener {
            dialog.dismiss()
            performFullWipe()
        }

        dialog.show()
    }

    private fun performFullWipe() {
        lifecycleScope.launch {
            val db = AppDb.get(requireContext())
            val messageDao = db.messageDao()
            val myOnion = Prefs.getOnionAddress(requireContext()) ?: return@launch
            val timestamp = System.currentTimeMillis().toString()
            val messageId = UUID.randomUUID().toString()

            val initiatorText = "You wiped out the chat at ${formatWipeTimestamp(System.currentTimeMillis())}"

            // 1. Delete media files locally
            withContext(Dispatchers.IO) {
                val messages = messageDao.getBySender(contactOnion)
                messages.forEach { msg ->
                    msg.localFilePath?.let { path ->
                        try {
                            val file = File(URI(path))
                            if (file.exists()) file.delete()
                        } catch (e: Exception) {}
                    }
                    msg.thumbnailPath?.let { path ->
                        try {
                            val file = File(path)
                            if (file.exists()) file.delete()
                        } catch (e: Exception) {}
                    }
                }
                // 2. Delete from DB
                messageDao.deleteAllBySender(contactOnion)
            }

            // 3. Insert local system bubble for initiator
            withContext(Dispatchers.IO) {
                messageDao.insert(
                    MessageEntity(
                        messageId = messageId,
                        msg = MessageEncryptionManager.encryptLocal(requireContext(), initiatorText).encryptedBlob,
                        senderOnion = contactOnion,
                        time = timestamp,
                        isSent = true,
                        type = "WIPE_SYSTEM",
                        uploadState = "done",
                        uploadProgress = 100
                    )
                )
            }

            Toast.makeText(requireContext(), "Chat wiped out", Toast.LENGTH_SHORT).show()
            navController.popBackStack()
        }
    }

    private fun formatWipeTimestamp(ts: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ts }
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        return if (sameDay) {
            SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(ts))
        } else {
            SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(ts))
        }
    }

}
