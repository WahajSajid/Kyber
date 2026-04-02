package app.secure.kyber.activities

import android.annotation.SuppressLint
import app.secure.kyber.R
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import java.io.File

class FullscreenMediaActivity : AppCompatActivity() {

    private lateinit var fullImage: ImageView
    private lateinit var playerView: PlayerView
    private var exoPlayer: ExoPlayer? = null

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_full_screen_media)

        fullImage = findViewById(R.id.fullImage)
        playerView = findViewById(R.id.playerView)

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
            val file = File(path)
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
            playerView.visibility = View.GONE
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
            playerView.visibility = View.VISIBLE

            exoPlayer = ExoPlayer.Builder(this).build()
            playerView.player = exoPlayer
            
            val mediaItem = MediaItem.fromUri(uri)
            exoPlayer?.setMediaItem(mediaItem)
            exoPlayer?.prepare()
            exoPlayer?.playWhenReady = true

        } else {
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }
}