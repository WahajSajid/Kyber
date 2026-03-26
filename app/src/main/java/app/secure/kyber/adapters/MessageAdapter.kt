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
import app.secure.kyber.Other.DecryptRevealTextView
import app.secure.kyber.Other.WaveformView
import app.secure.kyber.R
import app.secure.kyber.roomdb.MessageEntity
import app.secure.kyber.roomdb.MessageUiModel
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.Locale
import kotlin.random.Random

@Suppress("DEPRECATION")
class MessageAdapter(
    private val myId: String,
    private val onClick: (MessageUiModel) -> Unit = {},
    private val onLongClick: (View, MessageUiModel) -> Unit = { _, _ -> },
    private val onEmojiSelected: (MessageUiModel, String) -> Unit = { _, _ -> },
    private val onMoreEmojisClicked: (MessageUiModel) -> Unit = {},
    private var recentEmojis: List<String> = listOf("👌", "😊", "😂", "😍", "💜", "🎮")
) : ListAdapter<MessageUiModel, MessageAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<MessageUiModel>() {
            override fun areItemsTheSame(a: MessageUiModel, b: MessageUiModel) = a.id == b.id
            override fun areContentsTheSame(a: MessageUiModel, b: MessageUiModel) = a == b
        }
        private val SPEED_STEPS = listOf(1.0f, 1.5f, 2.0f)
        private val SCRAMBLE_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*"

        fun generateEncryptedPlaceholder(length: Int): String {
            if (length <= 0) return "••••••••"
            return (1..length.coerceAtLeast(8))
                .map { SCRAMBLE_CHARS[Random.nextInt(SCRAMBLE_CHARS.length)] }
                .joinToString("")
        }

        private val persistentCache = mutableMapOf<Long, String>()
        fun clearPersistentCache() = persistentCache.clear()
    }

    private var activePlayer: MediaPlayer? = null
    private var activeUri: String? = null
    private var activeHolder: VH? = null
    private var activeHandler: android.os.Handler? = null
    private var activeSpeedIdx: Int = 0
    private val openMenuPositions = mutableSetOf<Int>()
    private var recyclerView: RecyclerView? = null

    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val decryptedTextCache: MutableMap<Long, String> get() = persistentCache
    private val animatingIds = mutableSetOf<Long>()
    private val decryptJobs = mutableMapOf<Long, Job>()

    var messageDecryptor: suspend (String) -> String = { it }

    // True once the very first submitList with >1 item has been processed.
    private var firstLoadDone = false

    override fun submitList(list: List<MessageUiModel>?) {
        val prevSize = currentList.size
        handlePreCache(list)
        super.submitList(list) {
            if (list != null && list.size > prevSize && prevSize > 0) {
                recyclerView?.scrollToPosition(list.size - 1)
            }
        }
    }

    override fun submitList(list: List<MessageUiModel>?, commitCallback: Runnable?) {
        val prevSize = currentList.size
        handlePreCache(list)
        super.submitList(list) {
            if (list != null && list.size > prevSize && prevSize > 0) {
                recyclerView?.scrollToPosition(list.size - 1)
            }
            commitCallback?.run()
        }
    }

    private fun handlePreCache(list: List<MessageUiModel>?) {
        if (!firstLoadDone && currentList.isEmpty() && list != null) {
            if (list.size > 1) {
                list.forEach { persistentCache[it.id] = it.decryptedMsg }
            }
            firstLoadDone = true
        }
    }

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        recyclerView = rv
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        super.onDetachedFromRecyclerView(rv)
        recyclerView = null
        adapterScope.coroutineContext.cancelChildren()
    }

    init { setHasStableIds(true) }

    override fun getItemId(position: Int) = getItem(position).id.hashCode().toLong()

    fun releasePlayer() { stopPlayback(resetUi = true) }

    fun updateRecentEmojis(newList: List<String>, reactedItemId: String? = null) {
        recentEmojis = newList
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvSent: TextView = view.findViewById(R.id.tvSentMsg)
        val tvRcv: DecryptRevealTextView = view.findViewById(R.id.tvMsgRcv)
        val tvSentTime: TextView = view.findViewById(R.id.tvSentTime)
        val tvRcvTime: TextView = view.findViewById(R.id.tvRcvTime)
        val rlSent: LinearLayout = view.findViewById(R.id.rlMsgSent)
        val rlRcvd: LinearLayout = view.findViewById(R.id.rlMsgRcvd)

        val sentMedia: FrameLayout = view.findViewById(R.id.sent_message_media)
        val rcvdMedia: FrameLayout = view.findViewById(R.id.received_message_media)
        val ivSentMedia: ImageView = view.findViewById(R.id.ivSentMedia)
        val ivRcvMedia: ImageView = view.findViewById(R.id.ivRcvMedia)
        val ivSentPlay: ImageView = view.findViewById(R.id.ivSentPlay)
        val ivRcvPlay: ImageView = view.findViewById(R.id.ivRcvPlay)

        val sentAudio: LinearLayout = view.findViewById(R.id.sentAudioContainer)
        val ivSentPlayPause: ConstraintLayout = view.findViewById(R.id.ivSentPlayPause)
        val ivSentPlayIcon: ImageView = view.findViewById(R.id.ivSentPlayIcon)
        val waveformSent: WaveformView = view.findViewById(R.id.waveformSent)
        val tvSentSpeed: TextView = view.findViewById(R.id.tvSentSpeed)
        val tvSentDuration: TextView = view.findViewById(R.id.tvSentAudioDuration)

        val rcvAudio: LinearLayout = view.findViewById(R.id.rcvAudioContainer)
        val ivRcvPlayPause: FrameLayout = view.findViewById(R.id.ivRcvPlayPause)
        val ivRcvPlayIcon: ImageView = view.findViewById(R.id.ivRcvPlayIcon)
        val waveformRcv: WaveformView = view.findViewById(R.id.waveformRcv)
        val tvRcvSpeed: TextView = view.findViewById(R.id.tvRcvSpeed)
        val tvRcvDuration: TextView = view.findViewById(R.id.tvRcvAudioDuration)

        val sentMessageTimeLayout: LinearLayout? = view.findViewById(R.id.sent_message_time_layout)
        val receivedMessageTimeLayout: LinearLayout? = view.findViewById(R.id.received_message_time_layout)
        val sentTimeAudio: TextView? = view.findViewById(R.id.tvSentTimeAudio)
        val rcvTimeAudio: TextView? = view.findViewById(R.id.tvRcvTimeAudio)

        val tvSentReaction: TextView = view.findViewById(R.id.tvSentReaction)
        val tvRcvReaction: TextView = view.findViewById(R.id.tvReceivedReaction)
        val tvDecryptingRcv: TextView? = view.findViewById(R.id.tvDecryptingRcv)

        val actionMenuSent: View = view.findViewById(R.id.actionMenuSent)
        val actionMenuReceived: View = view.findViewById(R.id.actionMenuReceived)
        val sentEmojiBar: View = view.findViewById(R.id.emoji_reaction_bar_sent)
        val receivedEmojiBar: View = view.findViewById(R.id.emoji_reaction_bar_received)

        val replyBtnSent: LinearLayout = view.findViewById(R.id.btnReplySent)
        val forwardBtnSent: LinearLayout = view.findViewById(R.id.btnForwardSent)
        val copyBtnSent: LinearLayout = view.findViewById(R.id.btnCopySent)
        val deleteBtnSent: LinearLayout = view.findViewById(R.id.btnDeleteSent)
        val infoBtnSent: LinearLayout = view.findViewById(R.id.btnInfoSent)

        val replyBtnRcv: LinearLayout = view.findViewById(R.id.btnReplyRcv)
        val forwardBtnRcv: LinearLayout = view.findViewById(R.id.btnForwardRcv)
        val copyBtnRcv: LinearLayout = view.findViewById(R.id.btnCopyRcv)
        val deleteBtnRcv: LinearLayout = view.findViewById(R.id.btnDeleteRcv)
        val infoBtnRcv: LinearLayout = view.findViewById(R.id.btnInfoRcv)

        val rvRecentEmojisSent: RecyclerView = sentEmojiBar.findViewById(R.id.rvRecentEmojis)
        val rvRecentEmojisReceived: RecyclerView = receivedEmojiBar.findViewById(R.id.rvRecentEmojis)
        val btnMoreSent: ImageButton = sentEmojiBar.findViewById(R.id.emojiMore)
        val btnMoreReceived: ImageButton = receivedEmojiBar.findViewById(R.id.emojiMore)

        fun playPauseFrame(sent: Boolean) = if (sent) ivSentPlayPause else ivRcvPlayPause
        fun playIcon(sent: Boolean) = if (sent) ivSentPlayIcon else ivRcvPlayIcon
        fun waveform(sent: Boolean) = if (sent) waveformSent else waveformRcv
        fun speedBadge(sent: Boolean) = if (sent) tvSentSpeed else tvRcvSpeed
        fun durationLabel(sent: Boolean) = if (sent) tvSentDuration else tvRcvDuration
        fun reaction(sent: Boolean) = if (sent) tvSentReaction else tvRcvReaction
        fun emojiBar(sent: Boolean) = if (sent) sentEmojiBar else receivedEmojiBar
        fun actionMenu(sent: Boolean) = if (sent) actionMenuSent else actionMenuReceived
        fun rvEmojis(sent: Boolean) = if (sent) rvRecentEmojisSent else rvRecentEmojisReceived
        fun btnMore(sent: Boolean) = if (sent) btnMoreSent else btnMoreReceived
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.msg_list_item, parent, false))

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.tvRcv.cancelAndShowFinal()
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val pos = holder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return
        val item = getItem(pos)
        val type = item.type.uppercase(Locale.US)
        val isSent = item.isSent
        val isMedia = type == "IMAGE" || type == "VIDEO"
        val isAudio = type == "AUDIO"

        holder.rlSent.isVisible = isSent
        holder.rlRcvd.isVisible = !isSent

        when {
            isAudio -> bindAudio(holder, item, isSent)
            isMedia -> bindMedia(holder, item, isSent, type)
            else    -> bindText(holder, item, isSent)
        }

        val isMenuOpen = openMenuPositions.contains(pos)
        if (isMenuOpen) {
            val emojiAdapter = RecentEmojiAdapter(recentEmojis, item.reaction) { emoji ->
                val finalEmoji = if (emoji == item.reaction) "" else emoji
                closeMenu(); onEmojiSelected(item, finalEmoji)
            }
            holder.rvEmojis(isSent).apply {
                layoutManager = LinearLayoutManager(holder.itemView.context, LinearLayoutManager.HORIZONTAL, false)
                adapter = emojiAdapter
            }
            holder.btnMore(isSent).setOnClickListener { onMoreEmojisClicked(item); closeMenu() }
            holder.emojiBar(isSent).visibility = View.VISIBLE
            holder.actionMenu(isSent).visibility = View.VISIBLE
        } else {
            holder.rvEmojis(isSent).adapter = null
            holder.emojiBar(isSent).visibility = View.GONE
            holder.actionMenu(isSent).visibility = View.GONE
        }
        holder.emojiBar(!isSent).visibility = View.GONE
        holder.actionMenu(!isSent).visibility = View.GONE
        holder.rvEmojis(!isSent).adapter = null

        holder.tvSentTime.text = convertDatetime(item.time)
        holder.tvRcvTime.text = convertDatetime(item.time)
        holder.sentTimeAudio?.text = convertDatetime(item.time)
        holder.rcvTimeAudio?.text = convertDatetime(item.time)

        val reactionView = holder.reaction(isSent)
        if (item.reaction.isNotEmpty()) { reactionView.text = item.reaction; reactionView.visibility = View.VISIBLE }
        else reactionView.visibility = View.GONE

        holder.itemView.setOnLongClickListener {
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) showMenu(p)
            true
        }
        holder.itemView.setOnClickListener {
            if (openMenuPositions.isNotEmpty()) closeMenu() else onClick(item)
        }

        holder.replyBtnSent.setOnClickListener { closeMenu(); onLongClick(it, item) }
        holder.forwardBtnSent.setOnClickListener { closeMenu(); onLongClick(it, item) }
        holder.copyBtnSent.setOnClickListener { closeMenu(); onLongClick(it, item) }
        holder.infoBtnSent.setOnClickListener { closeMenu(); onLongClick(it, item) }
        holder.deleteBtnSent.setOnClickListener { closeMenu(); onLongClick(it, item) }
        holder.replyBtnRcv.setOnClickListener { closeMenu(); onLongClick(it, item) }
        holder.forwardBtnRcv.setOnClickListener { closeMenu(); onLongClick(it, item) }
        holder.copyBtnRcv.setOnClickListener { closeMenu(); onLongClick(it, item) }
        holder.infoBtnRcv.setOnClickListener { closeMenu(); onLongClick(it, item) }
        holder.deleteBtnRcv.setOnClickListener { closeMenu(); onLongClick(it, item) }
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) { onBindViewHolder(holder, position); return }
        val pos = holder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return
        if (payloads.contains("START_PLAYBACK")) {
            val item = getItem(pos)
            startPlayback(holder, item.isSent, item.decryptedUri ?: return)
        } else onBindViewHolder(holder, position)
    }

    private fun showMenu(position: Int) {
        val prev = openMenuPositions.toSet()
        openMenuPositions.clear(); openMenuPositions.add(position)
        prev.forEach { if (it != position) notifyItemChanged(it) }
        notifyItemChanged(position)
    }

    fun closeMenu() {
        val prev = openMenuPositions.toSet()
        openMenuPositions.clear()
        prev.forEach { notifyItemChanged(it) }
    }

    fun showReactionImmediately(itemId: String, emoji: String) {
        val pos = currentList.indexOfFirst { it.id.toString() == itemId }
        if (pos == -1) return
        (recyclerView?.findViewHolderForAdapterPosition(pos) as? VH)?.let { h ->
            val rv = h.reaction(getItem(pos).isSent)
            if (emoji.isEmpty()) rv.visibility = View.GONE
            else { rv.text = emoji; rv.visibility = View.VISIBLE }
        }
    }

    private fun bindText(h: VH, item: MessageUiModel, sent: Boolean) {
        h.sentAudio.isVisible = false
        h.rcvAudio.isVisible = false
        h.sentMedia.isVisible = false
        h.rcvdMedia.isVisible = false
        h.rlSent.setBackgroundResource(R.drawable.sent_msg_bg)
        h.sentMessageTimeLayout?.visibility = View.VISIBLE
        h.receivedMessageTimeLayout?.visibility = View.VISIBLE

        if (sent) {
            h.tvSent.isVisible = true
            h.tvRcv.isVisible = false
            h.tvRcv.cancelAndShowFinal()
            h.tvSent.text = item.decryptedMsg
            h.tvDecryptingRcv?.visibility = View.GONE
            return
        }

        // ── RECEIVED ─────────────────────────────────────────────────────────
        h.tvRcv.isVisible = true
        h.tvSent.isVisible = false

        val msgId = item.id
        val cached = decryptedTextCache[msgId]

        if (cached != null) {
            h.tvRcv.cancelAndShowFinal()
            h.tvRcv.text = cached
            h.tvDecryptingRcv?.visibility = View.GONE
            return
        }

        if (animatingIds.contains(msgId)) {
            h.tvDecryptingRcv?.visibility = View.VISIBLE
            return
        }

        val encryptedPlaceholder = generateEncryptedPlaceholder(item.decryptedMsg.length)

        h.tvDecryptingRcv?.visibility = View.VISIBLE
        h.tvDecryptingRcv?.text = "Decrypting..."

        h.tvRcv.prepareForAnimation(encryptedPlaceholder)
        h.tvRcv.text = encryptedPlaceholder

        animatingIds.add(msgId)
        decryptJobs[msgId]?.cancel()

        decryptJobs[msgId] = adapterScope.launch {
            try {
                // messageDecryptor is still used for the visual animation effect if needed,
                // but we already have the decrypted text in item.decryptedMsg.
                // To keep the animation feel, we could delay slightly.
                delay(500) 
                val decrypted = item.decryptedMsg
                decryptedTextCache[msgId] = decrypted

                withContext(Dispatchers.Main) {
                    val liveVH = findVHForMessage(msgId) ?: run {
                        animatingIds.remove(msgId); decryptJobs.remove(msgId); return@withContext
                    }

                    liveVH.tvRcv.startPhase1(
                        decrypted = decrypted,
                        onPhase1Done = {
                            val vh2 = findVHForMessage(msgId)
                            if (vh2 != null) {
                                vh2.tvRcv.beginPhase2(
                                    onDone = {
                                        findVHForMessage(msgId)?.tvDecryptingRcv?.visibility = View.GONE
                                        animatingIds.remove(msgId)
                                        decryptJobs.remove(msgId)
                                    }
                                )
                            } else {
                                animatingIds.remove(msgId)
                                decryptJobs.remove(msgId)
                            }
                        }
                    )
                }

            } catch (_: Exception) {
                decryptedTextCache[msgId] = item.decryptedMsg
                withContext(Dispatchers.Main) {
                    findVHForMessage(msgId)?.let { vh ->
                        vh.tvRcv.cancelAndShowFinal()
                        vh.tvDecryptingRcv?.visibility = View.GONE
                    }
                    animatingIds.remove(msgId)
                    decryptJobs.remove(msgId)
                }
            }
        }
    }

    private fun findVHForMessage(msgId: Long): VH? {
        val idx = currentList.indexOfFirst { it.id == msgId }
        if (idx == -1) return null
        val vh = recyclerView?.findViewHolderForAdapterPosition(idx) as? VH ?: return null
        val pos = vh.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return null
        if (runCatching { getItem(pos) }.getOrNull()?.id != msgId) return null
        return vh
    }

    private fun bindMedia(h: VH, item: MessageUiModel, sent: Boolean, type: String) {
        h.sentAudio.isVisible = false; h.rcvAudio.isVisible = false
        h.tvSent.isVisible = false; h.tvRcv.isVisible = false
        h.tvRcv.cancelAndShowFinal()
        h.rlSent.setBackgroundResource(R.drawable.sent_msg_bg)
        val uriStr = item.decryptedUri ?: item.decryptedMsg
        val uri = try { uriStr.toUri() } catch (_: Exception) { null }
        if (sent) {
            h.sentMedia.isVisible = true; h.rcvdMedia.isVisible = false
            h.ivSentMedia.isVisible = true; h.ivSentPlay.isVisible = type == "VIDEO"
            if (uri != null) Glide.with(h.itemView.context).load(uri).apply(RequestOptions.centerCropTransform()).into(h.ivSentMedia)
            if (item.decryptedMsg.isNotBlank() && item.decryptedMsg != "photo" && item.decryptedMsg != "video") { h.tvSent.text = item.decryptedMsg; h.tvSent.isVisible = true }
            h.ivSentMedia.setOnClickListener { onClick(item) }; h.ivSentPlay.setOnClickListener { onClick(item) }
        } else {
            h.rcvdMedia.isVisible = true; h.sentMedia.isVisible = false
            h.ivRcvMedia.isVisible = true; h.ivRcvPlay.isVisible = type == "VIDEO"
            if (uri != null) Glide.with(h.itemView.context).load(uri).apply(RequestOptions.centerCropTransform()).into(h.ivRcvMedia)
            if (item.decryptedMsg.isNotBlank() && item.decryptedMsg != "photo" && item.decryptedMsg != "video") { h.tvRcv.text = item.decryptedMsg; h.tvRcv.isVisible = true }
            h.ivRcvMedia.setOnClickListener { onClick(item) }; h.ivRcvPlay.setOnClickListener { onClick(item) }
        }
    }

    private fun bindAudio(h: VH, item: MessageUiModel, sent: Boolean) {
        h.tvSent.isVisible = false; h.tvRcv.isVisible = false
        h.sentMedia.isVisible = false; h.rcvdMedia.isVisible = false
        h.tvRcv.cancelAndShowFinal()
        h.rlSent.setBackgroundResource(R.drawable.sent_msg_bg)
        h.sentMessageTimeLayout?.visibility = View.GONE
        h.receivedMessageTimeLayout?.visibility = View.GONE
        h.sentAudio.isVisible = sent; h.rcvAudio.isVisible = !sent

        val uriStr = item.decryptedUri ?: return
        h.waveform(sent).setAmplitudes(decodeAmplitudes(item.ampsJson))
        h.durationLabel(sent).text = formatDuration(getTotalDuration(h.itemView.context, uriStr))
        if (activeUri == uriStr) syncPlayingUi(h, sent)
        else { h.playIcon(sent).setImageResource(R.drawable.pause_icon_1); h.waveform(sent).setProgress(0f); h.speedBadge(sent).text = SPEED_STEPS[0].toLabel() }

        h.playPauseFrame(sent).setOnClickListener {
            if (activeUri == uriStr) {
                val player = activePlayer
                if (player != null && player.isPlaying) { player.pause(); activeHandler?.removeCallbacksAndMessages(null); h.playIcon(sent).setImageResource(R.drawable.pause_icon_1) }
                else if (player != null) { player.start(); h.playIcon(sent).setImageResource(R.drawable.pause_icon_0); startProgressUpdater(h, sent, uriStr) }
            } else startPlayback(h, sent, uriStr)
        }
        h.waveform(sent).setOnSeekListener { progress ->
            if (activeUri == uriStr) activePlayer?.let { it.seekTo((progress * it.duration).toInt()); h.waveform(sent).setProgress(progress) }
            else { startPlayback(h, sent, uriStr); activePlayer?.let { it.seekTo((progress * it.duration).toInt()); h.waveform(sent).setProgress(progress) } }
        }
        h.speedBadge(sent).setOnClickListener {
            activeSpeedIdx = if (activeUri == uriStr) (activeSpeedIdx + 1) % SPEED_STEPS.size else 0
            val speed = SPEED_STEPS[activeSpeedIdx]; h.speedBadge(sent).text = speed.toLabel()
            if (activeUri == uriStr) applySpeed(speed)
        }
    }

    private fun startPlayback(h: VH, sent: Boolean, uriStr: String) {
        stopPlayback(resetUi = true)
        val player = try { MediaPlayer().apply { setDataSource(h.itemView.context, uriStr.toUri()); prepare() } } catch (e: Exception) { e.printStackTrace(); return }
        activePlayer = player; activeUri = uriStr; activeHolder = h; activeSpeedIdx = 0
        applySpeed(SPEED_STEPS[0]); player.start()
        h.playIcon(sent).setImageResource(R.drawable.pause_icon_0)
        h.speedBadge(sent).text = SPEED_STEPS[0].toLabel()
        h.durationLabel(sent).text = formatDuration(player.duration)
        startProgressUpdater(h, sent, uriStr)
        player.setOnCompletionListener {
            activeHandler?.removeCallbacksAndMessages(null)
            h.playIcon(sent).setImageResource(R.drawable.pause_icon_1); h.waveform(sent).setProgress(0f)
            h.durationLabel(sent).text = formatDuration(player.duration)
            val cp = h.bindingAdapterPosition; activePlayer = null; activeUri = null; activeHolder = null
            if (cp != -1) playNextAudioIfAvailable(cp)
        }
    }

    private fun playNextAudioIfAvailable(currentPos: Int) {
        for (i in (currentPos + 1) until itemCount) {
            if (getItem(i).type.uppercase(Locale.US) == "AUDIO") { notifyItemChanged(i, "START_PLAYBACK"); break }
        }
    }

    private fun startProgressUpdater(h: VH, sent: Boolean, uriStr: String) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        activeHandler = handler
        handler.post(object : Runnable {
            override fun run() {
                val player = activePlayer ?: return; if (activeUri != uriStr) return
                if (player.isPlaying) { h.waveform(sent).setProgress(player.currentPosition.toFloat() / player.duration); h.durationLabel(sent).text = formatDuration(player.duration - player.currentPosition) }
                handler.postDelayed(this, 50)
            }
        })
    }

    private fun syncPlayingUi(h: VH, sent: Boolean) {
        val player = activePlayer ?: return
        h.playIcon(sent).setImageResource(if (player.isPlaying) R.drawable.pause_icon_0 else R.drawable.pause_icon_1)
        h.speedBadge(sent).text = SPEED_STEPS[activeSpeedIdx].toLabel()
        if (player.isPlaying) startProgressUpdater(h, sent, activeUri!!)
    }

    private fun stopPlayback(resetUi: Boolean) {
        activeHandler?.removeCallbacksAndMessages(null)
        activePlayer?.stop(); activePlayer?.release(); activePlayer = null
        if (resetUi) activeHolder?.let { h -> val pos = h.bindingAdapterPosition; if (pos != -1) { val s = getItem(pos).isSent; h.playIcon(s).setImageResource(R.drawable.pause_icon_1); h.waveform(s).setProgress(0f) } }
        activeUri = null; activeHolder = null
    }

    private fun applySpeed(speed: Float) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) activePlayer?.let { it.playbackParams = it.playbackParams.setSpeed(speed) }
    }

    private fun Float.toLabel() = if (this == 1.0f) "1x" else "${this}x"

    private fun decodeAmplitudes(json: String?): List<Float> {
        if (json.isNullOrBlank()) return emptyList()
        return try { val arr = JSONArray(json); List(arr.length()) { i -> arr.getDouble(i).toFloat() } } catch (_: Exception) { emptyList() }
    }

    private fun formatDuration(ms: Int): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60)
    }

    private fun getTotalDuration(context: Context, uriStr: String): Int {
        val r = MediaMetadataRetriever()
        return try { r.setDataSource(context, uriStr.toUri()); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt() ?: 0 } catch (_: Exception) { 0 } finally { r.release() }
    }

    private fun convertDatetime(time: String): String {
        return try { java.text.SimpleDateFormat("hh:mm a", Locale.getDefault()).format(java.util.Date(time.toLong())) } catch (_: Exception) { "" }
    }
}