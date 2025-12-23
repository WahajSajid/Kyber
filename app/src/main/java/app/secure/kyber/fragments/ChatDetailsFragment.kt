package app.secure.kyber.fragments

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.navigation.NavController
import androidx.navigation.findNavController
import app.secure.kyber.R
import app.secure.kyber.activities.MainActivity
import app.secure.kyber.databinding.DisappearingMessagesDialogBinding
import app.secure.kyber.databinding.FragmentChatBinding
import app.secure.kyber.databinding.FragmentChatDetailsBinding
import kotlin.math.roundToInt


class ChatDetailsFragment : Fragment() {
    private lateinit var binding: FragmentChatDetailsBinding

    private val targetUnionId by lazy {
        requireArguments().getString("contact_id").orEmpty()
    }
    private val contactName by lazy {
        requireArguments().getString("contact_name").orEmpty()
    }

    private lateinit var navController: NavController

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        binding = FragmentChatDetailsBinding.inflate(inflater, container, false)

        navController = requireActivity().findNavController(R.id.main_fragment)


        (requireActivity() as MainActivity).setAppChatUser("Chat Details")
        binding.tvName.text = contactName
        binding.tvHandle.text = targetUnionId
        binding.avatar.text = contactName.first().toString()


        //Setup the click listeners
        binding.disappearingMessagesLayout.setOnClickListener {

            Log.d("### Disappearing Messages Clicked ###", "Yes")


            //Setup the alert dialog
            val disappearingMessagesDialogView =
                LayoutInflater.from(requireContext())
                    .inflate(R.layout.disappearing_messages_dialog, null)
            val disappearingMessagesDialog =
                AlertDialog.Builder(requireContext()).setView(disappearingMessagesDialogView)
                    .create()
            disappearingMessagesDialog.window?.setBackgroundDrawable(
                ContextCompat.getDrawable(
                    requireContext(),
                    R.drawable.disappearing_messages_dialog_bg
                )
            )

            // Remove any default padding the window may add around your content
            disappearingMessagesDialog.window?.decorView?.setPadding(0, 0, 0, 0)
            disappearingMessagesDialog.show()


            //Handle dialog view options click

            //24 Hours Option click
            disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_24h)
                .setOnClickListener {
                    update24HoursOption(disappearingMessagesDialogView, disappearingMessagesDialog)
                }
            disappearingMessagesDialogView.findViewById<LinearLayout>(R.id.layout_24Hours)
                .setOnClickListener {
                    update24HoursOption(disappearingMessagesDialogView, disappearingMessagesDialog)
                }
            disappearingMessagesDialogView.findViewById<TextView>(R.id.opt_24h)
                .setOnClickListener {
                    update24HoursOption(disappearingMessagesDialogView, disappearingMessagesDialog)
                }


            //7 Days Option Click
            disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_7Days)
                .setOnClickListener {
                    update7DaysOption(disappearingMessagesDialogView, disappearingMessagesDialog)
                }
            disappearingMessagesDialogView.findViewById<LinearLayout>(R.id.layout_7Days)
                .setOnClickListener {
                    update7DaysOption(disappearingMessagesDialogView, disappearingMessagesDialog)
                }
            disappearingMessagesDialogView.findViewById<TextView>(R.id.opt_7d)
                .setOnClickListener {
                    update7DaysOption(disappearingMessagesDialogView, disappearingMessagesDialog)
                }


            //30 Days Option Click
            disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_30Days)
                .setOnClickListener {
                    update30DaysOption(disappearingMessagesDialogView, disappearingMessagesDialog)
                }
            disappearingMessagesDialogView.findViewById<LinearLayout>(R.id.layout_30Days)
                .setOnClickListener {
                    update30DaysOption(disappearingMessagesDialogView, disappearingMessagesDialog)
                }
            disappearingMessagesDialogView.findViewById<TextView>(R.id.opt_30d)
                .setOnClickListener {
                    update30DaysOption(disappearingMessagesDialogView, disappearingMessagesDialog)
                }


            //Always option click
            disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_always)
                .setOnClickListener {
                    updateAlwaysOption(disappearingMessagesDialogView, disappearingMessagesDialog)
                }
            disappearingMessagesDialogView.findViewById<LinearLayout>(R.id.layout_always)
                .setOnClickListener {
                    updateAlwaysOption(disappearingMessagesDialogView, disappearingMessagesDialog)
                }
            disappearingMessagesDialogView.findViewById<TextView>(R.id.opt_always)
                .setOnClickListener {
                    updateAlwaysOption(disappearingMessagesDialogView, disappearingMessagesDialog)
                }


            //Off option click
            disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_off)
                .setOnClickListener {
                    updateOffOption(disappearingMessagesDialogView, disappearingMessagesDialog)
                }
            disappearingMessagesDialogView.findViewById<LinearLayout>(R.id.layout_off)
                .setOnClickListener {
                    updateOffOption(disappearingMessagesDialogView, disappearingMessagesDialog)
                }
            disappearingMessagesDialogView.findViewById<TextView>(R.id.opt_off)
                .setOnClickListener {
                    updateOffOption(disappearingMessagesDialogView, disappearingMessagesDialog)
                }

        }

        binding.nicknameLayout.setOnClickListener {
            Log.d("### Nickname layout clicked ###", "Yes")
            //Setup the alert dialog
            val nickNameDialogView =
                LayoutInflater.from(context).inflate(R.layout.nick_name_dialog, null)
            val nickNameDialog = AlertDialog.Builder(context).setView(nickNameDialogView).create()
            nickNameDialog.window?.setBackgroundDrawable(
                ContextCompat.getDrawable(
                    requireContext(),
                    R.drawable.nick_name_dialog_bg
                )
            )

            // Remove any default padding the window may add around your content
            nickNameDialog.window?.decorView?.setPadding(0, 0, 0, 0)
            nickNameDialog.show()

        }

        binding.muteNotificationsLayout.setOnClickListener {

            Log.d("### Mute Notifications Layout clicked ###", "Yes")
            val muteNotificationsDialogView =
                LayoutInflater.from(context).inflate(R.layout.mute_notifications, null)
            val muteNotificationsDialog =
                AlertDialog.Builder(context).setView(muteNotificationsDialogView).create()
            muteNotificationsDialog.window?.setBackgroundDrawable(
                ContextCompat.getDrawable(
                    requireContext(),
                    R.drawable.mute_notifications_dialog_bg
                )
            )

            // Remove any default padding the window may add around your content
            muteNotificationsDialog.window?.decorView?.setPadding(0, 0, 0, 0)
            muteNotificationsDialog.show()
        }


        val args = bundleOf(
            "name" to contactName
        )
        //Shared Media Options click listeners
        binding.sharedMediaLayout.setOnClickListener {
            navController.navigate(R.id.action_chatDetailsFragment_to_sharedMediaFragment, args)
        }
        binding.sharedMediaNavigateButton.setOnClickListener {
            navController.navigate(R.id.action_chatDetailsFragment_to_sharedMediaFragment, args)
        }


        //Block Options Click listeners
        binding.blockLayout.setOnClickListener {
            showBlockDialog()
        }
        binding.blockText.setOnClickListener {
            showBlockDialog()
        }


        //Wipe Chat Options click listeners
        binding.wipeChatLayout.setOnClickListener {
            showWipeChatDialog()
        }
        binding.wipeChatText.setOnClickListener {
            showWipeChatDialog()
        }


        return binding.root
    }


    private fun showBlockDialog() {
        //Setup the alert dialog
        val blockDialogView =
            LayoutInflater.from(context).inflate(R.layout.block_dialog, null)
        val blockDialog = AlertDialog.Builder(context).setView(blockDialogView).create()
        blockDialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(
                requireContext(),
                R.drawable.block_dialog_bg
            )
        )

        // Remove any default padding the window may add around your content
        blockDialog.window?.decorView?.setPadding(0, 0, 0, 0)
        blockDialog.show()
    }

    private fun showWipeChatDialog() {
        //Setup the alert dialog
        val wipeChatDialogView =
            LayoutInflater.from(context).inflate(R.layout.wipe_chat_dialog, null)
        val wipeChatDialog = AlertDialog.Builder(context).setView(wipeChatDialogView).create()
        wipeChatDialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(
                requireContext(),
                R.drawable.wipe_chat_bg
            )
        )

        // Remove any default padding the window may add around your content
        wipeChatDialog.window?.decorView?.setPadding(0, 0, 0, 0)
        wipeChatDialog.show()
    }


    fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
        ).roundToInt()
    }


    private fun update24HoursOption(
        disappearingMessagesDialogView: View,
        disappearingMessagesDialog: AlertDialog
    ) {
        // Update the disappearing messages state
        binding.disappearingMessagesState.text = "24 Hours"


        //update the button drawables
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_24h)
            .setImageResource(R.drawable.radio_checked)
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_7Days)
            .setImageResource(R.drawable.radio_unchecked)
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_30Days)
            .setImageResource(R.drawable.radio_unchecked)
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_always)
            .setImageResource(R.drawable.radio_unchecked)
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_off)
            .setImageResource(R.drawable.radio_unchecked)

        disappearingMessagesDialog.dismiss()
    }

    private fun update7DaysOption(
        disappearingMessagesDialogView: View,
        disappearingMessagesDialog: AlertDialog
    ) {
        // Update the disappearing messages state
        binding.disappearingMessagesState.text = "7 Days"


        //update the button drawables
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_24h)
            .setImageResource(R.drawable.radio_unchecked)
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_7Days)
            .setImageResource(R.drawable.radio_checked)
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_30Days)
            .setImageResource(R.drawable.radio_unchecked)
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_always)
            .setImageResource(R.drawable.radio_unchecked)
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_off)
            .setImageResource(R.drawable.radio_unchecked)

        disappearingMessagesDialog.dismiss()
    }


    private fun update30DaysOption(
        disappearingMessagesDialogView: View,
        disappearingMessagesDialog: AlertDialog
    ) {
        // Update the disappearing messages state
        binding.disappearingMessagesState.text = "30 Days"


        //update the button drawables
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_24h)
            .setImageResource(R.drawable.radio_unchecked)
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_7Days)
            .setImageResource(R.drawable.radio_unchecked)
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_30Days)
            .setImageResource(R.drawable.radio_checked)
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_always)
            .setImageResource(R.drawable.radio_unchecked)
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_off)
            .setImageResource(R.drawable.radio_unchecked)

        disappearingMessagesDialog.dismiss()
    }

    private fun updateAlwaysOption(
        disappearingMessagesDialogView: View,
        disappearingMessagesDialog: AlertDialog
    ) {
        // Update the disappearing messages state
        binding.disappearingMessagesState.text = "Always"


        //update the button drawables
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_24h)
            .setImageResource(R.drawable.radio_unchecked)
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_7Days)
            .setImageResource(R.drawable.radio_unchecked)
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_30Days)
            .setImageResource(R.drawable.radio_unchecked)
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_always)
            .setImageResource(R.drawable.radio_checked)
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_off)
            .setImageResource(R.drawable.radio_unchecked)

        disappearingMessagesDialog.dismiss()
    }

    private fun updateOffOption(
        disappearingMessagesDialogView: View,
        disappearingMessagesDialog: AlertDialog
    ) {
        // Update the disappearing messages state
        binding.disappearingMessagesState.text = "Off"


        //update the button drawables
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_24h)
            .setImageResource(R.drawable.radio_unchecked)
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_7Days)
            .setImageResource(R.drawable.radio_unchecked)
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_30Days)
            .setImageResource(R.drawable.radio_unchecked)
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_always)
            .setImageResource(R.drawable.radio_unchecked)
        disappearingMessagesDialogView.findViewById<ImageButton>(R.id.radio_off)
            .setImageResource(R.drawable.radio_checked)

        disappearingMessagesDialog.dismiss()
    }

}