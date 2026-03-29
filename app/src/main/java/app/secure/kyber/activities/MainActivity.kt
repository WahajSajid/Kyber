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
import app.secure.kyber.databinding.ActivityMainBinding
import app.secure.kyber.databinding.FragmentGroupChatListBinding
import app.secure.kyber.fragments.ContactBottomSheet
import app.secure.kyber.fragments.GroupChatListFragment
import app.secure.kyber.onionrouting.UnionClient
import app.secure.kyber.workers.SyncWorker
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint

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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)


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

                }

            }
        }

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
            "contact_id" to contactID,
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
        groupName: String,
        creationDate: String,
        noOfMembers: String
    ) {
        args = bundleOf(
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

    public fun addButtonClick() {
        binding.ivAddContact.setOnClickListener {
            //navigate to create group screen
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
    }

}