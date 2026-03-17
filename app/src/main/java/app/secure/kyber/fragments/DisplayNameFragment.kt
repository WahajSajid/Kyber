package app.secure.kyber.fragments

import android.content.Intent
import android.os.Bundle
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
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
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
                    // Using a dummy public key for registration as per UnionClient logic
                    val dummyPublicKey = "dGVzdC1wdWJsaWMta2V5LWJhc2U2NC1lbmNvZGVk" 
                    
                    val response = repository.register(licenseKey, dummyPublicKey)
                    if (response.isSuccessful && response.body()?.success == true) {
                        val body = response.body()!!
                        Prefs.setName(requireContext(), name)
                        Prefs.setSessionToken(requireContext(), body.sessionToken)
                        Prefs.setOnionAddress(requireContext(), body.onionAddress)
                        
                        // Create circuit after registration
                        repository.createCircuit().also { circuitResp ->
                            if (circuitResp.isSuccessful) {
                                Prefs.setCircuitId(requireContext(), circuitResp.body()?.circuitId)
                            }
                        }
                        
                        goToMainActivity()
                    } else {
                        val errorMsg = response.body()?.error ?: "Registration failed"
                        Snackbar.make(binding.root, errorMsg, Snackbar.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Snackbar.make(binding.root, "Registration error: ${e.message}", Snackbar.LENGTH_SHORT).show()
                } finally {
                    binding.btnPwd.isEnabled = true
                }
            }
        }
    }

    private fun goToMainActivity() {
        val intent = Intent(activity, MainActivity::class.java)
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
    }
}
