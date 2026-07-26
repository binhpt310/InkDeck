package dev.inkdeck.market.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.R as EinkR
import dev.inkdeck.market.MarketFormat
import dev.inkdeck.market.MarketSnapshot
import dev.inkdeck.market.R
import dev.inkdeck.market.data.Direction

/**
 * One widget on the dashboard — design.md §9.1, §5.6.
 *
 * Composed of real `TextView`s rather than drawn on a `Canvas`. The price is the largest type in
 * the app (`display`, 34 sp) and Canvas text would be invisible to TalkBack and to `uiautomator`,
 * which the shared brief calls out as having bitten this project twice. Only the border, the
 * sparkline and the chart are drawn.
 *
 * [update] returns whether anything visible changed. That return value is what makes design.md
 * §13's "market auto-refresh `[P]` — only cards whose values changed" implementable: a tick that
 * finds four unchanged cards must not repaint or note a partial for any of them.
 */
class MarketCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val titleView = TextView(context)
    private val sourceView = TextView(context)
    private val priceView = TextView(context)
    private val changeView = TextView(context)
    private val sparkline = SparklineView(context)
    private val footerView = TextView(context)

    private var current: MarketSnapshot? = null
    private var currentStatus: MarketSnapshot.Status? = null

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = EinkTheme.ink900(context)
    }
    private val borderRect = RectF()
    private val radius = EinkTheme.dp(context, 4f)
    private val borderWidth = EinkTheme.dp(context, 1.5f)

    /**
     * §5.1 wants a full invert on press. Inverting a composed card means inverting five TextViews
     * and a Canvas view in step, so the acknowledgement here is a thickened border instead: same
     * purpose (tell the user the tap landed before the ~60 ms panel latency makes them tap
     * again), one paint field, no text-colour bookkeeping.
     */
    private var pressedFrame = false
    private var pressStartedAt = 0L
    private val releasePress = Runnable {
        pressedFrame = false
        invalidate()
    }

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        val pad = resources.getDimensionPixelSize(EinkR.dimen.ink_card_padding)
        setPadding(pad, pad, pad, pad)

        val header = LinearLayout(context).apply { orientation = HORIZONTAL }
        titleView.apply {
            setTextAppearance(EinkR.style.TextAppearance_InkDeck_Title2)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        sourceView.apply {
            setTextAppearance(EinkR.style.TextAppearance_InkDeck_Caption)
            maxLines = 1
            gravity = Gravity.END
        }
        header.addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            sourceView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.CENTER_VERTICAL
                it.marginStart = resources.getDimensionPixelSize(EinkR.dimen.ink_space_2)
            },
        )
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        priceView.apply {
            setTextAppearance(EinkR.style.TextAppearance_InkDeck_Display)
            maxLines = 1
            // A six-figure price at 34 sp overflows a 270 dp card. Shrinking is better than
            // ellipsising a number — "108,4…" is worse than useless. The floor is 22 sp, well
            // above §3.2's 14 sp minimum, so the price never becomes hard to read.
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 22, 34, 2, android.util.TypedValue.COMPLEX_UNIT_SP,
            )
        }
        addView(priceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        changeView.apply {
            setTextAppearance(EinkR.style.TextAppearance_InkDeck_Body)
            maxLines = 1
        }
        addView(changeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(
            sparkline,
            LayoutParams(LayoutParams.MATCH_PARENT, EinkTheme.dp(context, 64f).toInt()).also {
                it.topMargin = resources.getDimensionPixelSize(EinkR.dimen.ink_space_2)
                it.bottomMargin = resources.getDimensionPixelSize(EinkR.dimen.ink_space_2)
            },
        )

        footerView.apply {
            setTextAppearance(EinkR.style.TextAppearance_InkDeck_Caption)
            maxLines = 1
        }
        addView(footerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /**
     * @param staleAfterMs 2× the refresh interval; 0 disables the stale check (manual mode, where
     *   the user decides what "old" means).
     * @return true if anything the user can see is different from the last call.
     */
    fun update(snapshot: MarketSnapshot, staleAfterMs: Long): Boolean {
        val status = snapshot.status(System.currentTimeMillis(), staleAfterMs)
        if (snapshot == current && status == currentStatus) return false
        current = snapshot
        currentStatus = status
        render(snapshot, status)
        return true
    }

    private fun render(s: MarketSnapshot, status: MarketSnapshot.Status) {
        titleView.text = s.asset.display

        sourceView.text = when {
            // §9.1: `⚠ unoff.` is permanent on every VN widget, ahead of the provider name —
            // the warning matters more than which brokerage's back-end answered.
            s.unofficial -> context.getString(R.string.market_unofficial_short)
            s.attribution != null -> s.attribution
            else -> ""
        }

        val quote = s.quote
        if (quote == null) {
            priceView.text = MarketFormat.EM_DASH
            changeView.text = context.getString(R.string.market_state_error_badge)
            sparkline.setSeries(null, Direction.FLAT, context.getString(R.string.market_spark_none))
            footerView.text = s.error ?: context.getString(
                if (status == MarketSnapshot.Status.LOADING) R.string.market_loading
                else R.string.market_error_generic
            )
            contentDescription = "${s.asset.display}, ${footerView.text}"
            return
        }

        priceView.text = MarketFormat.price(quote.last)
        changeView.text = MarketFormat.changeLine(quote)
        sparkline.setSeries(
            s.spark,
            quote.direction,
            context.getString(
                R.string.market_spark_desc,
                s.asset.display,
                MarketFormat.changeSpoken(quote),
            ),
        )

        footerView.text = if (status == MarketSnapshot.Status.STALE) {
            // §2.3's stale encoding: ⌛ plus "as of HH:mm" in ink-500. The Caption appearance
            // already carries ink-500, so no colour is set here.
            context.getString(R.string.market_as_of, MarketFormat.clock(s.fetchedAtMs))
        } else {
            s.sparkSpan ?: ""
        }

        contentDescription = context.getString(
            R.string.market_card_desc,
            s.asset.display,
            MarketFormat.price(quote.last),
            MarketFormat.changeSpoken(quote),
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeCallbacks(releasePress)
                pressedFrame = true
                pressStartedAt = SystemClock.uptimeMillis()
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 120 ms floor, same as PressInvertView: a press shorter than two panel frames
                // may never be drawn at all.
                val held = SystemClock.uptimeMillis() - pressStartedAt
                postDelayed(releasePress, (MIN_HOLD_MS - held).coerceAtLeast(0L))
            }
        }
        return super.onTouchEvent(event)
    }

    /** Overridden so the touch handling above stays reachable to TalkBack's click action. */
    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        removeCallbacks(releasePress)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        borderPaint.strokeWidth = if (pressedFrame) borderWidth * 2.5f else borderWidth
        val inset = borderPaint.strokeWidth / 2f
        borderRect.set(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(borderRect, radius, radius, borderPaint)
    }

    private companion object {
        const val MIN_HOLD_MS = 120L
    }
}
