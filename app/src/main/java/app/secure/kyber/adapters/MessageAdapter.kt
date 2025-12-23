package app.secure.kyber.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.R
import app.secure.kyber.backend.models.MessageModel
import app.secure.kyber.roomdb.MessageEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MessageAdapter(
    private val myId: String,
    private val onClick: (MessageEntity) -> Unit = {}
) : ListAdapter<MessageEntity, MessageAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<MessageEntity>() {
            override fun areItemsTheSame(old: MessageEntity, new: MessageEntity) = old.id == new.id
            override fun areContentsTheSame(old: MessageEntity, new: MessageEntity) = old == new

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
        private val tv = view.findViewById<TextView>(R.id.tvSentMsg)
        private val tvMsgRcv = view.findViewById<TextView>(R.id.tvMsgRcv)

        private val tvRcvTime = view.findViewById<TextView>(R.id.tvRcvTime)
        private val tvSendTime = view.findViewById<TextView>(R.id.tvSentTime)
        private val rlSendMsg = view.findViewById<LinearLayout>(R.id.rlMsgSent)
        private val rlRecieveMsg = view.findViewById<LinearLayout>(R.id.rlMsgRcvd)



        fun bind(item: MessageEntity) {
            if (item.isSent){
                rlSendMsg.visibility = View.VISIBLE
                rlRecieveMsg.visibility = View.GONE
                tv.text = item.msg
                tvSendTime.text = convertDatetime(item.time)


            }else{

                rlSendMsg.visibility = View.GONE
                rlRecieveMsg.visibility = View.VISIBLE
                tvMsgRcv.text = item.msg
                tvRcvTime.text = convertDatetime(item.time)
            }
            // color/right-left, etc. can use senderId == myId
            itemView.setOnClickListener { onClick(item) }
        }

        // If you use payloads:
        // fun bindTextOnly(item: Message) { tv.text = item.text }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.msg_list_item, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    fun convertDatetime(rawMillis:String): String? {

        val instantFromMillis = Instant.ofEpochMilli(rawMillis.toLong())

//        val outFmt = DateTimeFormatter.ofPattern("dd-MMM-uuuu h:mm a").withZone(ZoneId.systemDefault())
        val outFmt = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())
        val pretty = outFmt.format(instantFromMillis)
        return pretty
    }

    // If you used payloads:
    // override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
    //     if ("payload_text" in payloads) holder.bindTextOnly(getItem(position))
    //     else super.onBindViewHolder(holder, position, payloads)
    // }
}
