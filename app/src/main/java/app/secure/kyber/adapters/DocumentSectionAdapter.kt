package app.secure.kyber.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.R
import app.secure.kyber.dataClasses.SectionDocument

class DocumentSectionAdapter(private val sectionsDocument: List<SectionDocument>) :
    RecyclerView.Adapter<DocumentSectionAdapter.SectionDocumentVH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionDocumentVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_section_documents, parent, false)
        return SectionDocumentVH(v)
    }

    override fun onBindViewHolder(holder: SectionDocumentVH, position: Int) {
        val section = sectionsDocument[position]
        holder.title.text = section.title

        val lm = LinearLayoutManager(holder.itemView.context, LinearLayoutManager.VERTICAL, false)
        holder.rvFiles.layoutManager = lm
//        holder.rvFiles.setHasFixedSize(true)
        holder.rvFiles.adapter = DocumentAdapter(section.items) { file ->
            // Handle click (open, preview, show details). Example:
            // Toast.makeText(holder.itemView.context, "Clicked ${file.name}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount(): Int = sectionsDocument.size

    class SectionDocumentVH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvSectionTitle)
        val rvFiles: RecyclerView = view.findViewById(R.id.rvFiles)
    }
}