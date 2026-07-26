package dev.inkdeck.lab

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import dev.inkdeck.eink.EinkTheme

/**
 * Draws deliberately ghost-prone patterns so a flush has something visible to clear.
 *
 * Ghosting is residue from previous waveforms. To see whether a flush works you first have to
 * accumulate some, which means many high-contrast full-area repaints with no flush in between.
 * [cycle] does exactly that and then settles on [PATTERN_WITNESS] — a mostly-white field whose
 * job is to make leftover ink obvious as grey haze.
 */
class GhostPatternView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = EinkTheme.monoTypeface(context)
        textSize = EinkTheme.sp(context, 13f)
    }

    var pattern: Int = PATTERN_WITNESS
        set(value) {
            field = value
            invalidate()
        }

    /** Repaints [times] different high-contrast patterns, then leaves the witness field up. */
    fun cycle(times: Int = 12, intervalMs: Long = 150L, onDone: (() -> Unit)? = null) {
        var i = 0
        val runner = object : Runnable {
            override fun run() {
                if (i >= times) {
                    pattern = PATTERN_WITNESS
                    onDone?.invoke()
                    return
                }
                pattern = i % PATTERN_COUNT
                i++
                postDelayed(this, intervalMs)
            }
        }
        post(runner)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        when (pattern) {
            0 -> checkerboard(canvas, EinkTheme.dp(context, 16f))
            1 -> stripes(canvas, EinkTheme.dp(context, 10f), diagonal = false)
            2 -> canvas.drawColor(Color.BLACK)
            3 -> stripes(canvas, EinkTheme.dp(context, 6f), diagonal = true)
            4 -> denseText(canvas, invert = false)
            5 -> denseText(canvas, invert = true)
            else -> witness(canvas)
        }
    }

    private fun checkerboard(canvas: Canvas, cell: Float) {
        paint.color = Color.BLACK
        var row = 0
        var y = 0f
        while (y < height) {
            var col = 0
            var x = 0f
            while (x < width) {
                if ((row + col) % 2 == 0) canvas.drawRect(x, y, x + cell, y + cell, paint)
                x += cell
                col++
            }
            y += cell
            row++
        }
    }

    private fun stripes(canvas: Canvas, width0: Float, diagonal: Boolean) {
        paint.color = Color.BLACK
        paint.strokeWidth = width0
        paint.style = Paint.Style.STROKE
        var x = -height.toFloat()
        while (x < width + height) {
            if (diagonal) {
                canvas.drawLine(x, 0f, x + height, height.toFloat(), paint)
            } else {
                canvas.drawLine(x, 0f, x, height.toFloat(), paint)
            }
            x += width0 * 2
        }
        paint.style = Paint.Style.FILL
    }

    private fun denseText(canvas: Canvas, invert: Boolean) {
        if (invert) canvas.drawColor(Color.BLACK)
        textPaint.color = if (invert) Color.WHITE else Color.BLACK
        val lineHeight = EinkTheme.dp(context, 18f)
        var y = lineHeight
        var n = 0
        while (y < height) {
            canvas.drawText(GHOST_LINE + n, EinkTheme.dp(context, 8f), y, textPaint)
            y += lineHeight
            n++
        }
    }

    /**
     * Mostly paper with a light structure. On a clean panel this reads as crisp black on white;
     * with ghosting it reads as black on grey with faint shapes from the earlier patterns.
     */
    private fun witness(canvas: Canvas) {
        textPaint.color = Color.BLACK
        val lineHeight = EinkTheme.dp(context, 26f)
        canvas.drawText("WITNESS FIELD", EinkTheme.dp(context, 12f), lineHeight, textPaint)
        canvas.drawText(
            "Any grey haze or leftover shape here is ghosting.",
            EinkTheme.dp(context, 12f),
            lineHeight * 2,
            textPaint,
        )
        canvas.drawText(
            "A working flush clears it to clean white.",
            EinkTheme.dp(context, 12f),
            lineHeight * 3,
            textPaint,
        )

        paint.color = Color.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = EinkTheme.dp(context, 1.5f)
        canvas.drawRect(
            EinkTheme.dp(context, 8f),
            lineHeight * 4,
            width - EinkTheme.dp(context, 8f),
            height - EinkTheme.dp(context, 8f),
            paint,
        )
        paint.style = Paint.Style.FILL
    }

    private companion object {
        const val PATTERN_COUNT = 6
        const val PATTERN_WITNESS = 99
        const val GHOST_LINE =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 ghosting probe line "
    }
}
