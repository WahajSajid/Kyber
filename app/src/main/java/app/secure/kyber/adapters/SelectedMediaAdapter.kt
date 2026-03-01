package app.secure.kyber.adapters

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.R
import com.bumptech.glide.Glide

class SelectedMediaAdapter(
    private val items: MutableList<app.secure.kyber.fragments.ChatFragment.SelectedMedia>,
    private val onRemove: (Int) -> Unit,
    private val onCaptionChanged: (Int, String) -> Unit
) : RecyclerView.Adapter<SelectedMediaAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumb: ImageView = view.findViewById(R.id.ivThumb)
        val btnRemove: ImageButton = view.findViewById(R.id.btnRemove)
        val etCaption: EditText = view.findViewById(R.id.etCaption)

        private var watcher: TextWatcher? = null

        fun bind(pos: Int) {
            val item = items[pos]
            Glide.with(ivThumb.context)
                .load(item.uri)
                .centerCrop()
                .into(ivThumb)

            btnRemove.setOnClickListener {
                onRemove(bindingAdapterPosition)
            }

            watcher?.let { etCaption.removeTextChangedListener(it) }

            etCaption.setText(item.caption ?: "")
            watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    onCaptionChanged(bindingAdapterPosition, s?.toString() ?: "")
                }
                override fun afterTextChanged(s: Editable?) {}
            }
            etCaption.addTextChangedListener(watcher)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_selected_media, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(position)
    }

    override fun getItemCount(): Int = items.size
}