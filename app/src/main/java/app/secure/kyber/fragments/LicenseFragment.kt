package app.secure.kyber.fragments

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import app.secure.kyber.R
import app.secure.kyber.activities.MainActivity
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.databinding.FragmentLicenseBinding
import com.google.android.material.snackbar.Snackbar

class LicenseFragment :  Fragment( R.layout.fragment_license) {

    private lateinit var binding: FragmentLicenseBinding
//    private lateinit var navController : NavController

    private val allowed = listOf("LbW42dQloM8cU7yZ", "DgW42dplov8cU5Ac", "eDQ32dQloM8cG3iP","tFQ33dWlov8cGrYO")
    private val allowedSet = allowed.map { it.trim() }.toSet()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentLicenseBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
//        val navController = Navigation.findNavController(requireActivity(), R.id.activity_login_graph)


        setClickListeners()

    }

//    private fun setClickListeners() {
//        binding.btnPwd.setOnClickListener{
//            goToMainActivity()
//        }
//    }

    private fun setClickListeners() {
        binding.btnPwd.setOnClickListener{

            val txt = binding.etPwd.text?.toString()?.trim()

            val isMatch = txt?.isNotEmpty() == true && txt in allowedSet


            if(binding.etPwd.text?.trim()?.isEmpty() == true || !isMatch){
                 Snackbar.make(binding.root, "Please enter a valid License Key", Snackbar.LENGTH_SHORT).show()

            }else{
                Prefs.setLicense(requireContext(),txt)
                loadFragment(SetPasswordFragment())
            }

        }
    }

    private fun goToMainActivity() {
        val intent = Intent(activity, MainActivity::class.java)
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
    }

    private fun loadFragment(toFragment: Fragment) {
        val transaction = (context as AppCompatActivity).supportFragmentManager.beginTransaction()
        transaction.replace(R.id.auth_fragment,toFragment)
        transaction.addToBackStack(null)
        transaction.commit()
    }
}
