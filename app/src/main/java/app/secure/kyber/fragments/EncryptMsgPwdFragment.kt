package app.secure.kyber.fragments

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.secure.kyber.R
import app.secure.kyber.activities.MainActivity
import app.secure.kyber.Utils.NetworkMonitor
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.databinding.FragmentEncryptMsgPwdBinding
import com.google.android.material.snackbar.Snackbar
import app.secure.kyber.MyApp.MyApp

class EncryptMsgPwdFragment :  Fragment( R.layout.fragment_encrypt_msg_pwd) {

    private lateinit var binding: FragmentEncryptMsgPwdBinding
    private var shownOfflineDialog = false
//    private lateinit var navController : NavController

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
//        navController = requireParentFragment().findNavController()
        if (!NetworkMonitor.isConnected.value) {
            showOfflineDialog()
        }
        setClickListeners()

    }

    private fun setClickListeners() {
        binding.btnPwd.setOnClickListener{
            if (!NetworkMonitor.isConnected.value) {
                showOfflineDialog()
                return@setOnClickListener
            }

            if(Prefs.getPassword(requireContext()).equals(binding.etPwd.text.toString().trim())){
                val myApp = requireActivity().application as MyApp
                myApp.isAppLocked = false
                myApp.lastBackgroundTime = System.currentTimeMillis()
                if (activity is MainActivity) {
                    (activity as MainActivity).lastInteractionTime = System.currentTimeMillis()
                    // Just pop back in MainActivity instead of restarting to prevent losing state
                    requireActivity().supportFragmentManager.popBackStack()
                } else {
                    goToMainActivity()
                }
            }else{
                Snackbar.make(binding.root, "Please enter correct password", Snackbar.LENGTH_SHORT).show()
            }
        }
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
            .setMessage("You are not connected to the network")
            .setPositiveButton("OK") { _, _ ->
                shownOfflineDialog = false
            }
            .setOnDismissListener {
                shownOfflineDialog = false
            }
            .show()
    }
}