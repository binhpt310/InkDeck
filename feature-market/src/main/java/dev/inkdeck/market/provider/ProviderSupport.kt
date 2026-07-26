package dev.inkdeck.market.provider

import dev.inkdeck.market.data.Candle
import dev.inkdeck.market.data.MarketUnavailable
import dev.inkdeck.market.data.Quote
import dev.inkdeck.net.InkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The three things every adapter in this package needs and none of them should re-invent.
 *
 * There is no shared base class: the adapters have nothing in common structurally (one reads a
 * JSON object, one reads a JSON array of arrays, one reads CSV), and a base class would only
 * exist to hold these three functions.
 */
internal object ProviderSupport {

    /**
     * Run a whole provider call on the IO dispatcher.
     *
     * Wrapping the *entire* body, not just the socket read, is deliberate. Stooq answers a daily
     * history request with twenty years of CSV; splitting that string and parsing the tail is
     * more work than the request itself, and doing it on the caller's dispatcher — which is Main,
     * because the ViewModel launches there — would drop frames on a 2-core ~1 GHz device that has
     * none to spare. Same for `JSONArray` over 96 klines, and same for the vault unwrap in
     * [FinnhubProvider], which reads a file.
     */
    suspend fun <T> onIo(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    /**
     * Blocking GET, or throw. Call inside [onIo].
     *
     * [InkHttp.getText] returns null on any failure by design — it is built for pollers whose
     * correct response is a stale state. Here that null has to become the thrown
     * [MarketUnavailable] the [MarketProvider] contract promises, and [source] is what the user
     * sees in the error state, so it names the provider rather than the URL: the URL can carry a
     * symbol the user did not type and, for Finnhub, must never carry the key.
     */
    fun fetch(source: String, url: String, headers: Map<String, String> = emptyMap()): String =
        InkHttp.getText(url, headers) ?: throw MarketUnavailable("Can't reach $source")

    /**
     * Pick the closest resolution a provider actually supports for a nominal candle width.
     *
     * [supported] must be sorted ascending by seconds. Rounding *up* rather than to the nearest:
     * a coarser candle over the same span draws fewer bars, and drawing fewer bars than asked is
     * always safer here than drawing more than the memory budget allows.
     */
    fun <T> resolutionFor(stepSeconds: Long, supported: List<Pair<Long, T>>): T {
        for ((seconds, token) in supported) {
            if (stepSeconds <= seconds) return token
        }
        return supported.last().second
    }

    /**
     * Build a [Quote] out of a candle series, for sources that publish history but no ticker.
     *
     * Both VN adapters and the Stooq history path land here. `change` is close-to-previous-close,
     * which is what an exchange means by "day change" — not close-minus-open, which understates a
     * gap. Needs at least two candles; one candle has no reference point and is not a quote.
     */
    fun quoteFromCandles(symbol: String, candles: List<Candle>): Quote {
        if (candles.size < 2) throw MarketUnavailable("No recent data for $symbol")
        val last = candles.last()
        val prev = candles[candles.size - 2]
        val change = last.close - prev.close
        val pct = if (prev.close != 0.0) change / prev.close * 100.0 else 0.0
        return Quote(
            symbol = symbol,
            last = last.close,
            change = change,
            changePct = pct,
            volume = last.volume,
            sourceTimeMs = last.openTimeMs,
            open = last.open,
            high = last.high,
            low = last.low,
        )
    }
}
