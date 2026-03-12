package app.secure.kyber.adapters

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.Other.WaveformView
import app.secure.kyber.R
import app.secure.kyber.roomdb.MessageEntity
import coil.size.ViewSizeResolver
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

        private val SCRAMBLE_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*"

        fun generateEncryptedPlaceholder(length: Int): String {
            if (length <= 0) return "••••••••"
            return (1..length.coerceAtLeast(8))
                .map { SCRAMBLE_CHARS[Random.nextInt(SCRAMBLE_CHARS.length)] }
                .joinToString("")
        }

        /**
         * Persistent cache that survives adapter re-creation (fragment re-entry).
         * Once a message id is stored here it will NEVER be animated again.
         * Key = MessageEntity.id (Long)
         */
        private val persistentCache = mutableMapOf<Long, String>()

        /** Call from outside (e.g. onDestroyView) if you ever need to wipe it. */
        fun clearPersistentCache() = persistentCache.clear()
    }

    private var activePlayer: MediaPlayer? = null
    private var activeUri: String? = null
    private var activeHolder: VH? = null
    private var activeHandler: android.os.Handler? = null
    private var activeSpeedIdx: Int = 0
    private val openMenuPositions = mutableSetOf<Int>()

    private var recyclerView: RecyclerView? = null

    // Coroutine scope for async per-item decryption work
    private val adapterScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Delegate to the static persistentCache so decrypted text survives adapter re-creation.
    // This is the key guard that prevents re-animation on fragment re-entry.
    private val decryptedTextCache: MutableMap<Long, String> get() = persistentCache

    // Track which messages are mid-animation (instance-level is fine — in-flight jobs
    // are naturally lost when the adapter is recreated, which is correct behaviour).
    private val animatingIds = mutableSetOf<Long>()

    private val decryptJobs = mutableMapOf<Long, Job>()

    /**
     * Pluggable decryptor. Replace this lambda from outside (e.g. ChatFragment) with
     * your real crypto implementation. It runs on Dispatchers.IO automatically.
     *
     * For the demo this is wired in ChatFragment to add a fake delay then return
     * the real message text.
     */
    var messageDecryptor: suspend (String) -> String = { it }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
        adapterScope.coroutineContext.cancelChildren()
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int) = getItem(position).id.hashCode().toLong()

    fun releasePlayer() {
        stopPlayback(resetUi = true)
    }

    fun updateRecentEmojis(newList: List<String>, reactedItemId: String? = null) {
        this.recentEmojis = newList
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvSent: TextView = view.findViewById(R.id.tvSentMsg)
        val tvRcv: TextView = view.findViewById(R.id.tvMsgRcv)
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
        val receivedMessageTimeLayout: LinearLayout? =
            view.findViewById(R.id.received_message_time_layout)
        val sentTimeAudio: TextView? = view.findViewById(R.id.tvSentTimeAudio)
        val rcvTimeAudio: TextView? = view.findViewById(R.id.tvRcvTimeAudio)

        val tvSentReaction: TextView = view.findViewById(R.id.tvSentReaction)
        val tvRcvReaction: TextView = view.findViewById(R.id.tvReceivedReaction)

        // Decrypting indicator (only used for received text messages)
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
        val rvRecentEmojisReceived: RecyclerView =
            receivedEmojiBar.findViewById(R.id.rvRecentEmojis)
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

    override fun onBindViewHolder(holder: VH, position: Int) {
        val adapterPosition = holder.bindingAdapterPosition
        if (adapterPosition == RecyclerView.NO_POSITION) return

        val item = getItem(adapterPosition)
        val type = item.type.uppercase(Locale.US)
        val isSent = item.isSent
        val isMedia = type == "IMAGE" || type == "VIDEO"
        val isAudio = type == "AUDIO"

        holder.rlSent.isVisible = isSent
        holder.rlRcvd.isVisible = !isSent

        when {
            isAudio -> bindAudio(holder, item, isSent)
            isMedia -> bindMedia(holder, item, isSent, type)
            else -> bindText(holder, item, isSent)
        }

        val isMenuOpen = openMenuPositions.contains(adapterPosition)

        if (isMenuOpen) {
            val emojiAdapter = RecentEmojiAdapter(recentEmojis, item.reaction) { emoji ->
                val finalEmoji = if (emoji == item.reaction) "" else emoji
                closeMenu()
                onEmojiSelected(item, finalEmoji)
            }
            holder.rvEmojis(isSent).apply {
                layoutManager = LinearLayoutManager(
                    holder.itemView.context,
                    LinearLayoutManager.HORIZONTAL,
                    false
                )
                adapter = emojiAdapter
            }
            holder.btnMore(isSent).setOnClickListener {
                onMoreEmojisClicked(item)
                closeMenu()
            }
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
        if (item.reaction.isNotEmpty()) {
            reactionView.text = item.reaction
            reactionView.visibility = View.VISIBLE
        } else {
            reactionView.visibility = View.GONE
        }

        holder.itemView.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) showMenu(pos)
            true
        }

        holder.itemView.setOnClickListener {
            if (openMenuPositions.isNotEmpty()) closeMenu()
            else onClick(item)
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
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        val adapterPosition = holder.bindingAdapterPosition
        if (adapterPosition == RecyclerView.NO_POSITION) return

        when {
            payloads.contains("START_PLAYBACK") -> {
                val item = getItem(adapterPosition)
                val isSent = item.isSent
                val uriStr = item.uri ?: return
                startPlayback(holder, isSent, uriStr)
            }
            else -> onBindViewHolder(holder, position)
        }
    }

    // ── Menu helpers ──────────────────────────────────────────────────────────

    private fun showMenu(position: Int) {
        val previousOpen = openMenuPositions.toSet()
        openMenuPositions.clear()
        openMenuPositions.add(position)
        previousOpen.forEach { if (it != position) notifyItemChanged(it) }
        notifyItemChanged(position)
    }

    fun closeMenu() {
        val previousOpen = openMenuPositions.toSet()
        openMenuPositions.clear()
        previousOpen.forEach { notifyItemChanged(it) }
    }

    // ── Reaction helper ───────────────────────────────────────────────────────

    fun showReactionImmediately(itemId: String, emoji: String) {
        val pos = currentList.indexOfFirst { it.id.toString() == itemId }
        if (pos == -1) return
        recyclerView?.findViewHolderForAdapterPosition(pos)?.let { vh ->
            (vh as? VH)?.let { holder ->
                val isSent = getItem(pos).isSent
                val reactionView = holder.reaction(isSent)
                if (emoji.isEmpty()) reactionView.visibility = View.GONE
                else { reactionView.text = emoji; reactionView.visibility = View.VISIBLE }
            }
        }
    }

    // ── Text binding with one-time scramble decryption animation ─────────────

    private fun bindText(h: VH, item: MessageEntity, sent: Boolean) {
        h.sentAudio.isVisible = false
        h.rcvAudio.isVisible = false
        h.sentMedia.isVisible = false
        h.rcvdMedia.isVisible = false
        h.rlSent.setBackgroundResource(R.drawable.sent_msg_bg)
        h.sentMessageTimeLayout?.visibility = View.VISIBLE
        h.receivedMessageTimeLayout?.visibility = View.VISIBLE

        val msgId = item.id
        val targetView = if (sent) h.tvSent else h.tvRcv

        if (sent) { h.tvSent.isVisible = true; h.tvRcv.isVisible = false }
        else      { h.tvRcv.isVisible = true;  h.tvSent.isVisible = false }

        // Sent messages show their own text immediately — no animation needed
        if (sent) {
            targetView.animate().cancel()
            targetView.alpha = 1f
            targetView.text = item.msg
            setDecryptingUi(h, isDecrypting = false, sent = true)
            return
        }

        // ── CASE 1: Already decrypted — show final text, no animation ─────────
        val cached = decryptedTextCache[msgId]
        if (cached != null) {
            targetView.animate().cancel()
            targetView.alpha = 1f
            targetView.text = cached
            setDecryptingUi(h, isDecrypting = false, sent = false)
            return
        }

        // ── CASE 2: Animation already running for this msgId ─────────────────
        if (animatingIds.contains(msgId)) {
            if (targetView.text.isNullOrBlank()) {
                targetView.text = generateEncryptedPlaceholder(item.msg.length)
            }
            setDecryptingUi(h, isDecrypting = true, sent = false)
            return
        }

        // ── CASE 3: First time seeing this received message — start the animation
        targetView.text = generateEncryptedPlaceholder(item.msg.length)
        targetView.alpha = 0.35f  // start faded for smooth fade-in

        setDecryptingUi(h, isDecrypting = true, sent = false)

        animatingIds.add(msgId)
        decryptJobs[msgId]?.cancel()

        // Capture holder directly for reliable direct writes inside the coroutine
        val capturedHolder = h
        val capturedSent   = sent
        val encryptedBase  = targetView.text.toString()

        val job = adapterScope.launch {
            try {
                // Initial "processing" delay before characters begin to resolve
                delay(1000)

                val decrypted = withContext(Dispatchers.IO) {
                    messageDecryptor(item.msg)
                }

                decryptedTextCache[msgId] = decrypted
                runSweepReveal(
                    msgId = msgId,
                    itemId = item.id,
                    encryptedBase = encryptedBase,
                    finalText = decrypted,
                    h = capturedHolder,
                    sent = capturedSent
                )

            } catch (_: Exception) {
                decryptedTextCache[msgId] = item.msg
                writeToHolder(capturedHolder, capturedSent, item.msg, item.id)
            } finally {
                animatingIds.remove(msgId)
                decryptJobs.remove(msgId)
            }
        }
        decryptJobs[msgId] = job
    }

    private fun setDecryptingUi(holder: VH, isDecrypting: Boolean, sent: Boolean) {
        if (sent) return
        val tv = holder.tvRcv
        val indicator = holder.tvDecryptingRcv ?: return
        if (isDecrypting) {
            indicator.visibility = View.VISIBLE
            indicator.text = "Decrypting..."
            tv.background = ContextCompat.getDrawable(
                holder.itemView.context,
                R.drawable.bg_message_decrypting_overlay
            )
        } else {
            indicator.visibility = View.GONE
            tv.background = null
        }
    }

    /**
     * Writes text + resets alpha directly to the captured holder if it still
     * represents the correct message; otherwise falls back to rvRef lookup.
     */
    private fun writeToHolder(h: VH, sent: Boolean, text: String, itemId: Long) {
        val pos = h.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) {
            val current = runCatching { getItem(pos) }.getOrNull()
            if (current?.id == itemId) {
                val tv = if (sent) h.tvSent else h.tvRcv
                tv.text = text
                tv.alpha = 1f
                return
            }
        }
        // Holder recycled — find the new one via RecyclerView
        updateTextViewForMessage(itemId, itemId, text, animate = false)
    }

    private fun setAlphaOnHolder(h: VH, sent: Boolean, alpha: Float, itemId: Long) {
        val pos = h.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) {
            val current = runCatching { getItem(pos) }.getOrNull()
            if (current?.id == itemId) {
                val tv = if (sent) h.tvSent else h.tvRcv
                tv.alpha = alpha
                return
            }
        }
        updateAlphaForMessage(itemId, itemId, alpha)
    }

    private suspend fun runSweepReveal(
        msgId: Long,
        itemId: Long,
        encryptedBase: String,
        finalText: String,
        h: VH,
        sent: Boolean
    ) {
        val FRAME_MS   = 70L
        val TOTAL_MS   = 1000L
        val steps      = (TOTAL_MS / FRAME_MS).toInt().coerceAtLeast(1)

        val length          = finalText.length
        val encryptedChars  = if (encryptedBase.length >= length) {
            encryptedBase.substring(0, length).toCharArray()
        } else {
            (encryptedBase + generateEncryptedPlaceholder(length - encryptedBase.length))
                .substring(0, length)
                .toCharArray()
        }

        // Smooth overall fade-in at the start of the sweep
        setAlphaOnHolder(h, sent, 0.35f, itemId)
        delay(FRAME_MS)
        setAlphaOnHolder(h, sent, 0.6f, itemId)
        delay(FRAME_MS)
        setAlphaOnHolder(h, sent, 0.85f, itemId)
        delay(FRAME_MS)
        setAlphaOnHolder(h, sent, 1f, itemId)

        for (step in 0..steps) {
            val progress    = step.toFloat() / steps.toFloat()
            val sweepIndex  = (progress * length).toInt().coerceIn(0, length)
            val buffer      = CharArray(length) { i ->
                if (i < sweepIndex) finalText[i] else encryptedChars[i]
            }
            writeToHolder(h, sent, String(buffer), itemId)
            delay(FRAME_MS)
        }

        // Final guaranteed state
        setAlphaOnHolder(h, sent, 1f, itemId)
        writeToHolder(h, sent, finalText, itemId)
        setDecryptingUi(h, isDecrypting = false, sent = sent)
    }

    private fun updateAlphaForMessage(msgId: Long, itemId: Long, alpha: Float) {
        val pos = currentList.indexOfFirst { it.id == itemId }
        if (pos == -1) return
        val vh = recyclerView?.findViewHolderForAdapterPosition(pos) as? VH ?: return
        if (vh.bindingAdapterPosition == RecyclerView.NO_POSITION) return
        val currentItem = runCatching { getItem(vh.bindingAdapterPosition) }.getOrNull() ?: return
        if (currentItem.id != itemId) return
        val tv = if (currentItem.isSent) vh.tvSent else vh.tvRcv
        tv.alpha = alpha
    }

    private fun updateTextViewForMessage(
        msgId: Long,
        itemId: Long,
        text: String,
        animate: Boolean
    ) {
        val pos = currentList.indexOfFirst { it.id == itemId }
        if (pos == -1) return
        val vh = recyclerView?.findViewHolderForAdapterPosition(pos) as? VH ?: return
        if (vh.bindingAdapterPosition == RecyclerView.NO_POSITION) return
        val currentItem = runCatching { getItem(vh.bindingAdapterPosition) }.getOrNull() ?: return
        if (currentItem.id != itemId) return
        val tv = if (currentItem.isSent) vh.tvSent else vh.tvRcv
        tv.text = text
        tv.alpha = 1f
    }

    // ── Media binding ─────────────────────────────────────────────────────────

    private fun bindMedia(h: VH, item: MessageEntity, sent: Boolean, type: String) {
        h.sentAudio.isVisible = false
        h.rcvAudio.isVisible = false
        h.tvSent.isVisible = false
        h.tvRcv.isVisible = false
        h.rlSent.setBackgroundResource(R.drawable.sent_msg_bg)

        val uriStr = item.uri ?: item.msg
        val uri = try { uriStr.toUri() } catch (_: Exception) { null }

        if (sent) {
            h.sentMedia.isVisible = true
            h.rcvdMedia.isVisible = false
            h.ivSentMedia.isVisible = true
            h.ivSentPlay.isVisible = type == "VIDEO"
            if (uri != null) Glide.with(h.itemView.context).load(uri)
                .apply(RequestOptions.centerCropTransform()).into(h.ivSentMedia)
            val caption = item.msg
            if (caption.isNotBlank() && caption != "photo" && caption != "video") {
                h.tvSent.text = caption; h.tvSent.isVisible = true
            }
            h.ivSentMedia.setOnClickListener { onClick(item) }
            h.ivSentPlay.setOnClickListener { onClick(item) }
        } else {
            h.rcvdMedia.isVisible = true
            h.sentMedia.isVisible = false
            h.ivRcvMedia.isVisible = true
            h.ivRcvPlay.isVisible = type == "VIDEO"
            if (uri != null) Glide.with(h.itemView.context).load(uri)
                .apply(RequestOptions.centerCropTransform()).into(h.ivRcvMedia)
            val caption = item.msg
            if (caption.isNotBlank() && caption != "photo" && caption != "video") {
                h.tvRcv.text = caption; h.tvRcv.isVisible = true
            }
            h.ivRcvMedia.setOnClickListener { onClick(item) }
            h.ivRcvPlay.setOnClickListener { onClick(item) }
        }
    }

    // ── Audio binding ─────────────────────────────────────────────────────────

    private fun bindAudio(h: VH, item: MessageEntity, sent: Boolean) {
        h.tvSent.isVisible = false
        h.tvRcv.isVisible = false
        h.sentMedia.isVisible = false
        h.rcvdMedia.isVisible = false
        h.rlSent.setBackgroundResource(R.drawable.sent_msg_bg)
        h.sentMessageTimeLayout?.visibility = View.GONE
        h.receivedMessageTimeLayout?.visibility = View.GONE

        h.sentAudio.isVisible = sent
        h.rcvAudio.isVisible = !sent

        val uriStr = item.uri ?: return
        val amplitudes = decodeAmplitudes(item.ampsJson)
        h.waveform(sent).setAmplitudes(amplitudes)
        val totalMs = getTotalDuration(h.itemView.context, uriStr)
        h.durationLabel(sent).text = formatDuration(totalMs)

        if (activeUri == uriStr) syncPlayingUi(h, sent)
        else {
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
            } else startPlayback(h, sent, uriStr)
        }

        h.waveform(sent).setOnSeekListener { progress ->
            if (activeUri == uriStr) {
                activePlayer?.let { player ->
                    player.seekTo((progress * player.duration).toInt())
                    h.waveform(sent).setProgress(progress)
                }
            } else {
                startPlayback(h, sent, uriStr)
                activePlayer?.let { player ->
                    player.seekTo((progress * player.duration).toInt())
                    h.waveform(sent).setProgress(progress)
                }
            }
        }

        h.speedBadge(sent).setOnClickListener {
            activeSpeedIdx = if (activeUri == uriStr) (activeSpeedIdx + 1) % SPEED_STEPS.size else 0
            val speed = SPEED_STEPS[activeSpeedIdx]
            h.speedBadge(sent).text = speed.toLabel()
            if (activeUri == uriStr) applySpeed(speed)
        }
    }

    // ── Audio playback ────────────────────────────────────────────────────────

    private fun startPlayback(h: VH, sent: Boolean, uriStr: String) {
        stopPlayback(resetUi = true)
        val player = try {
            MediaPlayer().apply { setDataSource(h.itemView.context, uriStr.toUri()); prepare() }
        } catch (e: Exception) { e.printStackTrace(); return }

        activePlayer = player; activeUri = uriStr; activeHolder = h; activeSpeedIdx = 0
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
            activePlayer = null; activeUri = null; activeHolder = null
            if (currentPos != -1) playNextAudioIfAvailable(currentPos)
        }
    }

    private fun playNextAudioIfAvailable(currentPos: Int) {
        for (i in (currentPos + 1) until itemCount) {
            if (getItem(i).type.uppercase(Locale.US) == "AUDIO") {
                notifyItemChanged(i, "START_PLAYBACK"); break
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
                    h.waveform(sent).setProgress(player.currentPosition.toFloat() / player.duration)
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
        activePlayer?.stop(); activePlayer?.release(); activePlayer = null
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
        activeUri = null; activeHolder = null
    }

    private fun applySpeed(speed: Float) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            activePlayer?.let { it.playbackParams = it.playbackParams.setSpeed(speed) }
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun Float.toLabel() = if (this == 1.0f) "1x" else "${this}x"

    private fun decodeAmplitudes(json: String?): List<Float> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i -> arr.getDouble(i).toFloat() }
        } catch (_: Exception) { emptyList() }
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
        } catch (_: Exception) { 0 } finally { retriever.release() }
    }

    private fun convertDatetime(time: String): String {
        return try {
            val formatter = java.text.SimpleDateFormat("hh:mm a", Locale.getDefault())
            formatter.format(java.util.Date(time.toLong()))
        } catch (_: Exception) { "" }
    }
}