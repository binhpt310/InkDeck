package dev.inkdeck.market

import android.content.Context
import dev.inkdeck.market.provider.BinanceProvider
import dev.inkdeck.market.provider.FinnhubProvider
import dev.inkdeck.market.provider.MarketProvider
import dev.inkdeck.market.provider.StooqProvider
import dev.inkdeck.market.provider.TcbsProvider
import dev.inkdeck.market.provider.VnDirectProvider

/** The three groups of design.md §9.2's picker. */
enum class MarketCategory(val id: String) {
    CRYPTO("crypto"),
    US("us"),
    VN("vn"),
}

/**
 * One entry in the widget grid.
 *
 * [id] is what [MarketPrefs] persists, so it must stay stable: `category:SYMBOL`, uppercased.
 * [display] is what the card shows — `BTC/USDT` rather than `BTCUSDT`, because the exchange's
 * concatenated form is genuinely hard to read on a dithered panel.
 */
data class MarketAsset(
    val category: MarketCategory,
    val symbol: String,
    val display: String,
) {
    val id: String get() = "${category.id}:$symbol"

    companion object {
        fun parse(id: String, display: String? = null): MarketAsset? {
            val cut = id.indexOf(':')
            if (cut <= 0 || cut == id.length - 1) return null
            val category = MarketCategory.entries.firstOrNull { it.id == id.substring(0, cut) }
                ?: return null
            val symbol = id.substring(cut + 1).uppercase()
            return MarketAsset(category, symbol, display ?: defaultDisplay(category, symbol))
        }

        fun defaultDisplay(category: MarketCategory, symbol: String): String =
            if (category == MarketCategory.CRYPTO && symbol.endsWith("USDT")) {
                symbol.dropLast(4) + "/USDT"
            } else {
                symbol
            }
    }
}

/**
 * Which providers serve which category, and in what order.
 *
 * The list is a **fallback chain**, tried head-first by [MarketRepository]. This is the payoff of
 * Plan.md §5.2's abstraction made concrete: when Finnhub's free tier stopped serving candles, the
 * fix was appending one element here, not touching the dashboard.
 *
 * Constructed per-instance rather than as an object singleton because [FinnhubProvider] needs a
 * Context to reach the vault, and a static holding an Activity context is how leaks start.
 */
class MarketProviders(context: Context) {

    private val binance = BinanceProvider()
    private val finnhub = FinnhubProvider(context)
    private val stooq = StooqProvider()
    private val vndirect = VnDirectProvider()
    private val tcbs = TcbsProvider()

    fun chainFor(category: MarketCategory): List<MarketProvider> = when (category) {
        // Binance has no documented free alternative worth the second adapter. Plan.md lists
        // CoinGecko, which needs a demo key and rate-limits harder than the refresh cadence here
        // would ever hit — a fallback that is worse than the primary in every dimension is not a
        // fallback, it is a second thing to maintain.
        MarketCategory.CRYPTO -> listOf(binance)

        // Finnhub first (real-time-ish, needs a key), Stooq behind it so US widgets still show a
        // number on a device with an empty vault.
        MarketCategory.US -> listOf(finnhub, stooq)

        // Both unofficial. See the header comment in VnProviders.kt.
        MarketCategory.VN -> listOf(vndirect, tcbs)
    }

    /** True when every provider that can serve this category is an undeclared endpoint. */
    fun isUnofficial(category: MarketCategory): Boolean =
        chainFor(category).all { it.unofficial }
}

/** The symbols offered in the picker before the user adds any of their own. */
object MarketCatalog {

    val builtIn: List<MarketAsset> = listOf(
        asset(MarketCategory.CRYPTO, "BTCUSDT"),
        asset(MarketCategory.CRYPTO, "ETHUSDT"),
        asset(MarketCategory.CRYPTO, "SOLUSDT"),
        asset(MarketCategory.US, "AAPL"),
        asset(MarketCategory.US, "NVDA"),
        asset(MarketCategory.US, "MSFT"),
        asset(MarketCategory.VN, "VN30"),
        asset(MarketCategory.VN, "FPT"),
        asset(MarketCategory.VN, "VCB"),
    )

    /** design.md §9.1 draws four cards; these are them. */
    val defaultEnabled: List<String> = listOf(
        "crypto:BTCUSDT",
        "crypto:ETHUSDT",
        "vn:VN30",
        "us:AAPL",
    )

    private fun asset(category: MarketCategory, symbol: String) =
        MarketAsset(category, symbol, MarketAsset.defaultDisplay(category, symbol))
}
