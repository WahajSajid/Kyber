package app.secure.kyber.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.R
import app.secure.kyber.dataClasses.MediaItem

class MediaAdapter(
    private val items: List<MediaItem>,
    private val onClick: (MediaItem) -> Unit = {}
) : RecyclerView.Adapter<MediaAdapter.MediaVH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false)
        return MediaVH(v)
    }

    override fun onBindViewHolder(holder: MediaVH, position: Int) {
        val item = items[position]
        // Load image into holder.ivThumb. Replace with Glide/Coil when available.
        // Example (Coil): holder.ivThumb.load(item.displayPathOrUrl) { crossfade(true) }
        // For demo we'll use drawable resource ids stored as numeric string (if sample).
        val context = holder.ivThumb.context
//        val res = item.displayPathOrUrl.toIntOrNull()
        val res = item.displayPathOrUrl
        if (res != null) {
            holder.ivThumb.setImageResource(res)
        } else {
//            holder.ivThumb.setImageResource(R.drawable.placeholder) // fallback
        }

        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    class MediaVH(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumb: ImageView = view.findViewById(R.id.ivThumb)
    }
}