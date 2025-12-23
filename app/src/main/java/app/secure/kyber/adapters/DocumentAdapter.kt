package app.secure.kyber.adapters

import app.secure.kyber.dataClasses.DocumentItem

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.R
import app.secure.kyber.Utils.FileUtils

class DocumentAdapter(
    private val items: List<DocumentItem>,
    private val onClick: (DocumentItem) -> Unit = {}
) : RecyclerView.Adapter<DocumentAdapter.FileVH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_documents_card, parent, false)
        return FileVH(v)
    }

    override fun onBindViewHolder(holder: FileVH, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.size.text = FileUtils.humanReadableByteCountSI(item.sizeBytes)
        holder.icon.setImageResource(iconForExtension(item.extension))
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    private fun iconForExtension(ext: String): Int {
        return when (ext.lowercase()) {
            "pdf" -> R.drawable.pdf_file
            "psd" -> R.drawable.psd_file
            "cdr" -> R.drawable.cdr_file
            "doc", "docx" -> R.drawable.doc_file
            else -> R.drawable.file
        }
    }

    class FileVH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivFileIcon)
        val name: TextView = view.findViewById(R.id.tvFileName)
        val size: TextView = view.findViewById(R.id.tvFileSize)
    }
}