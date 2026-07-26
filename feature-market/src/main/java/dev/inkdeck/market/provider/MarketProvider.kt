package dev.inkdeck.market.provider

import dev.inkdeck.market.data.Candle
import dev.inkdeck.market.data.Quote
import dev.inkdeck.market.data.Timeframe

/**
 * Plan.md §5.2, verbatim in its four members.
 *
 * This abstraction is the entire point of Phase 7. The free market sources are unequal and two of
 * them are undeclared endpoints that will change without notice; the interface exists so that a
 * broken provider is a **swap, not a rewrite**.
 *
 * Contract:
 *  - both suspend functions run their own IO dispatch; callers may invoke them from any context.
 *  - failure is a thrown [dev.inkdeck.market.data.MarketUnavailable], never a null or an empty
 *    list. An empty list means "the source has no data for this symbol", which is a different
 *    thing the UI renders differently.
 *  - [attribution] is a short human string the UI is required to display (terms compliance).
 *
 * ### Deviation from the Plan.md sketch
 *
 * [unofficial] is added, with a default of `false`. Plan.md §5.2 requires every VN widget to carry
 * a visible "unofficial" marker; putting the flag on the provider rather than on the symbol means
 * the marker follows the *source*, so a symbol that later moves onto a documented feed stops
 * claiming to be unofficial without anyone editing a catalogue entry.
 */
interface MarketProvider {

    val id: String

    suspend fun quote(symbol: String): Quote

    suspend fun candles(symbol: String, tf: Timeframe, n: Int): List<Candle>

    val attribution: String

    /**
     * True for undeclared endpoints — no documentation, no stability promise, may start demanding
     * headers or a referer at any time. Renders as `⚠ unoff.` on the card (design.md §9.1).
     */
    val unofficial: Boolean get() = false
}
