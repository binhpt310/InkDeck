package dev.inkdeck.market

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.inkdeck.eink.EinkAnim
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.R as EinkR
import dev.inkdeck.eink.refresh.EinkRefresher
import dev.inkdeck.eink.refresh.RefresherHost
import dev.inkdeck.eink.widget.EinkIconButton
import dev.inkdeck.eink.widget.EinkScrollView
import dev.inkdeck.eink.widget.EmptyStateView
import dev.inkdeck.eink.widget.PagedScrollRail
import dev.inkdeck.market.data.Timeframe
import dev.inkdeck.market.ui.MarketGridView
import dev.inkdeck.market.ui.SymbolDetailView
import dev.inkdeck.market.ui.WidgetPickerView
import kotlinx.coroutines.launch

/**
 * The Market tab — design.md §9, Plan.md §5.2.
 *
 * ### Refresh cadence
 *
 * Manual (the `⟳` button) plus automatic every N minutes **while this tab is on screen and the
 * screen is on**, never in the background. Three notes on how that is enforced:
 *
 *  - No `AlarmManager`. Plan.md §5.1b: the ROM refuses to schedule alarms for this package at
 *    all. A `Handler` post is the right tool anyway — it deliberately does *not* wake a sleeping
 *    device, which is exactly the behaviour wanted here.
 *  - Tabs are swapped with hide/show, not replace, so `onPause` never fires on a tab switch.
 *    Visibility is `isResumed && !isHidden`, and [onHiddenChanged] is overridden to catch it.
 *  - Rather than polling for the screen coming back on, becoming visible triggers a catch-up
 *    refresh if the data is older than one interval. A `SCREEN_ON` receiver would fire while the
 *    app is backgrounded, which is the one thing this must not do.
 *
 * ### Refresh classification (design.md §13)
 *
 * An auto-refresh is `[P]`, and only for the cards that actually changed — [MarketGridView.update]
 * returns that count and nothing is noted when it is zero. Timeframe change, picker open/close and
 * detail open/close are all `[F]`.
 */
class MarketFragment : Fragment(R.layout.fragment_market) {

    private val viewModel: MarketViewModel by viewModels()

    private lateinit var lastRefresh: android.widget.TextView
    private lateinit var scroller: EinkScrollView
    private lateinit var grid: MarketGridView
    private lateinit var rail: PagedScrollRail
    private lateinit var empty: EmptyStateView
    private lateinit var picker: WidgetPickerView
    private lateinit var detail: SymbolDetailView

    private val handler = Handler(Looper.getMainLooper())
    private val autoTick = Runnable { onAutoTick() }

    private var openAsset: MarketAsset? = null

    private val refresher: EinkRefresher?
        get() = (activity as? RefresherHost)?.refresher

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        lastRefresh = view.findViewById(R.id.lastRefresh)
        scroller = view.findViewById(R.id.scroller)
        grid = view.findViewById(R.id.grid)
        rail = view.findViewById(R.id.rail)
        empty = view.findViewById(R.id.empty)
        picker = view.findViewById(R.id.picker)
        detail = view.findViewById(R.id.detail)

        view.findViewById<EinkIconButton>(R.id.actionRefresh).apply {
            setIconResource(R.drawable.ic_market_refresh)
            iconTint = EinkTheme.ink900(requireContext())
            setOnClickListener { manualRefresh() }
        }
        view.findViewById<EinkIconButton>(R.id.actionWidgets).apply {
            setIconResource(R.drawable.ic_market_widgets)
            iconTint = EinkTheme.ink900(requireContext())
            setOnClickListener { openPicker() }
        }

        // Cards must stop short of the rail or their right edge sits underneath it.
        scroller.setPadding(
            0, 0,
            resources.getDimensionPixelSize(EinkR.dimen.ink_rail_width),
            0,
        )
        rail.refresher = refresher
        rail.attach(scroller)

        grid.onCardClick = { openDetail(it) }

        picker.refresher = refresher
        picker.listener = pickerListener
        picker.bind(viewModel.prefs, viewModel.providers)

        detail.listener = detailListener

        EinkAnim.strip(view)

        viewModel.syncSelection()
        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.snapshots.collect { render(it) } }
                launch { viewModel.refreshing.collect { renderClock(it) } }
                launch {
                    viewModel.candles.collect { result ->
                        if (result != null && detail.visibility == View.VISIBLE) {
                            detail.showChart(result, viewModel.isOnline())
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    private fun render(snapshots: List<MarketSnapshot>) {
        val assets = snapshots.map { it.asset }
        grid.setAssets(assets)

        val changed = grid.update(snapshots, viewModel.staleAfterMs())
        renderEmptyState(snapshots)

        // design.md §13: market auto-refresh is [P], and only for cards whose values changed.
        // Zero changed cards must cost nothing at all — noting a partial anyway would spend the
        // ghost budget on a screen that is pixel-identical to the one already on the panel.
        if (changed > 0) {
            refresher?.notePartial(SURFACE_GRID, "market-cards-changed=$changed")
        }

        openAsset?.let { detail.bindQuote(viewModel.snapshotFor(it)) }
    }

    /** design.md §5.7 — all four states, on the grid as a whole. */
    private fun renderEmptyState(snapshots: List<MarketSnapshot>) {
        val loading = snapshots.isNotEmpty() && snapshots.all { it.quote == null && it.loading }
        val allFailed = snapshots.isNotEmpty() &&
            snapshots.all { it.quote == null && !it.loading }

        val state = when {
            snapshots.isEmpty() -> EmptyStateView.State.EMPTY
            loading -> EmptyStateView.State.LOADING
            allFailed && !viewModel.isOnline() -> EmptyStateView.State.OFFLINE
            allFailed -> EmptyStateView.State.ERROR
            else -> null
        }

        if (state == null) {
            empty.visibility = View.GONE
            scroller.visibility = View.VISIBLE
            rail.visibility = View.VISIBLE
            return
        }

        empty.visibility = View.VISIBLE
        scroller.visibility = View.GONE
        rail.visibility = View.GONE

        when (state) {
            EmptyStateView.State.EMPTY -> empty.show(
                state, getString(R.string.market_empty_title),
                getString(R.string.market_empty_detail),
                getString(R.string.market_empty_action),
            ) { openPicker() }

            EmptyStateView.State.LOADING -> {
                empty.show(state, getString(R.string.market_loading),
                    getString(R.string.market_loading_detail))
                // Stepped, never animated (§5.7). The step is how many cards have answered, which
                // is a real measurement rather than a fake progress bar.
                val done = viewModel.snapshots.value.count { it.quote != null }
                empty.setLoadingProgress(
                    if (snapshots.isEmpty()) 1
                    else (done * 5 / snapshots.size).coerceIn(1, 5)
                )
            }

            EmptyStateView.State.OFFLINE -> empty.show(
                state, getString(R.string.market_offline_title),
                getString(R.string.market_offline_detail),
                getString(EinkR.string.ink_retry),
            ) { manualRefresh() }

            EmptyStateView.State.ERROR -> empty.show(
                state, getString(R.string.market_all_failed_title),
                snapshots.firstNotNullOfOrNull { it.error }
                    ?: getString(R.string.market_all_failed_detail),
                getString(EinkR.string.ink_retry),
            ) { manualRefresh() }
        }
    }

    private fun renderClock(refreshing: Boolean) {
        lastRefresh.text = when {
            refreshing -> getString(R.string.market_refreshing)
            viewModel.lastPassMs > 0L ->
                getString(R.string.market_last_refresh, MarketFormat.clock(viewModel.lastPassMs))
            else -> getString(R.string.market_never_refreshed)
        }
    }

    // ------------------------------------------------------------------ refresh cadence

    private fun manualRefresh() {
        viewModel.refreshAll()
        scheduleAuto()
    }

    private fun isTabVisible(): Boolean = isResumed && !isHidden

    private fun isScreenOn(): Boolean {
        val pm = context?.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
            ?: return true
        return pm.isInteractive
    }

    private fun scheduleAuto() {
        handler.removeCallbacks(autoTick)
        val minutes = viewModel.prefs.refreshMinutes
        if (minutes <= 0 || !isTabVisible()) return
        handler.postDelayed(autoTick, minutes * 60_000L)
    }

    private fun onAutoTick() {
        // Both conditions re-checked at fire time, not only at schedule time: the screen can go
        // off inside the interval, and a poll against a dark screen is battery spent on pixels
        // nobody is looking at.
        if (isTabVisible() && isScreenOn()) viewModel.refreshAll()
        scheduleAuto()
    }

    /**
     * Refresh on becoming visible if the data has already aged past one interval. This is what
     * replaces a `SCREEN_ON` receiver: the panel holds its last image with the power off, so what
     * the user sees when they pick the device up is exactly what was there when they put it down,
     * and it should be brought up to date before they read it.
     */
    private fun onBecameVisible() {
        val minutes = viewModel.prefs.refreshMinutes
        val age = System.currentTimeMillis() - viewModel.lastPassMs
        val stale = viewModel.lastPassMs == 0L || (minutes > 0 && age > minutes * 60_000L)
        if (stale) viewModel.refreshAll()
        scheduleAuto()
    }

    override fun onResume() {
        super.onResume()
        if (isTabVisible()) onBecameVisible()
    }

    override fun onPause() {
        handler.removeCallbacks(autoTick)
        super.onPause()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // Tabs are hide/show, so this — not onPause — is where a tab switch shows up.
        if (hidden) handler.removeCallbacks(autoTick) else if (isResumed) onBecameVisible()
    }

    override fun onDestroyView() {
        handler.removeCallbacks(autoTick)
        super.onDestroyView()
    }

    // ------------------------------------------------------------------ overlays

    private val pickerListener = object : WidgetPickerView.Listener {
        override fun onSelectionChanged() {
            viewModel.syncSelection()
            viewModel.refreshAll()
        }

        override fun onRefreshIntervalChanged(minutes: Int) {
            scheduleAuto()
        }

        override fun onClose() = closePicker()
    }

    private val detailListener = object : SymbolDetailView.Listener {
        override fun onTimeframeSelected(asset: MarketAsset, tf: Timeframe) {
            detail.showChartLoading()
            viewModel.loadCandles(asset, tf)
            // §13: a chart timeframe change is [F]. The plot area is the whole lower two thirds
            // of the screen and every candle moves, so a partial would ghost the old series
            // through the new one.
            refresher?.flush("market-timeframe=${tf.label}")
        }

        override fun onRetry(asset: MarketAsset, tf: Timeframe) {
            detail.showChartLoading()
            viewModel.loadCandles(asset, tf)
        }

        override fun onClose() = closeDetail()
    }

    private fun openPicker() {
        picker.bind(viewModel.prefs, viewModel.providers)
        picker.visibility = View.VISIBLE
        refresher?.resetSurface(SURFACE_GRID)
        refresher?.flush("market-picker-open")
    }

    private fun closePicker() {
        if (picker.visibility != View.VISIBLE) return
        picker.visibility = View.GONE
        refresher?.flush("market-picker-close")
    }

    private fun openDetail(asset: MarketAsset) {
        openAsset = asset
        detail.open(asset, viewModel.snapshotFor(asset))
        detail.visibility = View.VISIBLE
        viewModel.loadCandles(asset, detail.timeframe)
        refresher?.resetSurface(SURFACE_GRID)
        refresher?.flush("market-detail-open=${asset.id}")
    }

    private fun closeDetail() {
        if (detail.visibility != View.VISIBLE) return
        detail.visibility = View.GONE
        openAsset = null
        viewModel.clearCandles()
        refresher?.flush("market-detail-close")
    }

    /**
     * Called by the host's Back handling; true means the press was consumed. Mirrors
     * `TasksFragment.closeEditorIfOpen`. The coordinator has to wire this in `MainActivity`, or
     * Back will leave the app from on top of an overlay.
     */
    fun closeOverlayIfOpen(): Boolean {
        if (detail.visibility == View.VISIBLE) {
            closeDetail()
            return true
        }
        if (picker.visibility == View.VISIBLE) {
            closePicker()
            return true
        }
        return false
    }

    private companion object {
        const val SURFACE_GRID = "market-grid"
    }
}
