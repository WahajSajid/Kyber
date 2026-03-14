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
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.secure.kyber.R
import app.secure.kyber.roomdb.GroupMessageEntity
import app.secure.kyber.Other.DecryptRevealTextView
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
            override fun areItemsTheSame(old: GroupMessageEntity, new: GroupMessageEntity) = old.messageId == new.messageId
            override fun areContentsTheSame(old: GroupMessageEntity, new: GroupMessageEntity) = old == new
        }
        private val SPEED_STEPS = listOf(1.0f, 1.5f, 2.0f)
        private val SCRAMBLE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*"

        fun generateEncryptedPlaceholder(length: Int): String {
            if (length <= 0) return "••••••••"
            return (1..length.coerceAtLeast(8)).map { SCRAMBLE_CHARS[Random.nextInt(SCRAMBLE_CHARS.length)] }.joinToString("")
        }

        private val persistentCache = mutableMapOf<String, String>()
        fun clearPersistentCache() = persistentCache.clear()
    }

    private var activePlayer: MediaPlayer? = null
    private var activeUri: String? = null
    private var activeHolder: VH? = null
    private var activeHandler: android.os.Handler? = null
    private var activeSpeedIdx: Int = 0
    private val openMenuPositions = mutableSetOf<Int>()

    private val adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val decryptedTextCache: MutableMap<String, String> get() = persistentCache
    private val animatingIds = mutableSetOf<String>()
    private val decryptJobs = mutableMapOf<String, Job>()

    var messageDecryptor: suspend (String) -> String = { it }
    private var rvRef: RecyclerView? = recyclerView

    init { setHasStableIds(true) }

    override fun submitList(list: List<GroupMessageEntity>?) {
        if (currentList.isEmpty() && list != null) list.forEach { persistentCache[it.messageId] = it.msg }
        super.submitList(list)
    }

    override fun submitList(list: List<GroupMessageEntity>?, commitCallback: Runnable?) {
        if (currentList.isEmpty() && list != null) list.forEach { persistentCache[it.messageId] = it.msg }
        super.submitList(list, commitCallback)
    }

    override fun onAttachedToRecyclerView(rv: RecyclerView) { super.onAttachedToRecyclerView(rv); rvRef = rv }
    override fun onDetachedFromRecyclerView(rv: RecyclerView) { super.onDetachedFromRecyclerView(rv); rvRef = null; adapterScope.coroutineContext.cancelChildren() }
    override fun getItemId(position: Int) = getItem(position).messageId.hashCode().toLong()

    fun releasePlayer() { stopPlayback(resetUi = true) }
    fun updateRecentEmojis(newList: List<String>) { recentEmojis = newList }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvSentMsg: TextView = view.findViewById(R.id.tvSentMsg)
        // DecryptRevealTextView — same id as old tvMsgRcv, is a subclass of AppCompatTextView
        val tvMsgRcv: DecryptRevealTextView = view.findViewById(R.id.tvMsgRcv)
        val tvRcvTime: TextView = view.findViewById(R.id.tvRcvTime)
        val tvSendTime: TextView = view.findViewById(R.id.tvSentTime)
        val rlSendMsg: LinearLayout = view.findViewById(R.id.rlMsgSent)
        val rlRecieveMsg: LinearLayout = view.findViewById(R.id.rlMsgRcvd)
        val receivedMessageTime: LinearLayout = view.findViewById(R.id.receivedMessageTime)
        val sentMessageTime: LinearLayout = view.findViewById(R.id.sentMessageTime)
        val senderName: TextView = view.findViewById(R.id.sender_name)
        val senderId: TextView = view.findViewById(R.id.sender_id)

        val flMediaSent: FrameLayout = view.findViewById(R.id.flMediaSent)
        val ivMediaSent: ShapeableImageView = view.findViewById(R.id.ivMediaSent)
        val ivPlaySent: ImageView = view.findViewById(R.id.ivPlaySent)
        val flMediaRcv: FrameLayout = view.findViewById(R.id.flMediaRcv)
        val ivMediaRcv: ShapeableImageView = view.findViewById(R.id.ivMediaRcv)
        val ivPlayRcv: ImageView = view.findViewById(R.id.ivPlayRcv)

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
        VH(LayoutInflater.from(parent.context).inflate(R.layout.group_message_item, parent, false))

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.tvMsgRcv.cancelAndShowFinal()
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val pos = holder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return
        val item = getItem(pos)
        val showTime = if (pos < itemCount - 1) convertDatetime(item.time) != convertDatetime(getItem(pos + 1).time) else true
        val type = item.type.uppercase(Locale.US)
        val isSent = item.senderId == myId
        val isMedia = type == "IMAGE" || type == "VIDEO"
        val isAudio = type == "AUDIO"

        holder.rlSendMsg.isVisible = isSent
        holder.rlRecieveMsg.isVisible = !isSent
        holder.sentMessageTime.isVisible = isSent && showTime
        holder.receivedMessageTime.isVisible = !isSent && showTime

        val isMenuOpen = openMenuPositions.contains(pos)
        if (isMenuOpen) {
            val emojiAdapter = RecentEmojiAdapter(recentEmojis, item.reaction) { emoji ->
                val fe = if (emoji == item.reaction) "" else emoji; closeMenu(); onEmojiSelected(item, fe)
            }
            holder.rvEmojis(isSent).apply { layoutManager = LinearLayoutManager(holder.itemView.context, LinearLayoutManager.HORIZONTAL, false); adapter = emojiAdapter }
            holder.btnMore(isSent).setOnClickListener { onMoreEmojisClicked(item); closeMenu() }
            holder.emojiBar(isSent).visibility = View.VISIBLE
            holder.actionMenu(isSent).visibility = View.VISIBLE
        } else {
            holder.rvEmojis(isSent).adapter = null
            holder.emojiBar(isSent).visibility = View.GONE
            holder.actionMenu(isSent).visibility = View.GONE
        }

        when { isAudio -> bindAudio(holder, item, isSent); isMedia -> bindMedia(holder, item, isSent, type); else -> bindText(holder, item, isSent) }

        if (isSent) holder.tvSendTime.text = convertDatetime(item.time)
        else { holder.senderName.text = item.senderName; holder.senderId.text = item.senderId; holder.tvRcvTime.text = convertDatetime(item.time) }

        val rv = holder.reaction(isSent)
        if (item.reaction.isNotEmpty()) { rv.text = item.reaction; rv.visibility = View.VISIBLE } else rv.visibility = View.GONE

        holder.itemView.setOnLongClickListener { val p = holder.bindingAdapterPosition; if (p != RecyclerView.NO_POSITION) showMenu(p); true }
        holder.itemView.setOnClickListener { if (openMenuPositions.isNotEmpty()) closeMenu() else onClick(item) }
        setupActionMenuButtons(holder, item)
    }

    fun showReactionImmediately(itemId: String, emoji: String) {
        val pos = currentList.indexOfFirst { it.messageId == itemId }; if (pos == -1) return
        (rvRef?.findViewHolderForAdapterPosition(pos) as? VH)?.let { h ->
            val r = h.reaction(getItem(pos).senderId == myId)
            if (emoji.isEmpty()) r.visibility = View.GONE else { r.text = emoji; r.visibility = View.VISIBLE }
        }
    }

    private fun setupActionMenuButtons(holder: VH, item: GroupMessageEntity) {
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
        val pos = holder.bindingAdapterPosition; if (pos == RecyclerView.NO_POSITION) return
        if (payloads.contains("START_PLAYBACK")) { val item = getItem(pos); startPlayback(holder, item.senderId == myId, item.uri ?: return) }
        else onBindViewHolder(holder, position)
    }

    private fun showMenu(position: Int) {
        val prev = openMenuPositions.toSet(); openMenuPositions.clear(); openMenuPositions.add(position)
        prev.forEach { if (it != position) notifyItemChanged(it) }; notifyItemChanged(position)
    }

    fun closeMenu() { val prev = openMenuPositions.toSet(); openMenuPositions.clear(); prev.forEach { notifyItemChanged(it) } }

    // ─────────────────────────────────────────────────────────────────────────
    // bindText — strict 2-phase sequential animation for received group messages
    // ─────────────────────────────────────────────────────────────────────────
    private fun bindText(h: VH, item: GroupMessageEntity, sent: Boolean) {
        h.sentAudio.isVisible = false; h.rcvAudio.isVisible = false
        h.flMediaSent.isVisible = false; h.flMediaRcv.isVisible = false
        h.rlSendMsg.setBackgroundResource(R.drawable.sent_msg_bg)

        if (sent) {
            h.tvSentMsg.isVisible = true; h.tvMsgRcv.isVisible = false
            h.tvMsgRcv.cancelAndShowFinal()
            h.tvSentMsg.text = item.msg; h.tvDecryptingRcv?.visibility = View.GONE
            return
        }

        h.tvMsgRcv.isVisible = true; h.tvSentMsg.isVisible = false
        val msgId = item.messageId
        val cached = decryptedTextCache[msgId]

        if (cached != null) {
            h.tvMsgRcv.cancelAndShowFinal(); h.tvMsgRcv.text = cached
            h.tvDecryptingRcv?.visibility = View.GONE; return
        }

        if (animatingIds.contains(msgId)) {
            h.tvDecryptingRcv?.visibility = View.VISIBLE; return
        }

        // ── Brand new message — strict 2-phase animation ─────────────────────
        val encryptedPlaceholder = generateEncryptedPlaceholder(item.msg.length)
        h.tvDecryptingRcv?.visibility = View.VISIBLE
        h.tvDecryptingRcv?.text = "Decrypting..."
        // prepareForAnimation sets phase = SIZING before setText so any onDraw during
        // the layout pass draws nothing — prevents encrypted text flashing without background.
        h.tvMsgRcv.prepareForAnimation(encryptedPlaceholder)
        h.tvMsgRcv.text = encryptedPlaceholder   // size the view (onDraw suppressed via SIZING)

        animatingIds.add(msgId)
        decryptJobs[msgId]?.cancel()
        decryptJobs[msgId] = adapterScope.launch {
            try {
                val decrypted = withContext(Dispatchers.IO) { messageDecryptor(item.msg) }
                decryptedTextCache[msgId] = decrypted
                withContext(Dispatchers.Main) {
                    val liveVH = findVHForMessage(msgId) ?: run { animatingIds.remove(msgId); decryptJobs.remove(msgId); return@withContext }
                    liveVH.tvMsgRcv.startPhase1(
                        decrypted = decrypted,
                        onPhase1Done = {
                            val vh2 = findVHForMessage(msgId)
                            if (vh2 != null) {
                                vh2.tvMsgRcv.beginPhase2(
                                    onDone = {
                                        findVHForMessage(msgId)?.tvDecryptingRcv?.visibility = View.GONE
                                        animatingIds.remove(msgId); decryptJobs.remove(msgId)
                                    }
                                )
                            } else {
                                animatingIds.remove(msgId); decryptJobs.remove(msgId)
                            }
                        }
                    )
                }
            } catch (_: Exception) {
                decryptedTextCache[msgId] = item.msg
                withContext(Dispatchers.Main) {
                    findVHForMessage(msgId)?.let { vh -> vh.tvMsgRcv.cancelAndShowFinal(); vh.tvDecryptingRcv?.visibility = View.GONE }
                    animatingIds.remove(msgId); decryptJobs.remove(msgId)
                }
            }
        }
    }

    private fun findVHForMessage(msgId: String): VH? {
        val idx = currentList.indexOfFirst { it.messageId == msgId }; if (idx == -1) return null
        val vh = rvRef?.findViewHolderForAdapterPosition(idx) as? VH ?: return null
        val pos = vh.bindingAdapterPosition; if (pos == RecyclerView.NO_POSITION) return null
        if (runCatching { getItem(pos) }.getOrNull()?.messageId != msgId) return null
        return vh
    }

    private fun bindMedia(h: VH, item: GroupMessageEntity, sent: Boolean, type: String) {
        h.sentAudio.isVisible = false; h.rcvAudio.isVisible = false
        h.tvSentMsg.isVisible = false; h.tvMsgRcv.isVisible = false
        h.tvMsgRcv.cancelAndShowFinal()
        h.rlSendMsg.setBackgroundResource(R.drawable.sent_msg_bg)
        val uriStr = item.uri ?: item.msg; val uri = try { uriStr.toUri() } catch (_: Exception) { null }
        if (sent) {
            h.flMediaSent.isVisible = true; h.flMediaRcv.isVisible = false; h.ivMediaSent.isVisible = true; h.ivPlaySent.isVisible = type == "VIDEO"
            if (uri != null) Glide.with(h.itemView.context).load(uri).into(h.ivMediaSent)
            if (item.msg.isNotBlank() && item.msg != "photo" && item.msg != "video") { h.tvSentMsg.text = item.msg; h.tvSentMsg.isVisible = true }
        } else {
            h.flMediaRcv.isVisible = true; h.flMediaSent.isVisible = false; h.ivMediaRcv.isVisible = true; h.ivPlayRcv.isVisible = type == "VIDEO"
            if (uri != null) Glide.with(h.itemView.context).load(uri).into(h.ivMediaRcv)
            if (item.msg.isNotBlank() && item.msg != "photo" && item.msg != "video") { h.tvMsgRcv.text = item.msg; h.tvMsgRcv.isVisible = true }
        }
    }

    private fun bindAudio(h: VH, item: GroupMessageEntity, sent: Boolean) {
        h.tvSentMsg.isVisible = false; h.tvMsgRcv.isVisible = false
        h.flMediaSent.isVisible = false; h.flMediaRcv.isVisible = false
        h.tvMsgRcv.cancelAndShowFinal()
        h.rlSendMsg.setBackgroundResource(R.drawable.sent_msg_bg)
        h.sentAudio.isVisible = sent; h.rcvAudio.isVisible = !sent
        val uriStr = item.uri ?: return
        h.waveform(sent).setAmplitudes(decodeAmplitudes(item.ampsJson))
        h.durationLabel(sent).text = formatDuration(getTotalDuration(h.itemView.context, uriStr))
        if (activeUri == uriStr) syncPlayingUi(h, sent)
        else { h.playIcon(sent).setImageResource(R.drawable.play_ic); h.waveform(sent).setProgress(0f); h.speedBadge(sent).text = SPEED_STEPS[0].toLabel() }

        h.playPauseFrame(sent).setOnClickListener {
            if (activeUri == uriStr) {
                val player = activePlayer
                if (player != null && player.isPlaying) { player.pause(); activeHandler?.removeCallbacksAndMessages(null); h.playIcon(sent).setImageResource(R.drawable.play_ic) }
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
        h.playIcon(sent).setImageResource(R.drawable.pause_icon_0); h.speedBadge(sent).text = SPEED_STEPS[0].toLabel()
        h.durationLabel(sent).text = formatDuration(player.duration); startProgressUpdater(h, sent, uriStr)
        player.setOnCompletionListener {
            activeHandler?.removeCallbacksAndMessages(null)
            h.playIcon(sent).setImageResource(R.drawable.play_ic); h.waveform(sent).setProgress(0f)
            h.durationLabel(sent).text = formatDuration(player.duration)
            val cp = h.bindingAdapterPosition; activePlayer = null; activeUri = null; activeHolder = null
            if (cp != -1) playNextAudioIfAvailable(cp)
        }
    }

    private fun playNextAudioIfAvailable(currentPos: Int) {
        for (i in (currentPos + 1) until itemCount) { if (getItem(i).type.uppercase(Locale.US) == "AUDIO") { notifyItemChanged(i, "START_PLAYBACK"); break } }
    }

    private fun startProgressUpdater(h: VH, sent: Boolean, uriStr: String) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper()); activeHandler = handler
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
        h.playIcon(sent).setImageResource(if (player.isPlaying) R.drawable.pause_icon_0 else R.drawable.play_ic)
        h.speedBadge(sent).text = SPEED_STEPS[activeSpeedIdx].toLabel()
        if (player.isPlaying) startProgressUpdater(h, sent, activeUri!!)
    }

    private fun stopPlayback(resetUi: Boolean) {
        activeHandler?.removeCallbacksAndMessages(null); activePlayer?.stop(); activePlayer?.release(); activePlayer = null
        if (resetUi) activeHolder?.let { h -> val pos = h.bindingAdapterPosition; if (pos != -1) { val s = getItem(pos).senderId == myId; h.playIcon(s).setImageResource(R.drawable.play_ic); h.waveform(s).setProgress(0f) } }
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
        val s = (ms / 1000).coerceAtLeast(0); return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60)
    }

    private fun getTotalDuration(context: Context, uriStr: String): Int {
        val r = MediaMetadataRetriever()
        return try { r.setDataSource(context, uriStr.toUri()); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt() ?: 0 } catch (_: Exception) { 0 } finally { r.release() }
    }

    private fun convertDatetime(time: String): String {
        return try { java.text.SimpleDateFormat("hh:mm a", Locale.getDefault()).format(java.util.Date(time.toLong())) } catch (_: Exception) { "" }
    }
}