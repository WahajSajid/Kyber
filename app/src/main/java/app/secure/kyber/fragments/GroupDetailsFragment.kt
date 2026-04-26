package app.secure.kyber.fragments

import android.annotation.SuppressLint
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
import androidx.navigation.NavController
import androidx.navigation.findNavController
import app.secure.kyber.R
import app.secure.kyber.activities.MainActivity
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.databinding.FragmentChatDetailsBinding
import app.secure.kyber.databinding.FragmentGroupDetailsBinding
import kotlin.collections.forEach

class GroupDetailsFragment : Fragment() {

    private lateinit var binding: FragmentGroupDetailsBinding
    private lateinit var navController: NavController

    private val groupName by lazy {
        requireArguments().getString("group_name").orEmpty()
    }

    private val noOfMembers by lazy {
        requireArguments().getString("no_of_members").orEmpty()
    }

    private val creationDate by lazy {
        requireArguments().getString("creation_date").orEmpty()
    }

    private val groupId by lazy {
        requireArguments().getString("group_id").orEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        binding = FragmentGroupDetailsBinding.inflate(inflater, container, false)


        navController = requireActivity().findNavController(R.id.main_fragment)

        (requireActivity() as MainActivity).setAppChatUser("Group Info")
        binding.tvName.text = groupName
        binding.avatar.text = groupName.first().toString()
        binding.noOfMembers.text = "$noOfMembers Members"
        binding.groupCreationDate.text = creationDate
        binding.noOfMembersText.text = "$noOfMembers Members"

        val status = Prefs.getChatSpecificDisappearingStatus(requireContext(), groupId) 
            ?: Prefs.getDisappearingMessageStatus(requireContext())
        binding.disappearingMessagesState.text = status
        binding.muteNotificationsStatus.text = Prefs.getMuteNotificationStatus(requireContext())


        binding.disappearingMessagesLayout.setOnClickListener {
            showDisappearingMessagesDialog()
        }

        binding.nicknameLayout.setOnClickListener {
            showNicknameDialog()
        }

        binding.muteNotificationsLayout.setOnClickListener {
            showMuteNotificationsDialog()
        }

        val args = bundleOf("name" to "Group")
        binding.sharedMediaLayout.setOnClickListener {
            navController.navigate(R.id.action_groupDetailsFragment_to_sharedMediaFragment, args)
        }
        binding.sharedMediaNavigateButton.setOnClickListener {
            navController.navigate(R.id.action_groupDetailsFragment_to_sharedMediaFragment, args)
        }

        binding.blockLayout.setOnClickListener { showBlockDialog() }
        binding.blockText.setOnClickListener { showBlockDialog() }

        binding.wipeChatLayout.setOnClickListener { showWipeChatDialog() }
        binding.wipeChatText.setOnClickListener { showWipeChatDialog() }

        return binding.root
    }


    private fun showDisappearingMessagesDialog() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.disappearing_messages_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.disappearing_messages_dialog_bg))
        dialog.window?.decorView?.setPadding(0, 0, 0, 0)
        
        val currentStatus = Prefs.getChatSpecificDisappearingStatus(requireContext(), groupId) 
            ?: Prefs.getDisappearingMessageStatus(requireContext()) ?: "5 Minutes"
        
        val radioButtons = listOf(
            dialogView.findViewById<ImageView>(R.id.radio_5m),
            dialogView.findViewById<ImageView>(R.id.radio_15m),
            dialogView.findViewById<ImageView>(R.id.radio_1h),
            dialogView.findViewById<ImageView>(R.id.radio_1d),
            dialogView.findViewById<ImageView>(R.id.radio_2d)
        )
        
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
            Prefs.setChatSpecificDisappearingStatus(requireContext(), groupId, label)
            binding.disappearingMessagesState.text = label
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
//            dialogView.findViewById<ImageButton>(selectedRadioId).setImageResource(R.drawable.radio_checked)
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
        dialog.show()
    }

}