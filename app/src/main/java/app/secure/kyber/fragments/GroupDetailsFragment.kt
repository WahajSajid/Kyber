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

        binding.disappearingMessagesState.text =
            Prefs.getDisappearingMessageStatus(requireContext())
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
        val dialogView =
            LayoutInflater.from(context).inflate(R.layout.disappearing_messages_dialog, null)
        val dialog = AlertDialog.Builder(context).setView(dialogView).create()
        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(
                requireContext(),
                R.drawable.disappearing_messages_dialog_bg
            )
        )
        dialog.window?.decorView?.setPadding(0, 0, 0, 0)
        dialog.show()

//        val options = mapOf(
//            R.id.layout_24Hours to ("24 Hours" to R.id.radio_24h),
//            R.id.layout_7Days to ("7 Days" to R.id.radio_7Days),
//            R.id.layout_30Days to ("30 Days" to R.id.radio_30Days),
//            R.id.layout_always to ("Always" to R.id.radio_always),
//            R.id.layout_off to ("Off" to R.id.radio_off)
//        )

//        val radioButtons = options.values.map { dialogView.findViewById<ImageView>(it.second) }
//
//        updateSelectionDisappearingMessages(radioButtons)
//
//        options.forEach { (layoutId, pair) ->
//            dialogView.findViewById<LinearLayout>(layoutId).setOnClickListener {
//                Prefs.setDisappearingMessagesStatus(requireContext(), pair.first)
//                updateSelection(pair.second, radioButtons, dialog)
//                binding.disappearingMessagesState.text = pair.first
//            }
//        }
    }


    fun updateSelectionDisappearingMessages(radioButtons: List<ImageView>) {

        val status = Prefs.getDisappearingMessageStatus(requireContext())
        when (status) {
            "24 Hours" -> {
                radioButtons[0].setImageResource(R.drawable.radio_checked)
                radioButtons[1].setImageResource(R.drawable.radio_unchecked)
                radioButtons[2].setImageResource(R.drawable.radio_unchecked)
                radioButtons[3].setImageResource(R.drawable.radio_unchecked)
                radioButtons[4].setImageResource(R.drawable.radio_unchecked)
            }

            "7 Days" -> {
                radioButtons[0].setImageResource(R.drawable.radio_unchecked)
                radioButtons[1].setImageResource(R.drawable.radio_checked)
                radioButtons[2].setImageResource(R.drawable.radio_unchecked)
                radioButtons[3].setImageResource(R.drawable.radio_unchecked)
                radioButtons[4].setImageResource(R.drawable.radio_unchecked)
            }

            "30 Days" -> {
                radioButtons[0].setImageResource(R.drawable.radio_unchecked)
                radioButtons[1].setImageResource(R.drawable.radio_unchecked)
                radioButtons[2].setImageResource(R.drawable.radio_checked)
                radioButtons[3].setImageResource(R.drawable.radio_unchecked)
                radioButtons[4].setImageResource(R.drawable.radio_unchecked)
            }

            "Always" -> {
                radioButtons[0].setImageResource(R.drawable.radio_unchecked)
                radioButtons[1].setImageResource(R.drawable.radio_unchecked)
                radioButtons[2].setImageResource(R.drawable.radio_unchecked)
                radioButtons[3].setImageResource(R.drawable.radio_checked)
                radioButtons[4].setImageResource(R.drawable.radio_unchecked)
            }

            "Off" -> {
                radioButtons[0].setImageResource(R.drawable.radio_unchecked)
                radioButtons[1].setImageResource(R.drawable.radio_unchecked)
                radioButtons[2].setImageResource(R.drawable.radio_unchecked)
                radioButtons[3].setImageResource(R.drawable.radio_unchecked)
                radioButtons[4].setImageResource(R.drawable.radio_checked)
            }

            else -> {
                radioButtons[0].setImageResource(R.drawable.radio_unchecked)
                radioButtons[1].setImageResource(R.drawable.radio_unchecked)
                radioButtons[2].setImageResource(R.drawable.radio_unchecked)
                radioButtons[3].setImageResource(R.drawable.radio_unchecked)
                radioButtons[4].setImageResource(R.drawable.radio_checked)
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