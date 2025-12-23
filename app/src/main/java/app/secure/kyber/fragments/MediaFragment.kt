package app.secure.kyber.fragments

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import app.secure.kyber.Utils.DateUtils
import app.secure.kyber.R
import app.secure.kyber.adapters.MediaSectionAdapter
import app.secure.kyber.dataClasses.MediaItem
import app.secure.kyber.databinding.FragmentMediaBinding

class MediaFragment : Fragment() {

    private lateinit var binding: FragmentMediaBinding

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        binding = FragmentMediaBinding.inflate(inflater, container, false)

        // Sample data: create timestamps: today, yesterday, within last week, older months
        val now = System.currentTimeMillis()
        val oneDay = 24L * 60 * 60 * 1000

        val samples = listOf(
            // For demonstration I'm using drawable resource ids stored in displayPathOrUrl as strings.
            MediaItem("1", R.drawable.sample_1, now - (1000L)),
            MediaItem("2", R.drawable.sample_2, now - (2000L)),
            MediaItem("3", R.drawable.sample_3, now - (5000L)),
            // Yesterday
            MediaItem("4", R.drawable.sample_4, now - oneDay - 10000),
            MediaItem("5", R.drawable.sample_5, now - oneDay - 20000),
            MediaItem("4", R.drawable.sample_6, now - oneDay - 10000),
            MediaItem("5", R.drawable.sample_7, now - oneDay - 20000)
            // Last week
//            MediaItem("6", R.drawable.sample6.toString(), now - (oneDay * 3)),
//            MediaItem("7", R.drawable.sample7.toString(), now - (oneDay * 4)),
//            // Older month e.g. two months ago
//            MediaItem("8", R.drawable.sample8.toString(), now - (oneDay * 40)),
//            MediaItem("9", R.drawable.sample9.toString(), now - (oneDay * 80))
        )

        val sections = DateUtils.groupIntoMediaSections(samples)

        binding.rvSections.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSections.adapter = MediaSectionAdapter(sections)




        return binding.root
    }

}