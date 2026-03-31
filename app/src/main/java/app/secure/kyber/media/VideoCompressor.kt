package app.secure.kyber.media

import android.content.Context
import android.media.*
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object VideoCompressor {

    private const val TAG = "VideoCompressor"
    private const val MAX_DIMENSION         = 1280
    private const val TARGET_VIDEO_BPS      = 1_200_000
    private const val SKIP_THRESHOLD_BYTES  = 5 * 1024 * 1024L
    private const val TIMEOUT_US            = 10_000L

    data class CompressionResult(
        val outputPath: String,
        val wasCompressed: Boolean,
        val thumbnailPath: String?
    )

    fun compress(
        context: Context,
        inputPath: String,
        onProgress: ((Int) -> Unit)? = null
    ): CompressionResult? {
        val inputFile = File(inputPath.removePrefix("file://"))
        if (!inputFile.exists()) {
            Log.e(TAG, "Input file missing: $inputPath")
            return null
        }

        // ── Read metadata ─────────────────────────────────────────────────────
        val retriever = MediaMetadataRetriever()
        val origWidth: Int
        val origHeight: Int
        val durationMs: Long
        val rotation: Int
        val thumbnailPath: String?

        try {
            retriever.setDataSource(inputFile.absolutePath)
            origWidth  = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            origHeight = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            rotation   = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            thumbnailPath = generateThumbnail(context, retriever, inputFile.nameWithoutExtension)
        } finally {
            retriever.release()
        }

        // ── Skip if already small ─────────────────────────────────────────────
        val longerSide = maxOf(origWidth, origHeight)
        if (inputFile.length() < SKIP_THRESHOLD_BYTES && longerSide <= MAX_DIMENSION) {
            onProgress?.invoke(100)
            return CompressionResult(
                outputPath    = "file://${inputFile.absolutePath}",
                wasCompressed = false,
                thumbnailPath = thumbnailPath
            )
        }

        val (outWidth, outHeight) = scaleToMax(origWidth, origHeight, MAX_DIMENSION, rotation)
        Log.d(TAG, "Compressing ${origWidth}x${origHeight} → ${outWidth}x${outHeight}")

        val outputFile = File(
            context.filesDir,
            "sent_media/video_comp_${System.currentTimeMillis()}.mp4"
        ).also { it.parentFile?.mkdirs() }

        return try {
            transcodeOnEglThread(
                inputFile  = inputFile,
                outputFile = outputFile,
                outWidth   = outWidth,
                outHeight  = outHeight,
                durationMs = durationMs,
                onProgress = onProgress
            )
            if (outputFile.exists() && outputFile.length() > 0) {
                Log.d(TAG, "Done: ${outputFile.length() / 1024}KB")
                CompressionResult(
                    outputPath    = "file://${outputFile.absolutePath}",
                    wasCompressed = true,
                    thumbnailPath = thumbnailPath
                )
            } else {
                Log.e(TAG, "Output file empty after transcode")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Compression failed", e)
            outputFile.delete()
            null
        }
    }

    // ── Run transcoding on a HandlerThread with EGL context ───────────────────

    private fun transcodeOnEglThread(
        inputFile: File,
        outputFile: File,
        outWidth: Int,
        outHeight: Int,
        durationMs: Long,
        onProgress: ((Int) -> Unit)?
    ) {
        val thread = HandlerThread("VideoCompressor-EGL").apply { start() }
        val handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        var transcodeError: Throwable? = null

        handler.post {
            try {
                doTranscode(inputFile, outputFile, outWidth, outHeight, durationMs, onProgress)
            } catch (e: Throwable) {
                transcodeError = e
            } finally {
                latch.countDown()
            }
        }

        val completed = latch.await(10, TimeUnit.MINUTES)
        thread.quit()

        if (!completed) throw RuntimeException("Transcode timed out")
        transcodeError?.let { throw it }
    }

    private fun doTranscode(
        inputFile: File,
        outputFile: File,
        outWidth: Int,
        outHeight: Int,
        durationMs: Long,
        onProgress: ((Int) -> Unit)?
    ) {
        // ── EGL setup so Surface pipeline works on this thread ────────────────
        val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1, // EGL_RECORDABLE_ANDROID
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
        val eglConfig = configs[0]!!

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0
        )

        // ── Encoder ───────────────────────────────────────────────────────────
        val encoder = MediaCodec.createEncoderByType("video/avc")
        val encFmt = MediaFormat.createVideoFormat("video/avc", outWidth, outHeight).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, TARGET_VIDEO_BPS)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
        }
        encoder.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderSurface = encoder.createInputSurface()
        encoder.start()

        // ── EGL pbuffer surface (needed to make context current) ──────────────
        val pbAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        val eglSurface: EGLSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig, pbAttribs, 0
        )
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        // ── Decoder ───────────────────────────────────────────────────────────
        val videoExtractor = MediaExtractor().apply { setDataSource(inputFile.absolutePath) }
        val audioExtractor = MediaExtractor().apply { setDataSource(inputFile.absolutePath) }
        val videoTrackIdx = findTrack(videoExtractor, "video/")
        val audioTrackIdx = findTrack(audioExtractor, "audio/")

        videoExtractor.selectTrack(videoTrackIdx)
        val videoFormat = videoExtractor.getTrackFormat(videoTrackIdx)
        val inputMime = videoFormat.getString(MediaFormat.KEY_MIME) ?: "video/avc"

        val decoder = MediaCodec.createDecoderByType(inputMime)
        // Decoder renders to encoder's input surface — valid now because EGL context is current
        decoder.configure(videoFormat, encoderSurface, null, 0)
        decoder.start()

        // ── Muxer (lazy — created when encoder output format is known) ─────────
        var muxer: MediaMuxer? = null
        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false

        data class Pending(val data: ByteArray, val pts: Long, val flags: Int)
        val pendingSamples = mutableListOf<Pending>()

        val bufInfo = MediaCodec.BufferInfo()
        var decDone = false
        var encDone = false

        try {
            while (!encDone) {
                // Feed decoder
                if (!decDone) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val sz = videoExtractor.readSampleData(buf, 0)
                        if (sz < 0) {
                            decoder.queueInputBuffer(
                                inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            decDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inIdx, 0, sz, videoExtractor.sampleTime, 0
                            )
                            videoExtractor.advance()
                        }
                    }
                }

                // Drain decoder → encoder surface
                val decIdx = decoder.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
                if (decIdx >= 0) {
                    decoder.releaseOutputBuffer(decIdx, bufInfo.size > 0)
                    if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encoder.signalEndOfInputStream()
                    }
                }

                // Drain encoder
                val encIdx = encoder.dequeueOutputBuffer(bufInfo, TIMEOUT_US)
                when {
                    encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Create muxer now — all tracks must be added before start()
                        muxer = MediaMuxer(
                            outputFile.absolutePath,
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                        )
                        muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                        if (audioTrackIdx >= 0) {
                            audioExtractor.selectTrack(audioTrackIdx)
                            muxerAudioTrack = muxer.addTrack(
                                audioExtractor.getTrackFormat(audioTrackIdx)
                            )
                        }
                        muxer.start()
                        muxerStarted = true

                        // Flush buffered samples
                        for (p in pendingSamples) {
                            val b = MediaCodec.BufferInfo().also {
                                it.set(0, p.data.size, p.pts, p.flags)
                            }
                            muxer.writeSampleData(
                                muxerVideoTrack, ByteBuffer.wrap(p.data), b
                            )
                        }
                        pendingSamples.clear()
                    }

                    encIdx >= 0 -> {
                        val isConfig = bufInfo.flags and
                                MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!isConfig && bufInfo.size > 0) {
                            val outBuf = encoder.getOutputBuffer(encIdx)!!
                            if (muxerStarted) {
                                muxer!!.writeSampleData(muxerVideoTrack, outBuf, bufInfo)
                            } else {
                                val copy = ByteArray(bufInfo.size)
                                outBuf.get(copy)
                                pendingSamples.add(Pending(copy, bufInfo.presentationTimeUs, bufInfo.flags))
                            }
                        }
                        encoder.releaseOutputBuffer(encIdx, false)
                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encDone = true
                        }
                        if (durationMs > 0 && bufInfo.presentationTimeUs > 0) {
                            val pct = ((bufInfo.presentationTimeUs / 1000f) / durationMs * 100f)
                                .toInt().coerceIn(1, 99)
                            onProgress?.invoke(pct)
                        }
                    }
                }
            }

            // ── Audio remux ───────────────────────────────────────────────────
            if (muxerStarted && muxerAudioTrack >= 0) {
                val aBuf = ByteBuffer.allocate(256 * 1024)
                val aInfo = MediaCodec.BufferInfo()
                while (true) {
                    val sz = audioExtractor.readSampleData(aBuf, 0)
                    if (sz < 0) break
                    aInfo.set(0, sz, audioExtractor.sampleTime, 0)
                    muxer!!.writeSampleData(muxerAudioTrack, aBuf, aInfo)
                    audioExtractor.advance()
                }
            }

            onProgress?.invoke(100)

        } finally {
            try { decoder.stop(); decoder.release() } catch (e: Exception) { }
            try { encoder.stop(); encoder.release() } catch (e: Exception) { }
            try { encoderSurface.release() } catch (e: Exception) { }
            if (muxerStarted) try { muxer?.stop() } catch (e: Exception) { }
            try { muxer?.release() } catch (e: Exception) { }
            videoExtractor.release()
            audioExtractor.release()
            // ── EGL teardown ──────────────────────────────────────────────────
            try {
                EGL14.eglMakeCurrent(
                    eglDisplay,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            } catch (e: Exception) { }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i)
                .getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    private fun scaleToMax(w: Int, h: Int, max: Int, rotation: Int): Pair<Int, Int> {
        val (ew, eh) = if (rotation == 90 || rotation == 270) h to w else w to h
        if (ew <= max && eh <= max) return makeEven(ew) to makeEven(eh)
        val scale = max.toFloat() / maxOf(ew, eh)
        return makeEven((ew * scale).toInt()) to makeEven((eh * scale).toInt())
    }

    private fun makeEven(v: Int) = if (v % 2 != 0) v - 1 else v

    private fun generateThumbnail(
        context: Context,
        retriever: MediaMetadataRetriever,
        baseName: String
    ): String? {
        return try {
            val bmp = retriever.getFrameAtTime(
                1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(0) ?: return null

            val maxDim = 320
            val scale  = minOf(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height, 1f)
            val thumb  = if (scale < 1f)
                android.graphics.Bitmap.createScaledBitmap(
                    bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true
                ) else bmp

            val dir  = File(context.filesDir, "thumbnails").apply { mkdirs() }
            val file = File(dir, "thumb_${baseName}_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { thumb.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
            if (thumb != bmp) thumb.recycle()
            bmp.recycle()
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Thumbnail failed", e)
            null
        }
    }
}