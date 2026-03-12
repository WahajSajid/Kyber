package app.secure.kyber.adapters

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
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
import app.secure.kyber.R
import app.secure.kyber.roomdb.GroupMessageEntity
import app.secure.kyber.Other.WaveformView
import com.bumptech.glide.Glide
import com.google.android.material.imageview.ShapeableImageView
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

class GroupMessagesAdapter(
    private val onClick: (GroupMessageEntity) -> Unit = {},
    private val onLongClick: (View, GroupMessageEntity) -> Unit = { _, _ -> },
    private val onEmojiSelected: (GroupMessageEntity, String) -> Unit = { _, _ -> },
    private val onMoreEmojisClicked: (GroupMessageEntity) -> Unit = {},
    private val myId: String,
    private var recentEmojis: List<String> = listOf("👌", "😊", "😂", "😍", "💜", "🎮"),
    private val recyclerView: RecyclerView? = null
) : ListAdapter<GroupMessageEntity, GroupMessagesAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<GroupMessageEntity>() {
            override fun areItemsTheSame(old: GroupMessageEntity, new: GroupMessageEntity) =
                old.messageId == new.messageId
            override fun areContentsTheSame(old: GroupMessageEntity, new: GroupMessageEntity) =
                old == new
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
         * Key = GroupMessageEntity.messageId (String)
         */
        private val persistentCache = mutableMapOf<String, String>()

        fun clearPersistentCache() = persistentCache.clear()
    }

    private var activePlayer: MediaPlayer? = null
    private var activeUri: String? = null
    private var activeHolder: VH? = null
    private var activeHandler: android.os.Handler? = null
    private var activeSpeedIdx: Int = 0
    private val openMenuPositions = mutableSetOf<Int>()

    private val adapterScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Delegate to the static persistentCache — survives adapter re-creation.
    private val decryptedTextCache: MutableMap<String, String> get() = persistentCache

    // Instance-level is fine — in-flight jobs are naturally lost on re-creation.
    private val animatingIds = mutableSetOf<String>()
    private val decryptJobs = mutableMapOf<String, Job>()

    /**
     * Pluggable decryptor. Assign from ChatFragment for real crypto.
     * For the demo, ChatFragment wires this to a fake delay + passthrough.
     */
    var messageDecryptor: suspend (String) -> String = { it }

    // Mutable internal reference — set via onAttachedToRecyclerView so it is
    // always valid regardless of how the adapter is constructed.
    private var rvRef: RecyclerView? = recyclerView

    init {
        setHasStableIds(true)
    }

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        rvRef = rv
    }

    override fun getItemId(position: Int) = getItem(position).messageId.hashCode().toLong()

    fun releasePlayer() { stopPlayback(resetUi = true) }

    fun updateRecentEmojis(newList: List<String>) {
        this.recentEmojis = newList
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        rvRef = null
        adapterScope.coroutineContext.cancelChildren()
    }

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

        val sentAudio: LinearLayout = view.findViewById(R.id.sentAudioContainer)
        val ivSentPlayPause: FrameLayout = view.findViewById(R.id.ivSentPlayPause)
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

        val tvSentReaction: TextView = view.findViewById(R.id.tvSentReaction)
        val tvRcvReaction: TextView = view.findViewById(R.id.tvRcvReaction)

        // Decrypting indicator for received group text messages
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.group_message_item, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val adapterPosition = holder.bindingAdapterPosition
        if (adapterPosition == RecyclerView.NO_POSITION) return

        val item = getItem(adapterPosition)
        val showTime = if (adapterPosition < itemCount - 1) {
            convertDatetime(item.time) != convertDatetime(getItem(adapterPosition + 1).time)
        } else true

        val type = item.type.uppercase(Locale.US)
        val isSent = item.senderId == myId
        val isMedia = type == "IMAGE" || type == "VIDEO"
        val isAudio = type == "AUDIO"

        holder.rlSendMsg.isVisible = isSent
        holder.rlRecieveMsg.isVisible = !isSent
        holder.sentMessageTime.isVisible = isSent && showTime
        holder.receivedMessageTime.isVisible = !isSent && showTime

        val isMenuOpen = openMenuPositions.contains(adapterPosition)

        if (isMenuOpen) {
            val emojiAdapter = RecentEmojiAdapter(recentEmojis, item.reaction) { emoji ->
                val finalEmoji = if (emoji == item.reaction) "" else emoji
                closeMenu()
                onEmojiSelected(item, finalEmoji)
            }
            holder.rvEmojis(isSent).apply {
                layoutManager = LinearLayoutManager(
                    holder.itemView.context, LinearLayoutManager.HORIZONTAL, false
                )
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

        when {
            isAudio -> bindAudio(holder, item, isSent)
            isMedia -> bindMedia(holder, item, isSent, type)
            else    -> bindText(holder, item, isSent)
        }

        if (isSent) {
            holder.tvSendTime.text = convertDatetime(item.time)
        } else {
            holder.senderName.text = item.senderName
            holder.senderId.text = item.senderId
            holder.tvRcvTime.text = convertDatetime(item.time)
        }

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
            if (openMenuPositions.isNotEmpty()) closeMenu() else onClick(item)
        }

        setupActionMenuButtons(holder, isSent, item)
    }

    fun showReactionImmediately(itemId: String, emoji: String) {
        val pos = currentList.indexOfFirst { it.messageId == itemId }
        if (pos == -1) return
        recyclerView?.findViewHolderForAdapterPosition(pos)?.let { vh ->
            (vh as? VH)?.let { holder ->
                val isSent = getItem(pos).senderId == myId
                val reactionView = holder.reaction(isSent)
                if (emoji.isEmpty()) reactionView.visibility = View.GONE
                else { reactionView.text = emoji; reactionView.visibility = View.VISIBLE }
            }
        }
    }

    private fun setupActionMenuButtons(holder: VH, isSent: Boolean, item: GroupMessageEntity) {
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

        val adapterPosition = holder.bindingAdapterPosition
        if (adapterPosition == RecyclerView.NO_POSITION) return

        when {
            payloads.contains("START_PLAYBACK") -> {
                val item = getItem(adapterPosition)
                val isSent = item.senderId == myId
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

    // ── Text binding with one-time scramble decryption animation ─────────────

    private fun bindText(h: VH, item: GroupMessageEntity, sent: Boolean) {
        h.sentAudio.isVisible = false
        h.rcvAudio.isVisible  = false
        h.flMediaSent.isVisible = false
        h.flMediaRcv.isVisible = false
        h.rlSendMsg.setBackgroundResource(R.drawable.sent_msg_bg)

        val msgId = item.messageId
        val targetView = if (sent) h.tvSentMsg else h.tvMsgRcv

        if (sent) { h.tvSentMsg.isVisible = true; h.tvMsgRcv.isVisible = false }
        else      { h.tvMsgRcv.isVisible = true;  h.tvSentMsg.isVisible = false }

        // Sent messages show their own text immediately — no animation needed
        if (sent) {
            targetView.animate().cancel()
            targetView.alpha = 1f
            targetView.text = item.msg
            setDecryptingUi(h, isDecrypting = false, sent = true)
            return
        }

        // ── CASE 1: Already decrypted ─────────────────────────────────────────
        val cached = decryptedTextCache[msgId]
        if (cached != null) {
            targetView.animate().cancel()
            targetView.alpha = 1f
            targetView.text = cached
            setDecryptingUi(h, isDecrypting = false, sent = false)
            return
        }

        // ── CASE 2: Animation already in flight ───────────────────────────────
        if (animatingIds.contains(msgId)) {
            if (targetView.text.isNullOrBlank()) {
                targetView.text = generateEncryptedPlaceholder(item.msg.length)
            }
            setDecryptingUi(h, isDecrypting = true, sent = false)
            return
        }

        // ── CASE 3: First time seeing this received message — start the animation
        // Show placeholder and immediately set alpha low for the fade-in effect
        targetView.text = generateEncryptedPlaceholder(item.msg.length)
        targetView.alpha = 0.35f

        setDecryptingUi(h, isDecrypting = true, sent = false)

        animatingIds.add(msgId)
        decryptJobs[msgId]?.cancel()

        // Capture the ViewHolder directly — direct writes are the most reliable
        // approach. updateTextViewForMessage() is used as a fallback if this
        // holder gets recycled before the animation finishes.
        val capturedHolder = h
        val capturedSent   = sent
        val encryptedBase  = targetView.text.toString()

        val job = adapterScope.launch {
            try {
                // Initial pause before characters begin to resolve
                delay(1000)

                val decrypted = withContext(Dispatchers.IO) {
                    messageDecryptor(item.msg)
                }

                decryptedTextCache[msgId] = decrypted
                runSweepReveal(
                    msgId = msgId,
                    itemMessageId = item.messageId,
                    encryptedBase = encryptedBase,
                    finalText = decrypted,
                    h = capturedHolder,
                    sent = capturedSent
                )

            } catch (_: Exception) {
                decryptedTextCache[msgId] = item.msg
                // Direct write to captured holder if still valid, else fallback
                writeToHolder(capturedHolder, capturedSent, item.msg, item.messageId)
            } finally {
                animatingIds.remove(msgId)
                decryptJobs.remove(msgId)
            }
        }
        decryptJobs[msgId] = job
    }

    private fun setDecryptingUi(holder: VH, isDecrypting: Boolean, sent: Boolean) {
        if (sent) return
        val tv = holder.tvMsgRcv
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
     * Writes text directly to the captured holder if it still represents the
     * correct message, otherwise falls back to finding via rvRef.
     */
    private fun writeToHolder(h: VH, sent: Boolean, text: String, messageId: String) {
        val pos = h.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) {
            val current = runCatching { getItem(pos) }.getOrNull()
            if (current?.messageId == messageId) {
                val tv = if (sent) h.tvSentMsg else h.tvMsgRcv
                tv.text = text
                tv.alpha = 1f
                return
            }
        }
        // Holder was recycled — find the new one
        updateTextViewForMessage(messageId, messageId, text)
    }

    private fun setAlphaOnHolder(h: VH, sent: Boolean, alpha: Float, messageId: String) {
        val pos = h.bindingAdapterPosition
        if (pos != RecyclerView.NO_POSITION) {
            val current = runCatching { getItem(pos) }.getOrNull()
            if (current?.messageId == messageId) {
                val tv = if (sent) h.tvSentMsg else h.tvMsgRcv
                tv.alpha = alpha
                return
            }
        }
        updateAlphaForMessage(messageId, alpha)
    }

    private suspend fun runSweepReveal(
        msgId: String,
        itemMessageId: String,
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
        setAlphaOnHolder(h, sent, 0.35f, itemMessageId)
        delay(FRAME_MS)
        setAlphaOnHolder(h, sent, 0.6f, itemMessageId)
        delay(FRAME_MS)
        setAlphaOnHolder(h, sent, 0.85f, itemMessageId)
        delay(FRAME_MS)
        setAlphaOnHolder(h, sent, 1f, itemMessageId)

        for (step in 0..steps) {
            val progress    = step.toFloat() / steps.toFloat()
            val sweepIndex  = (progress * length).toInt().coerceIn(0, length)
            val buffer      = CharArray(length) { i ->
                if (i < sweepIndex) finalText[i] else encryptedChars[i]
            }
            writeToHolder(h, sent, String(buffer), itemMessageId)
            delay(FRAME_MS)
        }

        // Final guaranteed state
        setAlphaOnHolder(h, sent, 1f, itemMessageId)
        writeToHolder(h, sent, finalText, itemMessageId)
        setDecryptingUi(h, isDecrypting = false, sent = sent)
    }

    private fun updateAlphaForMessage(itemMessageId: String, alpha: Float) {
        val pos = currentList.indexOfFirst { it.messageId == itemMessageId }
        if (pos == -1) return
        val vh = rvRef?.findViewHolderForAdapterPosition(pos) as? VH ?: return
        if (vh.bindingAdapterPosition == RecyclerView.NO_POSITION) return
        val currentItem = runCatching { getItem(vh.bindingAdapterPosition) }.getOrNull() ?: return
        if (currentItem.messageId != itemMessageId) return
        val isSent = currentItem.senderId == myId
        val tv = if (isSent) vh.tvSentMsg else vh.tvMsgRcv
        tv.alpha = alpha
    }

    private fun updateTextViewForMessage(
        msgId: String,
        itemMessageId: String,
        text: String
    ) {
        val pos = currentList.indexOfFirst { it.messageId == itemMessageId }
        if (pos == -1) return
        val vh = rvRef?.findViewHolderForAdapterPosition(pos) as? VH ?: return
        if (vh.bindingAdapterPosition == RecyclerView.NO_POSITION) return
        val currentItem = runCatching { getItem(vh.bindingAdapterPosition) }.getOrNull() ?: return
        if (currentItem.messageId != itemMessageId) return
        val isSent = currentItem.senderId == myId
        val tv = if (isSent) vh.tvSentMsg else vh.tvMsgRcv
        tv.text = text
    }

    // ── Media binding ─────────────────────────────────────────────────────────

    private fun bindMedia(h: VH, item: GroupMessageEntity, sent: Boolean, type: String) {
        h.sentAudio.isVisible = false
        h.rcvAudio.isVisible  = false
        h.tvSentMsg.isVisible = false
        h.tvMsgRcv.isVisible  = false
        h.rlSendMsg.setBackgroundResource(R.drawable.sent_msg_bg)

        val uriStr = item.uri ?: item.msg
        val uri = try { uriStr.toUri() } catch (_: Exception) { null }

        if (sent) {
            h.flMediaSent.isVisible = true; h.flMediaRcv.isVisible = false
            h.ivMediaSent.isVisible = true; h.ivPlaySent.isVisible = type == "VIDEO"
            if (uri != null) Glide.with(h.itemView.context).load(uri).into(h.ivMediaSent)
            if (item.msg.isNotBlank() && item.msg != "photo" && item.msg != "video") {
                h.tvSentMsg.text = item.msg; h.tvSentMsg.isVisible = true
            }
        } else {
            h.flMediaRcv.isVisible = true; h.flMediaSent.isVisible = false
            h.ivMediaRcv.isVisible = true; h.ivPlayRcv.isVisible = type == "VIDEO"
            if (uri != null) Glide.with(h.itemView.context).load(uri).into(h.ivMediaRcv)
            if (item.msg.isNotBlank() && item.msg != "photo" && item.msg != "video") {
                h.tvMsgRcv.text = item.msg; h.tvMsgRcv.isVisible = true
            }
        }
    }

    // ── Audio binding ─────────────────────────────────────────────────────────

    private fun bindAudio(h: VH, item: GroupMessageEntity, sent: Boolean) {
        h.tvSentMsg.isVisible = false; h.tvMsgRcv.isVisible = false
        h.flMediaSent.isVisible = false; h.flMediaRcv.isVisible = false
        h.rlSendMsg.setBackgroundResource(R.drawable.sent_msg_bg)
        h.sentAudio.isVisible = sent; h.rcvAudio.isVisible = !sent

        val uriStr = item.uri ?: return
        val amplitudes = decodeAmplitudes(item.ampsJson)
        h.waveform(sent).setAmplitudes(amplitudes)
        val totalMs = getTotalDuration(h.itemView.context, uriStr)
        h.durationLabel(sent).text = formatDuration(totalMs)

        if (activeUri == uriStr) syncPlayingUi(h, sent)
        else {
            h.playIcon(sent).setImageResource(R.drawable.play_ic)
            h.waveform(sent).setProgress(0f)
            h.speedBadge(sent).text = SPEED_STEPS[0].toLabel()
        }

        h.playPauseFrame(sent).setOnClickListener {
            if (activeUri == uriStr) {
                val player = activePlayer
                if (player != null && player.isPlaying) {
                    player.pause()
                    activeHandler?.removeCallbacksAndMessages(null)
                    h.playIcon(sent).setImageResource(R.drawable.play_ic)
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
            h.playIcon(sent).setImageResource(R.drawable.play_ic)
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
            if (player.isPlaying) R.drawable.pause_icon_0 else R.drawable.play_ic
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
                    val sent = getItem(pos).senderId == myId
                    h.playIcon(sent).setImageResource(R.drawable.play_ic)
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