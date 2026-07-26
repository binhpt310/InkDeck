package dev.inkdeck.eink.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.R

/**
 * The `☐` / `☑` box in a task row — design.md §8.1.
 *
 * Drawn rather than a platform `CheckBox` for two reasons: the platform widget animates the
 * tick on state change, and its 48 dp minimum plus internal padding puts the actual box well
 * under a comfortable target. Here the whole 56 dp square is the hit area and the glyph inside
 * is 28 dp.
 */
class EinkCheckbox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : PressInvertView(context, attrs, defStyleAttr) {

    var checked: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = EinkTheme.dp(context, 2f)
        strokeCap = Paint.Cap.SQUARE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val boxSize = EinkTheme.dp(context, 28f)
    private val radius = EinkTheme.dp(context, 3f)
    private val touchMin = resources.getDimensionPixelSize(R.dimen.ink_touch_min)
    private val rect = RectF()
    private val tick = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(touchMin, widthMeasureSpec),
            resolveSize(touchMin, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val ink = if (isEnabled) EinkTheme.ink900(context) else EinkTheme.ink300(context)
        val paper = EinkTheme.paper(context)

        val left = (width - boxSize) / 2f
        val top = (height - boxSize) / 2f
        rect.set(left, top, left + boxSize, top + boxSize)

        // Pressed inverts, so an unchecked box momentarily reads as filled — the same
        // acknowledgement language as every other tappable surface (§5.1).
        val filled = checked != inverted

        if (filled) {
            fillPaint.color = ink
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            drawTick(canvas, paper)
        } else {
            strokePaint.color = ink
            val inset = strokePaint.strokeWidth / 2f
            rect.inset(inset, inset)
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
        }
    }

    private fun drawTick(canvas: Canvas, color: Int) {
        strokePaint.color = color
        tick.reset()
        val w = rect.width()
        tick.moveTo(rect.left + w * 0.24f, rect.top + w * 0.52f)
        tick.lineTo(rect.left + w * 0.43f, rect.top + w * 0.71f)
        tick.lineTo(rect.left + w * 0.77f, rect.top + w * 0.30f)
        canvas.drawPath(tick, strokePaint)
    }
}
