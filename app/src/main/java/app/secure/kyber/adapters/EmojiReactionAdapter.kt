package app.secure.kyber.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.R

class EmojiReactionAdapter(
    private val onEmojiClick: (String) -> Unit
) : RecyclerView.Adapter<EmojiReactionAdapter.EmojiVH>() {

    private val emojis = mutableListOf<String>()

    class EmojiVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvEmoji: TextView = view.findViewById(R.id.tvEmoji)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmojiVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_emoji_reaction, parent, false)
        return EmojiVH(view)
    }

    override fun onBindViewHolder(holder: EmojiVH, position: Int) {
        val emoji = emojis[position]
        holder.tvEmoji.text = emoji
        holder.itemView.setOnClickListener {
            onEmojiClick(emoji)
        }

        // Add scale animation on click
        holder.itemView.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).start()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                }
            }
            false
        }
    }

    override fun getItemCount() = emojis.size

    fun updateEmojis(newEmojis: List<String>) {
        emojis.clear()
        emojis.addAll(newEmojis)
        notifyDataSetChanged()
    }
}