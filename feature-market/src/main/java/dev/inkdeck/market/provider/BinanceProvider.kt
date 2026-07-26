package dev.inkdeck.market.provider

import dev.inkdeck.market.data.Candle
import dev.inkdeck.market.data.MarketUnavailable
import dev.inkdeck.market.data.Quote
import dev.inkdeck.market.data.Timeframe
import org.json.JSONArray
import org.json.JSONObject

/**
 * Binance public REST — Plan.md §5.2's "documented & stable, best of the three".
 *
 * No key, no header, published rate limits, and a schema that has not moved in years. It is the
 * reference implementation of [MarketProvider]: if a new adapter looks unlike this one, the
 * difference should be forced by the source and commented.
 *
 * The public method is a one-line hop onto the IO dispatcher and the work lives in a private
 * blocking twin. That split is repeated in every adapter here — see [ProviderSupport.onIo] for
 * why the parsing has to be off the caller's dispatcher too, not just the socket read.
 */
class BinanceProvider : MarketProvider {

    override val id: String = ID
    override val attribution: String = "Binance public API"

    override suspend fun quote(symbol: String): Quote =
        ProviderSupport.onIo { blockingQuote(symbol) }

    override suspend fun candles(symbol: String, tf: Timeframe, n: Int): List<Candle> =
        ProviderSupport.onIo { blockingCandles(symbol, tf, n) }

    private fun blockingQuote(symbol: String): Quote {
        val body = ProviderSupport.fetch(
            NAME,
            "$BASE/api/v3/ticker/24hr?symbol=${symbol.uppercase()}",
        )
        val o = runCatching { JSONObject(body) }
            .getOrElse { throw MarketUnavailable("$NAME sent an unreadable quote", it) }

        // Binance sends every numeric as a JSON *string*. `optDouble` on a string field returns
        // the fallback, not the parsed number, so each one goes through the text accessor first.
        return Quote(
            symbol = symbol,
            last = o.num("lastPrice"),
            change = o.num("priceChange"),
            changePct = o.num("priceChangePercent"),
            volume = o.num("volume"),
            sourceTimeMs = o.optLong("closeTime", 0L),
            open = o.num("openPrice"),
            high = o.num("highPrice"),
            low = o.num("lowPrice"),
        )
    }

    private fun blockingCandles(symbol: String, tf: Timeframe, n: Int): List<Candle> {
        val interval = ProviderSupport.resolutionFor(tf.stepSeconds, INTERVALS)
        val body = ProviderSupport.fetch(
            NAME,
            "$BASE/api/v3/klines?symbol=${symbol.uppercase()}&interval=$interval&limit=$n",
        )
        val rows = runCatching { JSONArray(body) }
            .getOrElse { throw MarketUnavailable("$NAME sent unreadable candles", it) }

        // Each kline is a positional array, not an object:
        //   [ openTime, open, high, low, close, volume, closeTime, ... ]
        val out = ArrayList<Candle>(rows.length())
        for (i in 0 until rows.length()) {
            val k = rows.optJSONArray(i) ?: continue
            if (k.length() < 6) continue
            val open = k.optString(1).toDoubleOrNull() ?: continue
            val high = k.optString(2).toDoubleOrNull() ?: continue
            val low = k.optString(3).toDoubleOrNull() ?: continue
            val close = k.optString(4).toDoubleOrNull() ?: continue
            out += Candle(
                openTimeMs = k.optLong(0),
                open = open,
                high = high,
                low = low,
                close = close,
                volume = k.optString(5).toDoubleOrNull() ?: 0.0,
            )
        }
        return out
    }

    private fun JSONObject.num(key: String): Double =
        optString(key).toDoubleOrNull() ?: optDouble(key, Double.NaN)

    companion object {
        const val ID = "binance"
        private const val NAME = "Binance"
        private const val BASE = "https://api.binance.com"

        /** Ascending by seconds. Only the widths [Timeframe] can ask for are listed. */
        private val INTERVALS = listOf(
            60L to "1m",
            300L to "5m",
            900L to "15m",
            3600L to "1h",
            7200L to "2h",
            28800L to "8h",
            86400L to "1d",
            604800L to "1w",
        )
    }
}
