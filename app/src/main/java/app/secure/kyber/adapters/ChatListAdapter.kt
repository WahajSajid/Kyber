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

class ChatListAdapter(
    private var context: Context,
    private val onDeleteChat: (ChatModel) -> Unit,
    private val onItemClick: (ChatModel) -> Unit
) : ListAdapter<ChatModel, ChatListAdapter.VH>(DIFF) {

    private var recyclerView: RecyclerView? = null
    private var openMenuOnion: String? = null

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ChatModel>() {
            override fun areItemsTheSame(old: ChatModel, new: ChatModel) =
                old.onionAddress == new.onionAddress

            override fun areContentsTheSame(old: ChatModel, new: ChatModel) = old == new
        }
    }

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        recyclerView = rv
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        super.onDetachedFromRecyclerView(rv)
        recyclerView = null
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int) =
        getItem(position).onionAddress?.hashCode()?.toLong() ?: position.toLong()

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = itemView.findViewById(R.id.root)
        val chatActionMenu: View = itemView.findViewById(R.id.chatActionMenu)
        val btnDelete: View = itemView.findViewById(R.id.btnDelete)
        
        val titleTextView: TextView = itemView.findViewById(R.id.tvName)
        val timeTV: TextView = itemView.findViewById(R.id.tvRemain)
        val countTV: TextView = itemView.findViewById(R.id.badgeUnread)
        val subTitleTextView: TextView = itemView.findViewById(R.id.tvSubtitle)
        val avatarIV: ImageView = itemView.findViewById(R.id.imgAvatar)

        fun bind(item: ChatModel) {
            val onion = item.onionAddress ?: ""
            
            // Toggle menu visibility based on tracking
            chatActionMenu.visibility = if (openMenuOnion == onion) View.VISIBLE else View.GONE

            // Fix: Fallback to onionAddress if name is null or blank
            titleTextView.text = if (!item.name.isNullOrBlank()) item.name else onion
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

            root.setOnClickListener {
                if (openMenuOnion != null) {
                    closeMenu()
                } else {
                    onItemClick(item)
                }
            }

            root.setOnLongClickListener {
                toggleMenu(onion)
                true
            }

            btnDelete.setOnClickListener {
                closeMenu()
                onDeleteChat(item)
            }
        }

        private fun toggleMenu(onion: String) {
            if (openMenuOnion == onion) {
                closeMenu()
            } else {
                val oldOnion = openMenuOnion
                openMenuOnion = onion
                
                // Refresh old and new items
                oldOnion?.let { notifyItemChanged(findPosByOnion(it)) }
                notifyItemChanged(adapterPosition)
                
                // Auto-scroll if menu goes off screen
                recyclerView?.let { rv ->
                    itemView.post {
                        val rect = android.graphics.Rect()
                        itemView.getGlobalVisibleRect(rect)
                        val rvRect = android.graphics.Rect()
                        rv.getGlobalVisibleRect(rvRect)
                        
                        if (rect.top < rvRect.top) {
                            rv.smoothScrollBy(0, rect.top - rvRect.top - 50)
                        } else if (rect.bottom > rvRect.bottom) {
                            rv.smoothScrollBy(0, rect.bottom - rvRect.bottom + 50)
                        }
                    }
                }
            }
        }

        private fun closeMenu() {
            val oldOnion = openMenuOnion
            openMenuOnion = null
            oldOnion?.let { notifyItemChanged(findPosByOnion(it)) }
        }

        private fun findPosByOnion(onion: String): Int {
            return currentList.indexOfFirst { it.onionAddress == onion }
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
