package app.secure.kyber.Other

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * A premium, high-fidelity clock-style visualization for message burn time.
 * Features a glowing neon aesthetic, gradient arcs, and a sleek Swiss-watch inspired design.
 */
class CircularBurnProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1A000000") // Deep semi-transparent face
    }

    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#30FFFFFF")
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
    }

    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f
    }

    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var expiresAt: Long = 0L
    private var totalDuration: Long = 0L
    private val rect = RectF()

    // Premium Color Palette
    private val colorBlueStart = Color.parseColor("#00C6FF")
    private val colorBlueEnd   = Color.parseColor("#0072FF")
    private val colorAmberStart = Color.parseColor("#FDC830")
    private val colorAmberEnd   = Color.parseColor("#F37335")
    private val colorRedStart   = Color.parseColor("#FF416C")
    private val colorRedEnd     = Color.parseColor("#FF4B2B")

    fun setTimer(expiresAt: Long, totalDuration: Long) {
        this.expiresAt = expiresAt
        this.totalDuration = if (totalDuration > 0) totalDuration else 1L
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val padding = 6f // Space for glow
        rect.set(padding, padding, w - padding, h - padding)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (expiresAt <= 0L) return

        val now = System.currentTimeMillis()
        val remaining = (expiresAt - now).coerceAtLeast(0L)
        val progress = (remaining.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
        
        val sweepAngle = 360f * progress
        val (startCol, endCol) = when {
            progress > 0.5f -> colorBlueStart to colorBlueEnd
            progress > 0.2f -> colorAmberStart to colorAmberEnd
            else -> colorRedStart to colorRedEnd
        }

        val cx = width / 2f
        val cy = height / 2f
        val radius = rect.width() / 2f

        // 1. Draw Clock Face & Rim
        canvas.drawCircle(cx, cy, radius, facePaint)
        canvas.drawCircle(cx, cy, radius, rimPaint)

        // 2. Draw Ticks with subtle depth
        for (i in 0 until 12) {
            val angle = Math.toRadians((i * 30).toDouble())
            val isMajor = i % 3 == 0
            val tickLen = if (isMajor) radius * 0.2f else radius * 0.1f
            
            val innerR = radius - tickLen
            val startX = (cx + innerR * sin(angle)).toFloat()
            val startY = (cy - innerR * cos(angle)).toFloat()
            val endX = (cx + radius * sin(angle)).toFloat()
            val endY = (cy - radius * cos(angle)).toFloat()
            
            tickPaint.color = if (isMajor) Color.parseColor("#B0FFFFFF") else Color.parseColor("#60FFFFFF")
            tickPaint.strokeWidth = if (isMajor) 2.5f else 1.2f
            canvas.drawLine(startX, startY, endX, endY, tickPaint)
        }

        // 3. Draw Glowing Progress Arc
        // Inner Glow
        glowPaint.color = startCol
        glowPaint.alpha = 100
        glowPaint.strokeWidth = 8f
        canvas.drawArc(rect, -90f, sweepAngle, false, glowPaint)

        // Main Arc with Gradient
        progressPaint.strokeWidth = 4f
        val shader = SweepGradient(cx, cy, intArrayOf(startCol, endCol, startCol), null)
        val matrix = Matrix()
        matrix.setRotate(-90f, cx, cy)
        shader.setLocalMatrix(matrix)
        progressPaint.shader = shader
        canvas.drawArc(rect, -90f, sweepAngle, false, progressPaint)
        progressPaint.shader = null // Clear for next draw

        // 4. Draw Sleek Clock Hand
        val handAngle = Math.toRadians((sweepAngle - 90).toDouble())
        val handLen = radius * 0.85f
        val hX = (cx + handLen * cos(handAngle)).toFloat()
        val hY = (cy + handLen * sin(handAngle)).toFloat()

        // Hand Glow
        handPaint.color = endCol
        handPaint.alpha = 150
        handPaint.strokeWidth = 6f
        canvas.drawLine(cx, cy, hX, hY, handPaint)

        // Hand Needle
        handPaint.alpha = 255
        handPaint.strokeWidth = 2.5f
        handPaint.color = Color.WHITE
        canvas.drawLine(cx, cy, hX, hY, handPaint)

        // 5. Center Hub (Multi-layered)
        hubPaint.color = endCol
        canvas.drawCircle(cx, cy, radius * 0.12f, hubPaint)
        hubPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, radius * 0.05f, hubPaint)
    }

    fun update() {
        invalidate()
    }
}


