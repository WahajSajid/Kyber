package app.secure.kyber.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.R
import app.secure.kyber.dataClasses.SectionLink

class LinkSectionAdapter(private val sections: List<SectionLink>) :
    RecyclerView.Adapter<LinkSectionAdapter.SectionVH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_section_links, parent, false)
        return SectionVH(v)
    }

    override fun onBindViewHolder(holder: SectionVH, position: Int) {
        val section = sections[position]
        holder.title.text = section.title

        val lm = LinearLayoutManager(holder.itemView.context, LinearLayoutManager.VERTICAL, false)
        holder.rvLinks.layoutManager = lm
        holder.rvLinks.setHasFixedSize(true)
        holder.rvLinks.adapter = LinkAdapter(section.items) { item ->
            // handle item click e.g. open URL
            // val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.displayPathOrUrl))
            // holder.itemView.context.startActivity(intent)
        }
    }

    override fun getItemCount(): Int = sections.size

    class SectionVH(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvSectionTitle)
        val rvLinks: RecyclerView = view.findViewById(R.id.rvLinks)
    }
}