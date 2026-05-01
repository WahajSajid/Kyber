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
import android.widget.ProgressBar
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.Other.CircularBurnProgressView
import app.secure.kyber.Other.DecryptRevealTextView
import app.secure.kyber.Other.WaveformView
import app.secure.kyber.R
import app.secure.kyber.Utils.DateUtils
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.random.Random

@Suppress("DEPRECATION")
class MessageAdapter(
    private val myId: String,
    private val lastSeenMessageId: Long = 0L,
    private val onClick: (MessageUiModel) -> Unit = {},
    private val onLongClick: (View, MessageUiModel) -> Unit = { _, _ -> },
    private val onEmojiSelected: (MessageUiModel, String) -> Unit = { _, _ -> },
    private val onMoreEmojisClicked: (MessageUiModel) -> Unit = {},
    private var recentEmojis: List<String> = listOf("👌", "😊", "😂", "😍", "💜", "🎮"),
    private val onRetryUpload: (MessageUiModel) -> Unit = {},
    private val onRetryDownload: (MessageUiModel) -> Unit = {},
    private val onReplyQuoteClicked: (String) -> Unit = {},
    private val onWipeRequestAction: (MessageUiModel, Boolean) -> Unit = { _, _ -> },
) : ListAdapter<MessageUiModel, MessageAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<MessageUiModel>() {
            override fun areItemsTheSame(a: MessageUiModel, b: MessageUiModel) = a.id == b.id
            override fun areContentsTheSame(a: MessageUiModel, b: MessageUiModel) = a == b
        }
        private val SPEED_STEPS = listOf(1.0f, 1.5f, 2.0f)
        private val SCRAMBLE_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*"

        fun generateEncryptedPlaceholder(originalText: String): String {
            if (originalText.isEmpty()) return "••••••••"

            val builder = StringBuilder(originalText.length)
            for (char in originalText) {
                // Preserve spaces, tabs, and newlines so the TextView wraps lines identically
                if (char.isWhitespace()) {
                    builder.append(char)
                } else {
                    builder.append(SCRAMBLE_CHARS[Random.nextInt(SCRAMBLE_CHARS.length)])
                }
            }
            return builder.toString()
        }

        private val persistentCache = mutableMapOf<Long, String>()
        fun clearPersistentCache() = persistentCache.clear()

        /**
         * Returns true when [text] is composed entirely of emoji characters
         * (plus optional whitespace, ZWJ, and variation selectors).
         *
         * Uses codepoint-level inspection so it works for all supplementary-
         * plane emoji (🎉🔥🐶 etc.) which Android stores as surrogate pairs.
         */
        fun isEmojiOnly(text: String): Boolean {
            if (text.isBlank()) return false
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                // Allow plain whitespace between emojis
                if (Character.isWhitespace(cp)) continue
                // Zero-Width Joiner, Variation Selectors, combining keycap
                if (cp == 0x200D || cp == 0xFE0F || cp == 0xFE0E || cp == 0x20E3) continue
                // Variation Selector block (U+FE00–U+FE0F)
                if (cp in 0xFE00..0xFE0F) continue
                // Tag characters used in flag sequences (U+E0000–U+E007F)
                if (cp in 0xE0000..0xE007F) continue
                // Skin-tone / keycap digit modifiers (U+1F3FB–U+1F3FF, 0–9 keycaps)
                if (cp in 0x1F3FB..0x1F3FF) continue
                // Supplementary Multilingual Plane – all common emoji live here
                if (cp in 0x1F000..0x1FFFF) continue
                // Enclosed alphanumeric supplement / pictographs extension (SMP)
                if (cp in 0x1F100..0x1F1FF) continue
                // Misc Symbols and Pictographs, Transport, Map Symbols (BMP)
                if (cp in 0x2600..0x27BF) continue   // ☀☁⛅⛄♻ etc.
                if (cp in 0x2300..0x23FF) continue   // ⌚⏰⌛
                if (cp in 0x2B00..0x2BFF) continue   // ⬅⬆⬇⬛⬜
                if (cp in 0x2194..0x21FF) continue   // ↔↕↩↪
                if (cp in 0x25A0..0x27BF) continue   // ▪▫▶◀ + dingbats
                if (cp in 0x3000..0x303F) continue   // CJK symbols (✨-adjacent)
                if (cp == 0x00A9 || cp == 0x00AE) continue  // © ®
                // Character.OTHER_SYMBOL (So) covers many BMP emoji
                if (Character.getType(cp) == Character.OTHER_SYMBOL.toInt()) continue
                // Anything else → not emoji-only
                return false
            }
            return true
        }
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
    private var timerJob: Job? = null

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
        if (!firstLoadDone && currentList.isEmpty() && list != null && list.isNotEmpty()) {
            list.forEach {
                // Cache ONLY if you sent it OR if its local DB ID is older than what you've seen
                if (it.isSent || it.id <= lastSeenMessageId) {
                    persistentCache[it.id] = it.decryptedMsg
                }
            }
            firstLoadDone = true
        }
    }

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        recyclerView = rv
        startTimer()
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        super.onDetachedFromRecyclerView(rv)
        recyclerView = null
        timerJob?.cancel()
        adapterScope.coroutineContext.cancelChildren()
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int) = getItem(position).id.hashCode().toLong()

    fun releasePlayer() {
        stopPlayback(resetUi = true)
    }

    /**
     * Flashes the message bubble at [position] with a brief blue overlay,
     * mimicking the WhatsApp tap-to-navigate reply highlight.
     */
    fun highlightItem(position: Int) {
        val vh = recyclerView?.findViewHolderForAdapterPosition(position) as? VH ?: return
        // Highlight the full-width item row (match_parent ConstraintLayout) so the
        // flash covers the entire message row — not just the narrow bubble.
        val container: View = vh.itemView
        val overlay = android.graphics.drawable.ColorDrawable(0)
        container.foreground = overlay
        android.animation.ValueAnimator.ofArgb(0x55009FFF.toInt(), 0x00009FFF.toInt()).apply {
            duration = 1400
            addUpdateListener { overlay.color = it.animatedValue as Int }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    container.foreground = null
                }
            })
            start()
        }
    }

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
        val ivSentPlay: ConstraintLayout = view.findViewById(R.id.ivSentPlay)
        val ivRcvPlay: ConstraintLayout = view.findViewById(R.id.ivRcvPlay)

        val sentAudio: LinearLayout = view.findViewById(R.id.sentAudioContainer)
        val ivSentPlayPause: ConstraintLayout = view.findViewById(R.id.ivSentPlayPause)
        val ivSentPlayIcon: ImageView = view.findViewById(R.id.ivSentPlayIcon)
        val waveformSent: WaveformView = view.findViewById(R.id.waveformSent)
        val tvSentSpeed: TextView = view.findViewById(R.id.tvSentSpeed)
        val tvSentDuration: TextView = view.findViewById(R.id.tvSentAudioDuration)

        val rcvAudio: LinearLayout = view.findViewById(R.id.rcvAudioContainer)
        val ivRcvPlayPause: ConstraintLayout = view.findViewById(R.id.ivRcvPlayPause)
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

        // Wipe Request Views
        val rlWipeRequestSent: LinearLayout? = view.findViewById(R.id.rlWipeRequestSent)
        val tvWipeRequestSentStatus: TextView? = view.findViewById(R.id.tvWipeRequestSentStatus)
        val wipeSentMessageTimeLayout: LinearLayout? = view.findViewById(R.id.wipeSentMessageTime)
        val tvWipeSentTimeWipe: TextView? = view.findViewById(R.id.tvWipeSentTime)

        val rlWipeRequestRcvd: LinearLayout? = view.findViewById(R.id.rlWipeRequestRcvd)
        val llWipeRequestActions: LinearLayout? = view.findViewById(R.id.llWipeRequestActions)
        val btnAcceptWipe: View? = view.findViewById(R.id.btnAcceptWipe)
        val btnRejectWipe: View? = view.findViewById(R.id.btnRejectWipe)
        val tvWipeRequestRcvdStatus: TextView? = view.findViewById(R.id.tvWipeRequestRcvdStatus)
        val tvWipeRequestRcvdDesc: TextView? = view.findViewById(R.id.tvWipeRequestRcvdDesc)


        // Add in VH class after existing fields:
        val sentMediaProgressOverlay: FrameLayout = view.findViewById(R.id.sentMediaProgressOverlay)
        val sentMediaProgressBar: ProgressBar = view.findViewById(R.id.sentMediaProgressBar)
        val tvSentMediaProgress: TextView = view.findViewById(R.id.tvSentMediaProgress)

        val rcvMediaProgressOverlay: FrameLayout = view.findViewById(R.id.rcvMediaProgressOverlay)
        val rcvMediaProgressBar: ProgressBar = view.findViewById(R.id.rcvMediaProgressBar)
        val tvRcvMediaProgress: TextView = view.findViewById(R.id.tvRcvMediaProgress)

        val sentAudioProgressBar: ProgressBar = view.findViewById(R.id.sentAudioProgressBar)
        val tvSentAudioUploadState: TextView = view.findViewById(R.id.tvSentAudioUploadState)
        val tvSentAudioUploadStateLayout: ConstraintLayout =
            view.findViewById(R.id.tvSentAudioUploadStateLayout)


        val rcvAudioProgressBar: ProgressBar = view.findViewById(R.id.rcvAudioProgressBar)
        val tvRcvAudioDownloadState: TextView = view.findViewById(R.id.tvRcvAudioDownloadState)
        val tvRcvAudioDownloadStateLayout: ConstraintLayout = view.findViewById(R.id.tvRcvAudioDownloadStateLayout)



        val btnRetrySentMedia: android.widget.Button = view.findViewById(R.id.btnRetrySentMedia)
        val btnRetryRcvMedia: android.widget.Button = view.findViewById(R.id.btnRetryRcvMedia)
        val btnRetrySentAudio: android.widget.Button = view.findViewById(R.id.btnRetrySentAudio)
        val btnRetryRcvAudio: android.widget.Button = view.findViewById(R.id.btnRetryRcvAudio)

        // Text specific
        val ivSentStatus: ImageView? = view.findViewById(R.id.ivSentStatus)
        val btnRetrySentText: android.widget.Button? = view.findViewById(R.id.btnRetrySentText)
        
        // Voice specific status
        val ivVoiceSentStatus: ImageView? = view.findViewById(R.id.voice_sent_status)

        val burnProgressRcvAudio: CircularBurnProgressView = view.findViewById(R.id.burnProgressRcvAudio)
        val burnProgressRcvText: CircularBurnProgressView = view.findViewById(R.id.burnProgressRcvText)
        val burnProgressSentAudio: CircularBurnProgressView = view.findViewById(R.id.burnProgressSentAudio)
        val burnProgressSentText: CircularBurnProgressView = view.findViewById(R.id.burnProgressSentText)

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

        // Reply quote blocks embedded inside each bubble
        val replyQuoteBlockSent: View = view.findViewById(R.id.replyQuoteBlockSent)
        val replyQuoteBlockRcv: View = view.findViewById(R.id.replyQuoteBlockRcv)
        val tvReplyQuoteSent: TextView = replyQuoteBlockSent.findViewById(R.id.tvReplyQuote)
        val tvReplyQuoteRcv: TextView = replyQuoteBlockRcv.findViewById(R.id.tvReplyQuote)
        fun replyQuote(sent: Boolean) = if (sent) replyQuoteBlockSent else replyQuoteBlockRcv
        fun tvReplyQuote(sent: Boolean) = if (sent) tvReplyQuoteSent else tvReplyQuoteRcv

        val llSystemUpdateBubble: View = view.findViewById(R.id.llSystemUpdateBubble)
        val tvSystemUpdateText: TextView = view.findViewById(R.id.tvSystemUpdateText)
        val dateSeparatorLayout: View = view.findViewById(R.id.dateSeparatorLayout)
        val tvDateSeparator: TextView = view.findViewById(R.id.tvDateSeparator)
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

        // ── Reset UI State for Recycled Views ────────────────────────────────
        holder.llSystemUpdateBubble.visibility = View.GONE
        holder.rlWipeRequestSent?.visibility = View.GONE
        holder.rlWipeRequestRcvd?.visibility = View.GONE
        holder.dateSeparatorLayout.visibility = View.GONE
        val prevItem = if (pos > 0) getItem(pos - 1) else null
        val itemTime = item.time.toLongOrNull() ?: 0L
        val prevTime = prevItem?.time?.toLongOrNull() ?: 0L

        if (pos == 0 || !DateUtils.isSameDay(itemTime, prevTime)) {
            holder.dateSeparatorLayout.visibility = View.VISIBLE
            holder.tvDateSeparator.text = DateUtils.getChatSeparatorDate(itemTime)
        } else {
            holder.dateSeparatorLayout.visibility = View.GONE
        }

        val type = item.type.uppercase(Locale.US)
        val isSent = item.isSent
        val isMedia = type == "IMAGE" || type == "VIDEO"
        val isAudio = type == "AUDIO"
        val rawReaction = item.reaction
        val actualEmoji = extractEmoji(rawReaction)
        val reactionOwner = extractOwner(rawReaction)

        holder.rlSent.isVisible = isSent
        holder.rlRcvd.isVisible = !isSent

        // ── Reply Quote Block ─────────────────────────────────────────────
        val replyRaw = item.replyToText
        val replyText = extractReplyPreviewText(replyRaw)
        holder.replyQuote(isSent).isVisible = replyRaw.isNotEmpty()
        holder.replyQuote(!isSent).isVisible = false
        if (replyRaw.isNotEmpty()) {
            holder.tvReplyQuote(isSent).text = replyText
            holder.replyQuote(isSent).setOnClickListener { onReplyQuoteClicked(replyRaw) }
        } else {
            holder.replyQuote(isSent).setOnClickListener(null)
        }

        when {
            isAudio -> bindAudio(holder, item, isSent)
            isMedia -> bindMedia(holder, item, isSent, type)
            else -> bindText(holder, item, isSent)
        }

        val isMenuOpen = openMenuPositions.contains(pos)
        if (isMenuOpen) {

            // FIX: Restrict emoji editing to the reaction owner (or allow if empty)
            val canEditReaction =
                rawReaction.isEmpty() || reactionOwner == myId || reactionOwner.isEmpty()

            if (canEditReaction) {
                val emojiAdapter = RecentEmojiAdapter(recentEmojis, actualEmoji) { emoji ->
                    val finalEmoji = if (emoji == actualEmoji) "" else emoji
                    closeMenu(); onEmojiSelected(item, finalEmoji)
                }
                holder.rvEmojis(isSent).apply {
                    layoutManager = LinearLayoutManager(
                        holder.itemView.context,
                        LinearLayoutManager.HORIZONTAL,
                        false
                    )
                    adapter = emojiAdapter
                }
                holder.btnMore(isSent).setOnClickListener { onMoreEmojisClicked(item); closeMenu() }
                holder.emojiBar(isSent).visibility = View.VISIBLE
            } else {
                // Not the owner -> View Only. Hide the emoji bar.
                holder.rvEmojis(isSent).adapter = null
                holder.emojiBar(isSent).visibility = View.GONE
            }
            // Always show the action menu (Reply, Delete, etc.)
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

        // ── Burn Time Indicators ─────────────────────────────────────────────
        val expiresAt = item.expiresAt
        val startTime = item.time.toLongOrNull() ?: System.currentTimeMillis()
        val totalDuration = if (expiresAt > 0) (expiresAt - startTime).coerceAtLeast(1000L) else 0L

        if (expiresAt > 0) {
            if (isAudio) {
                if (isSent) {
                    holder.burnProgressSentAudio.isVisible = true
                    holder.burnProgressSentAudio.setTimer(expiresAt, totalDuration)
                    holder.burnProgressSentText.isVisible = false
                    holder.burnProgressRcvAudio.isVisible = false
                    holder.burnProgressRcvText.isVisible = false
                } else {
                    holder.burnProgressRcvAudio.isVisible = true
                    holder.burnProgressRcvAudio.setTimer(expiresAt, totalDuration)
                    holder.burnProgressSentAudio.isVisible = false
                    holder.burnProgressSentText.isVisible = false
                    holder.burnProgressRcvText.isVisible = false
                }
            } else {
                if (isSent) {
                    holder.burnProgressSentText.isVisible = true
                    holder.burnProgressSentText.setTimer(expiresAt, totalDuration)
                    holder.burnProgressSentAudio.isVisible = false
                    holder.burnProgressRcvAudio.isVisible = false
                    holder.burnProgressRcvText.isVisible = false
                } else {
                    holder.burnProgressRcvText.isVisible = true
                    holder.burnProgressRcvText.setTimer(expiresAt, totalDuration)
                    holder.burnProgressSentAudio.isVisible = false
                    holder.burnProgressSentText.isVisible = false
                    holder.burnProgressRcvAudio.isVisible = false
                }
            }
        } else {
            holder.burnProgressSentAudio.isVisible = false
            holder.burnProgressSentText.isVisible = false
            holder.burnProgressRcvAudio.isVisible = false
            holder.burnProgressRcvText.isVisible = false
        }

        // Render the actual parsed emoji
        val activeReactionView = holder.reaction(isSent)
        val isWipeType = type.startsWith("WIPE_")
        if (!isWipeType && actualEmoji.isNotEmpty()) {
            activeReactionView.text = actualEmoji
            activeReactionView.visibility = View.VISIBLE
        } else {
            activeReactionView.visibility = View.GONE
            activeReactionView.text = ""
        }

        val inactiveReactionView = holder.reaction(!isSent)
        inactiveReactionView.visibility = View.GONE
        inactiveReactionView.text = ""

        holder.itemView.setOnLongClickListener {
            val t = item.type.uppercase(Locale.US)
            if (t.startsWith("WIPE_") || t == "DISAPPEAR_SYSTEM" || t == "KEY_UPDATE") {
                return@setOnLongClickListener false
            }
            val p = holder.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) showMenu(p)
            true
        }
        holder.itemView.setOnClickListener {
            if (openMenuPositions.isNotEmpty()) {
                closeMenu()
                return@setOnClickListener
            }
            val t = item.type.uppercase(Locale.US)
            if (t.startsWith("WIPE_") || t == "DISAPPEAR_SYSTEM" || t == "KEY_UPDATE") {
                return@setOnClickListener
            }
            // Guard: do NOT fire onClick for text/emoji messages to avoid
            // "Media not available" shown by ChatFragment's onClick handler
            if (t != "IMAGE" && t != "VIDEO" && t != "AUDIO") {
                return@setOnClickListener  // text/emoji — swallow, nothing to open
            }
            if (t == "IMAGE" || t == "VIDEO") {
                val notReady = item.downloadState == "downloading"
                        || item.downloadState == "pending"
                        || item.uploadState == "uploading"
                        || item.uploadState == "pending"
                if (notReady) return@setOnClickListener
            }
            onClick(item)
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
            onBindViewHolder(holder, position); return
        }
        val pos = holder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return
        if (payloads.contains("START_PLAYBACK")) {
            val item = getItem(pos)
            startPlayback(holder, item.isSent, item.decryptedUri ?: return)
        } else onBindViewHolder(holder, position)

    }

    private fun startTimer() {
        if (timerJob?.isActive == true) return
        timerJob = adapterScope.launch {
            while (isActive) {
                delay(1000)
                updateVisibleTimers()
            }
        }
    }

    private fun updateVisibleTimers() {
        val rv = recyclerView ?: return
        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i)
            val vh = rv.getChildViewHolder(child) as? VH ?: continue
            vh.burnProgressRcvAudio.update()
            vh.burnProgressRcvText.update()
            vh.burnProgressSentAudio.update()
            vh.burnProgressSentText.update()
        }
    }

    private fun showMenu(position: Int) {
        val prev = openMenuPositions.toSet()
        openMenuPositions.clear(); openMenuPositions.add(position)
        prev.forEach { if (it != position) notifyItemChanged(it) }
        notifyItemChanged(position)
        // After layout pass completes, scroll so the full block
        // (emoji bar + message bubble + action menu) is visible.
        recyclerView?.post {
            recyclerView?.post { scrollMenuIntoView(position) }
        }
    }

    fun closeMenu() {
        val prev = openMenuPositions.toSet()
        openMenuPositions.clear()
        prev.forEach { notifyItemChanged(it) }
    }

    /**
     * Scrolls the RecyclerView so that the entire long-press overlay
     * (emoji reaction bar above + message bubble + action menu below)
     * is fully visible on screen.
     *
     * Layout structure (ConstraintLayout root, wrap_content height):
     *   [emoji_reaction_bar_sent/received]  — above the bubble (GONE when not shown)
     *   [rlMsgSent / rlMsgRcvd]             — the message bubble
     *   [actionMenuSent / actionMenuReceived]— below the bubble (GONE when not shown)
     *
     * We compare the itemView.top and itemView.bottom positions (in the RV's
     * coordinate system) against the RV's visible height and scroll by the
     * minimum delta needed to bring the whole block on screen.
     */
    private fun scrollMenuIntoView(position: Int) {
        val rv = recyclerView ?: return
        val vh = rv.findViewHolderForAdapterPosition(position) as? VH ?: return
        val root = vh.itemView

        // itemView encompasses the full ConstraintLayout (emoji bar + bubble + action menu)
        // because clipChildren=false lets the overlay extend the measured height.
        // However, the item's measured height may not yet reflect the newly-visible
        // action menu — so we ask the action menu for its own screen location.
        val rvTop = 0
        val rvBottom = rv.height

        // Top of the block = top of the emoji bar (or top of the bubble if bar is GONE)
        val isSent = getItem(position).isSent
        val emojiBar = vh.emojiBar(isSent)
        val blockTop: Int = if (emojiBar.visibility == android.view.View.VISIBLE) {
            val loc = IntArray(2); emojiBar.getLocationInWindow(loc)
            val rvLoc = IntArray(2); rv.getLocationInWindow(rvLoc)
            loc[1] - rvLoc[1]
        } else {
            val loc = IntArray(2); root.getLocationInWindow(loc)
            val rvLoc = IntArray(2); rv.getLocationInWindow(rvLoc)
            loc[1] - rvLoc[1]
        }

        // Bottom of the block = bottom of the action menu (or bottom of the bubble if GONE)
        val actionMenu = vh.actionMenu(isSent)
        val blockBottom: Int = if (actionMenu.visibility == android.view.View.VISIBLE) {
            val loc = IntArray(2); actionMenu.getLocationInWindow(loc)
            val rvLoc = IntArray(2); rv.getLocationInWindow(rvLoc)
            loc[1] - rvLoc[1] + actionMenu.height
        } else {
            val loc = IntArray(2); root.getLocationInWindow(loc)
            val rvLoc = IntArray(2); rv.getLocationInWindow(rvLoc)
            loc[1] - rvLoc[1] + root.height
        }

        val blockHeight = blockBottom - blockTop
        val rvHeight = rvBottom - rvTop

        val scrollDelta: Int = when {
            // Block is taller than the screen — just show the top (message + emoji bar)
            blockHeight >= rvHeight -> {
                if (blockTop < rvTop) blockTop - rvTop - 16 else 0
            }
            // Bottom of action menu is below visible area — scroll down
            blockBottom > rvBottom -> blockBottom - rvBottom + 16
            // Top of emoji bar is above visible area — scroll up
            blockTop < rvTop -> blockTop - rvTop - 16
            // Already fully visible
            else -> 0
        }

        if (scrollDelta != 0) {
            rv.smoothScrollBy(0, scrollDelta)
        }
    }

    fun showReactionImmediately(itemId: String, emoji: String) {
        val pos = currentList.indexOfFirst { it.id.toString() == itemId }
        if (pos == -1) return
        (recyclerView?.findViewHolderForAdapterPosition(pos) as? VH)?.let { h ->
            val isSent = getItem(pos).isSent

            // Render active side
            val activeRv = h.reaction(isSent)
            if (emoji.isEmpty()) {
                activeRv.visibility = View.GONE
                activeRv.text = ""
            } else {
                activeRv.text = emoji
                activeRv.visibility = View.VISIBLE
            }

            // Forcefully clear inactive side
            val inactiveRv = h.reaction(!isSent)
            inactiveRv.visibility = View.GONE
            inactiveRv.text = ""
        }
    }

    private fun applyStatusIndicator(imageView: ImageView?, item: MessageUiModel) {
        if (imageView == null) return
        imageView.setImageResource(R.drawable.privacy)
        when {
            item.seenAt > 0L -> {
                // 🟢 Seen
                imageView.setColorFilter(android.graphics.Color.parseColor("#4CAF50"))
            }
            item.deliveredAt > 0L -> {
                // 🔵 Delivered
                imageView.setColorFilter(android.graphics.Color.parseColor("#2196F3"))
            }
            item.uploadState == "done" -> {
                // 🟡 Sent
                imageView.setColorFilter(android.graphics.Color.parseColor("#FFC107"))
            }
            item.uploadState == "failed" -> {
                // 🔴 Failed
                imageView.setColorFilter(android.graphics.Color.parseColor("#F44336"))
            }
            else -> {
                // 🔴 Sending (pending / uploading)
                imageView.setColorFilter(android.graphics.Color.parseColor("#F44336"))
            }
        }
    }

    private fun bindText(h: VH, item: MessageUiModel, sent: Boolean) {
        h.sentAudio.isVisible = false
        h.rcvAudio.isVisible = false
        h.sentMedia.isVisible = false
        h.rcvdMedia.isVisible = false

        val type = item.type.uppercase(Locale.US)
        if (type == "WIPE_REQUEST") {
            h.rlSent.isVisible = false
            h.rlRcvd.isVisible = false
            h.tvDecryptingRcv?.isVisible = false
            h.sentMessageTimeLayout?.visibility = View.GONE
            h.receivedMessageTimeLayout?.visibility = View.GONE

            if (sent) {
                h.rlWipeRequestSent?.isVisible = true
                h.rlWipeRequestRcvd?.isVisible = false
                h.wipeSentMessageTimeLayout?.isVisible = true
                h.tvWipeSentTimeWipe?.text = convertDatetime(item.time)
                h.tvWipeRequestSentStatus?.text = "Wipe Chat Request Sent"
            } else {
                h.rlWipeRequestSent?.isVisible = false
                h.rlWipeRequestRcvd?.isVisible = true

                val reqState = item.reaction.uppercase(Locale.US)
                if (reqState == "REJECTED") {
                    h.llWipeRequestActions?.isVisible = false
                    h.tvWipeRequestRcvdStatus?.isVisible = true
                    h.tvWipeRequestRcvdStatus?.text = "Request Rejected"
                } else if (reqState == "ACCEPTED") {
                    h.llWipeRequestActions?.isVisible = false
                    h.tvWipeRequestRcvdStatus?.isVisible = true
                    h.tvWipeRequestRcvdStatus?.text = "Request Accepted"
                } else {
                    h.llWipeRequestActions?.isVisible = true
                    h.tvWipeRequestRcvdStatus?.isVisible = false
                }

                h.btnAcceptWipe?.setOnClickListener { onWipeRequestAction(item, true) }
                h.btnRejectWipe?.setOnClickListener { onWipeRequestAction(item, false) }
            }
            return
        } else if (type == "WIPE_RESPONSE" || type == "WIPE_SYSTEM") {
            h.rlSent.isVisible = false
            h.rlRcvd.isVisible = false
            h.tvDecryptingRcv?.isVisible = false
            h.sentMessageTimeLayout?.visibility = View.GONE
            h.receivedMessageTimeLayout?.visibility = View.GONE

            if (type == "WIPE_SYSTEM") {
                h.rlSent.isVisible = false
                h.rlRcvd.isVisible = false
                h.rlWipeRequestRcvd?.isVisible = false
                h.rlWipeRequestSent?.isVisible = false
                h.llSystemUpdateBubble.visibility = View.VISIBLE
                h.tvSystemUpdateText.text = item.decryptedMsg
                return
            }

            val responseAction = parseWipeResponseAction(item.decryptedMsg)
            val statusText = if (responseAction == "ACCEPTED") {
                "Chat Cleared"
            } else {
                "Wipe request was rejected"
            }
            if (sent) {
                h.rlWipeRequestSent?.isVisible = true
                h.rlWipeRequestRcvd?.isVisible = false
                h.tvWipeRequestSentStatus?.text = statusText
            } else {
                h.rlWipeRequestSent?.isVisible = false
                h.rlWipeRequestRcvd?.isVisible = true
                h.llWipeRequestActions?.isVisible = false
                h.tvWipeRequestRcvdStatus?.isVisible = false
                h.tvWipeRequestRcvdDesc?.text = statusText
            }
            return
        } else if (type == "DISAPPEAR_SYSTEM" || type == "KEY_UPDATE") {
            h.rlSent.isVisible = false
            h.rlRcvd.isVisible = false
            h.tvDecryptingRcv?.isVisible = false
            h.sentMessageTimeLayout?.visibility = View.GONE
            h.receivedMessageTimeLayout?.visibility = View.GONE
            h.rlWipeRequestSent?.isVisible = false
            h.rlWipeRequestRcvd?.isVisible = false
            
            h.llSystemUpdateBubble.visibility = View.VISIBLE
            h.tvSystemUpdateText.text = item.decryptedMsg
            return
        } else {
            h.rlWipeRequestSent?.isVisible = false
            h.rlWipeRequestRcvd?.isVisible = false
            h.llSystemUpdateBubble.visibility = View.GONE
        }

        h.sentMessageTimeLayout?.visibility = View.VISIBLE
        h.receivedMessageTimeLayout?.visibility = View.VISIBLE

        // ── Emoji-only detection ──────────────────────────────────────────────
        // Only strip the bubble when the message is pure emoji AND has no quoted reply
        // (the reply block needs a bubble background to remain legible).
        val emojiOnly = item.replyToText.isEmpty() && isEmojiOnly(item.decryptedMsg)
        val normalPad = h.itemView.resources
            .getDimensionPixelSize(R.dimen._2sdp)

        if (sent) {
            h.tvSent.isVisible = true
            h.tvRcv.isVisible = false
            h.tvRcv.cancelAndShowFinal()
            h.tvSent.text = item.decryptedMsg
            h.tvDecryptingRcv?.visibility = View.GONE

            // Apply or restore bubble style
            if (emojiOnly) {
                h.rlSent.background = null
                h.rlSent.setPadding(0, 0, 0, 0)
                val emojiCount = countEmojis(item.decryptedMsg)
                h.tvSent.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (emojiCount < 6) 36f else 16f)
            } else {
                h.rlSent.setBackgroundResource(R.drawable.sent_msg_bg)
                h.rlSent.setPadding(normalPad, normalPad, normalPad, normalPad)
                h.tvSent.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            }

            // ── 4-State message status indicator ─────────────────────────────────
            applyStatusIndicator(h.ivSentStatus, item)
            h.btnRetrySentText?.isVisible = item.uploadState == "failed"

            h.btnRetrySentText?.setOnClickListener { onRetryUpload(item) }
            return
        }

        // ── RECEIVED ─────────────────────────────────────────────────────────
        h.tvRcv.isVisible = true
        h.tvSent.isVisible = false

        // Apply or restore received bubble style
        if (emojiOnly) {
            h.rlRcvd.background = null
            h.rlRcvd.setPadding(0, 0, 0, 0)
            val emojiCount = countEmojis(item.decryptedMsg)
            h.tvRcv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (emojiCount < 6) 36f else 16f)
        } else {
            h.rlRcvd.setBackgroundResource(R.drawable.recv_msg_bg)
            h.rlRcvd.setPadding(normalPad, normalPad, normalPad, normalPad)
            h.tvRcv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
        }

        val msgId = item.id
        val cached = decryptedTextCache[msgId]

        if (cached != null || msgId <= lastSeenMessageId) {
            h.tvRcv.cancelAndShowFinal()
            h.tvRcv.text = item.decryptedMsg
            h.tvDecryptingRcv?.visibility = View.GONE
            decryptedTextCache[msgId] = item.decryptedMsg
            return
        }

        if (animatingIds.contains(msgId)) {
            // Already animating in a background coroutine. 
            // Ensure the UI state is consistent (showing 'Decrypting' and scramble)
            // while we wait for the coroutine to progress to the reveal phase.
            h.tvDecryptingRcv?.visibility = View.VISIBLE
            h.tvDecryptingRcv?.text = "Decrypting..."
            
            // If the view was recycled, it might have final text from another message.
            // Reset it to the scramble state so it looks correct while waiting.
            val placeholder = generateEncryptedPlaceholder(item.decryptedMsg)
            h.tvRcv.prepareForAnimation(placeholder)
            h.tvRcv.text = placeholder
            return
        }


        if (emojiOnly) {
            h.tvRcv.cancelAndShowFinal()
            h.tvRcv.text = item.decryptedMsg
            h.tvDecryptingRcv?.visibility = View.GONE
            decryptedTextCache[msgId] = item.decryptedMsg
            return
        }

        val encryptedPlaceholder = generateEncryptedPlaceholder(item.decryptedMsg)
        h.tvDecryptingRcv?.visibility = View.VISIBLE
        h.tvDecryptingRcv?.text = "Decrypting..."
        h.tvRcv.prepareForAnimation(encryptedPlaceholder)
        h.tvRcv.text = encryptedPlaceholder
        animatingIds.add(msgId)
        decryptJobs[msgId]?.cancel()

        decryptJobs[msgId] = adapterScope.launch {
            try {
                delay(250)
                val decrypted = item.decryptedMsg
                decryptedTextCache[msgId] = decrypted

                withContext(Dispatchers.Main) {
                    // Update cache even if VH is not visible, so when we scroll back it's already decrypted.
                    decryptedTextCache[msgId] = decrypted
                    
                    val liveVH = findVHForMessage(msgId)
                    if (liveVH == null) {
                        animatingIds.remove(msgId)
                        decryptJobs.remove(msgId)
                        return@withContext
                    }

                    liveVH.tvDecryptingRcv?.visibility = View.VISIBLE
                    liveVH.tvRcv.startPhase1(
                        decrypted = decrypted,
                        onPhase1Done = {
                            val vh2 = findVHForMessage(msgId)
                            if (vh2 != null) {
                                vh2.tvDecryptingRcv?.visibility = View.GONE
                                vh2.tvRcv.beginPhase2(onDone = {
                                    animatingIds.remove(msgId)
                                    decryptJobs.remove(msgId)
                                })
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

    private fun parseWipeResponseAction(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            runCatching {
                return JSONObject(trimmed).optString("action", "").uppercase(Locale.US)
            }
        }
        return trimmed.uppercase(Locale.US)
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
        // ── Fix: explicitly hide time layouts so recycled text-message views don't bleed ──
        h.sentMessageTimeLayout?.visibility = View.GONE
        h.receivedMessageTimeLayout?.visibility = View.GONE

        val ctx = h.itemView.context

        // ── Resolve the best available source for image display ──────────────────
        // For VIDEO: we NEVER try to render a .mp4 directly — Glide can't extract
        // frames from it without an explicit frame/transformer. We use thumbnailPath
        // or Glide's built-in .frame() option instead.
        // For IMAGE: use localFilePath → decryptedUri → placeholder
        fun loadVideoThumbnail(target: ImageView) {
            when {
                // 1. Prefer the pre-generated thumbnail (set by VideoCompressor)
                !item.thumbnailPath.isNullOrBlank() -> {
                    val thumbFile = java.io.File(item.thumbnailPath!!)
                    if (thumbFile.exists()) {
                        Glide.with(ctx)
                            .load(thumbFile)
                            .apply(
                                RequestOptions.centerCropTransform()
                                    .placeholder(R.drawable.video_playback_bg)
                                    .error(R.drawable.video_playback_bg)
                            )
                            .into(target)
                        return
                    }
                }
                // 2. Fall back to frame extraction from the local video file
                !item.localFilePath.isNullOrBlank() -> {
                    val videoFile = java.io.File(item.localFilePath!!)
                    if (videoFile.exists()) {
                        Glide.with(ctx)
                            .asBitmap()
                            .load(videoFile)
                            .apply(
                                com.bumptech.glide.request.RequestOptions()
                                    .frame(0L)  // first available frame — works for short clips too
                                    .centerCrop()
                                    .placeholder(R.drawable.video_playback_bg)
                                    .error(R.drawable.video_playback_bg)
                            )
                            .into(target)
                        return
                    }
                }
            }
            // 3. Nothing available — show placeholder (downloading or no thumbnail)
            Glide.with(ctx)
                .load(R.drawable.video_playback_bg)
                .apply(RequestOptions.centerCropTransform())
                .into(target)
        }

        fun loadImageSource(target: ImageView) {
            val uriStr: String = when {
                !item.localFilePath.isNullOrBlank() && !item.localFilePath!!.startsWith("content://") -> {
                    val f = java.io.File(item.localFilePath!!)
                    if (f.exists()) "file://${item.localFilePath}" else ""
                }

                else -> {
                    val rawSource = item.decryptedUri ?: item.decryptedMsg
                    resolveMediaSource(ctx, rawSource, type)
                }
            }
            // While downloading, try to show a real thumbnail if available
            val isDownloading = item.downloadState == "downloading" || item.downloadState == "pending"
            if (isDownloading) {
                val thumbFile = item.thumbnailPath?.let { java.io.File(it) }
                if (thumbFile != null && thumbFile.exists()) {
                    // Real thumbnail available — show it during download
                    Glide.with(ctx)
                        .load(thumbFile)
                        .apply(
                            RequestOptions.centerCropTransform()
                                .placeholder(R.drawable.photos)
                                .error(R.drawable.photos)
                        )
                        .into(target)
                } else {
                    // No thumbnail yet — generic placeholder
                    Glide.with(ctx)
                        .load(R.drawable.photos)
                        .apply(RequestOptions.centerCropTransform())
                        .into(target)
                }
                return
            }
            if (uriStr.isBlank()) {
                Glide.with(ctx)
                    .load(R.drawable.photos)
                    .apply(RequestOptions.centerCropTransform())
                    .into(target)
            } else {
                val uri = try {
                    uriStr.toUri()
                } catch (_: Exception) {
                    null
                }
                if (uri != null) {
                    Glide.with(ctx)
                        .load(uri)
                        .apply(
                            RequestOptions.centerCropTransform()
                                .placeholder(R.drawable.photos)
                                .error(R.drawable.photos)
                        )
                        .into(target)
                }
            }
        }

        if (sent) {
            h.sentMedia.isVisible = true; h.rcvdMedia.isVisible = false
            h.ivSentMedia.isVisible = true; h.ivSentPlay.isVisible = type == "VIDEO"
            if (type == "VIDEO") loadVideoThumbnail(h.ivSentMedia)
            else loadImageSource(h.ivSentMedia)
            if (item.decryptedMsg.isNotBlank() && item.decryptedMsg != "photo" && item.decryptedMsg != "video") {
                h.tvSent.text = item.decryptedMsg; h.tvSent.isVisible = true
            }
            h.ivSentMedia.setOnClickListener { onClick(item) }
            h.ivSentPlay.setOnClickListener { onClick(item) }
        } else {
            h.rcvdMedia.isVisible = true; h.sentMedia.isVisible = false
            h.ivRcvMedia.isVisible = true; h.ivRcvPlay.isVisible = type == "VIDEO"
            if (type == "VIDEO") loadVideoThumbnail(h.ivRcvMedia)
            else loadImageSource(h.ivRcvMedia)
            if (item.decryptedMsg.isNotBlank() && item.decryptedMsg != "photo" && item.decryptedMsg != "video") {
                h.tvRcv.text = item.decryptedMsg; h.tvRcv.isVisible = true
            }
            h.ivRcvMedia.setOnClickListener { onClick(item) }
            h.ivRcvPlay.setOnClickListener { onClick(item) }
        }

        // ── Upload/download progress overlays ────────────────────────────────────
        if (sent) {
            when (item.uploadState) {
                "pending" -> {
                    h.sentMediaProgressOverlay.visibility = View.VISIBLE
                    h.sentMediaProgressBar.progress = 0
                    h.tvSentMediaProgress.text = "Preparing…"
                    h.btnRetrySentMedia.visibility = View.GONE
                    h.ivSentPlay.isVisible = false
                }

                "compressing" -> {
                    h.sentMediaProgressOverlay.visibility = View.VISIBLE
                    h.sentMediaProgressBar.progress = item.uploadProgress
                    h.tvSentMediaProgress.text = "Compressing… ${item.uploadProgress}%"
                    h.btnRetrySentMedia.visibility = View.GONE
                    h.ivSentPlay.isVisible = false
                }

                "uploading" -> {
                    h.sentMediaProgressOverlay.visibility = View.VISIBLE
                    h.sentMediaProgressBar.progress = item.uploadProgress
                    h.tvSentMediaProgress.text = "Uploading… ${item.uploadProgress}%"
                    h.btnRetrySentMedia.visibility = View.GONE
                    h.ivSentPlay.isVisible = false
                }

                "failed" -> {
                    h.sentMediaProgressOverlay.visibility = View.VISIBLE
                    h.tvSentMediaProgress.text = "Failed"
                    h.sentMediaProgressBar.progress = 0
                    h.btnRetrySentMedia.visibility = View.VISIBLE
                    h.btnRetrySentMedia.setOnClickListener { onRetryUpload(item) }
                }

                else -> {
                    h.sentMediaProgressOverlay.visibility = View.GONE
                    h.btnRetrySentMedia.visibility = View.GONE
                }
            }
        } else {
            when (item.downloadState) {
                "pending", "downloading" -> {
                    h.rcvMediaProgressOverlay.visibility = View.VISIBLE
                    h.rcvMediaProgressBar.progress = item.downloadProgress
                    h.tvRcvMediaProgress.text = "${item.downloadProgress}%"
                    h.ivRcvPlay.isVisible = false
                }

                "failed" -> {
                    h.rcvMediaProgressOverlay.visibility = View.VISIBLE
                    h.tvRcvMediaProgress.text = "Failed"
                    h.rcvMediaProgressBar.progress = 0
                    h.btnRetryRcvMedia.visibility = View.VISIBLE
                    h.btnRetryRcvMedia.setOnClickListener { onRetryDownload(item) }
                }

                else -> {
                    h.rcvMediaProgressOverlay.visibility = View.GONE
                    h.btnRetryRcvMedia.visibility = View.GONE
                }
            }
        }

        // ── Click guard: only allow opening completed media ───────────────────────
        if (sent) {
            val sentReady = item.uploadState == "done"
            h.ivSentMedia.isClickable = sentReady
            h.ivSentPlay.isClickable = sentReady
            
            // ── Show Time and Status for Sent Media ──
            h.sentMessageTimeLayout?.visibility = View.VISIBLE
            applyStatusIndicator(h.ivSentStatus, item)
        } else {
            val rcvReady = item.downloadState == "done"
            h.ivRcvMedia.isClickable = rcvReady
            h.ivRcvPlay.isClickable = rcvReady
            
            // ── Show Time for Received Media ──
            h.receivedMessageTimeLayout?.visibility = View.VISIBLE
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

        // FIX: Route the raw decrypted source through the Base64 file resolver
        // Prefer the directly assembled local path to avoid Base64 re-decode
        val uriStr = if (!item.localFilePath.isNullOrBlank() && item.downloadState == "done") {
            "file://${item.localFilePath}"
        } else {
            val rawSource = item.decryptedUri ?: item.decryptedMsg
            resolveMediaSource(h.itemView.context, rawSource, "AUDIO")
        }
        if (uriStr.isBlank() && item.downloadState != "downloading") return
        if (item.downloadState == "downloading" || item.downloadState == "pending") {
            // Not ready yet — show state UI only, skip playback setup
            // (the progress block above already handled the UI)
            // We still want to show the container
        } else if (uriStr.isBlank()) return

        h.waveform(sent).setAmplitudes(decodeAmplitudes(item.ampsJson))


        // Show upload/download state for audio
        if (sent) {
            when (item.uploadState) {
                "pending", "uploading" -> {
                    h.sentAudioProgressBar.visibility = View.VISIBLE
                    h.sentAudioProgressBar.progress = item.uploadProgress
                    h.tvSentAudioUploadStateLayout.visibility = View.VISIBLE

                    h.tvSentAudioUploadState.text = if (item.uploadState == "pending")
                        "Preparing..." else "Sending ${item.uploadProgress}%"
                    h.playPauseFrame(true).isEnabled = false
                    h.waveform(true).alpha = 0.4f
                }

                "failed" -> {
                    h.sentAudioProgressBar.visibility = View.VISIBLE
                    h.sentAudioProgressBar.progress = 0
                    h.tvSentAudioUploadStateLayout.visibility = View.VISIBLE
                    h.tvSentAudioUploadState.text = "Upload failed"
                    h.btnRetrySentAudio.visibility = View.VISIBLE
                    h.btnRetrySentAudio.setOnClickListener { onRetryUpload(item) }
                    h.playPauseFrame(true).isEnabled = false
                }

                else -> {
                    h.sentAudioProgressBar.visibility = View.GONE
                    h.tvSentAudioUploadStateLayout.visibility = View.GONE
                    h.btnRetrySentAudio.visibility = View.GONE
                    h.playPauseFrame(true).isEnabled = true
                    h.waveform(true).alpha = 1f
                }
            }
        } else {
            when (item.downloadState) {
                "pending", "downloading" -> {
                    h.rcvAudioProgressBar.visibility = View.VISIBLE
                    h.rcvAudioProgressBar.progress = item.downloadProgress
                    h.tvRcvAudioDownloadStateLayout.visibility = View.VISIBLE
                    h.tvRcvAudioDownloadState.text = if (item.downloadState == "pending")
                        "Receiving..." else "Downloading ${item.downloadProgress}%"
                    h.playPauseFrame(false).isEnabled = false
                    h.waveform(false).alpha = 0.4f
                    // Show duration from metadata even before assembly
                    if (item.mediaDurationMs > 0L) {
                        val secs = (item.mediaDurationMs / 1000).toInt()
                        h.durationLabel(false).text = String.format(
                            java.util.Locale.getDefault(), "%d:%02d", secs / 60, secs % 60
                        )
                    }
                }

                "failed" -> {
                    h.rcvAudioProgressBar.visibility = View.VISIBLE
                    h.rcvAudioProgressBar.progress = 0
                    h.tvRcvAudioDownloadStateLayout.visibility = View.VISIBLE
                    h.tvRcvAudioDownloadState.text = "Download failed"
                    h.btnRetryRcvAudio.visibility = View.VISIBLE
                    h.btnRetryRcvAudio.setOnClickListener { onRetryDownload(item) }
                    h.playPauseFrame(false).isEnabled = false
                }

                else -> {
                    h.rcvAudioProgressBar.visibility = View.GONE
                    h.tvRcvAudioDownloadStateLayout.visibility = View.GONE
                    h.btnRetryRcvAudio.visibility = View.GONE
                    h.playPauseFrame(false).isEnabled = true
                    h.waveform(false).alpha = 1f
                }
            }
        }


        //enabling the play_pause_icon once the downloading progress reaches 100 %
        when (item.downloadProgress) {
            100 -> {
                h.playPauseFrame(false).isEnabled = true
                h.rcvAudioProgressBar.visibility = View.GONE
                h.tvRcvAudioDownloadStateLayout.visibility = View.GONE
            }
        }


        h.durationLabel(sent).text = formatDuration(getTotalDuration(h.itemView.context, uriStr))
        if (activeUri == uriStr) syncPlayingUi(h, sent)
        else {
            h.playIcon(sent).setImageResource(R.drawable.pause_icon_1); h.waveform(sent)
                .setProgress(0f); h.speedBadge(sent).text = SPEED_STEPS[0].toLabel()
        }

        h.playPauseFrame(sent).setOnClickListener {
            if (activeUri == uriStr) {
                val player = activePlayer
                if (player != null && player.isPlaying) {
                    player.pause(); activeHandler?.removeCallbacksAndMessages(null); h.playIcon(sent)
                        .setImageResource(R.drawable.pause_icon_1)
                } else if (player != null) {
                    player.start(); h.playIcon(sent)
                        .setImageResource(R.drawable.pause_icon_0); startProgressUpdater(
                        h,
                        sent,
                        uriStr
                    )
                }
            } else startPlayback(h, sent, uriStr)
        }
        h.waveform(sent).setOnSeekListener { progress ->
            if (activeUri == uriStr) activePlayer?.let {
                it.seekTo((progress * it.duration).toInt()); h.waveform(
                sent
            ).setProgress(progress)
            }
            else {
                startPlayback(
                    h,
                    sent,
                    uriStr
                ); activePlayer?.let {
                    it.seekTo((progress * it.duration).toInt()); h.waveform(sent)
                    .setProgress(progress)
                }
            }
        }
        h.speedBadge(sent).setOnClickListener {
            activeSpeedIdx = if (activeUri == uriStr) (activeSpeedIdx + 1) % SPEED_STEPS.size else 0
            val speed = SPEED_STEPS[activeSpeedIdx]; h.speedBadge(sent).text = speed.toLabel()
            if (activeUri == uriStr) applySpeed(speed)
        }
        
        if (sent) {
            applyStatusIndicator(h.ivVoiceSentStatus, item)
        }
    }

    private fun startPlayback(h: VH, sent: Boolean, uriStr: String) {
        stopPlayback(resetUi = true)
        val player = try {
            MediaPlayer().apply { setDataSource(h.itemView.context, uriStr.toUri()); prepare() }
        } catch (e: Exception) {
            e.printStackTrace(); return
        }
        activePlayer = player; activeUri = uriStr; activeHolder = h; activeSpeedIdx = 0
        applySpeed(SPEED_STEPS[0]); player.start()
        h.playIcon(sent).setImageResource(R.drawable.pause_icon_0)
        h.speedBadge(sent).text = SPEED_STEPS[0].toLabel()
        h.durationLabel(sent).text = formatDuration(player.duration)
        startProgressUpdater(h, sent, uriStr)
        player.setOnCompletionListener {
            activeHandler?.removeCallbacksAndMessages(null)
            h.playIcon(sent).setImageResource(R.drawable.pause_icon_1); h.waveform(sent)
            .setProgress(0f)
            h.durationLabel(sent).text = formatDuration(player.duration)
            val cp = h.bindingAdapterPosition; activePlayer = null; activeUri = null; activeHolder =
            null
            if (cp != -1) playNextAudioIfAvailable(cp)
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
                val player = activePlayer ?: return; if (activeUri != uriStr) return
                if (player.isPlaying) {
                    h.waveform(sent)
                        .setProgress(player.currentPosition.toFloat() / player.duration); h.durationLabel(
                        sent
                    ).text = formatDuration(player.duration - player.currentPosition)
                }
                handler.postDelayed(this, 50)
            }
        })
    }

    private fun syncPlayingUi(h: VH, sent: Boolean) {
        val player = activePlayer ?: return
        h.playIcon(sent)
            .setImageResource(if (player.isPlaying) R.drawable.pause_icon_0 else R.drawable.pause_icon_1)
        h.speedBadge(sent).text = SPEED_STEPS[activeSpeedIdx].toLabel()
        if (player.isPlaying) startProgressUpdater(h, sent, activeUri!!)
    }

    private fun stopPlayback(resetUi: Boolean) {
        activeHandler?.removeCallbacksAndMessages(null)
        activePlayer?.stop(); activePlayer?.release(); activePlayer = null
        if (resetUi) activeHolder?.let { h ->
            val pos = h.bindingAdapterPosition; if (pos != -1) {
            val s = getItem(pos).isSent; h.playIcon(s)
                .setImageResource(R.drawable.pause_icon_1); h.waveform(s).setProgress(0f)
        }
        }
        activeUri = null; activeHolder = null
    }

    private fun applySpeed(speed: Float) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) activePlayer?.let {
            it.playbackParams = it.playbackParams.setSpeed(speed)
        }
    }

    private fun Float.toLabel() = if (this == 1.0f) "1x" else "${this}x"

    private fun decodeAmplitudes(json: String?): List<Float> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json); List(arr.length()) { i -> arr.getDouble(i).toFloat() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun formatDuration(ms: Int): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60)
    }

    private fun getTotalDuration(context: Context, uriStr: String): Int {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(
                context,
                uriStr.toUri()
            ); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt() ?: 0
        } catch (_: Exception) {
            0
        } finally {
            r.release()
        }
    }

    private fun convertDatetime(time: String): String {
        return try {
            java.text.SimpleDateFormat("hh:mm a", Locale.getDefault())
                .format(java.util.Date(time.toLong()))
        } catch (_: Exception) {
            ""
        }
    }

    private fun resolveMediaSource(context: Context, payload: String?, type: String): String {
        if (payload.isNullOrBlank()) return ""
        var cleanPayload = payload.trim()

        // Strip any legacy/network prefix tags
        if (cleanPayload.startsWith("[IMAGE] ")) cleanPayload =
            cleanPayload.removePrefix("[IMAGE] ")
        else if (cleanPayload.startsWith("[VIDEO] ")) cleanPayload =
            cleanPayload.removePrefix("[VIDEO] ")
        else if (cleanPayload.startsWith("[AUDIO] ")) cleanPayload =
            cleanPayload.removePrefix("[AUDIO] ")

        // If it's already a playable/loadable local or remote path, return it directly
        if (cleanPayload.startsWith("http") || cleanPayload.startsWith("file://") || cleanPayload.startsWith(
                "content://"
            )
        ) {
            return cleanPayload
        }

        // It is raw decrypted Base64 data. Decode to a local cache file so Glide/MediaPlayer can use it.
        return try {
            val bytes = android.util.Base64.decode(cleanPayload, android.util.Base64.DEFAULT)
            val ext = when (type.uppercase(java.util.Locale.US)) {
                "AUDIO" -> "mp3"
                "VIDEO" -> "mp4"
                else -> "jpg"
            }
            // Use a hash of the payload to avoid rewriting the same file
            val file = java.io.File(context.cacheDir, "kyber_media_${cleanPayload.hashCode()}.$ext")
            if (!file.exists()) {
                file.writeBytes(bytes)
            }
            "file://${file.absolutePath}"
        } catch (e: Exception) {
            cleanPayload // Fallback if decoding fails
        }
    }

    fun extractEmoji(rawReaction: String): String {
        val parts = rawReaction.split("|", limit = 2)
        return if (parts.size == 2) parts[1] else rawReaction
    }

    fun extractOwner(rawReaction: String): String {
        val parts = rawReaction.split("|", limit = 2)
        return if (parts.size == 2) parts[0] else ""
    }

    private fun extractReplyPreviewText(raw: String): String {
        if (!raw.startsWith("__reply__")) return raw
        val content = raw.removePrefix("__reply__")
        val sep = content.indexOf("::")
        if (sep < 0 || sep + 2 > content.length) return raw
        return content.substring(sep + 2)
    }


    fun countEmojis(text: String): Int {
        var count = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isWhitespace(cp)) continue
            count++
        }
        return count
    }

}