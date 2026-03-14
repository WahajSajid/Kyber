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
import android.util.AttributeSet
import android.util.TypedValue
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView

/**
 * Drop-in replacement for the received-message TextView.
 *
 * THREE FIXES applied in this version:
 *
 * FIX 1 — No flash of encrypted text without background:
 *   SIZING phase suppresses all drawing during setText() layout passes.
 *   The adapter must call prepareForAnimation() BEFORE setText(), which sets
 *   phase = SIZING immediately. Any onDraw() triggered by the setText() layout
 *   pass draws nothing (empty bubble). startPhase1() then transitions to
 *   OVERLAY_IN and begins the animation cleanly.
 *   Also: cancelAndShowFinal() no longer needs to be called before
 *   prepareForAnimation() — the adapter flow is: prepareForAnimation → setText
 *   → startPhase1 (no cancelAndShowFinal in between).
 *
 * FIX 2 — Encrypted text vertically centered in cream background:
 *   Text Y is computed as h/2 - (descent + ascent)/2, which is the standard
 *   formula for vertically centering text within a rect of height h.
 *   Previously it used compoundPaddingTop + textSize - descent, which placed
 *   text at the top baseline and looked slightly high.
 *
 * FIX 3 — Consistent animation speed regardless of message length:
 *   Duration is calculated from a fixed pixels-per-second rate rather than a
 *   fixed time. Previously, the wipe covered width pixels in a fixed time, so
 *   longer (wider) bubbles animated faster. Now:
 *     phase1Duration = width / OVERLAY_IN_SPEED_PX_PER_SEC  (in ms)
 *     phase2Duration = width / DECRYPT_REVEAL_SPEED_PX_PER_SEC  (in ms)
 *   Durations are clamped to [MIN_DURATION .. MAX_DURATION] to avoid extremes.
 *   Duration is computed in onSizeChanged() once the view has a real width.
 *
 * ANIMATION PHASES (unchanged):
 *   SIZING         → draw nothing (empty bubble, setText in progress)
 *   OVERLAY_IN     → cream + encrypted text build in L→R
 *   HOLD           → overlay fully visible, static
 *   DECRYPT_REVEAL → real text reveals L→R, overlay retreats L→R
 *   IDLE           → normal AppCompatTextView
 */
class DecryptRevealTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatTextView(context, attrs, defStyle) {

    private enum class Phase { IDLE, SIZING, OVERLAY_IN, HOLD, DECRYPT_REVEAL }
    private var phase = Phase.IDLE

    private var encryptedText: String = ""
    private var decryptedText: String = ""

    private var overlayInProgress: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    private var revealProgress: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    // ── speed constants (dp/s) → converted to px/s in init ───────────────────
    // These control how many pixels per second the wipe travels.
    // Increasing these values makes the animation faster.
    // Tune these two numbers to adjust feel across all message lengths.
    private val overlayInSpeedDpPerSec  = 220f   // phase 1: cream builds in
    private val revealSpeedDpPerSec     = 160f   // phase 2: decrypt reveal

    // Duration bounds (ms) to prevent extremes on very short or very long messages
    private val minDurationMs = 400L
    private val maxDurationMs = 2000L

    private val density: Float by lazy {
        resources.displayMetrics.density
    }

    // Computed in onSizeChanged when we know the actual pixel width
    private var phase1DurationMs: Long = 900L
    private var phase2DurationMs: Long = 1400L

    // ── paints ────────────────────────────────────────────────────────────────

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EDE8DC")   // cream / off-white
    }

    private val encryptedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1C1C")   // dark text on cream
    }

    private val realTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }

    private val overlayRect = RectF()
    private val cornerRadius: Float get() = dpToPx(6f)

    private var phase1Animator: ValueAnimator? = null
    private var phase2Animator: ValueAnimator? = null

    // ── size tracking ─────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) {
            // Recalculate durations based on actual pixel width
            val overlayInSpeedPxPerSec = overlayInSpeedDpPerSec * density
            val revealSpeedPxPerSec    = revealSpeedDpPerSec    * density

            phase1DurationMs = ((w.toFloat() / overlayInSpeedPxPerSec) * 1000f)
                .toLong()
                .coerceIn(minDurationMs, maxDurationMs)

            phase2DurationMs = ((w.toFloat() / revealSpeedPxPerSec) * 1000f)
                .toLong()
                .coerceIn(minDurationMs, maxDurationMs)
        }
    }

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * STEP 1 — call this BEFORE setText().
     *
     * Sets phase = SIZING so any onDraw() call during the upcoming setText()
     * layout pass draws nothing — no encrypted text flash.
     */
    fun prepareForAnimation(encrypted: String) {
        phase1Animator?.cancel(); phase1Animator = null
        phase2Animator?.cancel(); phase2Animator = null
        encryptedText = encrypted
        phase = Phase.SIZING
        overlayInProgress = 0f
        revealProgress = 0f
    }

    /**
     * STEP 2 — call this after setText(encryptedPlaceholder).
     *
     * Starts Phase 1: cream + encrypted text build in LEFT → RIGHT.
     * Duration is derived from pixel width for consistent visual speed.
     *
     * @param decrypted     the real message — stored internally, revealed in phase 2
     * @param onPhase1Done  called on main thread when phase 1 fully ends
     */
    fun startPhase1(
        decrypted: String,
        onPhase1Done: () -> Unit = {}
    ) {
        phase1Animator?.cancel(); phase1Animator = null
        phase2Animator?.cancel(); phase2Animator = null

        decryptedText = decrypted
        syncPaints()

        phase = Phase.OVERLAY_IN
        overlayInProgress = 0f
        revealProgress = 0f

        phase1Animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = phase1DurationMs
            interpolator = LinearInterpolator()
            addUpdateListener { overlayInProgress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    overlayInProgress = 1f
                    phase = Phase.HOLD
                    invalidate()
                    onPhase1Done()
                }
            })
            start()
        }
    }

    /**
     * STEP 3 — call this from the onPhase1Done callback.
     *
     * Starts Phase 2: real white text reveals L→R, overlay retreats L→R.
     * Duration is derived from pixel width for consistent visual speed.
     * Do NOT call setText() before this — real text is already stored internally.
     *
     * @param onDone  called on main thread when phase 2 fully ends
     */
    fun beginPhase2(
        onDone: () -> Unit = {}
    ) {
        phase2Animator?.cancel(); phase2Animator = null
        phase = Phase.DECRYPT_REVEAL
        revealProgress = 0f

        phase2Animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = phase2DurationMs
            interpolator = LinearInterpolator()
            addUpdateListener { revealProgress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    finishDecrypt()
                    onDone()
                }
            })
            start()
        }
    }

    /** Immediately show final state — real text, no overlay, no animation. */
    fun cancelAndShowFinal() {
        phase1Animator?.cancel(); phase1Animator = null
        phase2Animator?.cancel(); phase2Animator = null
        finishDecrypt()
    }

    // ── internal ──────────────────────────────────────────────────────────────

    private fun finishDecrypt() {
        val finalText = decryptedText
        phase = Phase.IDLE
        encryptedText = ""
        decryptedText = ""
        overlayInProgress = 0f
        revealProgress = 0f
        if (finalText.isNotEmpty()) text = finalText else invalidate()
    }

    private fun syncPaints() {
        val tf = typeface ?: Typeface.DEFAULT
        encryptedTextPaint.typeface = tf
        encryptedTextPaint.textSize = textSize
        realTextPaint.typeface = tf
        realTextPaint.textSize = textSize
    }

    override fun onDraw(canvas: Canvas) {
        when (phase) {

            Phase.IDLE -> super.onDraw(canvas)

            // Draw nothing — setText() layout pass in progress, prevent flash
            Phase.SIZING -> { /* empty bubble */ }

            // Phase 1: cream + encrypted text build in LEFT → RIGHT
            Phase.OVERLAY_IN -> {
                val w = width.toFloat(); val h = height.toFloat()
                if (w == 0f || h == 0f) return
                val visibleRight = w * overlayInProgress
                if (visibleRight > 0f) {
                    overlayRect.set(0f, 0f, visibleRight, h)
                    canvas.drawRoundRect(overlayRect, cornerRadius, cornerRadius, overlayPaint)
                    canvas.save()
                    canvas.clipRect(0f, 0f, visibleRight, h)
                    drawEncryptedText(canvas, h)
                    canvas.restore()
                }
            }

            // HOLD: fully visible overlay, static
            Phase.HOLD -> {
                val w = width.toFloat(); val h = height.toFloat()
                if (w == 0f || h == 0f) return
                overlayRect.set(0f, 0f, w, h)
                canvas.drawRoundRect(overlayRect, cornerRadius, cornerRadius, overlayPaint)
                canvas.save()
                canvas.clipRect(0f, 0f, w, h)
                drawEncryptedText(canvas, h)
                canvas.restore()
            }

            // Phase 2: real text reveals L→R, overlay retreats L→R
            Phase.DECRYPT_REVEAL -> {
                val w = width.toFloat(); val h = height.toFloat()
                if (w == 0f || h == 0f) return

                val revealRight = w * revealProgress
                val overlayLeft = w * revealProgress

                if (revealRight > 0f) {
                    canvas.save()
                    canvas.clipRect(0f, 0f, revealRight, h)
                    drawRealText(canvas, h)
                    canvas.restore()
                }

                if (overlayLeft < w) {
                    overlayRect.set(overlayLeft, 0f, w, h)
                    canvas.drawRoundRect(overlayRect, cornerRadius, cornerRadius, overlayPaint)
                    canvas.save()
                    canvas.clipRect(overlayLeft, 0f, w, h)
                    drawEncryptedText(canvas, h)
                    canvas.restore()
                }
            }
        }
    }

    // ── draw helpers ──────────────────────────────────────────────────────────

    /**
     * Draw encrypted text vertically centered within the cream rect of height [h].
     * Formula: h/2 - (descent + ascent)/2 places the text baseline so the
     * visual centre of the text cap-height aligns with the centre of the rect.
     */
    private fun drawEncryptedText(canvas: Canvas, h: Float) {
        val x = compoundPaddingLeft.toFloat()
        // Vertically centre: midpoint of rect minus midpoint of text bounds
        val y = h / 2f - (encryptedTextPaint.descent() + encryptedTextPaint.ascent()) / 2f
        canvas.drawText(encryptedText, x, y, encryptedTextPaint)
    }

    /**
     * Draw real decrypted text vertically centered within the view height [h].
     */
    private fun drawRealText(canvas: Canvas, h: Float) {
        val x = compoundPaddingLeft.toFloat()
        val y = h / 2f - (realTextPaint.descent() + realTextPaint.ascent()) / 2f
        canvas.drawText(decryptedText, x, y, realTextPaint)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
}