package dev.inkdeck.market.data

import java.io.IOException

/**
 * The value types the whole feature agrees on — Plan.md §5.2.
 *
 * Deliberately flat and provider-agnostic: every adapter in [dev.inkdeck.market.provider]
 * normalises into these, so swapping a broken source (which the VN ones *will* be) changes one
 * file and nothing downstream.
 */
data class Quote(
    val symbol: String,
    val last: Double,
    /** Absolute move over the provider's own reference window (usually 24 h or the session). */
    val change: Double,
    val changePct: Double,
    val volume: Double,
    /** Provider's timestamp for the value, epoch ms. 0 when the source does not supply one. */
    val sourceTimeMs: Long = 0L,
    val open: Double = Double.NaN,
    val high: Double = Double.NaN,
    val low: Double = Double.NaN,
) {
    val direction: Direction
        get() = when {
            changePct > 0.0 -> Direction.UP
            changePct < 0.0 -> Direction.DOWN
            else -> Direction.FLAT
        }
}

data class Candle(
    val openTimeMs: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
)

/**
 * design.md §2.3: direction is carried by glyph and stroke pattern, never by colour. This enum is
 * the single source for both, so a card and a chart can never disagree about which is which.
 */
enum class Direction(val glyph: String, val sign: String) {
    UP("▲", "+"),
    DOWN("▼", "−"),
    FLAT("–", ""),
}

/**
 * The six spans of design.md §9.3.
 *
 * [stepSeconds] is a *nominal* candle width, not a provider resolution string. Each adapter maps
 * it onto whatever its own API accepts (Binance takes `15m`, Finnhub takes `15`, Stooq only takes
 * `d`), which keeps provider vocabulary out of the UI and out of every other provider.
 *
 * [defaultCount] is capped at 96 everywhere on purpose — see [dev.inkdeck.market.MarketRepository].
 */
enum class Timeframe(
    val label: String,
    val stepSeconds: Long,
    val defaultCount: Int,
) {
    H1("1H", 60L, 60),
    H4("4H", 5 * 60L, 48),
    D1("1D", 15 * 60L, 96),
    W1("1W", 2 * 3600L, 84),
    M1("1M", 8 * 3600L, 90),
    Y1("1Y", 7 * 86400L, 52),
}

/**
 * A provider could not answer. Deliberately an [IOException] and deliberately *thrown* rather
 * than returned as null: the repository's job is to turn this into design.md §5.7's stale/error
 * state, and a null would let a caller forget.
 *
 * The message is shown to the user, so it names the source and not the stack.
 */
class MarketUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Thrown when a keyed provider has no key in the vault yet. Distinct so the UI can say so. */
class MarketKeyMissing(message: String) : IOException(message)
