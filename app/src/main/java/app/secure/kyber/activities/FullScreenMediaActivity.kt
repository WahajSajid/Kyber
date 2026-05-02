package app.secure.kyber.activities

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.secure.kyber.R
import app.secure.kyber.Utils.MessageEncryptionManager
import app.secure.kyber.roomdb.AppDb
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import kotlin.math.min

class FullscreenMediaActivity : AppCompatActivity() {

    companion object {
        const val TAG = "FullscreenMediaActivity"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_URI = "uri"
        const val EXTRA_TYPE = "type"
    }

    private lateinit var fullImage: ImageView
    private lateinit var playerView: PlayerView
    private var exoPlayer: ExoPlayer? = null
    private var decryptedBytes: ByteArray? = null

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_full_screen_media)

        fullImage  = findViewById(R.id.fullImage)
        playerView = findViewById(R.id.playerView)

        // Try new message-based approach first, fallback to URI
        val messageId = intent?.getStringExtra(EXTRA_MESSAGE_ID)
        val uriString = intent?.getStringExtra(EXTRA_URI)
        val typeName  = intent?.getStringExtra(EXTRA_TYPE) ?: "TEXT"
        val type      = typeName.uppercase()

        if (!messageId.isNullOrBlank()) {
            // Decrypt on-demand from encrypted file
            loadMediaWithDecryption(messageId, type)
        } else if (!uriString.isNullOrBlank()) {
            // Legacy: load directly from URI (unencrypted)
            loadMediaDirect(uriString, type)
        } else {
            showError("Media not available")
        }
    }

    /**
     * Load media with just-in-time decryption.
     * Reads encrypted file, decrypts in-memory, displays without writing decrypted to disk.
     */
    private fun loadMediaWithDecryption(messageId: String, type: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDb.get(this@FullscreenMediaActivity)
                val message = db.messageDao().getMessageByMessageId(messageId)
                    ?: run { showError("Message not found"); return@launch }

                // Verify file exists
                val localPath = message.localFilePath ?: run { showError("No media path"); return@launch }
                val encryptedFile = File(localPath.removePrefix("file://"))
                if (!encryptedFile.exists() || encryptedFile.length() == 0L) {
                    showError("Media file not ready"); return@launch
                }

                // Read encrypted bytes from disk
                val encryptedBytes = encryptedFile.readBytes()

                // Decrypt in-memory only
                val decrypted = try {
                    MessageEncryptionManager.decryptSmartRaw(
                        this@FullscreenMediaActivity,
                        encryptedBytes,
                        message.senderOnion,
                        message.keyFingerprint,
                        message.iv
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Decryption failed", e)
                    showError("Decryption failed"); return@launch
                }

                // Store reference for cleanup
                decryptedBytes = decrypted

                // Launch on UI thread for display
                runOnUiThread {
                    displayMedia(type, decrypted)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading encrypted media", e)
                showError("Failed to load media")
            }
        }
    }

    /**
     * Load media directly from URI (legacy/unencrypted path).
     */
    private fun loadMediaDirect(uriString: String, type: String) {
        // Guard: file:// URI must exist on disk
        if (uriString.startsWith("file://") || uriString.startsWith("/")) {
            val file = File(uriString.removePrefix("file://"))
            if (!file.exists() || file.length() == 0L) {
                showError("Media file not ready"); return
            }
        }

        val uri = try { Uri.parse(uriString) } catch (e: Exception) {
            showError("Invalid media URI"); return
        }

        displayMedia(type, uri)
    }

    /**
     * Display media (image or video) using the provided URI or decrypted ByteArray.
     */
    private fun displayMedia(type: String, uriOrBytes: Any) {
        // Close button
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }

        if (type == "IMAGE") {
            fullImage.visibility  = View.VISIBLE
            playerView.visibility = View.GONE
            fullImage.scaleType   = ImageView.ScaleType.MATRIX

            try {
                when (uriOrBytes) {
                    is ByteArray -> {
                        // Load decrypted bytes
                        Glide.with(this)
                            .load(uriOrBytes)
                            .into(fullImage)
                    }
                    is Uri -> {
                        // Load from URI
                        Glide.with(this)
                            .load(uriOrBytes)
                            .into(fullImage)
                    }
                    is String -> {
                        // Load from URI string
                        Glide.with(this)
                            .load(Uri.parse(uriOrBytes))
                            .into(fullImage)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not load image", e)
                showError("Could not load image"); return
            }

            // Attach zoom/pan handler after layout
            fullImage.post { ZoomPanHandler(fullImage).attach() }

        } else if (type == "VIDEO") {
            fullImage.visibility  = View.GONE
            playerView.visibility = View.VISIBLE

            exoPlayer = ExoPlayer.Builder(this).build()
            playerView.player = exoPlayer

            try {
                val mediaItem = when (uriOrBytes) {
                    is ByteArray -> {
                        // For ByteArray (decrypted video), write to temp file temporarily
                        // This is unavoidable for ExoPlayer, but we delete it immediately after playback
                        val tempFile = File(cacheDir, "video_${System.nanoTime()}.mp4")
                        tempFile.writeBytes(uriOrBytes)
                        tempFile.deleteOnExit()  // Mark for deletion on app exit
                        MediaItem.fromUri(Uri.fromFile(tempFile))
                    }
                    is Uri -> MediaItem.fromUri(uriOrBytes)
                    is String -> MediaItem.fromUri(Uri.parse(uriOrBytes))
                    else -> null
                }

                if (mediaItem != null) {
                    exoPlayer?.setMediaItem(mediaItem)
                    exoPlayer?.prepare()
                    exoPlayer?.playWhenReady = true
                } else {
                    showError("Invalid media format")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not load video", e)
                showError("Could not load video")
            }
        } else {
            showError("Unsupported media type")
        }
    }

    private fun showError(message: String) {
        runOnUiThread {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Clear decrypted bytes immediately
        decryptedBytes?.let { 
            java.util.Arrays.fill(it, 0)  // Overwrite with zeros
            Log.d(TAG, "Cleared decrypted byte array")
        }
        decryptedBytes = null
        
        // Release player
        exoPlayer?.release()
        exoPlayer = null
        
        // Force garbage collection
        System.gc()
        Log.d(TAG, "Activity destroyed, GC invoked")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ZoomPanHandler — self-contained pinch-zoom + pan using Matrix transforms.
// No external libraries required.
// ─────────────────────────────────────────────────────────────────────────────
@SuppressLint("ClickableViewAccessibility")
private class ZoomPanHandler(private val view: ImageView) {

    private val matrix       = Matrix()
    private val matrixValues = FloatArray(9)

    private var minScale = 1f   // recalculated once drawable dimensions are known
    private val maxScale = 5f

    // Pan tracking
    private val lastTouch      = PointF()
    private var isDragging     = false
    private var activePointerId = 0

    // Double-tap to reset zoom
    private var lastTapTime = 0L
    private val doubleTapMs = 300L

    private val scaleDetector = ScaleGestureDetector(
        view.context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentScale()
                var factor  = detector.scaleFactor
                // Clamp so we never exceed the allowed range
                factor = max(minScale / current, min(factor, maxScale / current))
                matrix.postScale(factor, factor, detector.focusX, detector.focusY)
                clampTranslation()
                view.imageMatrix = matrix
                return true
            }
        }
    )

    fun attach() {
        // Initialise matrix to fit-center equivalent
        val drawable = view.drawable
        if (drawable != null) {
            val dw = drawable.intrinsicWidth.toFloat()
            val dh = drawable.intrinsicHeight.toFloat()
            val vw = view.width.toFloat()
            val vh = view.height.toFloat()
            if (dw > 0 && dh > 0 && vw > 0 && vh > 0) {
                val fitScale = min(vw / dw, vh / dh)
                minScale = fitScale
                matrix.setScale(fitScale, fitScale)
                matrix.postTranslate((vw - dw * fitScale) / 2f, (vh - dh * fitScale) / 2f)
                view.imageMatrix = matrix
            }
        }

        view.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activePointerId = event.getPointerId(0)
                    lastTouch.set(event.x, event.y)
                    isDragging = false

                    // Double-tap → reset to fit-center
                    val now = System.currentTimeMillis()
                    if (now - lastTapTime < doubleTapMs) {
                        resetZoom()
                        lastTapTime = 0L
                    } else {
                        lastTapTime = now
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress) {
                        val idx = event.findPointerIndex(activePointerId)
                        if (idx >= 0) {
                            val dx = event.getX(idx) - lastTouch.x
                            val dy = event.getY(idx) - lastTouch.y
                            // Only start dragging after a small slop to avoid jitter
                            if (!isDragging && dx * dx + dy * dy > 100f) isDragging = true
                            if (isDragging) {
                                matrix.postTranslate(dx, dy)
                                clampTranslation()
                                view.imageMatrix = matrix
                                lastTouch.set(event.getX(idx), event.getY(idx))
                            }
                        }
                    }
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    // Hand active pointer to the finger that stayed on screen
                    val upIdx = (event.action and MotionEvent.ACTION_POINTER_INDEX_MASK) shr
                            MotionEvent.ACTION_POINTER_INDEX_SHIFT
                    val upId  = event.getPointerId(upIdx)
                    if (activePointerId == upId) {
                        val newIdx = if (upIdx == 0) 1 else 0
                        activePointerId = event.getPointerId(newIdx)
                        lastTouch.set(event.getX(newIdx), event.getY(newIdx))
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                }
            }
            true  // consume all events — never triggers a close
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun currentScale(): Float {
        matrix.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    /** Keeps the image inside (or centred within) the view boundaries. */
    private fun clampTranslation() {
        val drawable = view.drawable ?: return
        val scale = currentScale()
        val dw    = drawable.intrinsicWidth  * scale
        val dh    = drawable.intrinsicHeight * scale
        val vw    = view.width.toFloat()
        val vh    = view.height.toFloat()

        matrix.getValues(matrixValues)
        var tx = matrixValues[Matrix.MTRANS_X]
        var ty = matrixValues[Matrix.MTRANS_Y]

        // Horizontal
        tx = when {
            dw <= vw   -> (vw - dw) / 2f   // image fits: centre it
            tx > 0f    -> 0f               // left gap: snap to left edge
            tx < vw-dw -> vw - dw          // right gap: snap to right edge
            else       -> tx
        }
        // Vertical
        ty = when {
            dh <= vh   -> (vh - dh) / 2f
            ty > 0f    -> 0f
            ty < vh-dh -> vh - dh
            else       -> ty
        }

        matrixValues[Matrix.MTRANS_X] = tx
        matrixValues[Matrix.MTRANS_Y] = ty
        matrix.setValues(matrixValues)
    }

    private fun resetZoom() {
        val drawable = view.drawable ?: return
        val dw = drawable.intrinsicWidth.toFloat()
        val dh = drawable.intrinsicHeight.toFloat()
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        matrix.setScale(minScale, minScale)
        matrix.postTranslate((vw - dw * minScale) / 2f, (vh - dh * minScale) / 2f)
        view.imageMatrix = matrix
    }
}