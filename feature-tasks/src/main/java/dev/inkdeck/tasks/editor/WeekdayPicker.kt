package dev.inkdeck.tasks.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.inkdeck.eink.EinkTheme
import java.time.DayOfWeek

/**
 * `M T W T F S S` with filled circles for the selected days — design.md §8.2, shown only when
 * REPEAT is `wkly`.
 *
 * Circles rather than a second [dev.inkdeck.eink.widget.SegmentedControl] because this is the
 * one multi-select control in the editor, and a segmented bar reads as pick-one. The shape
 * change is the affordance: seven separate targets, any number filled.
 */
class WeekdayPicker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Monday-first, matching [DayOfWeek.getValue] 1..7 — not the US Sunday-first convention. */
    private val days = DayOfWeek.entries.toTypedArray()

    var selected: Set<DayOfWeek> = emptySet()
        set(value) {
            field = value
            // Canvas letters; without this the row is a single unlabelled box.
            contentDescription = if (value.isEmpty()) {
                context.getString(dev.inkdeck.tasks.R.string.tasks_a11y_no_days)
            } else {
                value.sortedBy { it.value }.joinToString(" ") { LETTERS[it.value - 1] }
            }
            invalidate()
        }

    var onChanged: ((Set<DayOfWeek>) -> Unit)? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = EinkTheme.dp(context, 1.5f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = EinkTheme.sp(context, 16f)
    }

    private val diameter = EinkTheme.dp(context, 40f)
    private val rowHeight = EinkTheme.dp(context, 56f).toInt()
    private var pressedIndex = -1

    init {
        isClickable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(rowHeight, heightMeasureSpec),
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val index = (event.x / (width.toFloat() / days.size)).toInt().coerceIn(0, days.size - 1)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = index
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val hit = pressedIndex
                pressedIndex = -1
                if (hit == index) {
                    val day = days[hit]
                    selected = if (day in selected) selected - day else selected + day
                    performClick()
                    onChanged?.invoke(selected)
                } else {
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        val ink = EinkTheme.ink900(context)
        val paper = EinkTheme.paper(context)
        val cell = width.toFloat() / days.size
        val radius = diameter / 2f
        val cy = height / 2f

        for (i in days.indices) {
            val cx = cell * i + cell / 2f
            val filled = (days[i] in selected) != (i == pressedIndex)

            if (filled) {
                fillPaint.color = ink
                canvas.drawCircle(cx, cy, radius, fillPaint)
            } else {
                strokePaint.color = EinkTheme.ink300(context)
                canvas.drawCircle(cx, cy, radius - strokePaint.strokeWidth / 2f, strokePaint)
            }

            textPaint.color = if (filled) paper else ink
            textPaint.typeface = if (filled) {
                EinkTheme.uiEmphasisTypeface(context)
            } else {
                EinkTheme.uiTypeface(context)
            }
            val metrics = textPaint.fontMetrics
            canvas.drawText(
                LETTERS[i],
                cx,
                cy - (metrics.ascent + metrics.descent) / 2f,
                textPaint,
            )
        }
    }

    companion object {
        /** Two `T`s and two `S`s is the standard compact form; position disambiguates. */
        val LETTERS = arrayOf("M", "T", "W", "T", "F", "S", "S")
    }
}
