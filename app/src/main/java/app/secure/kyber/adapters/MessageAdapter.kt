package app.secure.kyber.adapters

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.Other.WaveformView
import app.secure.kyber.R
import app.secure.kyber.roomdb.MessageEntity
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import org.json.JSONArray
import java.util.Locale

@Suppress("DEPRECATION")
class MessageAdapter(
    private val myId: String,
    private val onClick: (MessageEntity) -> Unit = {},
    private val onLongClick: (View, MessageEntity) -> Unit = { _, _ -> },
    private val onEmojiSelected: (MessageEntity, String) -> Unit = { _, _ -> },
    private val onMoreEmojisClicked: (MessageEntity) -> Unit = {},
    private var recentEmojis: List<String> = listOf("👌", "😊", "😂", "😍", "💜", "🎮")
) : ListAdapter<MessageEntity, MessageAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<MessageEntity>() {
            override fun areItemsTheSame(a: MessageEntity, b: MessageEntity) = a.id == b.id
            override fun areContentsTheSame(a: MessageEntity, b: MessageEntity) = a == b
        }
        private val SPEED_STEPS = listOf(1.0f, 1.5f, 2.0f)
    }

    private var activePlayer: MediaPlayer? = null
    private var activeUri: String? = null
    private var activeHolder: VH? = null
    private var activeHandler: android.os.Handler? = null
    private var activeSpeedIdx: Int = 0
    private var openMenuPosition: Int = -1

    init { setHasStableIds(true) }

    override fun getItemId(position: Int) = getItem(position).id.hashCode().toLong()

    fun releasePlayer() { stopPlayback(resetUi = true) }

    fun updateRecentEmojis(newList: List<String>) {
        this.recentEmojis = newList
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvSent        : TextView      = view.findViewById(R.id.tvSentMsg)
        val tvRcv         : TextView      = view.findViewById(R.id.tvMsgRcv)
        val tvSentTime    : TextView      = view.findViewById(R.id.tvSentTime)
        val tvRcvTime     : TextView      = view.findViewById(R.id.tvRcvTime)
        val rlSent        : LinearLayout  = view.findViewById(R.id.rlMsgSent)
        val rlRcvd        : LinearLayout  = view.findViewById(R.id.rlMsgRcvd)

        val sentMedia     : FrameLayout   = view.findViewById(R.id.sent_message_media)
        val rcvdMedia     : FrameLayout   = view.findViewById(R.id.received_message_media)
        val ivSentMedia   : ImageView     = view.findViewById(R.id.ivSentMedia)
        val ivRcvMedia    : ImageView     = view.findViewById(R.id.ivRcvMedia)
        val ivSentPlay    : ImageView     = view.findViewById(R.id.ivSentPlay)
        val ivRcvPlay     : ImageView     = view.findViewById(R.id.ivRcvPlay)

        val sentAudio       : LinearLayout = view.findViewById(R.id.sentAudioContainer)
        val ivSentPlayPause : ConstraintLayout  = view.findViewById(R.id.ivSentPlayPause)
        val ivSentPlayIcon  : ImageView    = view.findViewById(R.id.ivSentPlayIcon)
        val waveformSent    : WaveformView = view.findViewById(R.id.waveformSent)
        val tvSentSpeed     : TextView     = view.findViewById(R.id.tvSentSpeed)
        val tvSentDuration  : TextView     = view.findViewById(R.id.tvSentAudioDuration)

        val rcvAudio        : LinearLayout = view.findViewById(R.id.rcvAudioContainer)
        val ivRcvPlayPause  : FrameLayout  = view.findViewById(R.id.ivRcvPlayPause)
        val ivRcvPlayIcon   : ImageView    = view.findViewById(R.id.ivRcvPlayIcon)
        val waveformRcv     : WaveformView = view.findViewById(R.id.waveformRcv)
        val tvRcvSpeed      : TextView     = view.findViewById(R.id.tvRcvSpeed)
        val tvRcvDuration   : TextView     = view.findViewById(R.id.tvRcvAudioDuration)
        val sentMessageTimeLayout: LinearLayout? = view.findViewById(R.id.sent_message_time_layout)
        val receivedMessageTimeLayout: LinearLayout? = view.findViewById(R.id.received_message_time_layout)
        val sentTimeAudio: TextView? = view.findViewById(R.id.tvSentTimeAudio)
        val rcvTimeAudio: TextView? = view.findViewById(R.id.tvRcvTimeAudio)

        val tvSentReaction : TextView = view.findViewById(R.id.tvSentReaction)
        val tvRcvReaction : TextView = view.findViewById(R.id.tvReceivedReaction)

        // Changed to View to prevent ClassCastExceptions from included layouts
        val actionMenuSent: View = view.findViewById(R.id.actionMenuSent)
        val actionMenuReceived: View = view.findViewById(R.id.actionMenuReceived)
        val sentEmojiBar: View = view.findViewById(R.id.emoji_reaction_bar_sent)
        val receivedEmojiBar: View = view.findViewById(R.id.emoji_reaction_bar_received)

        val rvRecentEmojisSent: RecyclerView = sentEmojiBar.findViewById(R.id.rvRecentEmojis)
        val rvRecentEmojisReceived: RecyclerView = receivedEmojiBar.findViewById(R.id.rvRecentEmojis)
        val btnMoreSent: ImageButton = sentEmojiBar.findViewById(R.id.emojiMore)
        val btnMoreReceived: ImageButton = receivedEmojiBar.findViewById(R.id.emojiMore)

        fun playPauseFrame(sent: Boolean) = if (sent) ivSentPlayPause else ivRcvPlayPause
        fun playIcon(sent: Boolean)       = if (sent) ivSentPlayIcon  else ivRcvPlayIcon
        fun waveform(sent: Boolean)       = if (sent) waveformSent    else waveformRcv
        fun speedBadge(sent: Boolean)     = if (sent) tvSentSpeed     else tvRcvSpeed
        fun durationLabel(sent: Boolean)  = if (sent) tvSentDuration  else tvRcvDuration
        fun reaction(sent: Boolean)       = if (sent) tvSentReaction  else tvRcvReaction
        fun emojiBar(sent: Boolean)       = if (sent) sentEmojiBar    else receivedEmojiBar
        fun actionMenu(sent: Boolean)     = if (sent) actionMenuSent  else actionMenuReceived
        fun rvEmojis(sent: Boolean)       = if (sent) rvRecentEmojisSent else rvRecentEmojisReceived
        fun btnMore(sent: Boolean)        = if (sent) btnMoreSent     else btnMoreReceived
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.msg_list_item, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val adapterPosition = holder.bindingAdapterPosition
        if (adapterPosition == RecyclerView.NO_POSITION) return

        val item    = getItem(adapterPosition)
        val type    = item.type.uppercase(Locale.US)
        val isSent  = item.isSent
        val isMedia = type == "IMAGE" || type == "VIDEO"
        val isAudio = type == "AUDIO"

        holder.rlSent.isVisible = isSent
        holder.rlRcvd.isVisible = !isSent

        val isMenuOpen = adapterPosition == openMenuPosition
        val emojiBar = holder.emojiBar(isSent)
        val actionMenu = holder.actionMenu(isSent)

        if (isMenuOpen) {
            if (emojiBar.visibility != View.VISIBLE) {
                emojiBar.visibility = View.VISIBLE
                animateViewIn(emojiBar)
            }
            if (actionMenu.visibility != View.VISIBLE) {
                actionMenu.visibility = View.VISIBLE
                animateViewIn(actionMenu)
            }
        } else {
            if (emojiBar.visibility == View.VISIBLE) {
                animateViewOut(emojiBar)
            } else {
                emojiBar.visibility = View.GONE
            }
            if (actionMenu.visibility == View.VISIBLE) {
                animateViewOut(actionMenu)
            } else {
                actionMenu.visibility = View.GONE
            }
        }

        val currentReaction = item.reaction
        val emojiAdapter = RecentEmojiAdapter(recentEmojis, currentReaction) { emoji ->
            val finalEmoji = if (emoji == currentReaction) "" else emoji
            onEmojiSelected(item, finalEmoji)
            closeMenu()
        }

        holder.rvEmojis(isSent).apply {
            layoutManager = LinearLayoutManager(holder.itemView.context, LinearLayoutManager.HORIZONTAL, false)
            adapter = emojiAdapter
        }

        holder.btnMore(isSent).setOnClickListener {
            onMoreEmojisClicked(item)
            closeMenu()
        }

        when {
            isAudio -> bindAudio(holder, item, isSent)
            isMedia -> bindMedia(holder, item, isSent, type)
            else    -> bindText(holder, item, isSent)
        }

        holder.tvSentTime.text = convertDatetime(item.time)
        holder.tvRcvTime.text  = convertDatetime(item.time)
        holder.sentTimeAudio?.text = convertDatetime(item.time)
        holder.rcvTimeAudio?.text = convertDatetime(item.time)

        val reactionView = holder.reaction(isSent)
        if (item.reaction.isNotEmpty()) {
            reactionView.text = item.reaction
            reactionView.visibility = View.VISIBLE
        } else {
            reactionView.visibility = View.GONE
        }

        holder.itemView.setOnClickListener {
            if (openMenuPosition != -1) closeMenu()
            else onClick(item)
        }

        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                showMenuWithAnimation(pos)
            }
            true
        }

        holder.actionMenu(isSent).findViewById<View>(R.id.btnReply)?.setOnClickListener { closeMenu(); onLongClick(it, item) }
        holder.actionMenu(isSent).findViewById<View>(R.id.btnDelete)?.setOnClickListener { closeMenu(); onLongClick(it, item) }
        holder.actionMenu(isSent).findViewById<View>(R.id.btnCopy)?.setOnClickListener { closeMenu(); onLongClick(it, item) }
        holder.actionMenu(isSent).findViewById<View>(R.id.btnForward)?.setOnClickListener { closeMenu(); onLongClick(it, item) }
        holder.actionMenu(isSent).findViewById<View>(R.id.btnInfo)?.setOnClickListener { closeMenu(); onLongClick(it, item) }
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }

        val adapterPosition = holder.bindingAdapterPosition
        if (adapterPosition == RecyclerView.NO_POSITION) return

        if (payloads.contains("START_PLAYBACK")) {
            val item = getItem(adapterPosition)
            val isSent = item.isSent
            val uriStr = item.uri ?: return
            startPlayback(holder, isSent, uriStr)
        }
    }

    private fun showMenuWithAnimation(position: Int) {
        val oldPos = openMenuPosition
        openMenuPosition = position
        if (oldPos != -1 && oldPos != position) {
            notifyItemChanged(oldPos)
        }
        notifyItemChanged(position)
    }

    fun closeMenu() {
        if (openMenuPosition != -1) {
            val pos = openMenuPosition
            openMenuPosition = -1
            notifyItemChanged(pos)
        }
    }

    private fun animateViewIn(view: View) {
        view.alpha = 0f
        view.scaleX = 0.8f
        view.scaleY = 0.8f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
            .start()
    }

    private fun animateViewOut(view: View) {
        view.animate()
            .alpha(0f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .setDuration(150)
            .withEndAction {
                view.visibility = View.GONE
                view.scaleX = 1f
                view.scaleY = 1f
                view.alpha = 1f
            }
            .start()
    }

    private fun bindText(h: VH, item: MessageEntity, sent: Boolean) {
        h.sentAudio.isVisible = false
        h.rcvAudio.isVisible  = false
        h.sentMedia.isVisible = false
        h.rcvdMedia.isVisible = false
        h.rlSent.setBackgroundResource(R.drawable.sent_msg_bg)
        h.sentMessageTimeLayout?.visibility = View.VISIBLE
        h.receivedMessageTimeLayout?.visibility = View.VISIBLE

        if (sent) {
            h.tvSent.isVisible = true
            h.tvSent.text        = item.msg
            h.tvRcv.isVisible  = false
        } else {
            h.tvRcv.isVisible  = true
            h.tvRcv.text        = item.msg
            h.tvSent.isVisible = false
        }
    }

    private fun bindMedia(h: VH, item: MessageEntity, sent: Boolean, type: String) {
        h.sentAudio.isVisible = false
        h.rcvAudio.isVisible  = false
        h.tvSent.isVisible    = false
        h.tvRcv.isVisible     = false
        h.rlSent.setBackgroundResource(R.drawable.sent_msg_bg)

        val uriStr = item.uri ?: item.msg
        val uri    = try { uriStr.toUri() } catch (_: Exception) { null }

        if (sent) {
            h.sentMedia.isVisible  = true
            h.rcvdMedia.isVisible  = false
            h.ivSentMedia.isVisible = true
            h.ivSentPlay.isVisible  = type == "VIDEO"
            if (uri != null) Glide.with(h.itemView.context).load(uri)
                .apply(RequestOptions.centerCropTransform()).into(h.ivSentMedia)

            val caption = item.msg
            if (caption.isNotBlank() && caption != "photo" && caption != "video") {
                h.tvSent.text = caption; h.tvSent.isVisible = true
            }
            h.ivSentMedia.setOnClickListener { onClick(item) }
            h.ivSentPlay.setOnClickListener  { onClick(item) }
        } else {
            h.rcvdMedia.isVisible  = true
            h.sentMedia.isVisible  = false
            h.ivRcvMedia.isVisible  = true
            h.ivRcvPlay.isVisible   = type == "VIDEO"
            if (uri != null) Glide.with(h.itemView.context).load(uri)
                .apply(RequestOptions.centerCropTransform()).into(h.ivRcvMedia)

            val caption = item.msg
            if (caption.isNotBlank() && caption != "photo" && caption != "video") {
                h.tvRcv.text = caption; h.tvRcv.isVisible = true
            }
            h.ivRcvMedia.setOnClickListener { onClick(item) }
            h.ivRcvPlay.setOnClickListener  { onClick(item) }
        }
    }

    private fun bindAudio(h: VH, item: MessageEntity, sent: Boolean) {
        h.tvSent.isVisible    = false
        h.tvRcv.isVisible     = false
        h.sentMedia.isVisible = false
        h.rcvdMedia.isVisible = false
        h.rlSent.setBackgroundResource(R.drawable.sent_msg_bg)
        h.sentMessageTimeLayout?.visibility = View.GONE
        h.receivedMessageTimeLayout?.visibility = View.GONE

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
            h.playIcon(sent).setImageResource(R.drawable.pause_icon_1)
            h.waveform(sent).setProgress(0f)
            h.speedBadge(sent).text = SPEED_STEPS[0].toLabel()
        }

        h.playPauseFrame(sent).setOnClickListener {
            if (activeUri == uriStr) {
                val player = activePlayer
                if (player != null && player.isPlaying) {
                    player.pause()
                    activeHandler?.removeCallbacksAndMessages(null)
                    h.playIcon(sent).setImageResource(R.drawable.pause_icon_1)
                } else if (player != null) {
                    player.start()
                    h.playIcon(sent).setImageResource(R.drawable.pause_icon_0)
                    startProgressUpdater(h, sent, uriStr)
                }
            } else {
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
                    h.waveform(sent).setProgress(progress)
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
        stopPlayback(resetUi = true)
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
        h.playIcon(sent).setImageResource(R.drawable.pause_icon_0)
        h.speedBadge(sent).text = SPEED_STEPS[0].toLabel()
        h.durationLabel(sent).text = formatDuration(player.duration)
        startProgressUpdater(h, sent, uriStr)
        player.setOnCompletionListener {
            activeHandler?.removeCallbacksAndMessages(null)
            h.playIcon(sent).setImageResource(R.drawable.pause_icon_1)
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

    private fun startProgressUpdater(h: VH, sent: Boolean, uriStr: String) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
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
            if (player.isPlaying) R.drawable.pause_icon_0 else R.drawable.pause_icon_1
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
                    val sent = getItem(pos).isSent
                    h.playIcon(sent).setImageResource(R.drawable.pause_icon_1)
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