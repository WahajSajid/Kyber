package app.secure.kyber.fragments

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import app.secure.kyber.Utils.DateUtils
import app.secure.kyber.R
import app.secure.kyber.adapters.LinkSectionAdapter
import app.secure.kyber.dataClasses.LinkItem
import app.secure.kyber.databinding.FragmentLinksBinding

class LinksFragment : Fragment() {

    private lateinit var binding: FragmentLinksBinding

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        binding = FragmentLinksBinding.inflate(inflater, container, false)


        // Sample timestamps and data
        val now = System.currentTimeMillis()
        val oneDay = 24L * 60 * 60 * 1000

        val samples = listOf(
            LinkItem(
                "1",
                "FREE Things",
                R.drawable.sample_link_1,
                now - 1000,
                "https://www.instagram.com/community/file/131292101..."
            ),
            LinkItem(
                "2",
                "Speedy Chow | Food",
                R.drawable.sample_link_2,
                now - 2000,
                "https://www.instagram.com/community/file/1364368331..."
            ),

            LinkItem(
                "3",
                "150+ Wizard",
                R.drawable.sample_link_3,
                now - oneDay - 20000, "https://www.instagram.com/community/file/1364368331..."
            ),
            LinkItem(
                "4",
                "100+ FREE Search Bar Component Types",
                R.drawable.sample_link_4,
                now - oneDay - 50000,
                "https://www.instagram.com/community/file/1364368331..."
            ),
            // older
//            MediaItem("5", "Older Item", "https://example.com/older", now - oneDay * 10)
        )

        val sections = DateUtils.groupIntoLinksSections(samples)
        binding.rvSections.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSections.adapter = LinkSectionAdapter(sections)


        return binding.root
    }
}