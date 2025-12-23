package app.secure.kyber.fragments

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.secure.kyber.R
import app.secure.kyber.activities.MainActivity
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.databinding.FragmentEncryptMsgPwdBinding
import com.google.android.material.snackbar.Snackbar

class EncryptMsgPwdFragment :  Fragment( R.layout.fragment_encrypt_msg_pwd) {

    private lateinit var binding: FragmentEncryptMsgPwdBinding
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
        setClickListeners()

    }

    private fun setClickListeners() {
        binding.btnPwd.setOnClickListener{
            if(Prefs.getPassword(requireContext()).equals(binding.etPwd.text.toString().trim())){
                goToMainActivity()
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
}