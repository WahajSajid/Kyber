package app.secure.kyber.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.Other.Objects
import app.secure.kyber.R
import app.secure.kyber.backend.models.ChatModel
import com.bumptech.glide.Glide
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ChatListAdapter(private var context: Context, private val onItemClick: (ChatModel) -> Unit) :
    ListAdapter<ChatModel, ChatListAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ChatModel>() {
            override fun areItemsTheSame(old: ChatModel, new: ChatModel) =
                old.onionAddress == new.onionAddress

            override fun areContentsTheSame(old: ChatModel, new: ChatModel) = old == new
        }
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int) =
        getItem(position).onionAddress?.hashCode()?.toLong() ?: position.toLong()

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val titleTextView: TextView = itemView.findViewById(R.id.tvName)
        val timeTV: TextView = itemView.findViewById(R.id.tvRemain)
        val countTV: TextView = itemView.findViewById(R.id.badgeUnread)
        val subTitleTextView: TextView = itemView.findViewById(R.id.tvSubtitle)
        val avatarIV: ImageView = itemView.findViewById(R.id.imgAvatar)

        fun bind(item: ChatModel) {
            // Fix: Fallback to onionAddress if name is null or blank
            titleTextView.text = if (!item.name.isNullOrBlank()) item.name else item.onionAddress
            subTitleTextView.text = item.lastMessage
            timeTV.text = item.time?.let {
                it.toLongOrNull()?.let { ms -> Objects.formatTimestamp(ms) } ?: ""
            } ?: ""

            if (item.unreadCount == 0 || item.unreadCount == null) {
                countTV.visibility = View.GONE
            } else {
                countTV.visibility = View.VISIBLE
                countTV.text = item.unreadCount.toString()
            }

            Glide.with(context)
                .load(item.avatarRes)
                .placeholder(R.drawable.ano_ic)
                .into(avatarIV)

            itemView.setOnClickListener {
                onItemClick(item)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatListAdapter.VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: ChatListAdapter.VH, position: Int) {
        holder.bind(getItem(position))
    }
}
