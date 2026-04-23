package app.secure.kyber.fragments

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import app.secure.kyber.R
import app.secure.kyber.activities.MainActivity
import app.secure.kyber.Utils.NetworkMonitor
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.databinding.FragmentEncryptMsgPwdBinding
import app.secure.kyber.services.WipeService
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import app.secure.kyber.MyApp.MyApp

class EncryptMsgPwdFragment : Fragment(R.layout.fragment_encrypt_msg_pwd) {

    private lateinit var binding: FragmentEncryptMsgPwdBinding
    private var shownOfflineDialog = false

    // Wipe dialog / service countdown
    private var wipeCountdownTimer: CountDownTimer? = null
    private var wipeDialog: AlertDialog? = null

    // Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentEncryptMsgPwdBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!NetworkMonitor.isConnected.value) {
            showOfflineDialog()
        }

        setClickListeners()
        restoreAttemptWarningIfNeeded()
    }

    override fun onDestroyView() {
        wipeCountdownTimer?.cancel()
        wipeDialog?.dismiss()
        super.onDestroyView()
    }

    // Click listeners

    private fun setClickListeners() {
        binding.btnPwd.setOnClickListener {
            hideKeyboard()

            if (!NetworkMonitor.isConnected.value) {
                showOfflineDialog()
                return@setOnClickListener
            }

            val input = binding.etPwd.text.toString().trim()
            val ctx = requireContext()

            // Check wipe-out password BEFORE anything else
            val wipePassword = Prefs.getWipePassword(ctx)
            if (!wipePassword.isNullOrEmpty() && input == wipePassword) {
                startWipeProcess(isAuthorized = true)
                return@setOnClickListener
            }

            // Normal login check
            if (Prefs.getPassword(ctx).equals(input)) {
                // Successful login reset attempt counter and login
                Prefs.setFailedAttempts(ctx, 0)
                performLogin()
            } else {
                // Track failed attempts
                val attempts = Prefs.getFailedAttempts(ctx) + 1
                Prefs.setFailedAttempts(ctx, attempts)

                when (attempts) {
                    1 -> {
                        binding.tvLockoutCountdown.visibility = View.VISIBLE
                        binding.tvLockoutCountdown.text = "2 login attempts remaining"
                    }
                    2 -> {
                        binding.tvLockoutCountdown.visibility = View.VISIBLE
                        binding.tvLockoutCountdown.text = "1 login attempt remaining"
                    }
                    3 -> {
                        binding.tvLockoutCountdown.visibility = View.VISIBLE
                        binding.tvLockoutCountdown.text = "⚠️ Too many wrong attempts. One more wrong attempt will wipe out the whole app"
                    }
                    else -> {
                        // 4th wrong attempt — WIPE (no UNDO)
                        startWipeProcess(isAuthorized = false)
                    }
                }
            }
        }
    }

    // Login helper

    private fun performLogin() {
        Prefs.setFailedAttempts(requireContext(), 0)
        binding.tvLockoutCountdown.visibility = View.GONE
        binding.tvLockoutCountdown.text = ""

        val myApp = requireActivity().application as MyApp
        myApp.isAppLocked = false
        myApp.lastBackgroundTime = System.currentTimeMillis()
        if (activity is MainActivity) {
            (activity as MainActivity).lastInteractionTime = System.currentTimeMillis()
            requireActivity().supportFragmentManager.popBackStack()
        } else {
            goToMainActivity()
        }
    }

    // Wipe orchestration

    private fun startWipeProcess(isAuthorized: Boolean) {
        startWipeService(isAuthorized)
        showInAppWipeDialog(isAuthorized)
    }

    private fun startWipeService(isAuthorized: Boolean) {
        val ctx = requireContext().applicationContext
        Prefs.setWipePending(ctx, true)
        val intent = Intent(ctx, WipeService::class.java).apply {
            putExtra(WipeService.EXTRA_AUTHORIZED, isAuthorized)
        }
        ctx.startForegroundService(intent)
    }

    private fun showInAppWipeDialog(isAuthorized: Boolean) {
        if (!isAdded) return

        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.wipe_timer_dialog, null)

        val tvMessage   = dialogView.findViewById<TextView>(R.id.wipe_timer_message)
        val tvCountdown = dialogView.findViewById<TextView>(R.id.wipe_timer_countdown)
        val btnUndo     = dialogView.findViewById<MaterialButton>(R.id.btn_wipe_undo)

        tvMessage.text = if (isAuthorized)
            "App data will be wiped out"
        else
            "App is being wiped due to unauthorized access"

        btnUndo.visibility = if (isAuthorized) View.VISIBLE else View.GONE

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawable(
            ContextCompat.getDrawable(requireContext(), R.drawable.disappearing_messages_dialog_bg)
        )
        dialog.window?.decorView?.setPadding(0, 0, 0, 0)
        wipeDialog = dialog

        if (isAuthorized) {
            btnUndo.setOnClickListener {
                cancelInAppWipe(dialog)
            }
        }

        dialog.show()

        wipeCountdownTimer = object : CountDownTimer(5_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1_000L).toInt() + 1
                if (isAdded) tvCountdown.text = seconds.toString()
            }

            override fun onFinish() {
                tvCountdown.text = "0"
                dialog.dismiss()
            }
        }.start()
    }

    private fun cancelInAppWipe(dialog: AlertDialog) {
        wipeCountdownTimer?.cancel()
        dialog.dismiss()
        val cancelIntent = Intent(requireContext(), WipeService::class.java).apply {
            action = WipeService.ACTION_CANCEL_WIPE
        }
        requireContext().startService(cancelIntent)
        showSnackbar("Wipe-out cancelled.")
    }

    private fun goToMainActivity() {
        val intent = Intent(activity, MainActivity::class.java)
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
    }

    private fun showOfflineDialog() {
        if (shownOfflineDialog || !isAdded) return
        shownOfflineDialog = true
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setMessage("Connection cannot be established")
            .setPositiveButton("OK") { _, _ ->
                shownOfflineDialog = false
            }
            .setOnDismissListener {
                shownOfflineDialog = false
            }
            .show()
    }

    private fun showSnackbar(message: String) {
        val snackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT)
        snackbar.anchorView = binding.etPwd
        snackbar.show()
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }


    private fun restoreAttemptWarningIfNeeded() {
        val attempts = Prefs.getFailedAttempts(requireContext())
        if (attempts <= 0) return

        val message = when (attempts) {
            1 -> "2 login attempts remaining"
            2 -> "1 login attempt remaining"
            else -> "⚠️ Too many wrong attempts. One more wrong attempt will wipe out the whole app"
        }

        binding.tvLockoutCountdown.visibility = View.VISIBLE
        binding.tvLockoutCountdown.text = message
    }
}