package app.secure.kyber.fragments

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import app.secure.kyber.R
import app.secure.kyber.backend.common.Prefs
import app.secure.kyber.databinding.FragmentSetPasswordBinding
import com.google.android.material.snackbar.Snackbar

class SetPasswordFragment : Fragment( R.layout.fragment_set_password) {

    private lateinit var binding: FragmentSetPasswordBinding

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
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
                findNavController().navigate(R.id.action_setPasswordFragment_to_displayNameFragment)
            }

        }
    }
}