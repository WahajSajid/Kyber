package app.secure.kyber.activities

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavArgs
import androidx.navigation.NavController
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import app.secure.kyber.R
import app.secure.kyber.databinding.ActivityMainBinding
import app.secure.kyber.fragments.ContactBottomSheet
import app.secure.kyber.onionrouting.UnionClient
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
    private lateinit var controller: NavController
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        controller = this.findNavController(R.id.main_fragment)

        setUpNavBar()
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
                bottomBar.visibility = View.GONE
                ivAddContact.visibility =View.GONE
                ivBackBtn.setOnClickListener { backPressed() }
            }
            R.id.netwokFragment -> {
                ivAddContact.visibility =View.GONE
                ivDotsContact.visibility =View.GONE
                ivAdd.visibility = View.GONE
                ivVpn.visibility = View.VISIBLE
                ivBackBtn.visibility = View.GONE
                ivCall.visibility = View.GONE
                ivVideo.visibility = View.GONE
                ivLogo.visibility = View.GONE
                appBarLayout.visibility = View.VISIBLE
                bottomBar.visibility = View.VISIBLE
            }
            R.id.settingFragment -> {
                ivAddContact.visibility =View.GONE
                ivDotsContact.visibility =View.GONE
                ivAdd.visibility = View.GONE
                ivVpn.visibility = View.VISIBLE
                ivBackBtn.visibility = View.GONE
                ivCall.visibility = View.GONE
                ivVideo.visibility = View.GONE
                ivLogo.visibility = View.GONE
                appBarLayout.visibility = View.VISIBLE
                bottomBar.visibility = View.VISIBLE
            }
            R.id.contactsFragment -> {
                ivAddContact.visibility =View.VISIBLE
                ivDotsContact.visibility =View.VISIBLE
                ivAdd.visibility = View.GONE
                ivVpn.visibility = View.GONE
                ivBackBtn.visibility = View.GONE
                ivCall.visibility = View.GONE
                ivVideo.visibility = View.GONE
                ivLogo.visibility = View.GONE
                appBarLayout.visibility = View.VISIBLE
                bottomBar.visibility = View.VISIBLE
                ivAddContact.setOnClickListener {
                    ContactBottomSheet.newInstance().show(supportFragmentManager, ContactBottomSheet.TAG)
                }
            }

            R.id.chatDetailsFragment -> {
                ivAddContact.visibility =View.GONE
                ivDotsContact.visibility =View.GONE
                ivAdd.visibility = View.GONE
                ivVpn.visibility = View.GONE
                ivBackBtn.visibility = View.VISIBLE
                ivCall.visibility = View.GONE
                ivVideo.visibility = View.GONE
                ivLogo.visibility = View.GONE
                appBarLayout.visibility = View.VISIBLE
                bottomBar.visibility = View.GONE
                ivAddContact.visibility = View.GONE
                ivBackBtn.setOnClickListener { backPressed() }

            }

            R.id.sharedMediaFragment ->{
                ivAddContact.visibility =View.GONE
                ivDotsContact.visibility =View.GONE
                ivAdd.visibility = View.GONE
                ivVpn.visibility = View.GONE
                ivBackBtn.visibility = View.VISIBLE
                ivCall.visibility = View.GONE
                ivVideo.visibility = View.GONE
                ivLogo.visibility = View.GONE
                appBarLayout.visibility = View.VISIBLE
                bottomBar.visibility = View.GONE
                ivAddContact.visibility = View.GONE
                ivBackBtn.setOnClickListener { backPressed() }
            }


            else -> {
                ivAddContact.visibility =View.GONE
                ivDotsContact.visibility =View.GONE
                ivCall.visibility = View.GONE
                ivVideo.visibility = View.GONE
                ivBackBtn.visibility = View.GONE
                ivLogo.visibility = View.VISIBLE
                ivVpn.visibility = View.VISIBLE
                bottomBar.visibility = View.VISIBLE
                appBarLayout.visibility = View.VISIBLE
                ivAdd.visibility = View.VISIBLE
                ivAdd.setOnClickListener {
                    bottomBar.selectedItemId = R.id.contactsFragment
                }
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

    override fun onResume() {
        super.onResume()
        controller.addOnDestinationChangedListener(listener)
    }

    override fun onPause() {
        controller.removeOnDestinationChangedListener(listener)
        super.onPause()
    }

    private fun setUpNavBar() {
        val navView: BottomNavigationView = findViewById(R.id.bottomNavigationView)
        controller = this.findNavController(R.id.main_fragment)
        navView.setupWithNavController(controller)
    }
    private fun setAppBar(string: String) {
        tvTitle.text = string
    }

    public fun setAppChatUser(string:String){
        tvTitle.text = string
    }

    public fun onChatDetailsClick(contactID:String, contactName:String){
        tvTitle.setOnClickListener {
            args = bundleOf(
                "contact_id" to contactID,
                "contact_name" to contactName
            )

            appBarLayout.setOnClickListener {
                args = bundleOf(
                    "contact_id" to contactID,
                    "contact_name" to contactName
                )
            }
            controller.navigate(R.id.action_chatFragment_to_chatDetailsFragment,args)


        }
    }


    private fun backPressed() {
        controller.popBackStack()
    }
}