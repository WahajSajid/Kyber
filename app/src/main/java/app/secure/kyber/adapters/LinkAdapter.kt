package app.secure.kyber.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.R
import app.secure.kyber.dataClasses.LinkItem

class LinkAdapter(
    private val items: List<LinkItem>,
    private val onClick: (LinkItem) -> Unit = {}
) : RecyclerView.Adapter<LinkAdapter.LinkVH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LinkVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_link_card, parent, false)
        return LinkVH(v)
    }

    override fun onBindViewHolder(holder: LinkVH, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.url.text = item.url // for demo this contains the URL or resource id string

        // Demo image loading: if displayPathOrUrl is a drawable resource id encoded as string, load it.
//        val resId = item.displayPathOrUrl.toIntOrNull()
        val resId = item.displayPathOrUrl
        if (resId != null) {
            holder.thumb.setImageResource(resId)
        } else {
            // Replace with Coil/Glide when using URIs.
//            holder.thumb.setImageResource(R.drawable.placeholder)
        }

        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    class LinkVH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.ivThumb)
        val title: TextView = view.findViewById(R.id.tvTitle)
        val url: TextView = view.findViewById(R.id.tvUrl)
    }
}