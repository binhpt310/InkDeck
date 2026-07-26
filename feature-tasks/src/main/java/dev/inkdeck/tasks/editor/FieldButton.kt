package dev.inkdeck.tasks.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.widget.PressInvertView

/**
 * The bordered `▤ Sat 25 Jul 2026` / `◷ 14:30` fields in design.md §8.2 — an icon and a
 * left-aligned value in a 56 dp box that opens a picker.
 *
 * Not an [dev.inkdeck.eink.widget.EinkButton]: that centres its label and has no icon slot, and
 * a date shifting left and right as the day name changes length is exactly the kind of
 * instability that makes a form feel loose.
 */
class FieldButton(context: Context) : PressInvertView(context) {

    var value: CharSequence = ""
        set(v) {
            field = v
            contentDescription = v
            invalidate()
        }

    /** Shown in ink_500 when the field is unset, so "no date" reads as absence, not as a value. */
    var placeholder: Boolean = false
        set(v) {
            field = v
            invalidate()
        }

    private var icon: Drawable? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = EinkTheme.dp(context, 1.5f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = EinkTheme.sp(context, 16f)
    }

    private val radius = EinkTheme.dp(context, 4f)
    private val padding = EinkTheme.dp(context, 12f)
    private val iconSize = EinkTheme.dp(context, 24f).toInt()
    private val rect = RectF()

    fun setIconResource(@DrawableRes res: Int) {
        icon = ContextCompat.getDrawable(context, res)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val ink = EinkTheme.ink900(context)
        val paper = EinkTheme.paper(context)
        val inset = strokePaint.strokeWidth / 2f
        rect.set(inset, inset, width - inset, height - inset)

        val foreground = if (inverted) paper else ink

        if (inverted) {
            fillPaint.color = ink
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        }
        strokePaint.color = if (inverted) paper else ink
        canvas.drawRoundRect(rect, radius, radius, strokePaint)

        var x = padding
        icon?.let {
            val top = (height - iconSize) / 2
            it.setBounds(x.toInt(), top, x.toInt() + iconSize, top + iconSize)
            it.setTint(foreground)
            it.draw(canvas)
            x += iconSize + EinkTheme.dp(context, 10f)
        }

        textPaint.color = when {
            inverted -> paper
            placeholder -> EinkTheme.ink500(context)
            else -> ink
        }
        textPaint.typeface = EinkTheme.uiTypeface(context)
        val metrics = textPaint.fontMetrics
        val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f
        val available = width - x - padding
        canvas.drawText(ellipsize(value.toString(), available), x, baseline, textPaint)
    }

    private fun ellipsize(text: String, maxWidth: Float): String {
        if (maxWidth <= 0f) return ""
        if (textPaint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && textPaint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }
}
