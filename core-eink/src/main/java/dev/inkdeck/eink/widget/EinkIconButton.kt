package dev.inkdeck.eink.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.R

/**
 * A square icon-only tap target: title-bar actions, the paged-scroll rail, the terminal key row.
 *
 * Pressed inverts the whole cell (ink fill, paper glyph) per design.md §5.1 rather than tinting
 * the glyph — a tint change of one or two ramp steps disappears once the panel dithers it.
 *
 * Vector drawables only (design.md §14 item 7): 212 dpi sits between density buckets, so any
 * bitmap gets scaled and blurred.
 */
class EinkIconButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : PressInvertView(context, attrs, defStyleAttr) {

    var icon: Drawable? = null
        set(value) {
            field = value?.mutate()
            invalidate()
        }

    /** Glyph colour when not pressed. ink_500 marks an inactive tab; ink_900 is the default. */
    var iconTint: Int = EinkTheme.ink900(context)
        set(value) {
            field = value
            invalidate()
        }

    var iconSize: Int = resources.getDimensionPixelSize(R.dimen.ink_icon)
        set(value) {
            field = value
            requestLayout()
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val minTarget = resources.getDimensionPixelSize(R.dimen.ink_touch_min)

    fun setIconResource(@DrawableRes res: Int) {
        icon = ContextCompat.getDrawable(context, res)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Grow to the 56 dp minimum only where the caller left the size open. An explicit
        // EXACTLY spec is a deliberate layout decision and is honoured.
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) {
            measuredWidth
        } else {
            maxOf(measuredWidth, minTarget)
        }
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            measuredHeight
        } else {
            maxOf(measuredHeight, minTarget)
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val glyphColor = if (inverted) {
            fillPaint.color = EinkTheme.ink900(context)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
            EinkTheme.paper(context)
        } else {
            iconTint
        }

        val d = icon ?: return
        val left = (width - iconSize) / 2
        val top = (height - iconSize) / 2
        d.setBounds(left, top, left + iconSize, top + iconSize)
        d.setColorFilter(glyphColor, PorterDuff.Mode.SRC_IN)
        d.draw(canvas)
    }
}
