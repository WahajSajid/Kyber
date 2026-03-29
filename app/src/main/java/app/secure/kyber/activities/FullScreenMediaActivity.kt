package app.secure.kyber.activities

import android.annotation.SuppressLint
import app.secure.kyber.R
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import java.util.Locale

class FullscreenMediaActivity : AppCompatActivity() {

    private lateinit var fullImage: ImageView
    private lateinit var fullVideo: VideoView
    private lateinit var videoControls: LinearLayout
    private lateinit var videoSeekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val updateSeekBar = object : Runnable {
        override fun run() {
            if (fullVideo.isPlaying) {
                val currentPos = fullVideo.currentPosition
                videoSeekBar.progress = currentPos
                tvCurrentTime.text = formatTime(currentPos)
            }
            handler.postDelayed(this, 1000)
        }
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_full_screen_media)

        fullImage = findViewById(R.id.fullImage)
        fullVideo = findViewById(R.id.fullVideo)
        videoControls = findViewById(R.id.videoControls)
        videoSeekBar = findViewById(R.id.videoSeekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)

        val uriString = intent?.getStringExtra("uri")
        val typeName  = intent?.getStringExtra("type") ?: "TEXT"
        val type      = typeName.uppercase()

        // ── Guard 1: missing URI ──────────────────────────────────────────
        if (uriString.isNullOrBlank()) {
            android.widget.Toast.makeText(this, "Media not available", android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // ── Guard 2: file:// URI must exist on disk before we touch any player ──
        if (uriString.startsWith("file://") || uriString.startsWith("/")) {
            val path = uriString.removePrefix("file://")
            val file = java.io.File(path)
            if (!file.exists() || file.length() == 0L) {
                android.widget.Toast.makeText(this, "Media file not ready", android.widget.Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        }

        val uri = try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Invalid media URI", android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (type == "IMAGE") {
            fullImage.visibility = View.VISIBLE
            fullVideo.visibility = View.GONE
            videoControls.visibility = View.GONE
            try {
                Glide.with(this).load(uri).into(fullImage)
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Could not load image", android.widget.Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            fullImage.setOnClickListener { finish() }

        } else if (type == "VIDEO") {
            fullImage.visibility = View.GONE
            fullVideo.visibility = View.VISIBLE
            videoControls.visibility = View.VISIBLE

            // ── Error listener must be set BEFORE setVideoURI ────────────
            fullVideo.setOnErrorListener { _, what, extra ->
                android.util.Log.e("FullscreenMedia", "VideoView error: what=$what extra=$extra")
                handler.removeCallbacks(updateSeekBar)
                android.widget.Toast.makeText(
                    this, "Could not play video", android.widget.Toast.LENGTH_SHORT
                ).show()
                finish()
                true // consumed
            }

            fullVideo.setVideoURI(uri)

            fullVideo.setOnPreparedListener { _ ->
                videoSeekBar.max = fullVideo.duration
                tvTotalTime.text = formatTime(fullVideo.duration)
                fullVideo.start()
                handler.post(updateSeekBar)
            }

            fullVideo.setOnClickListener {
                if (fullVideo.isPlaying) fullVideo.pause() else fullVideo.start()
            }

            videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        fullVideo.seekTo(progress)
                        tvCurrentTime.text = formatTime(progress)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

            fullVideo.setOnCompletionListener {
                handler.removeCallbacks(updateSeekBar)
                videoSeekBar.progress = 0
                tvCurrentTime.text = formatTime(0)
            }

        } else {
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        // Stop playback and handler when user navigates away
        try {
            if (::fullVideo.isInitialized && fullVideo.isPlaying) {
                fullVideo.pause()
            }
        } catch (e: Exception) { /* ignore partial init */ }
        handler.removeCallbacks(updateSeekBar)
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateSeekBar)
        try {
            if (::fullVideo.isInitialized) {
                fullVideo.stopPlayback()  // safer than relying on VideoView's own onDetach
            }
        } catch (e: Exception) {
            android.util.Log.w("FullscreenMedia", "onDestroy cleanup: ${e.message}")
        }

        handler.removeCallbacks(updateSeekBar)

        super.onDestroy()
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

}