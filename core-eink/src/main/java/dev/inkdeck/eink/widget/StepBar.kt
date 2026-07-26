package dev.inkdeck.eink.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import dev.inkdeck.eink.EinkTheme

/**
 * The stepped block bar that replaces every spinner and every indeterminate progress bar —
 * design.md §5.7, §14 item 9.
 *
 * ```
 *   ▪▪▪░░
 * ```
 *
 * A spinner is a continuous animation: at 16 fps it stutters, and it burns a panel refresh per
 * frame for as long as it is on screen. Five discrete states repaint at most five times and
 * each one is legible.
 */
class StepBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var steps: Int = DEFAULT_STEPS
        set(value) {
            field = value.coerceAtLeast(1)
            invalidate()
        }

    /** How many blocks are filled. Advanced by the caller; this view never animates itself. */
    var progress: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, steps)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    private val filledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val gap = EinkTheme.dp(context, 4f)
    private val defaultHeight = EinkTheme.dp(context, 12f).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(EinkTheme.dp(context, 160f).toInt(), widthMeasureSpec)
        val h = resolveSize(defaultHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        filledPaint.color = EinkTheme.ink900(context)
        emptyPaint.color = EinkTheme.ink200(context)

        val totalGap = gap * (steps - 1)
        val blockWidth = (width - totalGap) / steps
        if (blockWidth <= 0f) return

        var x = 0f
        for (i in 0 until steps) {
            val paint = if (i < progress) filledPaint else emptyPaint
            canvas.drawRect(x, 0f, x + blockWidth, height.toFloat(), paint)
            x += blockWidth + gap
        }
    }

    private companion object {
        const val DEFAULT_STEPS = 5
    }
}
