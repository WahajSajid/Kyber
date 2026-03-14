package app.secure.kyber.Other

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView
import kotlin.random.Random

/**
 * Drop-in replacement for the received-message TextView.
 *
 * CHANGES IN THIS VERSION:
 *
 * 1. Cream background width now matches the ENCRYPTED text width per line,
 *    not the decrypted text width. This means the cream overlay snugly fits
 *    the encrypted placeholder that was visible before decryption, so the
 *    bubble looks natural in the encrypted state. A separate array
 *    [lineEncryptedWidthsPx] stores the measured pixel width of each encrypted
 *    line string. Both OVERLAY_IN and DECRYPT_REVEAL use this width for the
 *    cream rect bounds.
 *
 * 2. Animation is slower and smoother:
 *    - overlayInSpeedDpPerSec reduced from 190 → 90
 *    - revealSpeedDpPerSec reduced from 140 → 70
 *    - minLineDurationMs raised from 200 → 400
 *    - maxLineDurationMs raised from 650 → 1400
 *    - interLinePauseMs raised from 80 → 120
 *
 * ANIMATION PHASES (unchanged):
 *   SIZING         → draw nothing (prevents flash)
 *   OVERLAY_IN     → cream rows build in L→R, line by line
 *   HOLD           → all cream rows fully visible, static
 *   DECRYPT_REVEAL → real text reveals L→R, cream rows retreat L→R, line by line
 *   IDLE           → normal AppCompatTextView
 */
class DecryptRevealTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatTextView(context, attrs, defStyle) {

    private enum class Phase { IDLE, SIZING, OVERLAY_IN, HOLD, DECRYPT_REVEAL }
    private var phase = Phase.IDLE

    private var encryptedSingleLine: String = ""
    private var decryptedText: String = ""
    private var realLayout: StaticLayout? = null
    private var lineEncryptedTexts: List<String> = emptyList()

    // Width of each encrypted text line in pixels — used for cream rect bounds
    private var lineEncryptedWidthsPx: FloatArray = FloatArray(0)

    private var lineCount: Int = 1

    // Global progress: 0 → lineCount
    private var animProgress: Float = 0f
        set(v) { field = v.coerceAtLeast(0f); invalidate() }

    // ── timing constants ──────────────────────────────────────────────────────
    // Lower dp/s values = slower, more readable animation
    private val overlayInSpeedDpPerSec = 90f    // phase 1 speed (dp per second per line)
    private val revealSpeedDpPerSec    = 70f    // phase 2 speed (dp per second per line)
    private val minLineDurationMs      = 400L   // minimum time per line
    private val maxLineDurationMs      = 1400L  // maximum time per line
    private val interLinePauseMs       = 120L   // pause between lines
    // Fraction of each line's slot that is active wipe; rest is inter-line pause gap
    private val wipeFraction           = 0.82f

    private val density get() = resources.displayMetrics.density

    // Horizontal padding added to each cream rect beyond the encrypted text width
    private val linePaddingPx get() = dpToPx(6f)

    // ── paints ────────────────────────────────────────────────────────────────
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EDE8DC")
    }
    private val encryptPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1C1C")
    }
    private val realPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    private val rowRect = RectF()
    private val cornerRadius get() = dpToPx(4f)

    private val easeOut = DecelerateInterpolator(1.5f)

    private var phase1Animator: ValueAnimator? = null
    private var phase2Animator: ValueAnimator? = null

    private val scramble = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#\$%^&*"

    // ── public API ────────────────────────────────────────────────────────────

    fun prepareForAnimation(encrypted: String) {
        phase1Animator?.cancel(); phase1Animator = null
        phase2Animator?.cancel(); phase2Animator = null
        encryptedSingleLine = encrypted
        realLayout = null
        lineEncryptedTexts = emptyList()
        lineEncryptedWidthsPx = FloatArray(0)
        lineCount = 1
        phase = Phase.SIZING
        animProgress = 0f
    }

    fun startPhase1(
        decrypted: String,
        onPhase1Done: () -> Unit = {}
    ) {
        phase1Animator?.cancel(); phase1Animator = null
        phase2Animator?.cancel(); phase2Animator = null

        decryptedText = decrypted
        syncPaints()
        buildRealLayout()

        phase = Phase.OVERLAY_IN
        animProgress = 0f

        val total = lineCount.toFloat()
        val dur   = computeTotalDuration(overlayInSpeedDpPerSec)

        phase1Animator = ValueAnimator.ofFloat(0f, total).apply {
            duration = dur
            interpolator = LinearInterpolator()
            addUpdateListener { animProgress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    animProgress = total
                    phase = Phase.HOLD
                    invalidate()
                    onPhase1Done()
                }
            })
            start()
        }
    }

    fun beginPhase2(onDone: () -> Unit = {}) {
        phase2Animator?.cancel(); phase2Animator = null
        phase = Phase.DECRYPT_REVEAL
        animProgress = 0f

        val total = lineCount.toFloat()
        val dur   = computeTotalDuration(revealSpeedDpPerSec)

        phase2Animator = ValueAnimator.ofFloat(0f, total).apply {
            duration = dur
            interpolator = LinearInterpolator()
            addUpdateListener { animProgress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    finishDecrypt()
                    onDone()
                }
            })
            start()
        }
    }

    fun cancelAndShowFinal() {
        phase1Animator?.cancel(); phase1Animator = null
        phase2Animator?.cancel(); phase2Animator = null
        finishDecrypt()
    }

    // ── internal ──────────────────────────────────────────────────────────────

    private fun buildRealLayout() {
        val availWidth = width - compoundPaddingLeft - compoundPaddingRight
        if (availWidth <= 0 || decryptedText.isEmpty()) {
            lineCount = 1
            // Fallback: single encrypted line, measure its width
            syncEncryptPaint()
            lineEncryptedTexts = listOf(encryptedSingleLine)
            lineEncryptedWidthsPx = floatArrayOf(encryptPaint.measureText(encryptedSingleLine))
            return
        }

        val tp = paint as? TextPaint ?: TextPaint(paint)

        @Suppress("DEPRECATION")
        val sl: StaticLayout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder
                .obtain(decryptedText, 0, decryptedText.length, tp, availWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
                .setIncludePad(includeFontPadding)
                .build()
        } else {
            StaticLayout(
                decryptedText, tp, availWidth,
                Layout.Alignment.ALIGN_NORMAL,
                lineSpacingMultiplier, lineSpacingExtra,
                includeFontPadding
            )
        }

        realLayout = sl
        lineCount  = sl.lineCount.coerceAtLeast(1)

        syncEncryptPaint()

        val encryptedList    = ArrayList<String>(lineCount)
        val encryptedWidths  = FloatArray(lineCount)

        for (i in 0 until lineCount) {
            // Generate encrypted chars sized to the real line's pixel width
            val realLineWidth = sl.getLineWidth(i)
            val charW = encryptPaint.measureText("m").coerceAtLeast(1f)
            val count = ((realLineWidth / charW).toInt()).coerceAtLeast(3)
            val sb = StringBuilder(count)
            repeat(count) { sb.append(scramble[Random.nextInt(scramble.length)]) }
            val encStr = sb.toString()

            // Measure the actual encrypted string we just built
            val encW = encryptPaint.measureText(encStr)

            encryptedList.add(encStr)
            encryptedWidths[i] = encW   // ← this is the cream rect width for this line
        }

        lineEncryptedTexts    = encryptedList
        lineEncryptedWidthsPx = encryptedWidths
    }

    private fun syncEncryptPaint() {
        val tp = paint as? TextPaint ?: TextPaint(paint)
        encryptPaint.typeface = tp.typeface ?: Typeface.DEFAULT
        encryptPaint.textSize = tp.textSize
    }

    private fun finishDecrypt() {
        val finalText = decryptedText
        phase = Phase.IDLE
        encryptedSingleLine = ""
        decryptedText = ""
        realLayout = null
        lineEncryptedTexts = emptyList()
        lineEncryptedWidthsPx = FloatArray(0)
        lineCount = 1
        animProgress = 0f
        if (finalText.isNotEmpty()) text = finalText else invalidate()
    }

    private fun syncPaints() {
        val tp = paint as? TextPaint ?: TextPaint(paint)
        val tf = tp.typeface ?: Typeface.DEFAULT
        encryptPaint.typeface = tf; encryptPaint.textSize = tp.textSize
        realPaint.typeface    = tf; realPaint.textSize    = tp.textSize
    }

    /**
     * Total duration = sum over lines of:
     *   slotMs = (wipeMs / wipeFraction) + pauseMs
     * where wipeMs is derived from the ENCRYPTED line width / speed.
     */
    private fun computeTotalDuration(speedDpPerSec: Float): Long {
        val speedPxPerSec = speedDpPerSec * density
        var total = 0L
        for (i in 0 until lineCount) {
            // Use encrypted line width for timing — matches the cream rect width
            val lw = encryptedLineWidthFor(i).coerceAtLeast(dpToPx(40f))
            val wipeMs = ((lw / speedPxPerSec) * 1000f)
                .toLong().coerceIn(minLineDurationMs, maxLineDurationMs)
            val pauseMs = if (i < lineCount - 1) interLinePauseMs else 0L
            total += (wipeMs / wipeFraction).toLong() + pauseMs
        }
        return total.coerceAtLeast(300L)
    }

    /** Apply ease-out within the active wipe fraction of a line's slot. */
    private fun toWipeFrac(rawFrac: Float): Float {
        if (rawFrac >= wipeFraction) return 1f
        return easeOut.getInterpolation(rawFrac / wipeFraction)
    }

    /** Width of the cream rect for line [i] = encrypted text width + padding. */
    private fun encryptedLineWidthFor(i: Int): Float =
        (if (i < lineEncryptedWidthsPx.size) lineEncryptedWidthsPx[i] else dpToPx(80f)) + linePaddingPx

    // ── onDraw ────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        when (phase) {
            Phase.IDLE   -> super.onDraw(canvas)
            Phase.SIZING -> { /* empty — prevents flash during setText */ }
            Phase.OVERLAY_IN     -> drawOverlayIn(canvas)
            Phase.HOLD           -> drawOverlayFull(canvas)
            Phase.DECRYPT_REVEAL -> drawDecryptReveal(canvas)
        }
    }

    // ── phase draw ────────────────────────────────────────────────────────────

    private fun drawOverlayIn(canvas: Canvas) {
        val sl = realLayout ?: return fallbackSingleLine(canvas, toWipeFrac(animProgress))
        val ox = compoundPaddingLeft.toFloat()
        val oy = compoundPaddingTop.toFloat()

        val done    = animProgress.toInt().coerceIn(0, lineCount)
        val rawFrac = animProgress - done.toFloat()

        for (i in 0 until done) drawCreamLine(canvas, sl, i, ox, oy, 1f)

        if (done < lineCount) {
            val wf = toWipeFrac(rawFrac)
            if (wf > 0f) drawCreamLine(canvas, sl, done, ox, oy, wf)
        }
    }

    private fun drawOverlayFull(canvas: Canvas) {
        val sl = realLayout ?: return fallbackSingleLine(canvas, 1f)
        val ox = compoundPaddingLeft.toFloat()
        val oy = compoundPaddingTop.toFloat()
        for (i in 0 until lineCount) drawCreamLine(canvas, sl, i, ox, oy, 1f)
    }

    private fun drawDecryptReveal(canvas: Canvas) {
        val sl = realLayout ?: return fallbackReveal(canvas, toWipeFrac(animProgress))
        val ox = compoundPaddingLeft.toFloat()
        val oy = compoundPaddingTop.toFloat()

        val done    = animProgress.toInt().coerceIn(0, lineCount)
        val rawFrac = animProgress - done.toFloat()
        val wf      = toWipeFrac(rawFrac)

        for (i in 0 until lineCount) {
            val lineTop    = sl.getLineTop(i).toFloat() + oy
            val lineBottom = sl.getLineBottom(i).toFloat() + oy
            val lineH      = lineBottom - lineTop
            // Cream rect width = encrypted line width (not decrypted line width)
            val lineW      = encryptedLineWidthFor(i)

            val lineStart = sl.getLineStart(i)
            val lineEnd   = sl.getLineVisibleEnd(i)
            val lineText  = decryptedText.substring(
                lineStart.coerceIn(0, decryptedText.length),
                lineEnd.coerceIn(0, decryptedText.length)
            )

            when {
                i < done -> {
                    // Fully revealed — real text only, no cream
                    val y = lineTop + lineH / 2f - (realPaint.descent() + realPaint.ascent()) / 2f
                    canvas.drawText(lineText, ox, y, realPaint)
                }

                i == done && wf > 0f -> {
                    // Currently animating — split at encrypted width * wf
                    val splitX = lineW * wf

                    // Real text revealed left of split
                    canvas.save()
                    canvas.clipRect(ox, lineTop, ox + splitX, lineBottom)
                    val y = lineTop + lineH / 2f - (realPaint.descent() + realPaint.ascent()) / 2f
                    canvas.drawText(lineText, ox, y, realPaint)
                    canvas.restore()

                    // Cream retreating right of split
                    val creamLeft = ox + splitX
                    if (creamLeft < ox + lineW) {
                        rowRect.set(creamLeft, lineTop, ox + lineW, lineBottom)
                        canvas.drawRoundRect(rowRect, cornerRadius, cornerRadius, overlayPaint)
                        canvas.save()
                        canvas.clipRect(creamLeft, lineTop, ox + lineW, lineBottom)
                        val ey = lineTop + lineH / 2f - (encryptPaint.descent() + encryptPaint.ascent()) / 2f
                        canvas.drawText(lineEncryptedTexts.getOrElse(i) { "" }, ox, ey, encryptPaint)
                        canvas.restore()
                    }
                }

                else -> {
                    // Not yet started — full cream row at encrypted width
                    drawCreamLine(canvas, sl, i, ox, oy, 1f)
                }
            }
        }
    }

    // ── per-line draw helper ──────────────────────────────────────────────────

    /**
     * Draw a cream rect for line [lineIndex].
     * Width = encryptedLineWidthFor(lineIndex) * wipeFrac.
     * The encrypted text is drawn and clipped to the same rect.
     */
    private fun drawCreamLine(
        canvas: Canvas, sl: StaticLayout,
        lineIndex: Int, ox: Float, oy: Float, wipeFrac: Float
    ) {
        val lineTop    = sl.getLineTop(lineIndex).toFloat() + oy
        val lineBottom = sl.getLineBottom(lineIndex).toFloat() + oy
        val lineH      = lineBottom - lineTop
        // Use encrypted width — the cream rect width matches the encrypted text
        val lineW      = encryptedLineWidthFor(lineIndex)
        val right      = ox + lineW * wipeFrac

        rowRect.set(ox, lineTop, right, lineBottom)
        canvas.drawRoundRect(rowRect, cornerRadius, cornerRadius, overlayPaint)

        canvas.save()
        canvas.clipRect(ox, lineTop, right, lineBottom)
        val ey = lineTop + lineH / 2f - (encryptPaint.descent() + encryptPaint.ascent()) / 2f
        canvas.drawText(lineEncryptedTexts.getOrElse(lineIndex) { "" }, ox, ey, encryptPaint)
        canvas.restore()
    }

    // ── single-line fallbacks (before realLayout is built) ────────────────────

    private fun fallbackSingleLine(canvas: Canvas, wf: Float) {
        val lw = encryptPaint.measureText(encryptedSingleLine) + linePaddingPx
        val h  = height.toFloat()
        val ox = compoundPaddingLeft.toFloat()
        rowRect.set(ox, 0f, ox + lw * wf, h)
        canvas.drawRoundRect(rowRect, cornerRadius, cornerRadius, overlayPaint)
        canvas.save()
        canvas.clipRect(ox, 0f, ox + lw * wf, h)
        val y = h / 2f - (encryptPaint.descent() + encryptPaint.ascent()) / 2f
        canvas.drawText(encryptedSingleLine, ox, y, encryptPaint)
        canvas.restore()
    }

    private fun fallbackReveal(canvas: Canvas, wf: Float) {
        val lw     = encryptPaint.measureText(encryptedSingleLine) + linePaddingPx
        val h      = height.toFloat()
        val ox     = compoundPaddingLeft.toFloat()
        val splitX = lw * wf

        canvas.save()
        canvas.clipRect(ox, 0f, ox + splitX, h)
        val y = h / 2f - (realPaint.descent() + realPaint.ascent()) / 2f
        canvas.drawText(decryptedText, ox, y, realPaint)
        canvas.restore()

        if (ox + splitX < ox + lw) {
            rowRect.set(ox + splitX, 0f, ox + lw, h)
            canvas.drawRoundRect(rowRect, cornerRadius, cornerRadius, overlayPaint)
            canvas.save()
            canvas.clipRect(ox + splitX, 0f, ox + lw, h)
            val ey = h / 2f - (encryptPaint.descent() + encryptPaint.ascent()) / 2f
            canvas.drawText(encryptedSingleLine, ox, ey, encryptPaint)
            canvas.restore()
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
}