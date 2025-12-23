package app.secure.kyber.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.R
import app.secure.kyber.dataClasses.SectionMedia

class MediaSectionAdapter(private val sections: List<SectionMedia>) :
    RecyclerView.Adapter<MediaSectionAdapter.SectionVH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_section_media, parent, false)
        return SectionVH(v)
    }

    override fun onBindViewHolder(holder: SectionVH, position: Int) {
        val section = sections[position]
        holder.title.text = section.title

        // Configure inner RecyclerView as a grid (3 columns shown in screenshot)
        val gridCols = 3
        val lm = GridLayoutManager(holder.itemView.context, gridCols)
        holder.rvMedia.layoutManager = lm

        // Ensure fixed size to help performance if thumbnails are fixed sized
        holder.rvMedia.setHasFixedSize(false)
        holder.rvMedia.adapter = MediaAdapter(section.items)
    }

    override fun getItemCount(): Int = sections.size

    class SectionVH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvSectionTitle)
        val rvMedia: RecyclerView = view.findViewById(R.id.rvMedia)
    }
}