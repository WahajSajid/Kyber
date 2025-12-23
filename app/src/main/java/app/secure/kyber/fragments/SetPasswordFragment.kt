package app.secure.kyber.fragments

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import app.secure.kyber.R
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.databinding.FragmentEncryptMsgPwdBinding
import app.secure.kyber.databinding.FragmentSetPasswordBinding
import com.google.android.material.snackbar.Snackbar
import kotlin.collections.contains

class SetPasswordFragment : Fragment( R.layout.fragment_set_password) {

    private lateinit var binding: FragmentSetPasswordBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentSetPasswordBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setClickListeners()
    }

    private fun setClickListeners() {
        binding.btnPwd.setOnClickListener{

             if(binding.etPwd.text?.toString()?.trim()?.isEmpty() == true){
                Snackbar.make(binding.root, "Please enter password", Snackbar.LENGTH_SHORT).show()

            }
            else if (binding.etPwd.text.toString().trim() != binding.etConfirmPwd.text.toString().trim()){
                 Snackbar.make(binding.root, "Passwords you entered don't match", Snackbar.LENGTH_SHORT).show()
             }
             else{
                Prefs.setPassword(requireContext(),binding.etPwd.text.toString().trim())
                loadFragment(DisplayNameFragment())
            }

        }
    }

    private fun loadFragment(toFragment: Fragment) {
        val transaction = (context as AppCompatActivity).supportFragmentManager.beginTransaction()
        transaction.replace(R.id.auth_fragment,toFragment)
        transaction.addToBackStack(null)
        transaction.commit()
    }

}