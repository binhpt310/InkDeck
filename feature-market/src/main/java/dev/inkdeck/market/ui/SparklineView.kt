package dev.inkdeck.market.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.market.data.Direction

/**
 * The 64 dp price line on a dashboard card — design.md §9.1.
 *
 * ### Why this exists instead of a chart library
 *
 * Every Android charting library animates on data change, most of them with no way to turn it
 * off, and at ~16 fps an animated redraw is a smear that also burns the ghost budget. Plan.md
 * §5.2 rules them out; this is forty lines of `Canvas` and no dependency.
 *
 * ### Why stroke pattern and not colour
 *
 * design.md §2.3: the panel has five distinguishable greys and dithers everything else. Two
 * series drawn in two greys are two textures the eye cannot separate, so direction is carried by
 * **stroke pattern** — solid up, dashed down, dotted flat — which survives dithering intact and
 * matches the `▲`/`▼`/`–` glyph on the line above.
 *
 * ### Two things that are not obvious
 *
 * The [Path] is built in [setSeries], not in [onDraw]. A repaint on this panel is cheap only if
 * it does no work; rebuilding 96 line segments on every invalidate would have made the card the
 * slowest thing on screen.
 *
 * The view is forced into a software layer. `DashPathEffect` on `drawPath` is one of the
 * operations the hardware canvas has never fully supported, and when it silently falls back the
 * dashes come out solid — which would destroy the *only* thing distinguishing an up line from a
 * down line here.
 */
class SparklineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var series: FloatArray? = null
    private var direction: Direction = Direction.FLAT

    private val path = Path()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = EinkTheme.dp(context, 1.5f)
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.ROUND
        color = EinkTheme.ink900(context)
    }

    private val baselinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = EinkTheme.dp(context, 1f)
        color = EinkTheme.ink200(context)
    }

    private val dash = DashPathEffect(
        floatArrayOf(EinkTheme.dp(context, 5f), EinkTheme.dp(context, 3f)), 0f,
    )
    private val dot = DashPathEffect(
        floatArrayOf(EinkTheme.dp(context, 1.5f), EinkTheme.dp(context, 3f)), 0f,
    )

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /** @param label spoken description; Canvas strokes say nothing to TalkBack on their own. */
    fun setSeries(values: FloatArray?, direction: Direction, label: CharSequence) {
        this.series = values
        this.direction = direction
        contentDescription = label
        linePaint.pathEffect = when (direction) {
            Direction.UP -> null
            Direction.DOWN -> dash
            Direction.FLAT -> dot
        }
        rebuild()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        path.reset()
        val values = series ?: return
        if (values.size < 2 || width <= 0 || height <= 0) return

        var min = values[0]
        var max = values[0]
        for (v in values) {
            if (v < min) min = v
            if (v > max) max = v
        }
        // A flat series has zero range; centring it beats dividing by zero or pinning it to the
        // top edge, which is what a naive normalisation does and looks like a bug.
        val range = (max - min).takeIf { it > 0f }

        val inset = linePaint.strokeWidth
        val usableW = width - inset * 2f
        val usableH = height - inset * 2f
        val stepX = usableW / (values.size - 1)

        for (i in values.indices) {
            val x = inset + stepX * i
            val y = if (range == null) {
                height / 2f
            } else {
                inset + usableH - (values[i] - min) / range * usableH
            }
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (series == null || series!!.size < 2) {
            // No history is not an error — Finnhub's free tier serves quotes and refuses candles.
            // A rule where the line would be reads as "nothing here" rather than "broken".
            val y = height / 2f
            canvas.drawLine(0f, y, width.toFloat(), y, baselinePaint)
            return
        }
        canvas.drawPath(path, linePaint)
    }
}
