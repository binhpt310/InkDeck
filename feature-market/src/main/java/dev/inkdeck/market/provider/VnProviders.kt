package dev.inkdeck.market.provider

import dev.inkdeck.market.data.Candle
import dev.inkdeck.market.data.MarketUnavailable
import dev.inkdeck.market.data.Quote
import dev.inkdeck.market.data.Timeframe
import org.json.JSONArray
import org.json.JSONObject

/*
 * ⚠️  Vietnam market data — Plan.md §5.2, flagged there and flagged again here.
 *
 * There is no free, officially supported Vietnam market API. Both adapters below call endpoints
 * that are **undeclared**: no documentation, no versioning, no deprecation notice, and no promise
 * that they will not start requiring a Referer, an Origin, a cookie or a signed token tomorrow.
 * They are the browser back-ends of two brokerages' own charts.
 *
 * Consequences, all of them deliberate:
 *
 *  - `unofficial = true`, so every widget backed by them carries a permanent `⚠ unoff.` marker
 *    (design.md §9.1). The user is told what they are looking at.
 *  - Parsing is defensive and gives up loudly. A schema change becomes a visible
 *    "unavailable" card with the last good value and an `⌛ as of` line, never a silent zero and
 *    never a crash.
 *  - Effort here is deliberately bounded. Making these robust is not possible — the failure mode
 *    is on the far end. The interface is what makes them replaceable, and the interface is done.
 *
 * When (not if) one breaks: write a new MarketProvider, change one line in MarketCatalog.
 */

/**
 * VNDirect's TradingView UDF chart feed. Serves history only, so the quote is derived from the
 * last two closes — see [ProviderSupport.quoteFromCandles].
 *
 * Handles equities (`FPT`, `VCB`) and indices (`VN30`, `VNINDEX`) through the same path.
 */
class VnDirectProvider : MarketProvider {

    override val id: String = ID
    override val attribution: String = "VNDirect dchart (unofficial)"
    override val unofficial: Boolean = true

    override suspend fun quote(symbol: String): Quote =
        ProviderSupport.quoteFromCandles(symbol, candles(symbol, Timeframe.Y1, 4))

    override suspend fun candles(symbol: String, tf: Timeframe, n: Int): List<Candle> = ProviderSupport.onIo {
                val resolution = ProviderSupport.resolutionFor(tf.stepSeconds, RESOLUTIONS)
                val to = System.currentTimeMillis() / 1000L
                // 3× the nominal span: HOSE trades ~4.5 h a day, five days a week, so an exact window
                // comes back near-empty.
                val from = to - tf.stepSeconds * n * 3

                val body = ProviderSupport.fetch(
                    NAME,
                    "$BASE/dchart/history?symbol=${symbol.uppercase()}&resolution=$resolution" +
                        "&from=$from&to=$to",
                )
                val o = runCatching { JSONObject(body) }
                    .getOrElse { throw MarketUnavailable("$NAME sent an unreadable response", it) }

                // UDF says s:"ok" | "no_data" | "error". Anything else means the shape moved.
                when (o.optString("s")) {
                    "ok" -> Unit
                    "no_data" -> return@onIo emptyList()
                    else -> throw MarketUnavailable("$NAME endpoint changed or refused")
                }

                val t = o.optJSONArray("t") ?: throw MarketUnavailable("$NAME endpoint changed")
                val open = o.optJSONArray("o")
                val high = o.optJSONArray("h")
                val low = o.optJSONArray("l")
                val close = o.optJSONArray("c") ?: throw MarketUnavailable("$NAME endpoint changed")
                val volume = o.optJSONArray("v")

                val count = minOf(t.length(), close.length())
                val first = (count - n).coerceAtLeast(0)
                val out = ArrayList<Candle>(count - first)
                for (i in first until count) {
                    val c = close.optDouble(i)
                    if (c.isNaN()) continue
                    out += Candle(
                        openTimeMs = t.optLong(i) * 1000L,
                        open = open.optDoubleOr(i, c),
                        high = high.optDoubleOr(i, c),
                        low = low.optDoubleOr(i, c),
                        close = c,
                        volume = volume.optDoubleOr(i, 0.0),
                    )
                }
                return@onIo out
    }

    private fun JSONArray?.optDoubleOr(index: Int, fallback: Double): Double {
        val v = this?.optDouble(index, Double.NaN) ?: Double.NaN
        return if (v.isNaN()) fallback else v
    }

    companion object {
        const val ID = "vndirect"
        private const val NAME = "VNDirect"
        private const val BASE = "https://dchart-api.vndirect.com.vn"

        private val RESOLUTIONS = listOf(
            60L to "1",
            300L to "5",
            900L to "15",
            1800L to "30",
            86400L to "60",
            604800L to "D",
            2592000L to "W",
        )
    }
}

/**
 * TCBS `apipubaws` long-term bars — the second unofficial VN source, used as VNDirect's fallback.
 *
 * Two unofficial sources is not redundancy in the usual sense: they can and will break
 * independently, so having both roughly doubles the odds that a VN card shows a number on any
 * given day. Neither is a guarantee and the `⚠ unoff.` marker stays on regardless.
 *
 * Only daily and coarser. TCBS exposes an intraday feed on a different path with a different
 * shape; wiring a second undocumented schema for finer VN candles is not worth the code.
 */
class TcbsProvider : MarketProvider {

    override val id: String = ID
    override val attribution: String = "TCBS (unofficial)"
    override val unofficial: Boolean = true

    override suspend fun quote(symbol: String): Quote =
        ProviderSupport.quoteFromCandles(symbol, candles(symbol, Timeframe.Y1, 4))

    override suspend fun candles(symbol: String, tf: Timeframe, n: Int): List<Candle> {
        val resolution = ProviderSupport.resolutionFor(tf.stepSeconds, RESOLUTIONS)
        val to = System.currentTimeMillis() / 1000L
        val body = ProviderSupport.fetch(
            NAME,
            "$BASE/stock-insight/v1/stock/bars-long-term" +
                "?ticker=${symbol.uppercase()}&type=stock&resolution=$resolution" +
                "&to=$to&countBack=$n",
        )
        val root = runCatching { JSONObject(body) }
            .getOrElse { throw MarketUnavailable("$NAME sent an unreadable response", it) }

        val rows = root.optJSONArray("data")
            ?: throw MarketUnavailable("$NAME endpoint changed or refused")

        val out = ArrayList<Candle>(rows.length())
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val close = r.optDouble("close", Double.NaN)
            if (close.isNaN()) continue
            out += Candle(
                // tradingDate is ISO-8601 with a zone, e.g. 2026-07-24T00:00:00.000Z. Only the
                // ordering matters to the chart, so a parse failure degrades to 0 rather than
                // dropping an otherwise good bar.
                openTimeMs = parseIso(r.optString("tradingDate")),
                open = r.optDouble("open", close),
                high = r.optDouble("high", close),
                low = r.optDouble("low", close),
                close = close,
                volume = r.optDouble("volume", 0.0),
            )
        }
        if (out.isEmpty()) throw MarketUnavailable("$NAME has no data for $symbol")
        return out
    }

    private fun parseIso(raw: String): Long = runCatching {
        java.time.Instant.parse(raw).toEpochMilli()
    }.getOrDefault(0L)

    companion object {
        const val ID = "tcbs"
        private const val NAME = "TCBS"
        private const val BASE = "https://apipubaws.tcbs.com.vn"

        private val RESOLUTIONS = listOf(
            86400L to "D",
            604800L to "W",
            2592000L to "M",
        )
    }
}
