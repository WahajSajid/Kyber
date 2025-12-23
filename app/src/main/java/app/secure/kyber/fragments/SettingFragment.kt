package app.secure.kyber.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.NavController
import androidx.navigation.findNavController
import app.secure.kyber.R
import app.secure.kyber.activities.QrCodeDialog
import app.secure.kyber.activities.QrCodeGenerator
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.databinding.FragmentDisplayNameBinding
import app.secure.kyber.databinding.FragmentSettingBinding


class SettingFragment : Fragment(R.layout.fragment_setting) {


    private lateinit var binding: FragmentSettingBinding
    private lateinit var navController : NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentSettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        navController = requireView().findNavController()

        val name = Prefs.getName(requireContext()).toString()
        val unionId = Prefs.getUnionId(requireContext()).toString()

        binding.tvName.text = unionId
        binding.tvNameDis.text = name

        binding.btnQR.setOnClickListener {
            QrCodeDialog.showQrDialog(requireContext(), unionId, name)
        }

        binding.btnShare.setOnClickListener {
            requireContext().shareText(unionId, subject = "Please use my this Id to add me on Kyber Chat")
        }

       val firstLetter = name[0]
        binding.avatarLetter.text = firstLetter.toString()


        //Set the card content dynamically

        //Auto Lock Card
        binding.autoLockCard.cardTitle.text = "Auto Lock"
        binding.autoLockCard.cardIcon.setImageResource(R.drawable.lock_ic)
        binding.autoLockCard.cardValue.text = "1 Minute"

        //Disappearing Chat Card
        binding.disappearingChatCard.cardTitle.text = "Disappearing Chat"
        binding.disappearingChatCard.cardIcon.setImageResource(R.drawable.disappearing_messages_icon)
        binding.disappearingChatCard.cardValue.text = "Off"

        //Search Privacy Card
        binding.searchPrivacyCard.cardTitle.text = "Search Privacy"
        binding.searchPrivacyCard.cardIcon.setImageResource(R.drawable.eye_off)
        binding.searchPrivacyCard.cardValue.text = "Off"

        //Encryption Timer Card
        binding.encryptionTimerCard.cardTitle.text = "Encryption Timer"
        binding.encryptionTimerCard.cardIcon.setImageResource(R.drawable.encryption_timer_ic)
        binding.encryptionTimerCard.cardValue.text = "24 Hours"




    }

    fun Context.shareText(text: String, subject: String? = null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        startActivity(Intent.createChooser(intent, "Share via"))
    }
}