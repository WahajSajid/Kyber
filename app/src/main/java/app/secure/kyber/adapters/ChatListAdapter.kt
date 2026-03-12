package app.secure.kyber.adapters

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.R
import app.secure.kyber.adapters.MessageAdapter.Companion.DIFF
import app.secure.kyber.backend.models.ChatModel
import app.secure.kyber.roomdb.MessageEntity
import com.bumptech.glide.Glide
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter


class ChatListAdapter( private var context : Context,private val onItemClick: (ChatModel) -> Unit ) :
    ListAdapter<ChatModel, ChatListAdapter.VH>(DIFF)  {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ChatModel>() {
            override fun areItemsTheSame(old: ChatModel, new: ChatModel) = old.id == new.id
            override fun areContentsTheSame(old: ChatModel, new: ChatModel) = old == new

            // optional partial update payload example (uncomment if needed)
            // override fun getChangePayload(oldItem: Message, newItem: Message): Any? =
            //     if (oldItem.text != newItem.text) "payload_text" else null
        }
    }

    init {
        setHasStableIds(true)   // smooth animations
    }
    override fun getItemId(position: Int) = getItem(position).id.hashCode().toLong()

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val titleTextView: TextView = itemView.findViewById(R.id.tvName)
        val timeTV: TextView = itemView.findViewById(R.id.tvRemain)
        val countTV: TextView = itemView.findViewById(R.id.badgeUnread)
        val subTitleTextView: TextView = itemView.findViewById(R.id.tvSubtitle)
        val avatarIV: ImageView = itemView.findViewById(R.id.imgAvatar)


        fun bind(item: ChatModel) {
            titleTextView.text = item.name
            subTitleTextView.text = item.lastMessage
            timeTV.text = convertDatetime(item.time!!)

            if (item.unreadCount == 0) {
                countTV.visibility = View.GONE
            } else {
                countTV.visibility = View.GONE
            }
            countTV.text = item.unreadCount.toString()

            Glide.with(context)
                .load(item.avatarRes)
                .placeholder(R.drawable.new_ic)
                .into(avatarIV)

            itemView.setOnClickListener {
                onItemClick(item)
            }

        }

        // If you use payloads:
        // fun bindTextOnly(item: Message) { tv.text = item.text }
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatListAdapter.VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: ChatListAdapter.VH, position: Int) {
        holder.bind(getItem(position))
    }

    fun convertDatetime(rawMillis:String): String? {

        val instantFromMillis = Instant.ofEpochMilli(rawMillis.toLong())

//        val outFmt = DateTimeFormatter.ofPattern("dd-MMM-uuuu h:mm a").withZone(ZoneId.systemDefault())
        val outFmt = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())
        val pretty = outFmt.format(instantFromMillis)

        return pretty
    }
}