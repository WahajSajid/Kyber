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
import android.text.Editable
import android.text.TextWatcher
import android.graphics.Color
import app.secure.kyber.utils.PasswordValidator
import app.secure.kyber.utils.PasswordStrength

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
        setupPasswordValidation()
        setClickListeners()
    }

    private fun setupPasswordValidation() {
        // Initial state
        binding.btnPwd.isEnabled = false
        binding.btnPwd.alpha = 0.5f

        binding.etPwd.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                val password = s?.toString() ?: ""
                
                if (password.isEmpty()) {
                    binding.tvPasswordStrength.visibility = View.GONE
                    binding.llRequirements.visibility = View.GONE
                    binding.btnPwd.isEnabled = false
                    binding.btnPwd.alpha = 0.5f
                    return
                }

                binding.tvPasswordStrength.visibility = View.VISIBLE
                binding.llRequirements.visibility = View.VISIBLE

                val result = PasswordValidator.validatePassword(password)

                // Update UI based on strength
                when (result.strength) {
                    PasswordStrength.WEAK -> {
                        binding.tvPasswordStrength.text = "Password Strength: Weak"
                        binding.tvPasswordStrength.setTextColor(Color.parseColor("#F44336")) // Red
                    }
                    PasswordStrength.MEDIUM -> {
                        binding.tvPasswordStrength.text = "Password Strength: Medium"
                        binding.tvPasswordStrength.setTextColor(Color.parseColor("#FFEB3B")) // Yellow
                    }
                    PasswordStrength.STRONG -> {
                        binding.tvPasswordStrength.text = "Password Strength: Strong"
                        binding.tvPasswordStrength.setTextColor(Color.parseColor("#4CAF50")) // Green
                    }
                }

                // Update Requirement: Length
                updateRequirementUI(
                    result.hasMinLength,
                    binding.ivCheckLength,
                    binding.tvRequirementLength
                )

                // Update Requirement: Case
                updateRequirementUI(
                    result.hasUpperAndLowerCase,
                    binding.ivCheckCase,
                    binding.tvRequirementCase
                )

                // Update Requirement: Digit
                updateRequirementUI(
                    result.hasDigit,
                    binding.ivCheckDigit,
                    binding.tvRequirementDigit
                )

                // Update Requirement: Special Char
                updateRequirementUI(
                    result.hasSpecialChar,
                    binding.ivCheckSpecial,
                    binding.tvRequirementSpecial
                )

                // Enforce button state
                if (result.strength == PasswordStrength.STRONG) {
                    binding.btnPwd.isEnabled = true
                    binding.btnPwd.alpha = 1.0f
                } else {
                    binding.btnPwd.isEnabled = false
                    binding.btnPwd.alpha = 0.5f
                }
            }
        })
    }

    private fun updateRequirementUI(isMet: Boolean, icon: android.widget.ImageView, text: android.widget.TextView) {
        if (isMet) {
            icon.setImageResource(R.drawable.ic_check_circle_24)
            icon.setColorFilter(Color.parseColor("#4CAF50")) // Green
            text.setTextColor(Color.parseColor("#4CAF50"))
        } else {
            icon.setImageResource(R.drawable.ic_cancel_circle_24)
            icon.setColorFilter(Color.parseColor("#9E9E9E")) // Gray
            text.setTextColor(Color.parseColor("#9E9E9E"))
        }
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