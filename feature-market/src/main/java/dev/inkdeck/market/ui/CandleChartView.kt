package dev.inkdeck.market.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.market.MarketFormat
import dev.inkdeck.market.data.Candle

/**
 * The OHLC chart of design.md §9.3, drawn with `Canvas` and nothing else.
 *
 * ### Hollow vs filled, never red vs green
 *
 * §9.3 is explicit: an up candle is a **hollow** body with a 1.5 dp outline, a down candle is a
 * **filled** body. Both are `ink-900`. On a five-step grey ramp any two-colour scheme collapses
 * into the same texture, whereas hollow and filled differ in overall area and read correctly even
 * where the panel has ghosted. Wicks are `ink-700` and one third the body width so a wick is
 * never mistaken for a thin body.
 *
 * ### Layout arithmetic
 *
 * Everything is derived in [onDraw] from the current bounds rather than cached, because the chart
 * is redrawn only when the timeframe changes — which design.md §13 classifies as `[F]`, a full
 * flush, so there is no partial-repaint budget to protect here. The sparkline on the dashboard
 * has the opposite requirement and caches its path; the difference is deliberate.
 */
class CandleChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var candles: List<Candle> = emptyList()

    private val bodyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = EinkTheme.dp(context, 1.5f)
        color = EinkTheme.ink900(context)
    }
    private val bodyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = EinkTheme.ink900(context)
    }
    private val wickPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = EinkTheme.dp(context, 1f)
        color = EinkTheme.ink700(context)
    }
    private val gridPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = EinkTheme.dp(context, 1f)
        color = EinkTheme.ink200(context)
        // Dotted, matching §9.3's `····` grid rows. A solid 1 dp ink-200 rule across 540 dp
        // competes with the candles; a dotted one recedes.
        pathEffect = DashPathEffect(
            floatArrayOf(EinkTheme.dp(context, 1f), EinkTheme.dp(context, 3f)), 0f,
        )
    }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EinkTheme.ink500(context)
        textSize = EinkTheme.sp(context, 14f) // caption; §3.2's absolute floor
        typeface = EinkTheme.uiTypeface(context)
    }

    private val axisWidth = EinkTheme.dp(context, 56f)
    private val axisHeight = EinkTheme.dp(context, 22f)

    init {
        // Same reason as SparklineView: DashPathEffect is unreliable on the hardware canvas, and
        // a grid that silently renders solid would fight the candles for attention.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setCandles(list: List<Candle>, label: CharSequence) {
        candles = list
        contentDescription = label
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val data = candles
        if (data.isEmpty() || width <= 0 || height <= 0) return

        var low = data[0].low
        var high = data[0].high
        for (c in data) {
            if (c.low < low) low = c.low
            if (c.high > high) high = c.high
        }
        val range = (high - low).takeIf { it > 0.0 } ?: return

        val plotLeft = axisWidth
        val plotRight = width.toFloat()
        val plotTop = axisText.textSize
        val plotBottom = height - axisHeight
        val plotHeight = plotBottom - plotTop
        if (plotHeight <= 0f || plotRight <= plotLeft) return

        fun yFor(value: Double) = plotBottom - ((value - low) / range * plotHeight).toFloat()

        // Grid. Five rows is what §9.3 draws and about as many labels as fit at 14 sp.
        val metrics = axisText.fontMetrics
        for (i in 0..GRID_ROWS) {
            val value = low + range * i / GRID_ROWS
            val y = yFor(value)
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
            canvas.drawText(
                MarketFormat.price(value),
                EinkTheme.dp(context, 2f),
                y - (metrics.ascent + metrics.descent) / 2f,
                axisText,
            )
        }

        // Candles. Bodies get 70 % of the slot so adjacent ones never touch: two abutting filled
        // bodies read as one wide block on a dithered panel.
        val slot = (plotRight - plotLeft) / data.size
        val bodyWidth = (slot * 0.7f).coerceAtLeast(EinkTheme.dp(context, 1.5f))
        val half = bodyWidth / 2f

        for (i in data.indices) {
            val c = data[i]
            val cx = plotLeft + slot * (i + 0.5f)

            canvas.drawLine(cx, yFor(c.high), cx, yFor(c.low), wickPaint)

            val yOpen = yFor(c.open)
            val yClose = yFor(c.close)
            val top = minOf(yOpen, yClose)
            val bottom = maxOf(yOpen, yClose)
            // A doji has zero body height and would draw nothing at all; give it the stroke width
            // so it still reads as a bar.
            val bodyBottom = if (bottom - top < bodyStroke.strokeWidth) {
                top + bodyStroke.strokeWidth
            } else {
                bottom
            }

            if (c.close >= c.open) {
                canvas.drawRect(cx - half, top, cx + half, bodyBottom, bodyStroke)
            } else {
                canvas.drawRect(cx - half, top, cx + half, bodyBottom, bodyFill)
            }
        }

        drawTimeAxis(canvas, data, plotLeft, slot, plotBottom)
    }

    private fun drawTimeAxis(
        canvas: Canvas,
        data: List<Candle>,
        plotLeft: Float,
        slot: Float,
        plotBottom: Float,
    ) {
        // Roughly one label per 72 dp. Denser than that and 14 sp labels collide, and there is no
        // rotating them — §3 forbids the italic-adjacent tricks that would buy the room.
        val perLabel = (EinkTheme.dp(context, 72f) / slot).toInt().coerceAtLeast(1)
        val baseline = plotBottom + axisHeight - EinkTheme.dp(context, 4f)
        var i = 0
        while (i < data.size) {
            val t = data[i].openTimeMs
            if (t > 0L) {
                canvas.drawText(
                    MarketFormat.clock(t),
                    plotLeft + slot * (i + 0.5f) - axisText.measureText("00:00") / 2f,
                    baseline,
                    axisText,
                )
            }
            i += perLabel
        }
    }

    private companion object {
        const val GRID_ROWS = 4
    }
}
