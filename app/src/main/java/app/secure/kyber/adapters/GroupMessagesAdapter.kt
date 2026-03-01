package app.secure.kyber.adapters

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.R
import app.secure.kyber.roomdb.GroupMessageEntity
import app.secure.kyber.Other.WaveformView
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
import org.json.JSONArray
import java.util.Locale

class GroupMessagesAdapter(
    private val onClick: (GroupMessageEntity) -> Unit = {},
    private val onLongClick: (View, GroupMessageEntity) -> Unit = { _, _ -> },
    private val myId: String
) : ListAdapter<GroupMessageEntity, GroupMessagesAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<GroupMessageEntity>() {
            override fun areItemsTheSame(old: GroupMessageEntity, new: GroupMessageEntity) =
                old.messageId == new.messageId

            override fun areContentsTheSame(old: GroupMessageEntity, new: GroupMessageEntity) =
                old == new
        }
        private val SPEED_STEPS = listOf(1.0f, 1.5f, 2.0f)
    }

    private var activePlayer:    MediaPlayer? = null
    private var activeUri:       String?      = null
    private var activeHolder:    VH?          = null
    private var activeHandler:   Handler?     = null
    private var activeSpeedIdx:  Int          = 0

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int) = getItem(position).messageId.hashCode().toLong()

    fun releasePlayer() { stopPlayback(resetUi = true) }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvSentMsg = view.findViewById<TextView>(R.id.tvSentMsg)
        val tvMsgRcv = view.findViewById<TextView>(R.id.tvMsgRcv)
        val tvRcvTime = view.findViewById<TextView>(R.id.tvRcvTime)
        val tvSendTime = view.findViewById<TextView>(R.id.tvSentTime)
        val rlSendMsg = view.findViewById<LinearLayout>(R.id.rlMsgSent)
        val rlRecieveMsg = view.findViewById<LinearLayout>(R.id.rlMsgRcvd)
        val receivedMessageTime = view.findViewById<LinearLayout>(R.id.receivedMessageTime)
        val sentMessageTime = view.findViewById<LinearLayout>(R.id.sentMessageTime)
        val senderName = view.findViewById<TextView>(R.id.sender_name)
        val senderId = view.findViewById<TextView>(R.id.sender_id)

        val flMediaSent = view.findViewById<FrameLayout>(R.id.flMediaSent)
        val ivMediaSent = view.findViewById<ShapeableImageView>(R.id.ivMediaSent)
        val ivPlaySent = view.findViewById<ImageView>(R.id.ivPlaySent)

        val flMediaRcv = view.findViewById<FrameLayout>(R.id.flMediaRcv)
        val ivMediaRcv = view.findViewById<ShapeableImageView>(R.id.ivMediaRcv)
        val ivPlayRcv = view.findViewById<ImageView>(R.id.ivPlayRcv)

        // Audio sent
        val sentAudio       : LinearLayout = view.findViewById(R.id.sentAudioContainer)
        val ivSentPlayPause : FrameLayout  = view.findViewById(R.id.ivSentPlayPause)
        val ivSentPlayIcon  : ImageView    = view.findViewById(R.id.ivSentPlayIcon)
        val waveformSent    : WaveformView = view.findViewById(R.id.waveformSent)
        val tvSentSpeed     : TextView     = view.findViewById(R.id.tvSentSpeed)
        val tvSentDuration  : TextView     = view.findViewById(R.id.tvSentAudioDuration)

        // Audio rcv
        val rcvAudio        : LinearLayout = view.findViewById(R.id.rcvAudioContainer)
        val ivRcvPlayPause  : FrameLayout  = view.findViewById(R.id.ivRcvPlayPause)
        val ivRcvPlayIcon   : ImageView    = view.findViewById(R.id.ivRcvPlayIcon)
        val waveformRcv     : WaveformView = view.findViewById(R.id.waveformRcv)
        val tvRcvSpeed      : TextView     = view.findViewById(R.id.tvRcvSpeed)
        val tvRcvDuration   : TextView     = view.findViewById(R.id.tvRcvAudioDuration)

        val tvSentReaction : TextView = view.findViewById(R.id.tvSentReaction)
        val tvRcvReaction : TextView = view.findViewById(R.id.tvRcvReaction)

        fun playPauseFrame(sent: Boolean) = if (sent) ivSentPlayPause else ivRcvPlayPause
        fun playIcon(sent: Boolean)       = if (sent) ivSentPlayIcon  else ivRcvPlayIcon
        fun waveform(sent: Boolean)       = if (sent) waveformSent    else waveformRcv
        fun speedBadge(sent: Boolean)     = if (sent) tvSentSpeed     else tvRcvSpeed
        fun durationLabel(sent: Boolean)  = if (sent) tvSentDuration  else tvRcvDuration
        fun reaction(sent: Boolean)       = if (sent) tvSentReaction  else tvRcvReaction
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.group_message_item, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val currentItem = getItem(position)
        val showTime = if (position < itemCount - 1) {
            val nextItem = getItem(position + 1)
            convertDatetime(currentItem.time) != convertDatetime(nextItem.time)
        } else {
            true
        }

        val type = currentItem.type.uppercase(Locale.US)
        val isSent = currentItem.senderId == myId
        val isMedia = type == "IMAGE" || type == "VIDEO"
        val isAudio = type == "AUDIO"

        holder.rlSendMsg.isVisible = isSent
        holder.rlRecieveMsg.isVisible = !isSent
        holder.sentMessageTime.isVisible = isSent && showTime
        holder.receivedMessageTime.isVisible = !isSent && showTime

        when {
            isAudio -> bindAudio(holder, currentItem, isSent)
            isMedia -> bindMedia(holder, currentItem, isSent, type)
            else    -> bindText(holder, currentItem, isSent)
        }

        if (isSent) {
            holder.tvSendTime.text = convertDatetime(currentItem.time)
        } else {
            holder.senderName.text = currentItem.senderName
            holder.senderId.text = currentItem.senderId
            holder.tvRcvTime.text = convertDatetime(currentItem.time)
        }

        // ===== REACTION HANDLING - THIS IS THE KEY PART =====
        val reactionView = holder.reaction(isSent)
        if (currentItem.reaction.isNotEmpty()) {
            reactionView.text = currentItem.reaction
            reactionView.visibility = View.VISIBLE
        } else {
            reactionView.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onClick(currentItem) }
        holder.itemView.setOnLongClickListener {
            onLongClick(it, currentItem)
            true
        }
    }

    private fun bindText(h: VH, item: GroupMessageEntity, sent: Boolean) {
        h.sentAudio.isVisible = false
        h.rcvAudio.isVisible  = false
        h.flMediaSent.isVisible = false
        h.flMediaRcv.isVisible = false
        h.rlSendMsg.setBackgroundResource(R.drawable.sent_msg_bg)

        if (sent) {
            h.tvSentMsg.isVisible = true
            h.tvSentMsg.text       = item.msg
            h.tvMsgRcv.isVisible  = false
        } else {
            h.tvMsgRcv.isVisible  = true
            h.tvMsgRcv.text        = item.msg
            h.tvSentMsg.isVisible = false
        }
    }

    private fun bindMedia(h: VH, item: GroupMessageEntity, sent: Boolean, type: String) {
        h.sentAudio.isVisible = false
        h.rcvAudio.isVisible  = false
        h.tvSentMsg.isVisible    = false
        h.tvMsgRcv.isVisible     = false
        h.rlSendMsg.setBackgroundResource(R.drawable.sent_msg_bg)

        val uriStr = item.uri ?: item.msg
        val uri    = try { uriStr.toUri() } catch (_: Exception) { null }

        if (sent) {
            h.flMediaSent.isVisible  = true
            h.flMediaRcv.isVisible  = false
            h.ivMediaSent.isVisible = true
            h.ivPlaySent.isVisible  = type == "VIDEO"
            if (uri != null) Glide.with(h.itemView.context).load(uri).into(h.ivMediaSent)

            if (item.msg.isNotBlank() && item.msg != "photo" && item.msg != "video" ) {
                h.tvSentMsg.text = item.msg; h.tvSentMsg.isVisible = true
            }
        } else {
            h.flMediaRcv.isVisible  = true
            h.flMediaSent.isVisible  = false
            h.ivMediaRcv.isVisible  = true
            h.ivPlayRcv.isVisible   = type == "VIDEO"
            if (uri != null) Glide.with(h.itemView.context).load(uri).into(h.ivMediaRcv)

            if (item.msg.isNotBlank() && item.msg != "photo" && item.msg != "video") {
                h.tvMsgRcv.text = item.msg; h.tvMsgRcv.isVisible = true
            }
        }
    }

    private fun bindAudio(h: VH, item: GroupMessageEntity, sent: Boolean) {
        h.tvSentMsg.isVisible    = false
        h.tvMsgRcv.isVisible     = false
        h.flMediaSent.isVisible = false
        h.flMediaRcv.isVisible = false
        h.rlSendMsg.setBackgroundResource(R.drawable.sent_msg_bg)

        h.sentAudio.isVisible = sent
        h.rcvAudio.isVisible  = !sent

        val uriStr = item.uri ?: return
        val amplitudes = decodeAmplitudes(item.ampsJson)
        h.waveform(sent).setAmplitudes(amplitudes)
        val totalMs = getTotalDuration(h.itemView.context, uriStr)
        h.durationLabel(sent).text = formatDuration(totalMs)

        if (activeUri == uriStr) {
            syncPlayingUi(h, sent)
        } else {
            h.playIcon(sent).setImageResource(android.R.drawable.ic_media_play)
            h.waveform(sent).setProgress(0f)
            h.speedBadge(sent).text = SPEED_STEPS[0].toLabel()
        }

        h.playPauseFrame(sent).setOnClickListener {
            if (activeUri == uriStr) {
                val player = activePlayer
                if (player != null && player.isPlaying) {
                    player.pause()
                    activeHandler?.removeCallbacksAndMessages(null)
                    h.playIcon(sent).setImageResource(android.R.drawable.ic_media_play)
                } else if (player != null) {
                    player.start()
                    h.playIcon(sent).setImageResource(android.R.drawable.ic_media_pause)
                    startProgressUpdater(h, sent, uriStr)
                }
            } else {
                stopPlayback(resetUi = true)
                startPlayback(h, sent, uriStr)
            }
        }

        h.waveform(sent).setOnSeekListener { progress ->
            if (activeUri == uriStr) {
                activePlayer?.let { player ->
                    val seekTo = (progress * player.duration).toInt()
                    player.seekTo(seekTo)
                    h.waveform(sent).setProgress(progress)
                }
            } else {
                startPlayback(h, sent, uriStr)
                activePlayer?.let { player ->
                    val seekTo = (progress * player.duration).toInt()
                    player.seekTo(seekTo)
                }
            }
        }

        h.speedBadge(sent).setOnClickListener {
            activeSpeedIdx = if (activeUri == uriStr) {
                (activeSpeedIdx + 1) % SPEED_STEPS.size
            } else 0
            val speed = SPEED_STEPS[activeSpeedIdx]
            h.speedBadge(sent).text = speed.toLabel()
            if (activeUri == uriStr) applySpeed(speed)
        }
    }

    private fun startPlayback(h: VH, sent: Boolean, uriStr: String) {
        val player = try {
            MediaPlayer().apply {
                setDataSource(h.itemView.context, uriStr.toUri())
                prepare()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }
        activePlayer   = player
        activeUri      = uriStr
        activeHolder   = h
        activeSpeedIdx = 0
        applySpeed(SPEED_STEPS[0])
        player.start()
        h.playIcon(sent).setImageResource(android.R.drawable.ic_media_pause)
        h.speedBadge(sent).text = SPEED_STEPS[0].toLabel()
        h.durationLabel(sent).text = formatDuration(player.duration)
        startProgressUpdater(h, sent, uriStr)
        player.setOnCompletionListener {
            activeHandler?.removeCallbacksAndMessages(null)
            h.playIcon(sent).setImageResource(android.R.drawable.ic_media_play)
            h.waveform(sent).setProgress(0f)
            h.durationLabel(sent).text = formatDuration(player.duration)

            val currentPos = h.bindingAdapterPosition
            activePlayer  = null
            activeUri     = null
            activeHolder  = null

            if (currentPos != -1) {
                playNextAudioIfAvailable(currentPos)
            }
        }
    }

    private fun playNextAudioIfAvailable(currentPos: Int) {
        for (i in (currentPos + 1) until itemCount) {
            val nextItem = getItem(i)
            if (nextItem.type.uppercase(Locale.US) == "AUDIO") {
                notifyItemChanged(i, "START_PLAYBACK")
                break
            }
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("START_PLAYBACK")) {
            val item = getItem(position)
            val isSent = item.senderId == myId
            val uriStr = item.uri ?: return
            startPlayback(holder, isSent, uriStr)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    private fun startProgressUpdater(h: VH, sent: Boolean, uriStr: String) {
        val handler = Handler(Looper.getMainLooper())
        activeHandler = handler
        handler.post(object : Runnable {
            override fun run() {
                val player = activePlayer ?: return
                if (activeUri != uriStr) return
                if (player.isPlaying) {
                    val progress = player.currentPosition.toFloat() / player.duration
                    h.waveform(sent).setProgress(progress)
                    h.durationLabel(sent).text = formatDuration(player.duration - player.currentPosition)
                }
                handler.postDelayed(this, 50)
            }
        })
    }

    private fun syncPlayingUi(h: VH, sent: Boolean) {
        val player = activePlayer ?: return
        h.playIcon(sent).setImageResource(
            if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        h.speedBadge(sent).text = SPEED_STEPS[activeSpeedIdx].toLabel()
        if (player.isPlaying) startProgressUpdater(h, sent, activeUri!!)
    }

    private fun stopPlayback(resetUi: Boolean) {
        activeHandler?.removeCallbacksAndMessages(null)
        activePlayer?.stop()
        activePlayer?.release()
        activePlayer = null
        if (resetUi) {
            activeHolder?.let { h ->
                val pos = h.bindingAdapterPosition
                if (pos != -1) {
                    val sent = getItem(pos).senderId == myId
                    h.playIcon(sent).setImageResource(android.R.drawable.ic_media_play)
                    h.waveform(sent).setProgress(0f)
                }
            }
        }
        activeUri    = null
        activeHolder = null
    }

    private fun applySpeed(speed: Float) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            activePlayer?.let { it.playbackParams = it.playbackParams.setSpeed(speed) }
        }
    }

    private fun Float.toLabel() = if (this == 1.0f) "1x" else "${this}x"

    private fun decodeAmplitudes(json: String?): List<Float> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i -> arr.getDouble(i).toFloat() }
        } catch (e: Exception) { emptyList() }
    }

    private fun formatDuration(ms: Int): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.getDefault(), "%d:%02d", totalSec / 60, totalSec % 60)
    }

    private fun getTotalDuration(context: Context, uriStr: String): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uriStr.toUri())
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt() ?: 0
        } catch (e: Exception) { 0 } finally { retriever.release() }
    }

    private fun convertDatetime(time: String): String {
        return try {
            val formatter = java.text.SimpleDateFormat("hh:mm a", Locale.getDefault())
            formatter.format(java.util.Date(time.toLong()))
        } catch (e: Exception) { "" }
    }
}