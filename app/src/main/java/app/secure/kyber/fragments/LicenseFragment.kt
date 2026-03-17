package app.secure.kyber.fragments

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.secure.kyber.R
import app.secure.kyber.backend.KyberRepository
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.databinding.FragmentLicenseBinding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LicenseFragment : Fragment(R.layout.fragment_license) {

    private lateinit var binding: FragmentLicenseBinding

    @Inject
    lateinit var repository: KyberRepository

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentLicenseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setClickListeners()
    }

    private fun setClickListeners() {
        binding.btnPwd.setOnClickListener {
            val key = binding.etPwd.text?.toString()?.trim()
            if (key.isNullOrEmpty()) {
                Snackbar.make(binding.root, "Please enter a valid License Key", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnPwd.isEnabled = false
            lifecycleScope.launch {
                try {
                    val response = repository.validateLicense(key)
                    if (response.isSuccessful && response.body()?.valid == true) {
                        Prefs.setLicense(requireContext(), key)
                        loadFragment(SetPasswordFragment())
                    } else {
                        val errorMsg = response.body()?.message ?: "Invalid license key"
                        Snackbar.make(binding.root, errorMsg, Snackbar.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("#### License Fragment Error ###", e.message, e)
                    Snackbar.make(binding.root, "Connection error", Snackbar.LENGTH_SHORT).show()
                } finally {
                    binding.btnPwd.isEnabled = true
                }
            }
        }
    }

    private fun loadFragment(toFragment: Fragment) {
        val transaction = (context as AppCompatActivity).supportFragmentManager.beginTransaction()
        transaction.replace(R.id.auth_fragment, toFragment)
        transaction.addToBackStack(null)
        transaction.commit()
    }
}
