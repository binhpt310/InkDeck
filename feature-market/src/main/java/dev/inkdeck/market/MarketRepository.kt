package dev.inkdeck.market

import android.content.Context
import android.net.ConnectivityManager
import dev.inkdeck.market.data.Candle
import dev.inkdeck.market.data.MarketKeyMissing
import dev.inkdeck.market.data.Quote
import dev.inkdeck.market.data.Timeframe
import dev.inkdeck.market.provider.MarketProvider
import kotlinx.coroutines.CancellationException

/**
 * What one widget knows about itself at one moment.
 *
 * Immutable, and compared by value in [MarketGridView] so that an auto-refresh only repaints the
 * cards whose numbers actually moved — design.md §13 classifies market auto-refresh as `[P]`
 * precisely on that condition.
 *
 * [spark] is a plain `FloatArray` of closes rather than the `List<Candle>` it came from: the
 * sparkline draws closes and nothing else, and on this device holding 48 boxed candles per card
 * for six cards to draw 48 floats each is memory spent on nothing.
 */
data class MarketSnapshot(
    val asset: MarketAsset,
    val quote: Quote? = null,
    val spark: FloatArray? = null,
    /**
     * How much time [spark] actually covers, e.g. `24h` or `96d`. Derived from the candle
     * timestamps rather than assumed: a daily-only source like Stooq answers the same request
     * with three months of bars, and captioning that "24h" would be a plain untruth.
     */
    val sparkSpan: String? = null,
    /** Which provider actually served — the fallback chain means it is not always the first. */
    val attribution: String? = null,
    val unofficial: Boolean = false,
    /** Wall clock of the last **successful** fetch, 0 if there has never been one. */
    val fetchedAtMs: Long = 0L,
    /** Non-null when the most recent attempt failed, whether or not [quote] is stale-but-present. */
    val error: String? = null,
    val loading: Boolean = false,
) {
    enum class Status { LOADING, OK, STALE, ERROR }

    /**
     * [staleAfterMs] is 2× the refresh interval per design.md §9.1 — one missed cycle is a blip,
     * two is something the user should be told about.
     */
    fun status(nowMs: Long, staleAfterMs: Long): Status = when {
        loading && quote == null -> Status.LOADING
        quote == null -> Status.ERROR
        error != null -> Status.STALE
        staleAfterMs > 0 && nowMs - fetchedAtMs > staleAfterMs -> Status.STALE
        else -> Status.OK
    }

    // Data classes generate identity-based equals for arrays, which would make every snapshot
    // unequal to the last and force a repaint of every card on every tick — the exact thing §13
    // says not to do. Both overridden to compare contents.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MarketSnapshot) return false
        return asset == other.asset &&
            quote == other.quote &&
            sparkSpan == other.sparkSpan &&
            attribution == other.attribution &&
            unofficial == other.unofficial &&
            fetchedAtMs == other.fetchedAtMs &&
            error == other.error &&
            loading == other.loading &&
            spark.contentEqualsOrBothNull(other.spark)
    }

    override fun hashCode(): Int {
        var h = asset.hashCode()
        h = 31 * h + (quote?.hashCode() ?: 0)
        h = 31 * h + (spark?.contentHashCode() ?: 0)
        h = 31 * h + (sparkSpan?.hashCode() ?: 0)
        h = 31 * h + (attribution?.hashCode() ?: 0)
        h = 31 * h + fetchedAtMs.hashCode()
        h = 31 * h + (error?.hashCode() ?: 0)
        h = 31 * h + loading.hashCode()
        return h
    }

    private fun FloatArray?.contentEqualsOrBothNull(other: FloatArray?): Boolean =
        if (this == null || other == null) this == null && other == null
        else this.contentEquals(other)
}

/**
 * Fetches through the fallback chain and remembers the last good answer.
 *
 * The cache is the whole reason offline degrades gracefully: a failed refresh keeps the previous
 * [MarketSnapshot.quote] and only adds an [MarketSnapshot.error], which the card renders as
 * design.md §5.7's stale state with an `⌛ as of HH:mm` line instead of blanking.
 *
 * Nothing is persisted. A snapshot from the last time the app ran is old enough that showing it
 * as "current but stale" would be a worse lie than an honest loading state.
 */
class MarketRepository(context: Context) {

    private val appContext = context.applicationContext
    private val providers = MarketProviders(appContext)
    private val cache = HashMap<String, MarketSnapshot>()

    @Synchronized
    fun cached(asset: MarketAsset): MarketSnapshot =
        cache[asset.id] ?: MarketSnapshot(asset)

    @Synchronized
    private fun store(snapshot: MarketSnapshot) {
        cache[snapshot.asset.id] = snapshot
    }

    /** Drop cards the user turned off, so their candles do not sit in the heap forever. */
    @Synchronized
    fun retainOnly(assets: Collection<MarketAsset>) {
        val keep = assets.mapTo(HashSet()) { it.id }
        cache.keys.retainAll(keep)
    }

    fun isOnline(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        @Suppress("DEPRECATION") // getActiveNetwork/NetworkCapabilities is API 23+, but this
        // device's ROM reports capabilities unreliably on its own hotspot; the legacy NetworkInfo
        // path is the one that has been observed to agree with whether requests actually work.
        return cm.activeNetworkInfo?.isConnected == true
    }

    /**
     * Refresh one widget. Never throws — the failure *is* the result, as a stale or error
     * snapshot on top of whatever was cached.
     */
    suspend fun refresh(asset: MarketAsset): MarketSnapshot {
        val previous = cached(asset)
        val chain = providers.chainFor(asset.category)

        var lastError: String? = null
        for (provider in chain) {
            val attempt = tryProvider(provider, asset, previous)
            if (attempt != null) {
                store(attempt)
                return attempt
            }
            lastError = providerError
        }

        val failed = previous.copy(
            loading = false,
            error = lastError ?: appContext.getString(R.string.market_error_generic),
            unofficial = providers.isUnofficial(asset.category),
        )
        store(failed)
        return failed
    }

    /**
     * Set by [tryProvider] instead of returned alongside the snapshot. A `Pair<Snapshot?, String>`
     * return would read worse at both call sites; this class is confined to one coroutine per
     * refresh pass and the field is only read immediately after the call that writes it.
     */
    private var providerError: String? = null

    private suspend fun tryProvider(
        provider: MarketProvider,
        asset: MarketAsset,
        previous: MarketSnapshot,
    ): MarketSnapshot? = try {
        val quote = provider.quote(asset.symbol)
        // Sparkline history is best-effort: a card with a price and no line is far more useful
        // than no card. Binance serves both from one host so this rarely splits, but Finnhub's
        // free tier serves quotes and refuses candles, which is exactly this case.
        val history = runCatching {
            provider.candles(asset.symbol, SPARK_TIMEFRAME, SPARK_POINTS)
        }.getOrNull()
        val spark = history?.toCloses() ?: previous.spark
        val span = history?.let { MarketFormat.spanLabel(it) } ?: previous.sparkSpan

        providerError = null
        MarketSnapshot(
            asset = asset,
            quote = quote,
            spark = spark,
            sparkSpan = span,
            attribution = provider.attribution,
            unofficial = provider.unofficial,
            fetchedAtMs = System.currentTimeMillis(),
            error = null,
            loading = false,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: MarketKeyMissing) {
        providerError = e.message
        null
    } catch (e: Exception) {
        // Deliberately no Log of the exception body: a Finnhub failure can echo the request, and
        // the request carries the token header. The message is provider-named and safe.
        providerError = e.message ?: appContext.getString(R.string.market_error_generic)
        null
    }

    /**
     * Candles for the detail chart. Returns the provider that served alongside them so the screen
     * can attribute honestly — the chain means "Finnhub" on the quote and "Stooq" on the chart is
     * a normal outcome, and claiming otherwise would be a terms problem, not a cosmetic one.
     */
    suspend fun candles(asset: MarketAsset, tf: Timeframe): CandleResult {
        val n = tf.defaultCount.coerceAtMost(MAX_CANDLES)
        var lastError: String? = null
        for (provider in providers.chainFor(asset.category)) {
            try {
                val candles = provider.candles(asset.symbol, tf, n)
                if (candles.isNotEmpty()) {
                    return CandleResult(candles, provider.attribution, provider.unofficial, null)
                }
                lastError = appContext.getString(R.string.market_error_no_data, asset.display)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: appContext.getString(R.string.market_error_generic)
            }
        }
        return CandleResult(
            emptyList(),
            null,
            providers.isUnofficial(asset.category),
            lastError ?: appContext.getString(R.string.market_error_generic),
        )
    }

    data class CandleResult(
        val candles: List<Candle>,
        val attribution: String?,
        val unofficial: Boolean,
        val error: String?,
    )

    private fun List<Candle>.toCloses(): FloatArray? {
        if (size < 2) return null
        val out = FloatArray(size)
        for (i in indices) out[i] = this[i].close.toFloat()
        return out
    }

    companion object {
        /**
         * 96 points at [SPARK_TIMEFRAME]'s 15-minute nominal step is one calendar day, which is
         * the window the crypto ticker's change is quoted over — so the line and the percentage
         * agree. Across a ~238 dp card that is 2.5 dp a segment, already at the limit of what the
         * panel resolves after dithering; more points would be parse time spent on detail the
         * hardware cannot show. Only closes are kept, never the candles.
         */
        const val SPARK_POINTS = 96
        private val SPARK_TIMEFRAME = Timeframe.D1

        /** Ceiling for the detail chart too: 96 candles over ~540 dp is 5.6 dp per candle. */
        const val MAX_CANDLES = 96
    }
}
