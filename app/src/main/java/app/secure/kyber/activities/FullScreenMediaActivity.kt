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

        val uriString = intent?.getStringExtra("uri") ?: return finish()
        val typeName = intent?.getStringExtra("type") ?: "TEXT"
        val uri = Uri.parse(uriString)
        val type = typeName.uppercase()

        if (type == "IMAGE") {
            fullImage.visibility = View.VISIBLE
            fullVideo.visibility = View.GONE
            videoControls.visibility = View.GONE
            Glide.with(this).load(uri).into(fullImage)
            fullImage.setOnClickListener { finish() }
        } else if (type == "VIDEO") {
            fullImage.visibility = View.GONE
            fullVideo.visibility = View.VISIBLE
            videoControls.visibility = View.VISIBLE
            fullVideo.setVideoURI(uri)
            
            fullVideo.setOnPreparedListener { mp ->
                videoSeekBar.max = fullVideo.duration
                tvTotalTime.text = formatTime(fullVideo.duration)
                fullVideo.start()
                handler.post(updateSeekBar)
            }

            fullVideo.setOnClickListener {
                if (fullVideo.isPlaying) {
                    fullVideo.pause()
                } else {
                    fullVideo.start()
                }
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
                videoSeekBar.progress = 0
                tvCurrentTime.text = formatTime(0)
                handler.removeCallbacks(updateSeekBar)
            }
        } else {
            finish()
        }
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateSeekBar)
    }
}