package dev.inkdeck.market.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.R as EinkR
import dev.inkdeck.eink.widget.EinkIconButton
import dev.inkdeck.eink.widget.EmptyStateView
import dev.inkdeck.eink.widget.SegmentedControl
import dev.inkdeck.market.MarketAsset
import dev.inkdeck.market.MarketFormat
import dev.inkdeck.market.MarketRepository
import dev.inkdeck.market.MarketSnapshot
import dev.inkdeck.market.R
import dev.inkdeck.market.data.Timeframe

/**
 * design.md §9.3 — one symbol, full width: price, timeframe rail, candles, OHLCV, attribution.
 *
 * Like the picker this is a sibling overlay, not a fragment. It carries all four §5.7 states over
 * the chart area via [EmptyStateView], because the chart is a second, independent data surface:
 * the quote at the top can be fine while `/stock/candle` answers 403.
 */
class SymbolDetailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onTimeframeSelected(asset: MarketAsset, tf: Timeframe)
        fun onRetry(asset: MarketAsset, tf: Timeframe)
        fun onClose()
    }

    var listener: Listener? = null

    private val titleView = TextView(context)
    private val sourceView = TextView(context)
    private val priceView = TextView(context)
    private val changeView = TextView(context)
    private val timeframeControl = SegmentedControl(context)
    private val chart = CandleChartView(context)
    private val chartState = EmptyStateView(context)
    private val statsView = TextView(context)
    private val attributionView = TextView(context)

    private var asset: MarketAsset? = null
    var timeframe: Timeframe = Timeframe.D1
        private set

    init {
        orientation = VERTICAL
        setBackgroundColor(EinkTheme.paper(context))
        isClickable = true

        addView(buildHeader(), LayoutParams(
            LayoutParams.MATCH_PARENT,
            resources.getDimensionPixelSize(EinkR.dimen.ink_bar_height),
        ))
        addView(divider(), LayoutParams(LayoutParams.MATCH_PARENT, dividerHeight()))
        addView(buildPriceBlock(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(divider(), LayoutParams(LayoutParams.MATCH_PARENT, dividerHeight()))

        timeframeControl.apply {
            segments = Timeframe.entries.map { it.label }
            selectedIndex = Timeframe.entries.indexOf(timeframe)
            onSelected = { index ->
                timeframe = Timeframe.entries[index]
                asset?.let { listener?.onTimeframeSelected(it, timeframe) }
            }
        }
        addView(
            timeframeControl,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                // §9.3 draws this rail at 48 dp. Built at 56 dp for the same reason
                // PagedScrollRail was: 48 dp misses §4's touch minimum, and a six-cell control is
                // already only ~90 dp per cell horizontally.
                resources.getDimensionPixelSize(EinkR.dimen.ink_touch_min),
            ).also {
                val m = resources.getDimensionPixelSize(EinkR.dimen.ink_screen_margin)
                it.setMargins(m, resources.getDimensionPixelSize(EinkR.dimen.ink_space_2), m, 0)
            },
        )

        val chartStack = FrameLayout(context).apply {
            val m = resources.getDimensionPixelSize(EinkR.dimen.ink_screen_margin)
            setPadding(m, resources.getDimensionPixelSize(EinkR.dimen.ink_space_4), m, 0)
        }
        chartStack.addView(
            chart,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        chartStack.addView(
            chartState,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        addView(chartStack, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        addView(divider(), LayoutParams(LayoutParams.MATCH_PARENT, dividerHeight()))

        statsView.apply {
            setTextAppearance(EinkR.style.TextAppearance_InkDeck_MonoUi)
            val m = resources.getDimensionPixelSize(EinkR.dimen.ink_screen_margin)
            setPadding(m, resources.getDimensionPixelSize(EinkR.dimen.ink_space_2), m,
                resources.getDimensionPixelSize(EinkR.dimen.ink_space_2))
        }
        addView(statsView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(divider(), LayoutParams(LayoutParams.MATCH_PARENT, dividerHeight()))

        attributionView.apply {
            setTextAppearance(EinkR.style.TextAppearance_InkDeck_Caption)
            val m = resources.getDimensionPixelSize(EinkR.dimen.ink_screen_margin)
            setPadding(m, resources.getDimensionPixelSize(EinkR.dimen.ink_space_2), m,
                resources.getDimensionPixelSize(EinkR.dimen.ink_space_2))
        }
        addView(attributionView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun buildHeader(): View {
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            EinkIconButton(context).apply {
                setIconResource(R.drawable.ic_market_back)
                contentDescription = context.getString(R.string.market_back)
                setOnClickListener { listener?.onClose() }
            },
            LayoutParams(
                resources.getDimensionPixelSize(EinkR.dimen.ink_touch_min),
                resources.getDimensionPixelSize(EinkR.dimen.ink_touch_min),
            ),
        )
        titleView.setTextAppearance(EinkR.style.TextAppearance_InkDeck_Title1)
        titleView.maxLines = 1
        header.addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        sourceView.apply {
            setTextAppearance(EinkR.style.TextAppearance_InkDeck_Caption)
            gravity = Gravity.END
            maxLines = 1
        }
        header.addView(
            sourceView,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.marginEnd = resources.getDimensionPixelSize(EinkR.dimen.ink_screen_margin)
            },
        )
        return header
    }

    private fun buildPriceBlock(): View {
        val block = LinearLayout(context).apply {
            orientation = VERTICAL
            val m = resources.getDimensionPixelSize(EinkR.dimen.ink_screen_margin)
            setPadding(m, resources.getDimensionPixelSize(EinkR.dimen.ink_space_2), m,
                resources.getDimensionPixelSize(EinkR.dimen.ink_space_2))
        }
        priceView.apply {
            setTextAppearance(EinkR.style.TextAppearance_InkDeck_Display)
            maxLines = 1
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 24, 34, 2, android.util.TypedValue.COMPLEX_UNIT_SP,
            )
        }
        changeView.setTextAppearance(EinkR.style.TextAppearance_InkDeck_BodyLarge)
        block.addView(priceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        block.addView(changeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return block
    }

    /** Open on [asset]. [timeframe] is deliberately not reset, so the user's pick survives. */
    fun open(asset: MarketAsset, snapshot: MarketSnapshot) {
        this.asset = asset
        titleView.text = asset.display
        bindQuote(snapshot)
        showChartLoading()
    }

    fun bindQuote(snapshot: MarketSnapshot) {
        val quote = snapshot.quote
        sourceView.text = when {
            snapshot.unofficial -> context.getString(R.string.market_unofficial_short)
            snapshot.attribution != null -> snapshot.attribution
            else -> ""
        }
        if (quote == null) {
            priceView.text = MarketFormat.EM_DASH
            changeView.text = snapshot.error ?: context.getString(R.string.market_error_generic)
            statsView.text = ""
            return
        }
        priceView.text = MarketFormat.price(quote.last)
        changeView.text = context.getString(
            R.string.market_change_line,
            MarketFormat.changeLine(quote),
            snapshot.sparkSpan.orEmpty(),
        )
        priceView.contentDescription = context.getString(
            R.string.market_card_desc,
            asset?.display.orEmpty(),
            MarketFormat.price(quote.last),
            MarketFormat.changeSpoken(quote),
        )
        statsView.text = context.getString(
            R.string.market_stats,
            MarketFormat.price(quote.open),
            MarketFormat.price(quote.high),
            MarketFormat.price(quote.low),
            MarketFormat.volume(quote.volume),
        )
    }

    fun showChartLoading() {
        chart.visibility = GONE
        chartState.visibility = VISIBLE
        chartState.show(
            EmptyStateView.State.LOADING,
            context.getString(R.string.market_loading),
        )
        // The bar is stepped, not animated (§5.7). Two of five steps says "started, not stalled"
        // without the caller having to pretend to know progress it cannot measure.
        chartState.setLoadingProgress(2)
    }

    fun showChart(result: MarketRepository.CandleResult, online: Boolean) {
        val a = asset ?: return
        if (result.candles.isEmpty()) {
            chart.visibility = GONE
            chartState.visibility = VISIBLE
            chartState.show(
                state = if (!online) EmptyStateView.State.OFFLINE else EmptyStateView.State.ERROR,
                title = result.error ?: context.getString(R.string.market_error_no_data, a.display),
                detail = if (result.unofficial) {
                    context.getString(R.string.market_unofficial_detail)
                } else {
                    null
                },
                actionLabel = context.getString(EinkR.string.ink_retry),
                onAction = { listener?.onRetry(a, timeframe) },
            )
        } else {
            chartState.visibility = GONE
            chart.visibility = VISIBLE
            chart.setCandles(
                result.candles,
                context.getString(
                    R.string.market_chart_desc,
                    a.display,
                    timeframe.label,
                    result.candles.size,
                ),
            )
        }

        // Terms compliance: whichever provider actually served is named, and the fallback chain
        // means that is not always the one the section header advertises.
        attributionView.text = result.attribution?.let {
            context.getString(R.string.market_attribution, it)
        } ?: context.getString(R.string.market_attribution_none)
    }

    private fun divider(): View = View(context).apply {
        setBackgroundColor(EinkTheme.ink200(context))
    }

    private fun dividerHeight(): Int = resources.getDimensionPixelSize(EinkR.dimen.ink_divider)
}
