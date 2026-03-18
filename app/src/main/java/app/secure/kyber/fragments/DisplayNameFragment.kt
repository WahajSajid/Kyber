package app.secure.kyber.fragments

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import app.secure.kyber.R
import app.secure.kyber.activities.MainActivity
import app.secure.kyber.backend.KyberRepository
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.databinding.FragmentDisplayNameBinding
import app.secure.kyber.onionrouting.UnionClient
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject

@AndroidEntryPoint
class DisplayNameFragment : Fragment(R.layout.fragment_display_name) {

    private lateinit var binding: FragmentDisplayNameBinding

    @Inject
    lateinit var repository: KyberRepository

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentDisplayNameBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setClickListeners()
    }

    private fun setClickListeners() {
        binding.btnPwd.setOnClickListener {
            val name = binding.etPwd.text?.toString()?.trim()
            if (name.isNullOrEmpty()) {
                Snackbar.make(binding.root, "Please enter a display name", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnPwd.isEnabled = false
            lifecycleScope.launch {
                try {
                    val licenseKey = Prefs.getLicense(requireContext()) ?: ""

                    // 1. Generate unique identity using UnionClient
                    val client = UnionClient()
                    val identity = client.exportIdentity()
                    val onionAddress = identity["onionAddress"] ?: ""
                    val publicKeyHex = identity["publicKey"] ?: ""
                    val publicKeyBase64 = identity["publicKeyBase64"] ?: ""

                    // 2. Hash the display name for the discovery service (API expects Base64)
                    val nameHash = hashUsername(name)
                    
                    // 3. Register using the Discovery endpoint since we are providing a username hash
                    // API expects Base64 encoded public key
                    val response = repository.registerDiscovery(
                        licenseKey = licenseKey,
                        publicKey = publicKeyBase64,
                        usernameHash = nameHash,
                        usernameEncrypted = "" // Placeholder
                    )

                    if (response.isSuccessful && response.body()?.success == true) {
                        val body = response.body()!!

                        // 4. Save all identity info to Prefs
                        Prefs.setName(requireContext(), name)
                        Prefs.setOnionAddress(requireContext(), onionAddress)
                        Prefs.setPublicKey(requireContext(), publicKeyHex)
                        Prefs.setSessionToken(requireContext(), body.sessionToken)
                        
                        // Backend might return a different onion address or session info
                        body.onionAddress?.let { Prefs.setOnionAddress(requireContext(), it) }

                        // Create initial circuit after registration
                        val circuitResp = repository.createCircuit()
                        if (circuitResp.isSuccessful) {
                            Prefs.setCircuitId(
                                requireContext(),
                                circuitResp.body()?.circuitId
                            )
                        }

                        goToMainActivity()
                    } else {
                        val errorMsg = response.body()?.error ?: "Registration failed"
                        Log.e("### Registration Error ###", errorMsg)
                        Snackbar.make(binding.root, errorMsg, Snackbar.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("DisplayNameFragment", "Error: ${e.message}", e)
                    Snackbar.make(
                        binding.root,
                        "Registration error: ${e.message}",
                        Snackbar.LENGTH_SHORT
                    ).show()
                } finally {
                    binding.btnPwd.isEnabled = true
                }
            }
        }
    }

    /**
     * Hashes username using SHA-256 and encodes to Base64 as required by the backend API.
     */
    private fun hashUsername(name: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(name.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(hashBytes, Base64.NO_WRAP)
    }

    private fun goToMainActivity() {
        val intent = Intent(activity, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
    }
}
