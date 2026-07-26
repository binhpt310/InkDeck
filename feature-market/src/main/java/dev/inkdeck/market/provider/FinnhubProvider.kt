package dev.inkdeck.market.provider

import android.content.Context
import dev.inkdeck.data.vault.SecretVault
import dev.inkdeck.market.data.Candle
import dev.inkdeck.market.data.MarketKeyMissing
import dev.inkdeck.market.data.MarketUnavailable
import dev.inkdeck.market.data.Quote
import dev.inkdeck.market.data.Timeframe
import org.json.JSONObject

/**
 * Finnhub free tier — US equities, Plan.md §5.2.
 *
 * ### The key
 *
 * Lives in [SecretVault] under [KEY_ID] and is read **at the point of use**, per the shared brief:
 * no field holds it, nothing logs it, and it is never appended to the URL. Finnhub documents
 * `X-Finnhub-Token` as an equivalent to the `token=` query parameter, which is what makes that
 * possible — a provider offering only a query parameter could not be used here without leaking
 * the key into OkHttp's logs and into any proxy's access log.
 *
 * Honest limitation: [SecretVault.getString] hands back a `String`, which is immutable and cannot
 * be zeroed, so the key sits in the heap until GC. Closing that would need an `InkHttp` overload
 * taking a `CharArray` header value; noted rather than hidden.
 *
 * ### The candle endpoint
 *
 * `/stock/candle` moved behind a paid plan at some point after Plan.md was written and answers
 * 403 on free keys. That is not something this module can fix, and it is exactly the case the
 * fallback chain in [dev.inkdeck.market.MarketCatalog] exists for: quotes come from here, history
 * degrades to [StooqProvider]. The call is implemented anyway so that a paid key just works.
 */
class FinnhubProvider(context: Context) : MarketProvider {

    private val appContext = context.applicationContext

    override val id: String = ID
    override val attribution: String = "Finnhub"

    override suspend fun quote(symbol: String): Quote  = ProviderSupport.onIo {
            val body = ProviderSupport.fetch(
                NAME,
                "$BASE/quote?symbol=${symbol.uppercase()}",
                authHeader(),
            )
            val o = runCatching { JSONObject(body) }
                .getOrElse { throw MarketUnavailable("$NAME sent an unreadable quote", it) }

            // c/d/dp/o/h/l/pc/t. An unknown symbol answers 200 with every field zero rather than an
            // error, so a zero last price is treated as "no such symbol" instead of a free stock.
            val last = o.optDouble("c", 0.0)
            if (last == 0.0) throw MarketUnavailable("$NAME has no data for $symbol")

            return@onIo Quote(
                symbol = symbol,
                last = last,
                change = o.optDouble("d", 0.0),
                changePct = o.optDouble("dp", 0.0),
                volume = Double.NaN, // /quote does not carry volume; the card renders "—".
                sourceTimeMs = o.optLong("t", 0L) * 1000L,
                open = o.optDouble("o", Double.NaN),
                high = o.optDouble("h", Double.NaN),
                low = o.optDouble("l", Double.NaN),
            )
    }

    override suspend fun candles(symbol: String, tf: Timeframe, n: Int): List<Candle>  = ProviderSupport.onIo {
            val resolution = ProviderSupport.resolutionFor(tf.stepSeconds, RESOLUTIONS)
            val to = System.currentTimeMillis() / 1000L
            // Over-request the window by 3× and trim: the US session has nights, weekends and
            // holidays in it, so an exact span returns far fewer bars than asked for.
            val from = to - tf.stepSeconds * n * 3

            val body = ProviderSupport.fetch(
                NAME,
                "$BASE/stock/candle?symbol=${symbol.uppercase()}&resolution=$resolution" +
                    "&from=$from&to=$to",
                authHeader(),
            )
            val o = runCatching { JSONObject(body) }
                .getOrElse { throw MarketUnavailable("$NAME sent unreadable candles", it) }

            if (o.optString("s") != "ok") return@onIo emptyList()

            val t = o.optJSONArray("t") ?: return@onIo emptyList()
            val open = o.optJSONArray("o") ?: return@onIo emptyList()
            val high = o.optJSONArray("h") ?: return@onIo emptyList()
            val low = o.optJSONArray("l") ?: return@onIo emptyList()
            val close = o.optJSONArray("c") ?: return@onIo emptyList()
            val volume = o.optJSONArray("v")

            val count = minOf(t.length(), open.length(), high.length(), low.length(), close.length())
            val first = (count - n).coerceAtLeast(0)
            val out = ArrayList<Candle>(count - first)
            for (i in first until count) {
                out += Candle(
                    openTimeMs = t.optLong(i) * 1000L,
                    open = open.optDouble(i),
                    high = high.optDouble(i),
                    low = low.optDouble(i),
                    close = close.optDouble(i),
                    volume = volume?.optDouble(i, 0.0) ?: 0.0,
                )
            }
            return@onIo out
    }

    /**
     * Opens the vault, reads the key, returns it inside the header map and keeps no other copy.
     * Called once per request on purpose — caching it in a field is precisely what the brief
     * forbids, and the cost is a file read plus one AES-GCM unwrap every five minutes.
     */
    private fun authHeader(): Map<String, String> {
        val vault = SecretVault.get(appContext)
        if (!vault.isUnlocked && vault.opensWithoutPassphrase) vault.unlockAuto()
        if (!vault.isUnlocked) {
            throw MarketKeyMissing("Unlock the vault to use $NAME")
        }
        if (!vault.contains(KEY_ID)) {
            throw MarketKeyMissing("No $NAME API key in the vault")
        }
        return mapOf(HEADER to vault.getString(KEY_ID))
    }

    companion object {
        const val ID = "finnhub"

        /** Vault secret id. Import it with the Phase 3 `.env` importer or the settings screen. */
        const val KEY_ID = "FINNHUB_API_KEY"

        private const val NAME = "Finnhub"
        private const val BASE = "https://finnhub.io/api/v1"
        private const val HEADER = "X-Finnhub-Token"

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
