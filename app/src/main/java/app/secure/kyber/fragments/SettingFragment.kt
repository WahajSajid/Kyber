package app.secure.kyber.fragments

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.findNavController
import app.secure.kyber.R
import app.secure.kyber.activities.QrCodeDialog
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.databinding.FragmentSettingBinding
import app.secure.kyber.workers.KeyRotationWorker
import com.google.android.material.switchmaterial.SwitchMaterial


class SettingFragment : Fragment(R.layout.fragment_setting) {

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

        val firstLetter = if (name.isNotEmpty()) name[0] else '?'
        binding.avatarLetter.text = firstLetter.toString()

        setupCardTitles()
        loadSavedValues()
        setupCardListeners()
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
        binding.encryptionTimerCard.cardIcon.setImageResource(R.drawable.encryption_timer_ic)
    }

    /** Reads persisted values and shows them in each card's value label. */
    private fun loadSavedValues() {
        binding.autoLockCard.cardValue.text =
            Prefs.getAutoLockTimeout(requireContext()) ?: "Never"

        binding.disappearingChatCard.cardValue.text =
            Prefs.getDisappearingMessageStatus(requireContext()) ?: "Off"

        // Search Privacy is Coming Soon — always display Off
        binding.searchPrivacyCard.cardValue.text = "Off"

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
        val currentSelection = Prefs.getAutoLockTimeout(requireContext()) ?: "Never"
        refreshAutoLockRadios(dialogView, currentSelection)

        fun select(label: String) {
            Prefs.setAutoLockTimeout(requireContext(), label)
            binding.autoLockCard.cardValue.text = label
            dialog.dismiss()
        }

        dialogView.findViewById<LinearLayout>(R.id.layout_1Minute)
            .setOnClickListener { select("1 Minute") }
        dialogView.findViewById<LinearLayout>(R.id.layout_5Minutes)
            .setOnClickListener { select("5 Minutes") }
        dialogView.findViewById<LinearLayout>(R.id.layout_15Minutes)
            .setOnClickListener { select("15 Minutes") }
        dialogView.findViewById<LinearLayout>(R.id.layout_never)
            .setOnClickListener { select("Never") }

        dialog.show()
    }

    private fun refreshAutoLockRadios(dialogView: View, selected: String) {
        val checked = ContextCompat.getDrawable(requireContext(), R.drawable.radio_checked)
        val unchecked = ContextCompat.getDrawable(requireContext(), R.drawable.radio_unchecked)
        dialogView.findViewById<ImageView>(R.id.radio_1m)
            .setImageDrawable(if (selected == "1 Minute") checked else unchecked)
        dialogView.findViewById<ImageView>(R.id.radio_5m)
            .setImageDrawable(if (selected == "5 Minutes") checked else unchecked)
        dialogView.findViewById<ImageView>(R.id.radio_15m)
            .setImageDrawable(if (selected == "15 Minutes") checked else unchecked)
        dialogView.findViewById<ImageView>(R.id.radio_never)
            .setImageDrawable(if (selected == "Never") checked else unchecked)
    }

    // ──────────────────────────────────────────────────────────────
    // 2) DISAPPEARING MESSAGES DIALOG
    // ──────────────────────────────────────────────────────────────

    private fun showDisappearingMessagesDialog() {
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.disappearing_messages_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.disappearing_messages_dialog_bg)
        )
        dialog.window?.decorView?.setPadding(0, 0, 0, 0)

        val currentSelection = Prefs.getDisappearingMessageStatus(requireContext()) ?: "Off"
        refreshDisappearingRadios(dialogView, currentSelection)

        fun select(label: String) {
            Prefs.setDisappearingMessagesStatus(requireContext(), label)
            binding.disappearingChatCard.cardValue.text = label
            dialog.dismiss()
        }

        dialogView.findViewById<LinearLayout>(R.id.layout_24Hours)
            .setOnClickListener { select("24 Hours") }
        dialogView.findViewById<LinearLayout>(R.id.layout_7Days)
            .setOnClickListener { select("7 Days") }
        dialogView.findViewById<LinearLayout>(R.id.layout_30Days)
            .setOnClickListener { select("30 Days") }
        dialogView.findViewById<LinearLayout>(R.id.layout_always)
            .setOnClickListener { select("Always") }
        dialogView.findViewById<LinearLayout>(R.id.layout_off)
            .setOnClickListener { select("Off") }

        dialog.show()
    }

    private fun refreshDisappearingRadios(dialogView: View, selected: String) {
        val checked = ContextCompat.getDrawable(requireContext(), R.drawable.radio_checked)
        val unchecked = ContextCompat.getDrawable(requireContext(), R.drawable.radio_unchecked)
        dialogView.findViewById<ImageView>(R.id.radio_24h)
            .setImageDrawable(if (selected == "24 Hours") checked else unchecked)
        dialogView.findViewById<ImageView>(R.id.radio_7Days)
            .setImageDrawable(if (selected == "7 Days") checked else unchecked)
        dialogView.findViewById<ImageView>(R.id.radio_30Days)
            .setImageDrawable(if (selected == "30 Days") checked else unchecked)
        dialogView.findViewById<ImageView>(R.id.radio_always)
            .setImageDrawable(if (selected == "Always") checked else unchecked)
        dialogView.findViewById<ImageView>(R.id.radio_off)
            .setImageDrawable(if (selected == "Off") checked else unchecked)
    }

    // ──────────────────────────────────────────────────────────────
    // 3) SEARCH PRIVACY DIALOG  (Coming Soon — no real backend)
    // ──────────────────────────────────────────────────────────────

    private fun showSearchPrivacyDialog() {
        val dialogView = LayoutInflater.from(context)
            .inflate(R.layout.search_privacy_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.disappearing_messages_dialog_bg)
        )
        dialog.window?.decorView?.setPadding(0, 0, 0, 0)

        // Intercept the switch: always revert it and show "Coming Soon"
        val switchPrivacy = dialogView.findViewById<SwitchMaterial>(R.id.switchPrivacy)
//        switchPrivacy.setOnCheckedChangeListener { buttonView, isChecked ->
//            if (isChecked) {
//                // Revert the toggle without triggering another listener cycle
//                buttonView.post { buttonView.isChecked = false }
//                Toast.makeText(requireContext(), "Coming Soon", Toast.LENGTH_SHORT).show()
//            }
//        }

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

    private fun Context.shareText(text: String, subject: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        startActivity(Intent.createChooser(intent, "Share via"))
    }
}
