package app.secure.kyber.fragments

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.NavController
import androidx.navigation.findNavController
import app.secure.kyber.R
import app.secure.kyber.activities.MainActivity
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.databinding.FragmentDisplayNameBinding
import app.secure.kyber.databinding.FragmentLicenseBinding
import com.google.android.material.snackbar.Snackbar
import kotlin.toString

class DisplayNameFragment : Fragment(R.layout.fragment_display_name) {


    private lateinit var binding: FragmentDisplayNameBinding
    private lateinit var navController : NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentDisplayNameBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        navController = requireView().findNavController()
        setClickListeners()

    }

    private fun setClickListeners() {
        binding.btnPwd.setOnClickListener{

            if(binding.etPwd.text?.toString()?.trim()?.isEmpty() == true){
                Snackbar.make(binding.root, "Please enter a display name", Snackbar.LENGTH_SHORT).show()
            }else{
                Prefs.setName(requireContext(),binding.etPwd.text.toString().trim())
                goToMainActivity()
            }

        }
    }

    private fun goToMainActivity() {
        val intent = Intent(activity, MainActivity::class.java)
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
    }
}