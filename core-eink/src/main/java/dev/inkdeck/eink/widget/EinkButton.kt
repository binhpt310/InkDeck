package dev.inkdeck.eink.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.R

/**
 * design.md §5.1.
 *
 *   Primary    ink_900 fill, paper text, emphasis weight
 *   Secondary  1.5 dp ink_900 border, ink_900 text
 *   Disabled   ink_300 dashed border, ink_500 text
 *
 * Height 56 dp, corner radius 4 dp, no ripple, no elevation. Disabled is marked by *stroke
 * pattern* rather than a lighter fill because design.md §14 item 3 bans colour as the sole
 * carrier of meaning — on a grey panel a paler fill and an enabled fill are the same thing.
 */
class EinkButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : PressInvertView(context, attrs, defStyleAttr) {

    enum class Variant { PRIMARY, SECONDARY }

    var text: CharSequence = ""
        set(value) {
            field = value
            contentDescription = value
            invalidate()
        }

    var variant: Variant = Variant.SECONDARY
        set(value) {
            field = value
            invalidate()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = EinkTheme.dp(context, 1.5f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = EinkTheme.sp(context, 16f)
    }

    private val radius = EinkTheme.dp(context, 4f)
    private val minHeight = resources.getDimensionPixelSize(R.dimen.ink_touch_min)
    private val rect = RectF()

    private val dash = DashPathEffect(
        floatArrayOf(EinkTheme.dp(context, 4f), EinkTheme.dp(context, 4f)), 0f
    )

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val exact = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
        if (!exact && measuredHeight < minHeight) {
            setMeasuredDimension(measuredWidth, minHeight)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val ink = EinkTheme.ink900(context)
        val paper = EinkTheme.paper(context)
        val inset = strokePaint.strokeWidth / 2f
        rect.set(inset, inset, width - inset, height - inset)

        if (!isEnabled) {
            strokePaint.color = EinkTheme.ink300(context)
            strokePaint.pathEffect = dash
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
            strokePaint.pathEffect = null
            drawLabel(canvas, EinkTheme.ink500(context))
            return
        }

        // Pressed inverts the whole button, so a pressed secondary reads exactly like an
        // unpressed primary — which is the point: one unmistakable state change.
        val filled = (variant == Variant.PRIMARY) != inverted

        if (filled) {
            fillPaint.color = ink
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
            drawLabel(canvas, paper)
        } else {
            strokePaint.color = ink
            canvas.drawRoundRect(rect, radius, radius, strokePaint)
            drawLabel(canvas, ink)
        }
    }

    private fun drawLabel(canvas: Canvas, color: Int) {
        textPaint.color = color
        textPaint.typeface = if (variant == Variant.PRIMARY) {
            EinkTheme.uiEmphasisTypeface(context)
        } else {
            EinkTheme.uiTypeface(context)
        }
        val metrics = textPaint.fontMetrics
        val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text.toString(), width / 2f, baseline, textPaint)
    }
}
