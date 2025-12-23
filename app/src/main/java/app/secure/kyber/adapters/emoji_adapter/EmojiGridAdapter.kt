package app.secure.kyber.adapters.emoji_adapter


import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import app.secure.kyber.R

class EmojiGridAdapter(
    private var emojis: List<String>,
    private val emojiClickListener: (String) -> Unit // Add this click listener
) : BaseAdapter() {

    override fun getCount(): Int = emojis.size

    override fun getItem(position: Int): Any = emojis[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view: View = convertView ?: LayoutInflater.from(parent?.context)
            .inflate(R.layout.item_emoji_grid, parent, false)

        val emojiTextView = view.findViewById<TextView>(R.id.emojiTextView)
        emojiTextView.text = emojis[position]

        // Handle emoji click and pass the emoji to the listener
        view.setOnClickListener {
            emojiClickListener(emojis[position])
        }

        return view
    }

    fun updateEmojis(newEmojis: List<String>) {
        emojis = newEmojis
        notifyDataSetChanged()
    }
}