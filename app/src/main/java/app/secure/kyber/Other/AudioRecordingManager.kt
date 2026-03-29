package app.secure.kyber.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException

/**
 * Manages audio recording lifecycle.
 *
 * Recordings are saved to the app's internal 'files/recordings' directory
 * to ensure they persist across fragment transitions and app restarts.
 */
class AudioRecordingManager(private val context: Context) {

    companion object {
        private const val SAMPLE_INTERVAL_MS = 100L   // 10 samples per second
        private const val MAX_AMPLITUDE      = 32767   // MediaRecorder max
    }

    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var isRecording = false

    // Amplitude samples captured during recording
    private val _amplitudeSamples = mutableListOf<Float>()
    val amplitudeSamples: List<Float> get() = _amplitudeSamples.toList()

    private val samplingHandler = Handler(Looper.getMainLooper())
    private val samplingRunnable = object : Runnable {
        override fun run() {
            val recorder = mediaRecorder ?: return
            if (!isRecording) return
            try {
                val raw = recorder.maxAmplitude
                _amplitudeSamples.add((raw.toFloat() / MAX_AMPLITUDE).coerceIn(0f, 1f))
            } catch (e: Exception) { /* ignore */ }
            samplingHandler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts audio recording.
     * @return true if recording started successfully, false otherwise.
     */
    fun startRecording(): Boolean {
        _amplitudeSamples.clear()
        return try {
            val recordingsDir = File(context.filesDir, "recordings")
            if (!recordingsDir.exists()) recordingsDir.mkdirs()
            
            // Use a unique filename for each recording
            val outputFile = File(recordingsDir, "audio_msg_${System.currentTimeMillis()}.m4a")
            currentOutputFile = outputFile

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mediaRecorder!!.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(32000)   // 32 kbps (was 128000) — clear voice, 4× smaller
                setAudioSamplingRate(16000)      // 16 kHz (was 44100) — sufficient for voice
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            // start sampling amplitude
            samplingHandler.postDelayed(samplingRunnable, SAMPLE_INTERVAL_MS)
            true
        } catch (e: IOException) {
            e.printStackTrace()
            releaseRecorder()
            false
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            releaseRecorder()
            false
        }
    }

    /**
     * Stops recording.
     * @return The absolute path of the recorded file, or null on error.
     */
    fun stopRecording(): String? {
        if (!isRecording) return null
        samplingHandler.removeCallbacks(samplingRunnable)
        return try {
            mediaRecorder?.apply { 
                stop()
                release() 
            }
            mediaRecorder = null
            isRecording = false
            
            val path = currentOutputFile?.absolutePath
            // CRITICAL FIX: Set currentOutputFile to null so cancelRecording() 
            // (called in onDestroy) doesn't delete this successful recording.
            currentOutputFile = null 
            path
        } catch (e: Exception) {
            e.printStackTrace()
            releaseRecorder()
            null
        }
    }

    /**
     * Cancels the current recording and deletes the temp file.
     */
    fun cancelRecording() {
        samplingHandler.removeCallbacks(samplingRunnable)
        releaseRecorder()
        // Only deletes the file if it hasn't been "claimed" by stopRecording()
        currentOutputFile?.delete()
        currentOutputFile = null
        _amplitudeSamples.clear()
    }

    fun isCurrentlyRecording() = isRecording

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun releaseRecorder() {
        try { mediaRecorder?.release() } catch (e: Exception) { /* ignore */ }
        mediaRecorder = null
        isRecording = false
    }
}
