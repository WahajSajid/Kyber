package app.secure.kyber.fragments

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import app.secure.kyber.R
import app.secure.kyber.Utils.DateUtils
import app.secure.kyber.adapters.DocumentSectionAdapter
import app.secure.kyber.dataClasses.DocumentItem
import app.secure.kyber.databinding.FragmentDocumentsBinding
import app.secure.kyber.databinding.FragmentLinksBinding
class DocumentsFragment : Fragment() {

    private lateinit var binding: FragmentDocumentsBinding

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        binding = FragmentDocumentsBinding.inflate(inflater, container, false)

        // Sample data (timestamps distributed across today/yesterday/older)
        val now = System.currentTimeMillis()
        val oneDay = 24L * 60 * 60 * 1000

        val samples = listOf(
            // Today
            DocumentItem("1", "Don Quixote", 24L * 1024 * 1024, "doc", now - 10000),
            DocumentItem("2", "Design System", (1.5 * 1024 * 1024 * 1024).toLong(), "cdr", now - 20000),
            DocumentItem("3", "The Lord of the Rings", (20.8 * 1024 * 1024 * 1024).toLong(), "pdf", now - 30000),

            // Yesterday
            DocumentItem("4", "Design System", 100L * 1024 * 1024, "psd", now - oneDay - 10000),
            DocumentItem("5", "Design", 120L * 1024 * 1024, "psd", now - oneDay - 20000),

            // Older
            DocumentItem("6", "Archived Notes", 5L * 1024 * 1024, "docx", now - oneDay * 10)
        )

        val sections = DateUtils.groupIntoDocumentSections(samples)
        binding.rvSections.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSections.adapter = DocumentSectionAdapter(sections)




        return binding.root
    }

}