package app.secure.kyber.activities

import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import app.secure.kyber.R
import app.secure.kyber.R.anim
import com.github.appintro.AppIntro
import dagger.hilt.android.AndroidEntryPoint
import com.github.appintro.AppIntroCustomLayoutFragment.Companion.newInstance

@AndroidEntryPoint
class AppIntroSliderActivity : AppIntro() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        var frag3 = newInstance(R.layout.fragment_on_boarding_3)
        addSlide(newInstance(R.layout.fragment_on_boarding))
        addSlide(newInstance(R.layout.fragment_on_boarding_2))
        addSlide(newInstance(R.layout.fragment_on_boarding_3_1))
        addSlide(frag3)


        showStatusBar(true)
        setStatusBarColorRes(android.R.color.transparent)
        setNavBarColorRes(android.R.color.transparent)
        setProgressIndicator()
        showStatusBar(true)
        isSystemBackButtonLocked = true
        setIndicatorColor(
            selectedIndicatorColor = getColor(R.color.primary_color),
            unselectedIndicatorColor = getColor(R.color.white)
        )
        isWizardMode = true
        isSkipButtonEnabled = false

    }

    public override fun onSkipPressed(currentFragment: Fragment?) {
        super.onSkipPressed(currentFragment)
        openAuthActivity()
    }

    public override fun onDonePressed(currentFragment: Fragment?) {
        super.onDonePressed(currentFragment)
        openAuthActivity()
    }

    private fun openAuthActivity() {
        val intent = Intent(this, AuthenticationActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        overridePendingTransition(
            anim.slide_in,
            anim.slide_out
        )
    }


}