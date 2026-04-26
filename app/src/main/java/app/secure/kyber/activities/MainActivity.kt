package app.secure.kyber.activities

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.commit
import androidx.navigation.NavArgs
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import app.secure.kyber.ApplicationClass
import app.secure.kyber.MyApp.MyApp
import app.secure.kyber.R
import app.secure.kyber.Utils.NetworkMonitor
import app.secure.kyber.databinding.ActivityMainBinding
import app.secure.kyber.databinding.FragmentGroupChatListBinding
import app.secure.kyber.fragments.ContactBottomSheet
import app.secure.kyber.fragments.GroupChatListFragment
import app.secure.kyber.onionrouting.UnionClient
import app.secure.kyber.services.GlobalSyncService
import app.secure.kyber.workers.SyncWorker
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import app.secure.kyber.backend.KyberRepository
import app.secure.kyber.backend.common.Prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tvTitle: TextView
    private lateinit var ivBackBtn: ImageView
    private lateinit var ivLogo: ImageView
    private lateinit var ivAdd: ImageView
    private lateinit var ivAddContact: ImageView
    private lateinit var ivDotsContact: ImageView
    private lateinit var ivCall: ImageView
    private lateinit var ivVideo: ImageView
    private lateinit var ivVpn: ImageView

    private lateinit var args: Bundle

    private lateinit var appBarLayout: AppBarLayout
    private lateinit var bottomBar: BottomNavigationView

    private lateinit var myApp: MyApp
    private lateinit var controller: NavController

    @Inject
    lateinit var repository: KyberRepository

    var lastInteractionTime: Long = System.currentTimeMillis()
    private val lockHandler = Handler(Looper.getMainLooper())
    private val lockRunnable = object : Runnable {
        override fun run() {
            checkAppLock()
            lockHandler.postDelayed(this, 10000L)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        lastInteractionTime = System.currentTimeMillis()
        return super.dispatchTouchEvent(ev)
    }


    private fun checkAppLock() {
        val timeout = app.secure.kyber.backend.common.Prefs.getAutoLockTimeoutMs(this)
        if (myApp.isAppLocked) {
            startActivity(Intent(this, ValidatePasswordActivity::class.java))
            finish()
        } else if (timeout > 0 && System.currentTimeMillis() - lastInteractionTime > timeout) {
            myApp.isAppLocked = true
            startActivity(Intent(this, ValidatePasswordActivity::class.java))
            finish()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // START GLOBAL SYNC SERVICE - Runs 24/7 Even When App is Closed
        startGlobalSyncService()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        observeInternetDisconnect()

        val isAppOpen = Prefs.getAppOpen(this)

        if(isAppOpen == "false"){
            Prefs.setAppOpen(this, "true")

            //push the new updated key to backend
            val publicKey = Prefs.getDummyPublicKey(this)
            val onionAddress = Prefs.getOnionAddress(this)

            lifecycleScope.launch {
                try {
                    val response = repository.updatePublicKey(onionAddress!!, publicKey!!)
                    if (response.isSuccessful) {  // Just check 200-299 status code
                        Log.d("### Key Rotation Success ###", "Successfully pushed new public key to backend.")
                    } else {
                        val errorBody = response.errorBody()?.string() ?: "Unknown error"
                        Log.e("### Key Rotation Failed ###", "Status: ${response.code()}, Error: $errorBody")
                    }
                } catch (e: Exception) {
                    Log.e("### Key Rotation Failed ###", "Aborting rotation.", e)
                }
            }


        }



        myApp = application as MyApp
        setSupportActionBar(binding.toolbar)
        // remove default app name
        supportActionBar?.setDisplayShowTitleEnabled(false)

        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        tvTitle = findViewById(R.id.titletoolbar)
        ivBackBtn = findViewById(R.id.ivBack)
        ivLogo = findViewById(R.id.ivLogo)
        ivAdd = findViewById(R.id.ivAdd)
        ivAddContact = findViewById(R.id.ivAddContact)
        ivDotsContact = findViewById(R.id.ivContactDots)
        ivCall = findViewById(R.id.ivCall)
        ivVideo = findViewById(R.id.ivVideo)
        ivVpn = findViewById(R.id.ivVpn)

        appBarLayout = findViewById(R.id.app_bar)
        bottomBar = findViewById(R.id.bottomNavigationView)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.main_fragment) as NavHostFragment
        controller = navHostFragment.navController

        setUpNavBar()


        //Setting up the click listener on the add button based on the state of tab buttons
        binding.ivAdd.setOnClickListener {
            when (myApp.tabBtnState) {
                "individual_chat" -> {
                    bottomBar.selectedItemId = R.id.contactsFragment
                }

                "group_chat" -> {
                    controller.navigate(R.id.action_groupChatListFragment_to_createGroupFragment)
                }

                "request_chat" -> {
                    bottomBar.selectedItemId = R.id.messageRequestsFragment
                }

            }
        }

    }

    private fun requestBatteryOptimizationBypass() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                showBatteryOptimizationDialog()
            }
        }
    }

    private fun showBatteryOptimizationDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_battery_optimization, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<View>(R.id.btnDisableOptimization).setOnClickListener {
            try {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = android.net.Uri.parse("package:$packageName")
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to open battery optimization settings", e)
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    private val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
        when (destination.id) {
            R.id.chatFragment -> {
                ivAdd.visibility = View.GONE
                ivDotsContact.visibility = View.GONE
                ivVpn.visibility = View.GONE
                ivBackBtn.visibility = View.VISIBLE
                ivCall.visibility = View.VISIBLE
                ivVideo.visibility = View.VISIBLE
                ivLogo.visibility = View.GONE
                appBarLayout.visibility = View.VISIBLE
                binding.titletoolbar.visibility = View.GONE
                binding.chatTittle.visibility = View.VISIBLE
                bottomBar.visibility = View.GONE
                ivAddContact.visibility = View.GONE
                ivBackBtn.setOnClickListener { backPressed() }
            }

            R.id.netwokFragment -> {
                ivAddContact.visibility = View.GONE
                ivDotsContact.visibility = View.GONE
                ivAdd.visibility = View.GONE
                ivVpn.visibility = View.VISIBLE
                ivBackBtn.visibility = View.GONE
                binding.chatTittle.visibility = View.GONE
                binding.titletoolbar.visibility = View.VISIBLE
                ivCall.visibility = View.GONE
                ivVideo.visibility = View.GONE
                ivLogo.visibility = View.GONE
                appBarLayout.visibility = View.VISIBLE
                bottomBar.visibility = View.VISIBLE
            }

            R.id.settingFragment -> {
                ivAddContact.visibility = View.GONE
                ivDotsContact.visibility = View.GONE
                ivAdd.visibility = View.GONE
                ivVpn.visibility = View.VISIBLE
                ivBackBtn.visibility = View.GONE
                binding.chatTittle.visibility = View.GONE
                binding.titletoolbar.visibility = View.VISIBLE
                ivCall.visibility = View.GONE
                ivVideo.visibility = View.GONE
                ivLogo.visibility = View.GONE
                appBarLayout.visibility = View.VISIBLE
                bottomBar.visibility = View.VISIBLE
            }

            R.id.contactsFragment -> {
                ivAddContact.visibility = View.VISIBLE
                ivDotsContact.visibility = View.VISIBLE
                ivAdd.visibility = View.GONE
                ivVpn.visibility = View.GONE
                ivBackBtn.visibility = View.GONE
                ivCall.visibility = View.GONE
                binding.chatTittle.visibility = View.GONE
                binding.titletoolbar.visibility = View.VISIBLE
                ivVideo.visibility = View.GONE
                ivLogo.visibility = View.GONE
                appBarLayout.visibility = View.VISIBLE
                bottomBar.visibility = View.VISIBLE
                ivAddContact.setOnClickListener {
                    ContactBottomSheet.newInstance()
                        .show(supportFragmentManager, ContactBottomSheet.TAG)
                }
            }

            R.id.chatDetailsFragment -> {
                ivAddContact.visibility = View.GONE
                ivDotsContact.visibility = View.GONE
                ivAdd.visibility = View.GONE
                ivVpn.visibility = View.GONE
                ivBackBtn.visibility = View.VISIBLE
                ivCall.visibility = View.GONE
                ivVideo.visibility = View.GONE
                binding.chatTittle.visibility = View.GONE
                binding.titletoolbar.visibility = View.VISIBLE
                ivLogo.visibility = View.GONE
                appBarLayout.visibility = View.VISIBLE
                bottomBar.visibility = View.GONE
                ivAddContact.visibility = View.GONE
                ivBackBtn.setOnClickListener { backPressed() }

            }

            R.id.groupDetailsFragment -> {
                ivAddContact.visibility = View.GONE
                ivDotsContact.visibility = View.GONE
                ivAdd.visibility = View.GONE
                ivVpn.visibility = View.GONE
                ivBackBtn.visibility = View.VISIBLE
                ivCall.visibility = View.GONE
                ivVideo.visibility = View.GONE
                binding.chatTittle.visibility = View.GONE
                binding.titletoolbar.visibility = View.VISIBLE
                ivLogo.visibility = View.GONE
                appBarLayout.visibility = View.VISIBLE
                bottomBar.visibility = View.GONE
                ivAddContact.visibility = View.GONE
                ivBackBtn.setOnClickListener { backPressed() }

            }

            R.id.sharedMediaFragment -> {
                ivAddContact.visibility = View.GONE
                ivDotsContact.visibility = View.GONE
                ivAdd.visibility = View.GONE
                ivVpn.visibility = View.GONE
                ivBackBtn.visibility = View.VISIBLE
                ivCall.visibility = View.GONE
                ivVideo.visibility = View.GONE
                ivLogo.visibility = View.GONE
                binding.chatTittle.visibility = View.GONE
                binding.titletoolbar.visibility = View.VISIBLE
                appBarLayout.visibility = View.VISIBLE
                bottomBar.visibility = View.GONE
                ivAddContact.visibility = View.GONE
                ivBackBtn.setOnClickListener { backPressed() }
            }


            else -> {
                ivAddContact.visibility = View.GONE
                ivDotsContact.visibility = View.GONE
                ivCall.visibility = View.GONE
                ivVideo.visibility = View.GONE
                ivBackBtn.visibility = View.GONE
                ivLogo.visibility = View.VISIBLE
                ivVpn.visibility = View.VISIBLE
                binding.chatTittle.visibility = View.GONE
                binding.titletoolbar.visibility = View.VISIBLE
                bottomBar.visibility = View.VISIBLE
                appBarLayout.visibility = View.VISIBLE
                ivAdd.visibility = View.VISIBLE
            }
        }

        when (destination.id) {
            R.id.chatFragment -> {
                setAppBar(getString(R.string.chat))
            }

            R.id.netwokFragment -> {
                setAppBar(getString(R.string.network))
            }

            R.id.settingFragment -> {
                setAppBar(getString(R.string.settings))
            }

            R.id.contactsFragment -> {
                setAppBar(getString(R.string.contacts))
            }


            else -> {
                setAppBar(getString(R.string.app_name))
            }
        }
    }

    override fun onPause() {
        controller.removeOnDestinationChangedListener(listener)
        lockHandler.removeCallbacks(lockRunnable)
        super.onPause()
    }

    private fun setUpNavBar() {
        val navView: BottomNavigationView = findViewById(R.id.bottomNavigationView)
        // controller is already initialized in onCreate
        navView.setupWithNavController(controller)
    }

    public fun setAppBar(string: String) {
        tvTitle.text = string
    }

    public fun setAppChatUser(string: String) {
        tvTitle.text = string
        binding.chatTittle.text = string
    }

    public fun onChatDetailsClick(contactID: String, contactName: String) {


        args = bundleOf(
            "contact_onion" to contactID,
            "contact_name" to contactName
        )
        binding.chatTittle.setOnClickListener {
            controller.navigate(R.id.action_chatFragment_to_chatDetailsFragment, args)
        }

        appBarLayout.setOnClickListener {
            controller.navigate(R.id.action_chatFragment_to_chatDetailsFragment, args)
        }


    }

    public fun onGroupChatDetailsClick(
        groupId: String,
        groupName: String,
        creationDate: String,
        noOfMembers: String
    ) {
        args = bundleOf(
            "group_id" to groupId,
            "group_name" to groupName,
            "creation_date" to creationDate,
            "no_of_members" to noOfMembers
        )

        binding.chatTittle.setOnClickListener {
            controller.navigate(R.id.action_chatFragment_to_groupDetailsFragment, args)
        }

        appBarLayout.setOnClickListener {
            controller.navigate(R.id.action_chatFragment_to_groupDetailsFragment, args)
        }


    }

    public fun viewVisibility() {
        ivAddContact.visibility = View.GONE
        ivDotsContact.visibility = View.GONE
        ivCall.visibility = View.GONE
        ivVideo.visibility = View.GONE
        ivBackBtn.visibility = View.GONE
        ivLogo.visibility = View.GONE
        ivVpn.visibility = View.GONE
        binding.chatTittle.visibility = View.GONE
        binding.titletoolbar.visibility = View.VISIBLE
        bottomBar.visibility = View.GONE
        appBarLayout.visibility = View.VISIBLE
        ivAdd.visibility = View.GONE
        binding.ivBack.visibility = View.VISIBLE
    }


    private fun backPressed() {
        controller.popBackStack()
    }

    public fun hideBottomBar() {
        bottomBar.visibility = View.GONE
    }

    public fun hideTopBar() {
        appBarLayout.visibility = View.GONE
    }


    override fun onResume() {
        super.onResume()
        controller.addOnDestinationChangedListener(listener)
        bottomBar.selectedItemId = R.id.chatListFragment

        // Every time the app comes to foreground, trigger an immediate
        // one-time sync to catch any messages missed since last open.
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()
        val immediateSync = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .addTag("foreground_sync")
            .build()
        androidx.work.WorkManager.getInstance(this)
            .enqueueUniqueWork(
                "foreground_sync_once",
                androidx.work.ExistingWorkPolicy.REPLACE,
                immediateSync
            )

        requestBatteryOptimizationBypass()
        checkAppLock()
        lockHandler.postDelayed(lockRunnable, 10000L)
    }

    private var networkDialogVisible = false
    private var networkObserverJob: Job? = null

    private fun observeInternetDisconnect() {
        networkObserverJob?.cancel()
        networkObserverJob = lifecycleScope.launch {
            NetworkMonitor.isConnected.collectLatest { connected ->
                if (connected) {
                    networkDialogVisible = false
                    connectionErrorDialog?.dismiss()
                    connectionErrorDialog = null
                    return@collectLatest
                }
                
                if (networkDialogVisible || isFinishing || isDestroyed) return@collectLatest
                
                // Connection Lost logic
                try {
                    // 1. Navigate to lock screen and clear backstack
                    if (controller.currentDestination?.id != R.id.encryptMsgPwdFragment2) {
                        val navOptions = androidx.navigation.NavOptions.Builder()
                            .setPopUpTo(R.id.activity_main_graph, true)
                            .build()
                        controller.navigate(R.id.encryptMsgPwdFragment2, null, navOptions)
                    }
                    
                    // 2. Show styled dialog
                    showConnectionErrorDialog()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Disconnect handling failed", e)
                }
            }
        }
    }

    private var connectionErrorDialog: AlertDialog? = null

    private fun showConnectionErrorDialog() {
        if (connectionErrorDialog?.isShowing == true) return
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_connection_error, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<View>(R.id.btnOk).setOnClickListener {
            dialog.dismiss()
            connectionErrorDialog = null
        }

        connectionErrorDialog = dialog
        dialog.show()
    }

    /**
     * Start GlobalSyncService for 24/7 background operation.
     * Service runs even when app is closed.
     */
    private fun startGlobalSyncService() {
        try {
            val serviceIntent = Intent(this, GlobalSyncService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                @Suppress("DEPRECATION")
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        networkObserverJob?.cancel()
        super.onDestroy()
    }
}