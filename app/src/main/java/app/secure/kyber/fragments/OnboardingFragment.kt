package app.secure.kyber.fragments

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.secure.kyber.R
import app.secure.kyber.activities.AuthenticationActivity
import app.secure.kyber.databinding.FragmentOnBoarding3Binding
import com.google.android.material.transition.MaterialFadeThrough

class OnboardingFragment : Fragment(R.layout.fragment_on_boarding_3) {

    private lateinit var binding: FragmentOnBoarding3Binding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentOnBoarding3Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

//        binding.btnGetStarted.setOnClickListener {
//            openAuthActivity()
//        }
    }
    private fun openAuthActivity() {
        val intent = Intent(activity, AuthenticationActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        exitTransition = MaterialFadeThrough()
    }
}