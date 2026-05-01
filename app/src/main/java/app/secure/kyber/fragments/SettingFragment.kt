package app.secure.kyber.fragments

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import app.secure.kyber.R
import app.secure.kyber.Utils.MessageEncryptionManager
import app.secure.kyber.activities.QrCodeDialog
import app.secure.kyber.backend.KyberRepository
import app.secure.kyber.backend.beans.PrivateMessageTransportDto
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.Utils.SystemUpdateManager
import app.secure.kyber.databinding.FragmentSettingBinding
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.KeyEntity
import app.secure.kyber.workers.KeyRotationWorker
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.database.FirebaseDatabase
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject


@AndroidEntryPoint
class SettingFragment : Fragment(R.layout.fragment_setting) {

    companion object {
        private const val TAG = "SettingFragment"
        const val NOTIF_CHANNEL_ID = "union_messages_channel"

        // Shared state for ACK callback from UnionService/SyncWorker
        @Volatile var pendingWipeRequestId: String? = null
        @Volatile var onWipeAckReceived: ((status: String) -> Unit)? = null
    }

    @Inject
    lateinit var repository: KyberRepository

    private lateinit var binding: FragmentSettingBinding
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentSettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        navController = requireView().findNavController()

        val name = Prefs.getName(requireContext()).toString()
        val shortId = Prefs.getShortId(requireContext()).toString()


        binding.tvName.text = shortId
        binding.tvNameDis.text = name

        binding.btnQR.setOnClickListener {
            QrCodeDialog.showQrDialog(requireContext(), shortId, name)
        }

        binding.btnShare.setOnClickListener {
            requireContext().shareText(shortId, subject = "-yPlease use my this Id to add me on Kyber Chat")
        }

//        val firstLetter = if (name.isNotEmpty()) name[0] else '?'
//        binding.avatarLetter.text = firstLetter.toString()

        setupCardTitles()
        loadSavedValues()
        setupCardListeners()
        setupRealtimeKeyObserver()


    }
    // Setup helpers

    private fun setupCardTitles() {
        binding.autoLockCard.cardTitle.text = "Auto Lock"
        binding.autoLockCard.cardIcon.setImageResource(R.drawable.lock_ic)

        binding.disappearingChatCard.cardTitle.text = "Disappearing Chat"
        binding.disappearingChatCard.cardIcon.setImageResource(R.drawable.disappearing_messages_icon)

        binding.searchPrivacyCard.cardTitle.text = "Search Privacy"
        binding.searchPrivacyCard.cardIcon.setImageResource(R.drawable.eye_off)

        binding.encryptionTimerCard.cardTitle.text = "Encryption Timer"
        binding.encryptionTimerCard.cardTitle.setTextColor(0xFFFF0000.toInt())
        binding.encryptionTimerCard.cardIcon.setImageResource(R.drawable.encryption_timer_ic)
        binding.encryptionTimerCard.cardIcon.setColorFilter(Color.RED)
    }

    /** Reads persisted values and shows them in each card's value label. */
    private fun loadSavedValues() {
        val prefs = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        // true = On, false = Off  |  default: On
        val isOn = prefs.getBoolean("search_privacy_on", true)


        val autoLock = Prefs.getAutoLockTimeout(requireContext()) ?: "1 Minute"
        val validAutoLock = if (autoLock in listOf("30 Seconds", "1 Minute", "3 Minutes")) autoLock else "1 Minute"
        binding.autoLockCard.cardValue.text = validAutoLock

        val disChat = Prefs.getDisappearingMessageStatus(requireContext()) ?: "5 Minutes"
        val validDisChat = if (disChat in listOf("5 Minutes", "15 Minutes", "1 Hour", "1 Day", "2 Days")) disChat else "5 Minutes"
        binding.disappearingChatCard.cardValue.text = validDisChat

        binding.searchPrivacyCard.cardValue.text = if (isOn) "On" else "Off"


        binding.encryptionTimerCard.cardValue.text =
            Prefs.getEncryptionTimer(requireContext()) ?: "24 Hours"
    }

    private fun setupCardListeners() {
        binding.autoLockCard.topCard.setOnClickListener {
            showAutoLockDialog()
        }

        binding.disappearingChatCard.topCard.setOnClickListener {
            showDisappearingMessagesDialog()
        }

        binding.searchPrivacyCard.topCard.setOnClickListener {
            showSearchPrivacyDialog()
        }

        binding.encryptionTimerCard.topCard.setOnClickListener {
            showEncryptionTimerDialog()
        }

        // ── Security item → Wipe-Out Password ──
        binding.itemSecurity.setOnClickListener {
            showWipePasswordDialog()
        }

        // ── Wipe Other Phone ──
        binding.itemWipeOtherPhone.setOnClickListener {
            showWipeOtherPhoneDialog()
        }
    }

    // 1) AUTO LOCK DIALOG
    private fun showAutoLockDialog() {
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.auto_lock_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.disappearing_messages_dialog_bg)
        )
        dialog.window?.decorView?.setPadding(0, 0, 0, 0)

        // Reflect the currently-saved selection
        val autoLock = Prefs.getAutoLockTimeout(requireContext()) ?: "1 Minute"
        val currentSelection = if (autoLock in listOf("30 Seconds", "1 Minute", "3 Minutes")) autoLock else "1 Minute"
        refreshAutoLockRadios(dialogView, currentSelection)

        fun select(label: String) {
            Prefs.setAutoLockTimeout(requireContext(), label)
            binding.autoLockCard.cardValue.text = label
            dialog.dismiss()
        }

        dialogView.findViewById<LinearLayout>(R.id.layout_30Seconds)
            .setOnClickListener { select("30 Seconds") }
        dialogView.findViewById<LinearLayout>(R.id.layout_1Minute)
            .setOnClickListener { select("1 Minute") }
        dialogView.findViewById<LinearLayout>(R.id.layout_3Minutes)
            .setOnClickListener { select("3 Minutes") }

        dialog.show()
    }

    private fun refreshAutoLockRadios(dialogView: View, selected: String) {
        val checked = ContextCompat.getDrawable(requireContext(), R.drawable.radio_checked)
        val unchecked = ContextCompat.getDrawable(requireContext(), R.drawable.radio_unchecked)
        dialogView.findViewById<ImageView>(R.id.radio_30s)
            .setImageDrawable(if (selected == "30 Seconds") checked else unchecked)
        dialogView.findViewById<ImageView>(R.id.radio_1m)
            .setImageDrawable(if (selected == "1 Minute") checked else unchecked)
        dialogView.findViewById<ImageView>(R.id.radio_3m)
            .setImageDrawable(if (selected == "3 Minutes") checked else unchecked)
    }

    // 2) DISAPPEARING MESSAGES DIALOG

    private fun showDisappearingMessagesDialog() {
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.disappearing_messages_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.disappearing_messages_dialog_bg)
        )
        dialog.window?.decorView?.setPadding(0, 0, 0, 0)

        val disChat = Prefs.getDisappearingMessageStatus(requireContext()) ?: "5 Minutes"
        val currentSelection = if (disChat in listOf("5 Minutes", "15 Minutes", "1 Hour", "1 Day", "2 Days")) disChat else "5 Minutes"
        refreshDisappearingRadios(dialogView, currentSelection)

        fun select(label: String) {
            val oldLabel = Prefs.getDisappearingMessageStatus(requireContext())
            if (oldLabel != label) {
                Prefs.setDisappearingMessagesStatus(requireContext(), label)
                binding.disappearingChatCard.cardValue.text = label
                
                lifecycleScope.launch {
                    SystemUpdateManager.broadcastGlobalDisappearingUpdate(requireContext(), label)
                }
            }
            dialog.dismiss()
        }

        dialogView.findViewById<LinearLayout>(R.id.layout_5Minutes)
            .setOnClickListener { select("5 Minutes") }
        dialogView.findViewById<LinearLayout>(R.id.layout_15Minutes)
            .setOnClickListener { select("15 Minutes") }
        dialogView.findViewById<LinearLayout>(R.id.layout_1Hour)
            .setOnClickListener { select("1 Hour") }
        dialogView.findViewById<LinearLayout>(R.id.layout_1Day)
            .setOnClickListener { select("1 Day") }
        dialogView.findViewById<LinearLayout>(R.id.layout_2Days)
            .setOnClickListener { select("2 Days") }

        dialog.show()
    }

    private fun refreshDisappearingRadios(dialogView: View, selected: String) {
        val checked = ContextCompat.getDrawable(requireContext(), R.drawable.radio_checked)
        val unchecked = ContextCompat.getDrawable(requireContext(), R.drawable.radio_unchecked)
        dialogView.findViewById<ImageView>(R.id.radio_5m)
            .setImageDrawable(if (selected == "5 Minutes") checked else unchecked)
        dialogView.findViewById<ImageView>(R.id.radio_15m)
            .setImageDrawable(if (selected == "15 Minutes") checked else unchecked)
        dialogView.findViewById<ImageView>(R.id.radio_1h)
            .setImageDrawable(if (selected == "1 Hour") checked else unchecked)
        dialogView.findViewById<ImageView>(R.id.radio_1d)
            .setImageDrawable(if (selected == "1 Day") checked else unchecked)
        dialogView.findViewById<ImageView>(R.id.radio_2d)
            .setImageDrawable(if (selected == "2 Days") checked else unchecked)
    }

    // 3) SEARCH PRIVACY DIALOG  (Coming Soon — no real backend)

    private fun showSearchPrivacyDialog() {
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.search_privacy_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.disappearing_messages_dialog_bg)
        )
        dialog.window?.decorView?.setPadding(0, 0, 0, 0)

        // --- Views ---
        val layoutOn   = dialogView.findViewById<LinearLayout>(R.id.layout_on)
        val layoutOff  = dialogView.findViewById<LinearLayout>(R.id.layout_off)
        val radioOn    = dialogView.findViewById<ImageView>(R.id.radio_on)
        val radioOff   = dialogView.findViewById<ImageView>(R.id.radio_off)

        // --- SharedPreferences ---
        val prefs = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        // true = On, false = Off  |  default: On
        val isOn = prefs.getBoolean("search_privacy_on", true)

        // --- Helper to update radio icons ---
        fun updateSelection(selectedOn: Boolean) {
            radioOn.setImageResource(
                if (selectedOn) {
                    R.drawable.radio_checked
                } else{
                    R.drawable.radio_unchecked
                }
            )
            radioOff.setImageResource(
                if (selectedOn) R.drawable.radio_unchecked else R.drawable.radio_checked
            )

            binding.searchPrivacyCard.cardValue.text =
                if (selectedOn) "On" else "Off"
        }

        // Apply saved state immediately
        updateSelection(isOn)

        // --- Click listeners ---
        layoutOn.setOnClickListener {
            updateSelection(true)
            prefs.edit().putBoolean("search_privacy_on", true).apply()
            dialog.dismiss()
        }

        layoutOff.setOnClickListener {
            updateSelection(false)
            prefs.edit().putBoolean("search_privacy_on", false).apply()
            dialog.dismiss()
        }

        dialog.show()
    }

    // 4) ENCRYPTION TIMER DIALOG

    private fun showEncryptionTimerDialog() {
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.encryption_timer_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.disappearing_messages_dialog_bg)
        )
        dialog.window?.decorView?.setPadding(0, 0, 0, 0)

        val currentSelection = Prefs.getEncryptionTimer(requireContext()) ?: "24 Hours"
        refreshEncryptionTimerRadios(dialogView, currentSelection)

        fun select(label: String) {
            Prefs.setEncryptionTimer(requireContext(), label)
            binding.encryptionTimerCard.cardValue.text = label
            // Reschedule (or cancel) the periodic key-rotation job with the new interval
            KeyRotationWorker.schedule(requireContext())
            dialog.dismiss()
        }

        // layout_30Days in the XML has text "Never" — map it to "Never"
        dialogView.findViewById<LinearLayout>(R.id.layout_24Hours)
            .setOnClickListener { select("24 Hours") }
        dialogView.findViewById<LinearLayout>(R.id.layout_48Hours)
            .setOnClickListener { select("48 Hours") }
        dialogView.findViewById<LinearLayout>(R.id.layout_7Days)
            .setOnClickListener { select("7 Days") }
        dialogView.findViewById<LinearLayout>(R.id.layout_30Days)
            .setOnClickListener { select("Never") }

        dialog.show()
    }

    private fun refreshEncryptionTimerRadios(dialogView: View, selected: String) {
        val checked = ContextCompat.getDrawable(requireContext(), R.drawable.radio_checked)
        val unchecked = ContextCompat.getDrawable(requireContext(), R.drawable.radio_unchecked)
        dialogView.findViewById<ImageView>(R.id.radio_24h)
            .setImageDrawable(if (selected == "24 Hours") checked else unchecked)
        dialogView.findViewById<ImageView>(R.id.radio_48h)
            .setImageDrawable(if (selected == "48 Hours") checked else unchecked)
        dialogView.findViewById<ImageView>(R.id.radio_7d)
            .setImageDrawable(if (selected == "7 Days") checked else unchecked)
        dialogView.findViewById<ImageView>(R.id.radio_30d)
            .setImageDrawable(if (selected == "Never") checked else unchecked)
    }

    // ──────────────────────────────────────────────────────────────
    // Shared utilities
    // ──────────────────────────────────────────────────────────────

    // ── NEW: Wipe-Out Password dialog ──────────────────────────────
    private fun showWipePasswordDialog() {
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.wipe_password_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.disappearing_messages_dialog_bg)
        )
        dialog.window?.decorView?.setPadding(0, 0, 0, 0)

        val etPassword = dialogView.findViewById<TextInputEditText>(R.id.et_wipe_password)
        val etConfirm  = dialogView.findViewById<TextInputEditText>(R.id.et_wipe_password_confirm)
        val tvError    = dialogView.findViewById<TextView>(R.id.tv_wipe_error)
        val btnSet     = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_set_wipe_password)

        // Show different label if password already exists
        val existing = Prefs.getWipePassword(requireContext())
        if (existing != null) {
            btnSet.text = "Update Wipe-Out Password"
        }

        btnSet.setOnClickListener {
            val newPwd  = etPassword.text.toString().trim()
            val confirm = etConfirm.text.toString().trim()
            val loginPwd = Prefs.getPassword(requireContext()) ?: ""

            when {
                newPwd.isEmpty() || confirm.isEmpty() -> {
                    tvError.text = "Password fields cannot be empty."
                    tvError.visibility = View.VISIBLE
                }
                newPwd != confirm -> {
                    tvError.text = "Passwords do not match."
                    tvError.visibility = View.VISIBLE
                }
                newPwd == loginPwd -> {
                    tvError.text = "You cannot use your login password as the wipe-out password. Please choose a different password."
                    tvError.visibility = View.VISIBLE
                }
                else -> {
                    Prefs.setWipePassword(requireContext(), newPwd)
                    tvError.visibility = View.GONE
                    dialog.dismiss()
                    Toast.makeText(
                        requireContext(),
                        "Wipe-out password set successfully.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        dialog.show()
    }

    // ══════════════════════════════════════════════════════
    // WIPE OTHER PHONE
    // ══════════════════════════════════════════════════════

    private fun showWipeOtherPhoneDialog() {
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.dialog_wipe_other_phone, null)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.disappearing_messages_dialog_bg)
        )
        dialog.window?.decorView?.setPadding(0, 0, 0, 0)

        val sectionInput   = dialogView.findViewById<LinearLayout>(R.id.section_input)
        val sectionLoading = dialogView.findViewById<LinearLayout>(R.id.section_loading)
        val sectionResult  = dialogView.findViewById<LinearLayout>(R.id.section_result)
        val etShortId      = dialogView.findViewById<TextInputEditText>(R.id.et_target_short_id)
        val etWipePwd      = dialogView.findViewById<TextInputEditText>(R.id.et_target_wipe_password)
        val tvError        = dialogView.findViewById<TextView>(R.id.tv_wipe_other_error)
        val tvLoadStatus   = dialogView.findViewById<TextView>(R.id.tv_loading_status)
        val btnProceed     = dialogView.findViewById<MaterialButton>(R.id.btn_wipe_other_proceed)
        val tvResultMsg    = dialogView.findViewById<TextView>(R.id.tv_result_message)
        val tvResultSub    = dialogView.findViewById<TextView>(R.id.tv_result_sub)
        val ivResultIcon   = dialogView.findViewById<ImageView>(R.id.iv_result_icon)
        val btnClose       = dialogView.findViewById<MaterialButton>(R.id.btn_result_close)

        fun showInput()   { sectionInput.visibility = View.VISIBLE; sectionLoading.visibility = View.GONE; sectionResult.visibility = View.GONE }
        fun showLoading(msg: String) { sectionInput.visibility = View.GONE; sectionLoading.visibility = View.VISIBLE; sectionResult.visibility = View.GONE; tvLoadStatus.text = msg }
        fun showResult(success: Boolean, title: String, sub: String = "") {
            sectionInput.visibility = View.GONE; sectionLoading.visibility = View.GONE; sectionResult.visibility = View.VISIBLE
            tvResultMsg.text = title
            if (sub.isNotBlank()) { tvResultSub.text = sub; tvResultSub.visibility = View.VISIBLE } else { tvResultSub.visibility = View.GONE }
            tvResultMsg.setTextColor(if (success) 0xFF4CAF50.toInt() else 0xFFFF6B6B.toInt())
            ivResultIcon.setImageResource(if (success) R.drawable.security_ic else R.drawable.security_ic)
            ivResultIcon.setColorFilter(if (success) 0xFF4CAF50.toInt() else 0xFFFF6B6B.toInt())
        }

        showInput()
        btnClose.setOnClickListener { dialog.dismiss() }

        btnProceed.setOnClickListener {
            val shortId  = etShortId.text?.toString()?.trim() ?: ""
            val wipePwd  = etWipePwd.text?.toString()?.trim() ?: ""
            if (shortId.isEmpty() || wipePwd.isEmpty()) {
                tvError.text = "All fields are required."
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            val myShortId = Prefs.getShortId(requireContext()).toString()
            if (shortId == myShortId) {
                tvError.text = "You cannot wipe your own phone from here."
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            tvError.visibility = View.GONE
            btnProceed.isEnabled = false
            showLoading("Looking up user...")

            // Firebase: shortId → onionAddress
            val db = FirebaseDatabase.getInstance(
                "https://kyber-b144e-default-rtdb.asia-southeast1.firebasedatabase.app/"
            )
            db.getReference("users").child(shortId).child("onion_address").get()
                .addOnSuccessListener { snapshot ->
                    val onionAddress = snapshot.value as? String
                    if (onionAddress.isNullOrBlank()) {
                        showInput()
                        tvError.text = "User not found. Check the Short ID."
                        tvError.visibility = View.VISIBLE
                        btnProceed.isEnabled = true
                        return@addOnSuccessListener
                    }
                    tvLoadStatus.text = "Verifying user on network..."
                    lifecycleScope.launch {
                        try {
                            val resp = withContext(Dispatchers.IO) { repository.getPublicKey(onionAddress) }
                            if (!resp.isSuccessful || resp.body() == null) {
                                withContext(Dispatchers.Main) {
                                    showInput(); tvError.text = "User not reachable on the network."; tvError.visibility = View.VISIBLE; btnProceed.isEnabled = true
                                }
                                return@launch
                            }
                            // User verified → dismiss this dialog, show timer dialog
                            withContext(Dispatchers.Main) {
                                dialog.dismiss()
                                showWipeTimerDialog(onionAddress, wipePwd)
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                showInput(); tvError.text = "Network error. Please try again."; tvError.visibility = View.VISIBLE; btnProceed.isEnabled = true
                            }
                        }
                    }
                }
                .addOnFailureListener {
                    showInput(); tvError.text = "Lookup failed: ${it.message}"; tvError.visibility = View.VISIBLE; btnProceed.isEnabled = true
                }
        }

        dialog.show()
    }

    private fun showWipeTimerDialog(targetOnion: String, targetWipePwd: String) {
        if (!isAdded) return
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.wipe_timer_dialog, null)
        val tvMessage   = dialogView.findViewById<TextView>(R.id.wipe_timer_message)
        val tvCountdown = dialogView.findViewById<TextView>(R.id.wipe_timer_countdown)
        val btnUndo     = dialogView.findViewById<MaterialButton>(R.id.btn_wipe_undo)

        tvMessage.text = "Remote wipe will be triggered on target device"
        btnUndo.visibility = View.VISIBLE

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.disappearing_messages_dialog_bg)
        )
        dialog.window?.decorView?.setPadding(0, 0, 0, 0)

        var cancelled = false
        btnUndo.setOnClickListener { cancelled = true; dialog.dismiss() }

        val timer = object : CountDownTimer(5_000L, 1_000L) {
            override fun onTick(ms: Long) {
                if (isAdded) tvCountdown.text = ((ms / 1_000L) + 1).toString()
            }
            override fun onFinish() {
                if (!isAdded) return
                tvCountdown.text = "0"
                if (cancelled) return
                dialog.dismiss()
                // Send the remote wipe command
                val requestId = UUID.randomUUID().toString()
                pendingWipeRequestId = requestId
                // Show a confirmation dialog that waits for ACK
                showWipeAwaitingDialog(requestId)
                lifecycleScope.launch(Dispatchers.IO) {
                    sendRemoteWipeRequest(targetOnion, targetWipePwd, requestId)
                }
            }
        }.start()

        dialog.setOnDismissListener { if (!cancelled) timer.cancel() }
        dialog.show()
    }

    private fun showWipeAwaitingDialog(requestId: String) {
        if (!isAdded) return
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_wipe_other_phone, null)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.disappearing_messages_dialog_bg)
        )
        dialog.window?.decorView?.setPadding(0, 0, 0, 0)

        val sectionInput   = dialogView.findViewById<LinearLayout>(R.id.section_input)
        val sectionLoading = dialogView.findViewById<LinearLayout>(R.id.section_loading)
        val sectionInstruction = dialogView.findViewById<TextView>(R.id.wipe_other_desc)
        val sectionResult  = dialogView.findViewById<LinearLayout>(R.id.section_result)
        val tvLoadStatus   = dialogView.findViewById<TextView>(R.id.tv_loading_status)
        val tvResultMsg    = dialogView.findViewById<TextView>(R.id.tv_result_message)
        val tvResultSub    = dialogView.findViewById<TextView>(R.id.tv_result_sub)
        val ivResultIcon   = dialogView.findViewById<ImageView>(R.id.iv_result_icon)
        val btnClose       = dialogView.findViewById<MaterialButton>(R.id.btn_result_close)

        sectionInput.visibility = View.GONE
        sectionLoading.visibility = View.VISIBLE
        sectionResult.visibility = View.GONE
        tvLoadStatus.text = "Sending wipe command..."

        fun showResult(success: Boolean, title: String, sub: String = "") {
            sectionLoading.visibility = View.GONE
            sectionInstruction.visibility = View.GONE
            sectionResult.visibility = View.VISIBLE
            tvResultMsg.text = title
            tvResultMsg.setTextColor(if (success) 0xFF4CAF50.toInt() else 0xFFFF6B6B.toInt())
            ivResultIcon.setColorFilter(if (success) 0xFF4CAF50.toInt() else 0xFFFF6B6B.toInt())
            if (sub.isNotBlank()) { tvResultSub.text = sub; tvResultSub.visibility = View.VISIBLE } else tvResultSub.visibility = View.GONE
        }

        btnClose.setOnClickListener { dialog.dismiss(); onWipeAckReceived = null; pendingWipeRequestId = null }

        // Register callback — invoked by UnionService on main thread
        onWipeAckReceived = { status ->
            if (isAdded) {
                when (status) {
                    "SUCCESS"          -> showResult(true,  "Remote Wipe Successful!", "Target device app data has been wiped.")
                    "NO_WIPE_PASSWORD" -> showResult(false, "Target Has No Wipe Password", "The target user has not set a wipe password.")
                    "WRONG_PASSWORD"   -> showResult(false, "Incorrect Wipe Password", "The wipe password provided does not match.")
                    else               -> showResult(false, "Wipe Failed", "An unknown error occurred.")
                }
            } else {
                dialog.dismiss()
            }
        }

        // Auto-dismiss after 30s if no ACK (timeout)
        Handler(Looper.getMainLooper()).postDelayed({
            if (dialog.isShowing && pendingWipeRequestId == requestId) {
                showResult(false, "No Response", "Target device did not respond. It may be offline.")
                pendingWipeRequestId = null
                onWipeAckReceived = null
            }
        }, 30_000L)

        dialog.show()
    }

    private suspend fun sendRemoteWipeRequest(targetOnion: String, wipePwd: String, requestId: String) {
        try {
            val ctx      = requireContext().applicationContext
            val myOnion  = Prefs.getOnionAddress(ctx) ?: return
            val myName   = Prefs.getName(ctx) ?: ""
            val pwdHash  = sha256(wipePwd)

            val payload = JSONObject()
                .put("action", "REMOTE_WIPE_REQUEST")
                .put("wipePasswordHash", pwdHash)
                .put("requestId", requestId)
                .put("initiatorOnion", myOnion)
                .toString()

            val pubKeyResp = repository.getPublicKey(targetOnion)
            if (!pubKeyResp.isSuccessful || pubKeyResp.body() == null) return
            val recipientPublicKey = pubKeyResp.body()!!.publicKey

            val enc = MessageEncryptionManager.encryptMessage(ctx, recipientPublicKey, payload)

            val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            val adapter = moshi.adapter(PrivateMessageTransportDto::class.java)
            val transport = PrivateMessageTransportDto(
                messageId = requestId,
                msg = enc.encryptedPayload,
                senderOnion = myOnion,
                senderName = myName,
                timestamp = System.currentTimeMillis().toString(),
                type = "REMOTE_WIPE_REQUEST",
                iv = enc.iv,
                senderKeyFingerprint = enc.senderKeyFingerprint,
                recipientKeyFingerprint = enc.recipientKeyFingerprint,
                senderPublicKey = enc.senderPublicKeyBase64
            )

            val json    = adapter.toJson(transport)
            val base64  = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            var circuit = Prefs.getCircuitId(ctx) ?: ""
            if (circuit.isEmpty()) {
                val r = repository.createCircuit()
                if (r.isSuccessful) { circuit = r.body()?.circuitId ?: ""; Prefs.setCircuitId(ctx, circuit) }
            }
            if (circuit.isNotEmpty()) {
                val resp = repository.sendMessage(targetOnion, base64, circuit)
                if (!resp.isSuccessful) {
                    // Retry with fresh circuit
                    val r2 = repository.createCircuit()
                    if (r2.isSuccessful) {
                        circuit = r2.body()?.circuitId ?: ""
                        Prefs.setCircuitId(ctx, circuit)
                        repository.sendMessage(targetOnion, base64, circuit)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendRemoteWipeRequest failed", e)
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun Context.shareText(text: String, subject: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        startActivity(Intent.createChooser(intent, "Share via"))
    }



    private fun setupRealtimeKeyObserver() {
        lifecycleScope.launch {
            val db = AppDb.get(requireContext())
            // CHANGED: Observe ALWAYS (not tied to STARTED state)
            // This ensures periodic rotations in background are detected when fragment becomes visible
            db.keyDao().observeActiveKey().collect { activeKey ->
                // This block executes whenever the active key changes in DB
                // Works even if fragment was paused during rotation

                if (activeKey != null) {
                    // Always display latest activation time
                    updateTimeDisplay(activeKey)
                } else {
                    binding.lastUpdateBadge.text = "Never"
                }
            }
        }
    }


    private fun updateTimeDisplay(activeKey: KeyEntity) {
        val diff = System.currentTimeMillis() - activeKey.activatedAt

        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
        val hours = TimeUnit.MILLISECONDS.toHours(diff)
        val days = TimeUnit.MILLISECONDS.toDays(diff)

        val timeStr = when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            hours < 24 -> "$hours hours ago"
            else -> "$days days ago"
        }
        binding.lastUpdateBadge.text = "Updated $timeStr"
    }
}
