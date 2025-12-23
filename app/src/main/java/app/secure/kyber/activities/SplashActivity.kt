package app.secure.kyber.activities

import android.Manifest
import android.R
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import app.secure.kyber.R.*
import androidx.core.view.WindowCompat
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.secure.kyber.backend.common.Prefs

import com.google.android.material.snackbar.Snackbar

import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi



@ExperimentalCoroutinesApi
@AndroidEntryPoint
class SplashActivity : AppCompatActivity() {



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layout.activity_splash)

        val window = this.window
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.statusBarColor = this.resources.getColor(color.primary_color,theme)

        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        openActivity()
//        if(ContextCompat.checkSelfPermission(
//                this, Manifest.permission.POST_NOTIFICATIONS
//            ) == PackageManager.PERMISSION_GRANTED ){
//            openActivity()
//        }else if(shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)){
//            Snackbar.make(
//                findViewById(android.R.id.content) ,
//                "Notifications blocked",
//                Snackbar.LENGTH_LONG
//            ).setAction("Settings") {
//                // Responds to click on the action
//                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
//                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
//                val uri: Uri = Uri.fromParts("package", packageName, null)
//                intent.data = uri
//                startActivity(intent)
//            }.show()
//          //  Handler().postDelayed({
//            openActivity()
//               // }, 4000)
//        }
//        else {
//            // The registered ActivityResultCallback gets the result of this request
//            requestPermissionLauncher.launch(
//                Manifest.permission.POST_NOTIFICATIONS
//            )
//        }

    }

    override fun onResume() {
        super.onResume()

    }

    private fun openActivity(){
        val handler = Handler()
        val runnable = Runnable {
            handler.removeCallbacksAndMessages(null)

            if(Prefs.getLicense(this)==null || Prefs.getLicense(this)==""){
                openIntoActivity()
            }
            else if(Prefs.getPassword(this)==null || Prefs.getPassword(this)==""){
                openIntoActivity()
            }
            else{
                openValidatePwdActivity()
            }

            //                if(user!=null && user.userId!!.isNotEmpty())
            //                {
            //                    openMainActivity()
            //                    handler.removeCallbacks(this)
            //                }else{
            //                    OpenAuthActivity()
            //                    handler.removeCallbacks(this);
            //                }
        }

        handler.postDelayed(runnable,2000)

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

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        overridePendingTransition(
            anim.slide_in,
            anim.slide_out
        )
    }

    private fun openValidatePwdActivity() {
        val intent = Intent(this, ValidatePasswordActivity::class.java)
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        overridePendingTransition(
            anim.slide_in,
            anim.slide_out
        )
    }

    private fun openIntoActivity(){
        val handler = Handler()
        val runnable = Runnable {
            handler.removeCallbacksAndMessages(null)

            val intent = Intent(this, AppIntroSliderActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            overridePendingTransition(
                app.secure.kyber.R.anim.slide_in,
                app.secure.kyber.R.anim.slide_out
            )
        }

        handler.postDelayed(runnable,2000)

    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            openActivity()
        } else {
            // Explain to the user that the feature is unavailable because the
            // features requires a permission that the user has denied. At the
            // same time, respect the user's decision. Don't link to system
            // settings in an effort to convince the user to change their
            // decision.
            openActivity()
        }
    }


    override fun onDestroy() {
        super.onDestroy()

    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        when (requestCode) {

        }
    }

}