package app.secure.kyber.fragments

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.secure.kyber.R
import app.secure.kyber.activities.MainActivity
import app.secure.kyber.adapters.SectionsPagerAdapter
import app.secure.kyber.databinding.FragmentSharedMediaBinding
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class SharedMediaFragment : Fragment() {

    private lateinit var binding: FragmentSharedMediaBinding
    private val tabTitles = arrayOf("Media", "Links", "Documents")

    private val contactName by lazy {
        requireArguments().getString("name").orEmpty()
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        binding = FragmentSharedMediaBinding.inflate(inflater, container, false)


        val appBarText = "$contactName Media"
        //Set the app bar text
        (requireActivity() as MainActivity).setAppChatUser(appBarText)


// Create adapter using childFragmentManager + lifecycle (important inside fragment)
        val adapter = SectionsPagerAdapter(this)
        binding.viewPager.adapter = adapter

        // Keep one page to each side for smoother swiping if desired
        binding.viewPager.offscreenPageLimit = 1

        // Attach TabLayoutMediator and use a custom tab view so we can show background selector
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            val tabView = LayoutInflater.from(requireContext())
                .inflate(R.layout.custom_tab_bg_1, null)
            val titleTv = tabView.findViewById<TextView>(R.id.tab1_title)
            titleTv.text = tabTitles[position]
            tab.customView = tabView
        }.attach()


        // Ensure the custom view selection state matches TabLayout selection
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tab.customView?.isSelected = true
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
                tab.customView?.isSelected = false
            }

            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // initialize selection states for the first layout pass
        view?.post {
            for (i in 0 until binding.tabLayout.tabCount) {
                val t = binding.tabLayout.getTabAt(i)
                t?.customView?.isSelected = t.isSelected == true
            }
        }

        return binding.root
    }

}