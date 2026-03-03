package app.secure.kyber.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.R

class RecentEmojiAdapter(
    emojisList: List<String>,
    private var currentReaction: String,
    private val onEmojiClick: (String) -> Unit
) : RecyclerView.Adapter<RecentEmojiAdapter.EmojiViewHolder>() {

    // FIX: Filter out any blank emojis right at the start so an empty item is never rendered
    private var emojis = emojisList.filter { it.isNotBlank() }

    class EmojiViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvEmoji: TextView = view.findViewById(R.id.tvEmojiItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmojiViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recent_emoji, parent, false)
        return EmojiViewHolder(view)
    }

    override fun onBindViewHolder(holder: EmojiViewHolder, position: Int) {
        val emoji = emojis[position]
        holder.tvEmoji.text = emoji

        if (currentReaction.isNotEmpty()) {
            if (emoji == currentReaction) {
                holder.tvEmoji.alpha = 1.0f
                holder.tvEmoji.scaleX = 1.25f
                holder.tvEmoji.scaleY = 1.25f
            } else {
                holder.tvEmoji.alpha = 0.5f
                holder.tvEmoji.scaleX = 0.9f
                holder.tvEmoji.scaleY = 0.9f
            }
        } else {
            holder.tvEmoji.alpha = 1.0f
            holder.tvEmoji.scaleX = 1.0f
            holder.tvEmoji.scaleY = 1.0f
        }

        holder.itemView.setOnClickListener { onEmojiClick(emoji) }
    }

    override fun getItemCount(): Int = emojis.size

    fun updateEmojis(newEmojis: List<String>, newReaction: String) {
        // FIX: Ensure no blank entries can be passed into the adapter
        this.emojis = newEmojis.filter { it.isNotBlank() }
        this.currentReaction = newReaction
        notifyDataSetChanged()
    }
}