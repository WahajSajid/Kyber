package app.secure.kyber.fragments

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import app.secure.kyber.R
import app.secure.kyber.Utils.SecureKeyManager
import app.secure.kyber.backend.KyberRepository
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.databinding.FragmentNetworkBinding
import app.secure.kyber.roomdb.AppDb
import app.secure.kyber.workers.KeyRotationWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class NetworkFragment : Fragment(R.layout.fragment_network) {

    private lateinit var binding: FragmentNetworkBinding
    private lateinit var navController: NavController
    
    @Inject
    lateinit var repository: KyberRepository

    private val handler = Handler(Looper.getMainLooper())
    private val updateTimeRunnable = object : Runnable {
        override fun run() {
            updateLastUpdateTime()
            handler.postDelayed(this, 60000) // Update every minute
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentNetworkBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        navController = view.findNavController()
        
        setupKeyDisplay()
        setupListeners()
    }

    private fun setupKeyDisplay() {
        lifecycleScope.launch {
            val db = AppDb.get(requireContext())
            val activeKey = db.keyDao().getActiveKey()

            if (activeKey != null) {
                binding.publicKeyField.text = truncatePublicKey(activeKey.publicKey)
                updateLastUpdateTime()
            } else {
                binding.publicKeyField.text = "No active key"
                binding.lastUpdateBadge.text = "Never"
            }
        }
    }

    private fun updateLastUpdateTime() {
        lifecycleScope.launch {
            val db = AppDb.get(requireContext())
            val activeKey = db.keyDao().getActiveKey() ?: return@launch
            
            val prefs = requireContext().getSharedPreferences("key_rotation_prefs", android.content.Context.MODE_PRIVATE)
            val lastManual = prefs.getLong("last_manual_rotation_time", 0L)
            val diff = System.currentTimeMillis() - activeKey.activatedAt
            val diffManual = System.currentTimeMillis() - lastManual

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
            
            val isCooldownActive = diffManual < TimeUnit.HOURS.toMillis(24) && lastManual != 0L
            binding.generateButton.isEnabled = !isCooldownActive
            binding.generateButton.alpha = if (isCooldownActive) 0.5f else 1.0f
        }
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
                // force=true bypasses the 23h guard since this is an explicit user action
                KeyRotationWorker.rotateKeys(requireContext(), repository, force = true)
                KeyRotationWorker.schedule(requireContext()) // Reset the 24h periodic timer

                // Only stamp the cooldown AFTER rotation succeeds (Fix 3)
                // This ensures a failed rotation doesn't lock the button for 24h
                val prefs = requireContext().getSharedPreferences("key_rotation_prefs", android.content.Context.MODE_PRIVATE)
                prefs.edit().putLong("last_manual_rotation_time", System.currentTimeMillis()).apply()

                setupKeyDisplay()
                Toast.makeText(requireContext(), "Keys rotated successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Rotation failed: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.generateButton.isEnabled = true
            }
        }
    }


    private fun truncatePublicKey(key: String, visibleChars: Int = 4): String {
        if (key.length <= visibleChars * 2) return key
        val start = key.take(visibleChars)
        val end = key.takeLast(visibleChars)
        val masked = "*".repeat(28)
        return "$start$masked$end"
    }


    override fun onResume() {
        super.onResume()
        // Fix 4: Refresh key display in case auto-rotation happened while away
        setupKeyDisplay()
        handler.post(updateTimeRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateTimeRunnable)
    }
}
