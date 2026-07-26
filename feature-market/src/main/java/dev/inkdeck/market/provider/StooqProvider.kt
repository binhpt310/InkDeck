package dev.inkdeck.market.provider

import dev.inkdeck.market.data.Candle
import dev.inkdeck.market.data.MarketUnavailable
import dev.inkdeck.market.data.Quote
import dev.inkdeck.market.data.Timeframe
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Stooq CSV — the no-key US fallback from Plan.md §5.2. Delayed, undocumented, and trivially
 * simple, which is a fair trade for something that works with nothing configured.
 *
 * Two endpoints, both CSV:
 *   - `/q/l/`  one line, the latest OHLCV for a symbol
 *   - `/q/d/l/` the full history at daily / weekly / monthly resolution
 *
 * **Only daily and coarser exist here.** Asking for `1H` gets daily candles. That is a property of
 * the source, and the chart says so rather than pretending: see [dev.inkdeck.market.MarketRepository],
 * which records which provider actually served, and the attribution line the UI shows.
 *
 * [quote] deliberately uses the history endpoint rather than the one-line one. The one-liner has
 * no previous close in any field combination Stooq offers, so a change computed from it would be
 * close-minus-open — which reads as 0.00% on a gap day and is worse than useless on a market
 * dashboard. Two rows of history give the real number.
 */
class StooqProvider : MarketProvider {

    override val id: String = ID
    override val attribution: String = "Stooq (delayed)"

    override suspend fun quote(symbol: String): Quote =
        ProviderSupport.quoteFromCandles(symbol, candles(symbol, Timeframe.Y1, 4))

    override suspend fun candles(symbol: String, tf: Timeframe, n: Int): List<Candle>  = ProviderSupport.onIo {
            val interval = ProviderSupport.resolutionFor(tf.stepSeconds, INTERVALS)
            val body = ProviderSupport.fetch(
                NAME,
                "$BASE/q/d/l/?s=${stooqSymbol(symbol)}&i=$interval",
            )

            val lines = body.lineSequence().filter { it.isNotBlank() }.toList()
            // A symbol Stooq does not know answers 200 with the single word "No data".
            if (lines.size < 2) throw MarketUnavailable("$NAME has no data for $symbol")

            val header = lines.first().split(',').map { it.trim().lowercase() }
            val iDate = header.indexOf("date")
            val iOpen = header.indexOf("open")
            val iHigh = header.indexOf("high")
            val iLow = header.indexOf("low")
            val iClose = header.indexOf("close")
            val iVolume = header.indexOf("volume")
            if (iDate < 0 || iClose < 0) throw MarketUnavailable("$NAME changed its CSV columns")

            // History is oldest-first and can be twenty years long. Only the tail is ever drawn, so
            // only the tail is parsed — on two ~1 GHz cores, parsing 5 000 rows to throw away 4 900
            // of them is a visible stall.
            val firstRow = (lines.size - n).coerceAtLeast(1)
            val out = ArrayList<Candle>(lines.size - firstRow)
            for (i in firstRow until lines.size) {
                val f = lines[i].split(',')
                if (f.size <= iClose) continue
                val close = f[iClose].toDoubleOrNull() ?: continue
                out += Candle(
                    openTimeMs = parseDate(f[iDate]),
                    open = f.getOrNull(iOpen)?.toDoubleOrNull() ?: close,
                    high = f.getOrNull(iHigh)?.toDoubleOrNull() ?: close,
                    low = f.getOrNull(iLow)?.toDoubleOrNull() ?: close,
                    close = close,
                    volume = f.getOrNull(iVolume)?.toDoubleOrNull() ?: 0.0,
                )
            }
            if (out.isEmpty()) throw MarketUnavailable("$NAME has no data for $symbol")
            return@onIo out
    }

    /** Stooq wants a market suffix. Anything already carrying a dot is passed through untouched. */
    private fun stooqSymbol(symbol: String): String {
        val s = symbol.lowercase()
        return if (s.contains('.')) s else "$s.us"
    }

    private fun parseDate(raw: String): Long =
        runCatching { DATE.get()!!.parse(raw.trim())?.time ?: 0L }.getOrDefault(0L)

    companion object {
        const val ID = "stooq"
        private const val NAME = "Stooq"
        private const val BASE = "https://stooq.com"

        private val INTERVALS = listOf(
            86400L to "d",
            604800L to "w",
            2592000L to "m",
        )

        // SimpleDateFormat is not thread-safe and candles() can run on two IO threads at once.
        private val DATE = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() =
                SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
        }
    }
}
