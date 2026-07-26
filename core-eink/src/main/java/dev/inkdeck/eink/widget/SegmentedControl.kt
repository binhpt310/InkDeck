package dev.inkdeck.eink.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.R

/**
 * The segmented selector used for `Today | Week | All | Done` (design.md §8.1) and for every
 * chip row in the task editor (§8.2: REMIND, REPEAT, PRIORITY, and the Telegram ON/OFF pair).
 *
 * One widget rather than four because they are all the same interaction: pick exactly one of a
 * short list, selection shown as a filled cell.
 *
 * Why a single custom View instead of a row of [EinkButton]s:
 *
 * - Selection must be **fill**, not a tint. On a five-step grey ramp a "selected" background at
 *   ink_200 and an unselected one at paper are two shades of pale that the panel dithers into
 *   near-identical texture. Filled ink_900 with paper text is unmistakable at a glance.
 * - Only the changed cells need repainting. Moving the selection dirties two rectangles, so it
 *   is a `[P]` on a strip 48 dp tall rather than a relayout of the row.
 * - The dividers between cells have to be shared, not doubled — abutting bordered buttons draw
 *   two 1.5 dp strokes side by side, which reads as a thick smudge.
 */
class SegmentedControl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var segments: List<CharSequence> = emptyList()
        set(value) {
            field = value
            if (selectedIndex >= value.size) selectedIndex = 0
            syncDescription()
            requestLayout()
            invalidate()
        }

    var selectedIndex: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, (segments.size - 1).coerceAtLeast(0))
            if (field != clamped) {
                field = clamped
                syncDescription()
                invalidate()
            }
        }

    /**
     * The cell labels are Canvas text, so without this the control is one unlabelled box to
     * TalkBack and to uiautomator. Announcing the options and the current pick is the closest
     * a single view can get to what a radio group would say.
     */
    private fun syncDescription() {
        if (segments.isEmpty()) return
        val current = segments.getOrNull(selectedIndex) ?: return
        contentDescription = "$current, ${segments.joinToString(" ")}"
    }

    /** Fired only on user taps, never by setting [selectedIndex] — so callers cannot loop. */
    var onSelected: ((index: Int) -> Unit)? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = EinkTheme.dp(context, 1.5f)
    }
    private val dividerPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = EinkTheme.dp(context, 1.5f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = EinkTheme.sp(context, 16f)
    }

    private val radius = EinkTheme.dp(context, 4f)
    private val defaultHeight = resources.getDimensionPixelSize(R.dimen.ink_touch_min)
    private val rect = RectF()
    private var pressedIndex = -1

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(defaultHeight, heightMeasureSpec),
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || segments.isEmpty()) return false
        val index = indexAt(event.x)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = index
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val hit = pressedIndex
                pressedIndex = -1
                if (hit >= 0 && hit == index) {
                    val changed = hit != selectedIndex
                    selectedIndex = hit
                    // Re-notify even when unchanged: a chip row may be the only way to
                    // re-confirm a value, and swallowing it makes the tap feel dead.
                    if (!changed) invalidate()
                    performClick()
                    onSelected?.invoke(hit)
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

    private fun indexAt(x: Float): Int {
        if (segments.isEmpty() || width == 0) return -1
        val cell = width.toFloat() / segments.size
        return (x / cell).toInt().coerceIn(0, segments.size - 1)
    }

    override fun onDraw(canvas: Canvas) {
        if (segments.isEmpty()) return
        val ink = EinkTheme.ink900(context)
        val paper = EinkTheme.paper(context)
        val disabledInk = EinkTheme.ink300(context)

        val border = if (isEnabled) ink else disabledInk
        val inset = strokePaint.strokeWidth / 2f
        rect.set(inset, inset, width - inset, height - inset)

        fillPaint.color = paper
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        val cell = (width - strokePaint.strokeWidth) / segments.size
        val metrics = textPaint.fontMetrics
        val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f

        for (i in segments.indices) {
            val left = inset + cell * i
            val right = left + cell
            // Press inverts the same way selection fills, so a press on the already-selected
            // cell flips it back to paper — still a visible acknowledgement.
            val filled = (i == selectedIndex) != (i == pressedIndex)

            if (filled) {
                fillPaint.color = if (isEnabled) ink else disabledInk
                if (i == 0 || i == segments.size - 1) {
                    // End cells keep the rounded outer corners; clipping to the rounded outline
                    // and filling a plain rect is cheaper than building a per-corner path.
                    canvas.save()
                    canvas.clipRect(left, inset, right, height - inset)
                    canvas.drawRoundRect(rect, radius, radius, fillPaint)
                    canvas.restore()
                } else {
                    canvas.drawRect(left, inset, right, height - inset, fillPaint)
                }
            }

            textPaint.color = when {
                filled -> paper
                isEnabled -> ink
                else -> EinkTheme.ink500(context)
            }
            textPaint.typeface = if (i == selectedIndex) {
                EinkTheme.uiEmphasisTypeface(context)
            } else {
                EinkTheme.uiTypeface(context)
            }
            canvas.drawText(
                ellipsize(segments[i].toString(), cell - EinkTheme.dp(context, 8f)),
                (left + right) / 2f,
                baseline,
                textPaint,
            )

            if (i > 0) {
                dividerPaint.color = border
                canvas.drawLine(left, inset, left, height - inset, dividerPaint)
            }
        }

        strokePaint.color = border
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
    }

    /**
     * Cells are equal width, so a long label in one segment has to be cut rather than allowed to
     * bleed under its neighbour's divider.
     */
    private fun ellipsize(text: String, maxWidth: Float): String {
        if (textPaint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && textPaint.measureText(text.substring(0, end) + "…") > maxWidth) end--
        return text.substring(0, end) + "…"
    }
}
