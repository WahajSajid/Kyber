package app.secure.kyber.Other

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Custom view that renders the waveform bars exactly like the screenshot:
 * - Played portion: bright teal/white bars
 * - Unplayed portion: muted dark bars
 * - Progress is set via [setProgress] (0f..1f)
 * - Amplitudes come from recording samples via [setAmplitudes]
 *   If no amplitudes supplied a default pattern is used.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ── Paints ────────────────────────────────────────────────────────────────
    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")   // bright white for played portion
    }
    private val unplayedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4DFFFFFF") // 30% white for unplayed portion
    }

    // ── Layout constants ──────────────────────────────────────────────────────
    private val barWidthDp  = 3f
    private val barGapDp    = 2f
    private val minHeightFraction = 0.15f   // bars are at least 15% of view height
    private val cornerRadiusDp = 1.5f

    // ── State ─────────────────────────────────────────────────────────────────
    private var amplitudes: List<Float> = emptyList()   // normalised 0f..1f per bar
    private var progress: Float = 0f                    // 0f..1f playback position

    // pre-computed bar rects updated in onSizeChanged
    private var barRects: List<RectF> = emptyList()
    private var barCount: Int = 0
    private var barWidthPx  = 0f
    private var barGapPx    = 0f
    private var cornerPx    = 0f

    private var onSeekListener: ((Float) -> Unit)? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /** Supply normalised amplitudes (0f‥1f) captured during recording. */
    fun setAmplitudes(amps: List<Float>) {
        amplitudes = amps.map { it.coerceIn(0f, 1f) }
        recalcBars()
        invalidate()
    }

    /** 0f = not started, 1f = finished */
    fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        invalidate()
    }

    fun setOnSeekListener(listener: (Float) -> Unit) {
        this.onSeekListener = listener
    }

    // ── View lifecycle ────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val seekProgress = (event.x / width).coerceIn(0f, 1f)
            onSeekListener?.invoke(seekProgress)
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        barWidthPx = barWidthDp * density
        barGapPx   = barGapDp  * density
        cornerPx   = cornerRadiusDp * density
        recalcBars()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (barRects.isEmpty()) return

        val playedCount = (progress * barRects.size).roundToInt().coerceIn(0, barRects.size)

        barRects.forEachIndexed { i, rect ->
            val paint = if (i < playedCount) playedPaint else unplayedPaint
            canvas.drawRoundRect(rect, cornerPx, cornerPx, paint)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun recalcBars() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val step = barWidthPx + barGapPx
        barCount = max(1, ((w + barGapPx) / step).toInt())

        // Build or resample amplitude list to match barCount
        val amps = resampleAmplitudes(barCount)

        val rects = ArrayList<RectF>(barCount)
        var x = 0f
        for (i in 0 until barCount) {
            val frac = minHeightFraction + amps[i] * (1f - minHeightFraction)
            val barH  = frac * h
            val top   = (h - barH) / 2f
            rects.add(RectF(x, top, x + barWidthPx, top + barH))
            x += step
        }
        barRects = rects
    }

    /**
     * Re-samples the amplitude list (which may have any size) to exactly
     * [targetCount] entries using linear interpolation.
     * Falls back to a visually-pleasing pseudo-random pattern if no data yet.
     */
    private fun resampleAmplitudes(targetCount: Int): List<Float> {
        val src = amplitudes.takeIf { it.isNotEmpty() } ?: generateDefaultPattern(targetCount)
        if (src.size == targetCount) return src

        return List(targetCount) { i ->
            val pos = i.toFloat() * (src.size - 1) / (targetCount - 1).coerceAtLeast(1)
            val lo  = pos.toInt().coerceIn(0, src.size - 1)
            val hi  = (lo + 1).coerceIn(0, src.size - 1)
            val t   = pos - lo
            src[lo] * (1f - t) + src[hi] * t
        }
    }

    /** Generates a deterministic waveform that looks like the screenshot. */
    private fun generateDefaultPattern(count: Int): List<Float> {
        val pattern = floatArrayOf(
            0.3f, 0.5f, 0.7f, 0.9f, 0.6f, 0.4f, 0.8f, 1.0f, 0.7f, 0.5f,
            0.3f, 0.6f, 0.9f, 0.7f, 0.4f, 0.8f, 0.6f, 0.3f, 0.7f, 0.5f,
            0.9f, 0.6f, 0.4f, 0.8f, 0.5f, 0.3f, 0.7f, 1.0f, 0.6f, 0.4f
        )
        return List(count) { i -> pattern[i % pattern.size] }
    }
}
