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

    // Lockout = 60 seconds
    private val LOCKOUT_DURATION_MS = 60_000L

    // In-app countdown display for the 1-minute lockout
    private var lockoutCountdownTimer: CountDownTimer? = null

    // Wipe dialog / service countdown
    private var wipeCountdownTimer: CountDownTimer? = null
    private var wipeDialog: AlertDialog? = null

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────

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

        // Resume lockout countdown if app was reopened during an active lockout
        resumeLockoutIfActive()

        setClickListeners()
    }

    override fun onDestroyView() {
        lockoutCountdownTimer?.cancel()
        wipeCountdownTimer?.cancel()
        wipeDialog?.dismiss()
        super.onDestroyView()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Resume lockout on app open (persists across app kill via Prefs)
    // ─────────────────────────────────────────────────────────────────────

    private fun resumeLockoutIfActive() {
        val ctx = requireContext()
        val lockoutStart = Prefs.getLockoutStartTime(ctx)
        if (lockoutStart == 0L) return  // no lockout stored

        val elapsed = System.currentTimeMillis() - lockoutStart
        val remaining = LOCKOUT_DURATION_MS - elapsed

        if (remaining <= 0) {
            // Lockout already expired while app was closed — reset silently
            clearLockout(ctx)
        } else {
            // Still in lockout — resume the countdown display
            startLockoutCountdownDisplay(remaining)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Click listeners
    // ─────────────────────────────────────────────────────────────────────

    private fun setClickListeners() {
        binding.btnPwd.setOnClickListener {
            if (!NetworkMonitor.isConnected.value) {
                showOfflineDialog()
                return@setOnClickListener
            }

            val input = binding.etPwd.text.toString().trim()
            val ctx = requireContext()

            // ── Feature 1.1: Check wipe-out password BEFORE anything else ──
            val wipePassword = Prefs.getWipePassword(ctx)
            if (!wipePassword.isNullOrEmpty() && input == wipePassword) {
                startWipeProcess(isAuthorized = true)
                return@setOnClickListener
            }

            // ── Resolve lockout state ──
            val lockoutStart = Prefs.getLockoutStartTime(ctx)
            val inLockout = lockoutStart > 0L &&
                    (System.currentTimeMillis() - lockoutStart) < LOCKOUT_DURATION_MS

            if (inLockout) {
                // ── In lockout window: one more wrong = WIPE ──
                if (Prefs.getPassword(ctx).equals(input)) {
                    // Correct password during lockout → login normally + clear lockout
                    clearLockout(ctx)
                    performLogin()
                } else {
                    // 4th consecutive wrong during lockout → WIPE (no UNDO)
                    clearLockout(ctx)
                    startWipeProcess(isAuthorized = false)
                }
                return@setOnClickListener
            }

            // ── Normal login check ──
            if (Prefs.getPassword(ctx).equals(input)) {
                // Successful login — reset any stale attempt counter
                Prefs.setFailedAttempts(ctx, 0)
                performLogin()
            } else {
                // ── Track failed attempts ──
                val attempts = Prefs.getFailedAttempts(ctx) + 1
                Prefs.setFailedAttempts(ctx, attempts)
                val remaining = 3 - attempts

                when {
                    remaining > 0 -> {
                        Snackbar.make(
                            binding.root,
                            "$remaining attempt${if (remaining != 1) "s" else ""} remaining",
                            Snackbar.LENGTH_SHORT
                        ).show()
                    }
                    else -> {
                        // 3rd wrong — start 1-minute lockout
                        val lockoutStartTime = System.currentTimeMillis()
                        Prefs.setLockoutStartTime(ctx, lockoutStartTime)
                        startLockoutCountdownDisplay(LOCKOUT_DURATION_MS)
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Lockout countdown display (in-app UI only — persistence via Prefs)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Shows the red warning text below the button with a live countdown.
     * Automatically resets the attempt counter when the timer expires.
     */
    private fun startLockoutCountdownDisplay(remainingMs: Long) {
        if (!isAdded) return

        binding.tvLockoutCountdown.visibility = View.VISIBLE

        lockoutCountdownTimer?.cancel()
        lockoutCountdownTimer = object : CountDownTimer(remainingMs, 1_000L) {

            override fun onTick(millisUntilFinished: Long) {
                if (!isAdded) return
                val secondsLeft = (millisUntilFinished / 1_000L).toInt() + 1
                binding.tvLockoutCountdown.text =
                    "Too many attempts.\nTry again in $secondsLeft second${if (secondsLeft != 1) "s" else ""}.\n⚠️ One more wrong attempt will wipe all data."
            }

            override fun onFinish() {
                if (!isAdded) return
                // 1 minute passed — reset counter, hide warning
                clearLockout(requireContext())
                binding.tvLockoutCountdown.visibility = View.GONE
                binding.tvLockoutCountdown.text = ""
                Snackbar.make(
                    binding.root,
                    "Lockout expired. You may try again.",
                    Snackbar.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    /** Clears lockout state from Prefs and resets attempt counter. */
    private fun clearLockout(ctx: android.content.Context) {
        Prefs.setFailedAttempts(ctx, 0)
        Prefs.setLockoutStartTime(ctx, 0L)
        lockoutCountdownTimer?.cancel()
        if (isAdded) {
            binding.tvLockoutCountdown.visibility = View.GONE
            binding.tvLockoutCountdown.text = ""
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Login helper
    // ─────────────────────────────────────────────────────────────────────

    private fun performLogin() {
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

    // ─────────────────────────────────────────────────────────────────────
    // Wipe orchestration (unchanged from previous implementation)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Starts the wipe process:
     * 1. Shows in-app countdown dialog (foreground UX)
     * 2. Starts WipeService foreground service (background/kill persistence)
     *
     * @param isAuthorized true = manual wipe (UNDO shown), false = unauthorized (no UNDO)
     */
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

        wipeCountdownTimer = object : CountDownTimer(10_000L, 1_000L) {
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
        Snackbar.make(binding.root, "Wipe-out cancelled.", Snackbar.LENGTH_SHORT).show()
    }

    // ─────────────────────────────────────────────────────────────────────
    // Existing helpers (UNCHANGED)
    // ─────────────────────────────────────────────────────────────────────

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
}