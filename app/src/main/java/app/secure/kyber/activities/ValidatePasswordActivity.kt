package app.secure.kyber.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.findNavController
import app.secure.kyber.R
import app.secure.kyber.Utils.NetworkMonitor
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch


@AndroidEntryPoint
class ValidatePasswordActivity : AppCompatActivity() {

    private lateinit var controller: NavController
    private var networkObserverJob: Job? = null
    private var networkDialogVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_validatepassword)

        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        val window = this.window
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.statusBarColor = this.resources.getColor(R.color.statusbar_color,theme)

        controller = this.findNavController(R.id.pwdSet_fragment)
        observeInternetDisconnect()


    }

    private fun observeInternetDisconnect() {
        networkObserverJob?.cancel()
        networkObserverJob = lifecycleScope.launch {
            NetworkMonitor.isConnected.collectLatest { connected ->
                if (connected) {
                    networkDialogVisible = false
                    return@collectLatest
                }
                if (networkDialogVisible || isFinishing || isDestroyed) return@collectLatest
                networkDialogVisible = true
                androidx.appcompat.app.AlertDialog.Builder(this@ValidatePasswordActivity)
                    .setMessage("Connection cannot be established")
                    .setPositiveButton("OK") { _, _ -> networkDialogVisible = false }
                    .setOnDismissListener { networkDialogVisible = false }
                    .show()
            }
        }
    }

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
        return super.onCreateView(name, context, attrs)
    }

    override fun onResume() {
        super.onResume()

    }
    override fun onDestroy() {
        networkObserverJob?.cancel()
        super.onDestroy()
    }
    private fun openActivity(){
        val handler = Handler()
        val runnable = Runnable {
            handler.removeCallbacksAndMessages(null)

            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            overridePendingTransition(
                R.anim.slide_in,
                R.anim.slide_out
            )
        }

        handler.postDelayed(runnable,2000)

    }



}