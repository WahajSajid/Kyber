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
import androidx.xr.scenecore.GroupEntity
import app.secure.kyber.R
import app.secure.kyber.adapters.MessageAdapter.Companion.DIFF
import app.secure.kyber.backend.models.ChatModel
import app.secure.kyber.roomdb.GroupsEntity
import app.secure.kyber.roomdb.MessageEntity
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit


class GroupChatListAdapter(
    private var context: Context,
    private val onItemClick: (GroupsEntity) -> Unit
) :
    ListAdapter<GroupsEntity, GroupChatListAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<GroupsEntity>() {
            override fun areItemsTheSame(old: GroupsEntity, new: GroupsEntity) =
                old.groupId == new.groupId

            override fun areContentsTheSame(old: GroupsEntity, new: GroupsEntity) = old == new

            // optional partial update payload example (uncomment if needed)
            // override fun getChangePayload(oldItem: Message, newItem: Message): Any? =
            //     if (oldItem.text != newItem.text) "payload_text" else null
        }
    }

    init {
        setHasStableIds(true)   // smooth animations
    }

    override fun getItemId(position: Int) = getItem(position).groupId.hashCode().toLong()

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val titleTextView: TextView = itemView.findViewById(R.id.tvName)
        val timeTV: TextView = itemView.findViewById(R.id.tvRemain)
        val countTV: TextView = itemView.findViewById(R.id.badgeUnread)
        val subTitleTextView: TextView = itemView.findViewById(R.id.tvSubtitle)
        val avatarIV: ImageView = itemView.findViewById(R.id.imgAvatar)
        val senderName: TextView = itemView.findViewById(R.id.sender_name)


        fun bind(item: GroupsEntity) {
            titleTextView.text = item.groupName
            subTitleTextView.text = item.lastMessage

            if (item.timeSpan != 0.toLong()) {
                timeTV.text = formatTimestamp(item.timeSpan)
            } else {
                timeTV.text = formatTimestamp(item.createdAt)
            }

            if (item.newMessagesCount == 0) {
                countTV.visibility = View.GONE
            } else {
                countTV.visibility = View.GONE
            }
            countTV.text = item.newMessagesCount.toString()

            Glide.with(context)
                .load(item.profileImageResId)
                .placeholder(R.drawable.group_ic)
                .into(avatarIV)

            itemView.setOnClickListener {
                onItemClick(item)
            }

        }

        // If you use payloads:
        // fun bindTextOnly(item: Message) { tv.text = item.text }
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupChatListAdapter.VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.group_chat_list_item, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: GroupChatListAdapter.VH, position: Int) {
        holder.bind(getItem(position))
    }

    fun convertDatetime(rawMillis: String): String? {

        val instantFromMillis = Instant.ofEpochMilli(rawMillis.toLong())

//        val outFmt = DateTimeFormatter.ofPattern("dd-MMM-uuuu h:mm a").withZone(ZoneId.systemDefault())
        val outFmt = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())
        val pretty = outFmt.format(instantFromMillis)

        return pretty
    }

    fun formatTimestamp(timestampMillis: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestampMillis

        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestampMillis

        val todayCalendar = Calendar.getInstance()
        todayCalendar.timeInMillis = now

        val yesterdayCalendar = Calendar.getInstance()
        yesterdayCalendar.timeInMillis = now
        yesterdayCalendar.add(Calendar.DAY_OF_YEAR, -1)

        return when {
            // Today - show time (10:44 AM)
            isSameDay(calendar, todayCalendar) -> {
                SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timestampMillis))
            }

            // Yesterday
            isSameDay(calendar, yesterdayCalendar) -> {
                "Yesterday"
            }

            // Within last 7 days - show day name (Monday, Tuesday, etc.)
            diff < TimeUnit.DAYS.toMillis(7) -> {
                SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(timestampMillis))
            }

            // Within same year - show date without year (Dec 20)
            calendar.get(Calendar.YEAR) == todayCalendar.get(Calendar.YEAR) -> {
                SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(timestampMillis))
            }

            // Older - show full date (Dec 20, 2024)
            else -> {
                SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(timestampMillis))
            }
        }
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

}