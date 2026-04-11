package app.secure.kyber.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import app.secure.kyber.MyApp.MyApp
import app.secure.kyber.R
import app.secure.kyber.activities.ValidatePasswordActivity
import app.secure.kyber.backend.KyberRepository
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.databinding.FragmentNetworkBinding
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.roomdb.KeyEntity
import app.secure.kyber.workers.KeyRotationWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.internal.trimSubstring
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class NetworkFragment : Fragment(R.layout.fragment_network) {

    private lateinit var binding: FragmentNetworkBinding
    private lateinit var navController: NavController
    private lateinit var myApp: MyApp
    
    @Inject
    lateinit var repository: KyberRepository

    // REALTIME KEY OBSERVER: Listens to ALL key changes in DB
    // Works regardless of fragment lifecycle or whether app is backgrounded
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentNetworkBinding.inflate(inflater, container, false)

        myApp = requireActivity().application as MyApp

        binding.btnDisconnect.setOnClickListener {

            AlertDialog.Builder(requireContext())
                .setMessage("Are you sure to disconnect?")
                .setPositiveButton("OK") { _, _ ->
                    myApp.isAppLocked = true
                    val intent = Intent(requireContext(), ValidatePasswordActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    requireActivity().finish()
                }
                .setCancelable(false)
                .show()
        }

        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        navController = view.findNavController()
        
        setupRealtimeKeyObserver()  // NEW: Observe key changes in real-time
        startPeriodicTimeRefresh()  // NEW: Update time display every 10 seconds
        setupListeners()
    }

    // NEW METHOD: REAL-TIME KEY OBSERVER (GLOBAL)
    // Flow observer ALWAYS runs (not paused when backgrounded)
    // + manual refresh on resume ensures updates are caught
    private fun setupRealtimeKeyObserver() {
        lifecycleScope.launch {
            val db = AppDb.get(requireContext())
            // CHANGED: Observe ALWAYS (not tied to STARTED state)
            // This ensures periodic rotations in background are detected when fragment becomes visible
            db.keyDao().observeActiveKey().collect { activeKey ->
                // This block executes whenever the active key changes in DB
                // Works even if fragment was paused during rotation
                
                if (activeKey != null) {
                    // Always display latest public key
                    binding.publicKeyField.text = truncatePublicKey(activeKey.publicKey)
                    
                    // Always display latest activation time
                    updateTimeDisplay(activeKey)
                    
                    // Always update button cooldown state
                    updateButtonState(activeKey)
                    
                    android.util.Log.d("NetworkFragment", 
                        "Real-time key update: ${truncatePublicKey(activeKey.publicKey)} " +
                        "activated at ${activeKey.activatedAt} (diff: ${System.currentTimeMillis() - activeKey.activatedAt}ms)")
                } else {
                    binding.publicKeyField.text = "No active key"
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

    // PERIODIC TIME REFRESH: Update time display every 10 seconds
    // This ensures "1 day ago" etc. are accurate and reset on new rotation
    private fun startPeriodicTimeRefresh() {
        lifecycleScope.launch {
            while (isAdded) {
                try {
                    val db = AppDb.get(requireContext())
                    val activeKey = db.keyDao().getActiveKey()
                    if (activeKey != null) {
                        updateTimeDisplay(activeKey)
                    }
                    kotlinx.coroutines.delay(10000)  // Update every 10 seconds
                } catch (e: Exception) {
                    android.util.Log.w("NetworkFragment", "Error in periodic time refresh", e)
                }
            }
        }
    }

    private fun updateButtonState(activeKey: KeyEntity) {
        val prefs = requireContext().getSharedPreferences("key_rotation_prefs", android.content.Context.MODE_PRIVATE)
        val lastManual = prefs.getLong("last_manual_rotation_time", 0L)
        val diffManual = System.currentTimeMillis() - lastManual
        
        val timerMs = Prefs.getEncryptionTimerMs(requireContext())
        val isCooldownActive = timerMs > 0L && lastManual != 0L && diffManual < timerMs
        
        binding.generateButton.isEnabled = !isCooldownActive
//        binding.generateButton.isEnabled = true
        binding.generateButton.alpha = if (isCooldownActive) 0.5f else 1.0f
    }

    private fun setupListeners() {
        binding.generateButton.setOnClickListener {
            performManualRotation()
        }
    }

    private fun performManualRotation() {
        binding.generateButton.isEnabled = false
        lifecycleScope.launch {
            try {
                // SAVE TIMESTAMP BEFORE ROTATION
                // This ensures when Flow observer triggers after DB changes,
                // updateButtonState() sees the cooldown is active
                val prefs = requireContext().getSharedPreferences("key_rotation_prefs", android.content.Context.MODE_PRIVATE)
                prefs.edit().putLong("last_manual_rotation_time", System.currentTimeMillis()).apply()
                
                // Now perform the rotation
                // force=true bypasses the 23h guard since this is an explicit user action
                KeyRotationWorker.rotateKeys(requireContext(), repository, force = true)
                KeyRotationWorker.schedule(requireContext()) // Reset the 24h periodic timer

                // NOTE: No need to manually call setupKeyDisplay() anymore
                // The Flow observer will automatically detect the DB change and update UI
                Toast.makeText(requireContext(), "Keys rotated successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Rotation failed: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.generateButton.isEnabled = true
            }
        }
    }

    private fun truncatePublicKey(key: String, visibleChars: Int = 8): String {
        val trimmedKey = key.trimSubstring(37, key.length - 4)
        if (trimmedKey.length <= visibleChars * 2) return trimmedKey
        val start = trimmedKey.take(visibleChars)
        val end = trimmedKey.takeLast(visibleChars)
        val masked = "*".repeat(28)
        return "$start$masked$end"
    }

    // NOTE: Lifecycle methods with safety refresh for periodic rotations
    // Flow observer runs always, but we also manually refresh on resume as a failsafe
    override fun onResume() {
        super.onResume()
        // ✅ SAFETY REFRESH: If periodic rotation happened while paused, this ensures we show latest data
        // Fetch active key and force UI update
        lifecycleScope.launch {
            try {
                val db = AppDb.get(requireContext())
                val activeKey = db.keyDao().getActiveKey()
                if (activeKey != null) {
                    binding.publicKeyField.text = truncatePublicKey(activeKey.publicKey)
                    updateTimeDisplay(activeKey)
                    updateButtonState(activeKey)
                    android.util.Log.d("NetworkFragment", 
                        "Safety refresh on resume: ${truncatePublicKey(activeKey.publicKey)} " +
                        "activated at ${activeKey.activatedAt}")
                }
            } catch (e: Exception) {
                android.util.Log.e("NetworkFragment", "Error refreshing on resume", e)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // No action needed - Flow observer continues in background
    }
}
