package app.secure.kyber.adapters

import android.media.MediaPlayer
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.VideoView
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.R
import app.secure.kyber.dataClasses.SelectedMediaParcelable
import com.bumptech.glide.Glide

class MediaPreviewPagerAdapter(
    private val items: List<SelectedMediaParcelable>
) : RecyclerView.Adapter<MediaPreviewPagerAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val ivImage: ImageView = view.findViewById(R.id.previewImage)
        val vvVideo: VideoView = view.findViewById(R.id.previewVideo)
        val ivPlay: ImageView = view.findViewById(R.id.ivPlay)

        fun bind(position: Int) {
            val item = items[position]
            
            // reset states
            vvVideo.stopPlayback()
            vvVideo.visibility = View.GONE
            ivPlay.visibility = View.GONE
            ivImage.visibility = View.VISIBLE

            if (item.type == "VIDEO") {
                ivPlay.visibility = View.VISIBLE
                
                // Show thumbnail for video
                Glide.with(ivImage.context)
                    .load(Uri.parse(item.uriString))
                    .centerCrop()
                    .into(ivImage)

                ivPlay.setOnClickListener {
                    ivPlay.visibility = View.GONE
                    ivImage.visibility = View.GONE
                    vvVideo.visibility = View.VISIBLE
                    
                    vvVideo.setVideoURI(Uri.parse(item.uriString))
                    vvVideo.setOnPreparedListener { mp ->
                        mp.isLooping = true
                        vvVideo.start()
                    }
                }
                
                vvVideo.setOnClickListener {
                    if (vvVideo.isPlaying) vvVideo.pause() else vvVideo.start()
                }

            } else {
                Glide.with(ivImage.context)
                    .load(item.getUri())
                    .centerCrop()
                    .into(ivImage)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_media_preview_page, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(position)

    override fun getItemCount(): Int = items.size
    
    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.vvVideo.stopPlayback()
    }
}