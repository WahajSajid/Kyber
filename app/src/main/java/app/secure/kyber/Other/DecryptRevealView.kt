package app.secure.kyber.Other

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView

/**
 * Drop-in replacement for the received-message TextView.
 *
 * THREE-PHASE animation, all directions LEFT → RIGHT:
 *
 * Phase 1  — OVERLAY IN  (overlayInProgress 0→1)
 *   Empty bubble visible.
 *   The cream rect + encrypted text builds in from left → right.
 *   visibleRight = width * overlayInProgress   (grows from 0 to full width)
 *   Real text is NOT drawn yet (alpha 0 via super invisible trick — we just don't call super.onDraw).
 *
 * Phase 2  — DECRYPT REVEAL  (revealProgress 0→1)
 *   The cream overlay + encrypted text retreat left → right (left edge moves right).
 *   overlayLeft  = width * revealProgress       (left edge of overlay grows from 0 → width)
 *   Simultaneously the real white text becomes visible from left → right, clipped to
 *   [0 .. revealRight] where revealRight = width * revealProgress
 *   (i.e. real text reveals in the same band the overlay is leaving).
 *
 * Normal mode (no animation): behaves exactly like AppCompatTextView.
 *
 * Public API:
 *   startDecryptAnimation(encrypted, phaseInMs, revealMs, onDone)
 *   cancelAndShowFinal()
 */
class DecryptRevealTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatTextView(context, attrs, defStyle) {

    // ── animation state ───────────────────────────────────────────────────────
    private enum class Phase { IDLE, OVERLAY_IN, DECRYPT_REVEAL }

    private var phase = Phase.IDLE
    private var encryptedText: String = ""

    /**
     * Phase 1: 0→1 means overlay builds in from left to right.
     * visibleRight = width * overlayInProgress
     */
    private var overlayInProgress: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    /**
     * Phase 2: 0→1 means overlay retreats left-to-right AND real text reveals left-to-right.
     * overlayLeft   = width * revealProgress  (left edge of overlay moves right → overlay shrinks from left)
     * revealRight   = width * revealProgress  (real text visible up to this x)
     */
    private var revealProgress: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    // ── paints ────────────────────────────────────────────────────────────────
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EDE8DC")   // cream / off-white
    }
    private val encryptedTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1C1C")   // dark text on cream
    }

    private val overlayRect = RectF()
    private val cornerRadius: Float get() = dpToPx(6f)

    private var phase1Animator: ValueAnimator? = null
    private var phase2Animator: ValueAnimator? = null

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Start the full three-phase animation.
     *
     * IMPORTANT: call setText("") before calling this so the bubble appears empty.
     * The real decrypted text must be set DURING the animation (between phase 1 and 2).
     * The adapter does this — it calls setText(decrypted) just before phase 2 starts.
     *
     * @param encrypted      the encrypted placeholder string
     * @param overlayInMs    duration of phase 1 (overlay building in), default 500ms
     * @param revealMs       duration of phase 2 (decrypt reveal), default 900ms
     * @param onReadyForReal called when phase 1 ends — adapter should setText(decrypted) here
     * @param onDone         called when phase 2 ends
     */
    fun startDecryptAnimation(
        encrypted: String,
        overlayInMs: Long = 500L,
        revealMs: Long = 900L,
        onReadyForReal: () -> Unit = {},
        onDone: () -> Unit = {}
    ) {
        cancelAndShowFinal()   // reset any previous state

        encryptedText = encrypted
        encryptedTextPaint.typeface = typeface
        encryptedTextPaint.textSize = textSize

        // Phase 1: build overlay in left → right
        phase = Phase.OVERLAY_IN
        overlayInProgress = 0f
        revealProgress = 0f

        phase1Animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = overlayInMs
            interpolator = LinearInterpolator()
            addUpdateListener { overlayInProgress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    overlayInProgress = 1f
                    // Notify adapter to set the real decrypted text now
                    onReadyForReal()
                    // Start phase 2
                    startPhase2(revealMs, onDone)
                }
                override fun onAnimationCancel(a: Animator) { /* handled by cancelAndShowFinal */ }
            })
            start()
        }
    }

    private fun startPhase2(revealMs: Long, onDone: () -> Unit) {
        phase = Phase.DECRYPT_REVEAL
        revealProgress = 0f

        phase2Animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = revealMs
            interpolator = LinearInterpolator()
            addUpdateListener { revealProgress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    finishDecrypt()
                    onDone()
                }
                override fun onAnimationCancel(a: Animator) { /* handled by cancelAndShowFinal */ }
            })
            start()
        }
    }

    /** Immediately snap to final state — real text visible, no overlay. */
    fun cancelAndShowFinal() {
        phase1Animator?.cancel(); phase1Animator = null
        phase2Animator?.cancel(); phase2Animator = null
        finishDecrypt()
    }

    // ── internal ──────────────────────────────────────────────────────────────

    private fun finishDecrypt() {
        phase = Phase.IDLE
        encryptedText = ""
        overlayInProgress = 0f
        revealProgress = 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        when (phase) {

            Phase.IDLE -> {
                // Normal TextView — just draw real text
                super.onDraw(canvas)
            }

            Phase.OVERLAY_IN -> {
                // Phase 1: empty bubble + overlay building in from left → right.
                // Do NOT draw real text yet (bubble appears empty).
                // Draw cream rect + encrypted text clipped to [0..visibleRight]
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
                // Real text intentionally NOT drawn — super.onDraw not called
            }

            Phase.DECRYPT_REVEAL -> {
                // Phase 2:
                // - Real white text reveals left → right: visible in [0..revealRight]
                // - Overlay retreats left → right: visible in [overlayLeft..width]
                //   where overlayLeft = width * revealProgress
                val w = width.toFloat(); val h = height.toFloat()
                if (w == 0f || h == 0f) return

                val revealRight  = w * revealProgress        // real text reveals up to here
                val overlayLeft  = w * revealProgress        // overlay now starts here (retreating left edge)

                // Draw real white text clipped to [0..revealRight]
                if (revealRight > 0f) {
                    canvas.save()
                    canvas.clipRect(0f, 0f, revealRight, h)
                    super.onDraw(canvas)
                    canvas.restore()
                }

                // Draw cream overlay + encrypted text clipped to [overlayLeft..width]
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

    private fun drawEncryptedText(canvas: Canvas, viewHeight: Float) {
        val paddingLeft = compoundPaddingLeft.toFloat()
        val textY = compoundPaddingTop + textSize - encryptedTextPaint.descent()
        canvas.drawText(encryptedText, paddingLeft, textY, encryptedTextPaint)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun dpToPx(dp: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)
}