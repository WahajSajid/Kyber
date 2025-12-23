package app.secure.kyber.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.R
import app.secure.kyber.roomdb.ContactEntity

class ContactListAdapter(
    private var context: Context,
    private val onItemClick: (ContactEntity) -> Unit
) : ListAdapter<ContactEntity, ContactListAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ContactEntity>() {
            override fun areItemsTheSame(old: ContactEntity, new: ContactEntity) = old.id == new.id
            override fun areContentsTheSame(old: ContactEntity, new: ContactEntity) = old == new
        }
    }

    init {
        setHasStableIds(true) // smooth animations
    }

    override fun getItemId(position: Int) = getItem(position).id.hashCode().toLong()

    // Inner class for the ViewHolder
    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val titleTextView: TextView = itemView.findViewById(R.id.tvName)
        val userId: TextView = itemView.findViewById(R.id.tvID)
        val letter: TextView = itemView.findViewById(R.id.letter)

        fun bind(item: ContactEntity) {
            titleTextView.text = item.name ?: ""
            userId.text = item.id ?: "" // keep the id visible if needed
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactListAdapter.VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.contact_list_item, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: ContactListAdapter.VH, position: Int) {
        val current = getItem(position)
        holder.bind(current)

        // determine the letter for the current item
        val name = (current.name ?: "").trim()
        val currentLetterChar = name.firstOrNull()?.let {
            if (it.isLetter()) it.uppercaseChar() else it.uppercaseChar()
        } ?: '#'

        // determine if we should show the letter: visible only when
        // it's the first item OR its first letter differs from the previous item's first letter
        val showLetter = if (position == 0) {
            true
        } else {
            val previous = getItem(position - 1)
            val prevName = (previous.name ?: "").trim()
            val prevLetterChar = prevName.firstOrNull()?.let {
                if (it.isLetter()) it.uppercaseChar() else it.uppercaseChar()
            } ?: '#'
            currentLetterChar != prevLetterChar
        }

        holder.letter.text = currentLetterChar.toString()
        holder.letter.visibility = if (showLetter) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener {
            onItemClick(current)
        }
    }
}