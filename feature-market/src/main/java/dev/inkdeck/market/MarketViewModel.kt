package dev.inkdeck.market

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.inkdeck.market.data.Timeframe
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the market state across configuration changes and, more importantly, across a tab switch:
 * `MainActivity` swaps tabs with hide/show, but a rotation still recreates the fragment, and
 * re-fetching every widget because the user turned the device sideways would be rude to both the
 * rate limits and the battery.
 *
 * There is no `Flow` from the repository. The sources are polled — nothing pushes — so a
 * `StateFlow` the ViewModel writes after each pass is the honest shape.
 */
class MarketViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = MarketPrefs(app)
    val providers = MarketProviders(app)

    private val repository = MarketRepository(app)

    private val _snapshots = MutableStateFlow<List<MarketSnapshot>>(emptyList())
    val snapshots: StateFlow<List<MarketSnapshot>> = _snapshots.asStateFlow()

    private val _candles = MutableStateFlow<MarketRepository.CandleResult?>(null)
    val candles: StateFlow<MarketRepository.CandleResult?> = _candles.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Wall clock of the last completed pass, for the `⟳ HH:mm` readout. 0 = never. */
    var lastPassMs: Long = 0L
        private set

    private var refreshJob: Job? = null
    private var candleJob: Job? = null

    fun isOnline(): Boolean = repository.isOnline()

    /** Re-read the picker's selection and show whatever is already cached, without fetching. */
    fun syncSelection() {
        val assets = prefs.enabledAssets()
        repository.retainOnly(assets)
        _snapshots.value = assets.map { repository.cached(it) }
    }

    /**
     * One refresh pass over every visible widget.
     *
     * Sequential, not parallel. Two ~1 GHz cores and one shared OkHttp dispatcher mean six
     * concurrent TLS handshakes do not finish six times faster, they finish at roughly the same
     * time and stall the main thread's share of the CPU on the way; and hitting one provider with
     * three simultaneous requests is the fastest route to a rate limit. Each card is published as
     * it lands, so the grid fills in progressively rather than all at once.
     */
    fun refreshAll() {
        if (refreshJob?.isActive == true) return
        val assets = prefs.enabledAssets()
        if (assets.isEmpty()) {
            _snapshots.value = emptyList()
            return
        }

        refreshJob = viewModelScope.launch {
            _refreshing.value = true
            // Mark everything that has never loaded as loading, so first run shows §5.7's
            // loading state instead of an error it has not earned yet.
            _snapshots.value = assets.map { asset ->
                repository.cached(asset).let { if (it.quote == null) it.copy(loading = true) else it }
            }

            val results = ArrayList<MarketSnapshot>(assets.size)
            for (asset in assets) {
                results += repository.refresh(asset)
                // Publish a copy of the running list plus the not-yet-fetched tail, so the grid
                // never briefly loses the cards below the one in flight.
                _snapshots.value = results + assets.drop(results.size).map { repository.cached(it) }
            }

            lastPassMs = System.currentTimeMillis()
            _refreshing.value = false
        }
    }

    fun snapshotFor(asset: MarketAsset): MarketSnapshot =
        _snapshots.value.firstOrNull { it.asset.id == asset.id } ?: repository.cached(asset)

    fun loadCandles(asset: MarketAsset, tf: Timeframe) {
        candleJob?.cancel()
        _candles.value = null
        candleJob = viewModelScope.launch {
            _candles.value = repository.candles(asset, tf)
        }
    }

    fun clearCandles() {
        candleJob?.cancel()
        _candles.value = null
    }

    /** 2× the interval, per design.md §9.1. 0 in manual mode: the user owns the clock there. */
    fun staleAfterMs(): Long = prefs.refreshMinutes * 2L * 60_000L
}
