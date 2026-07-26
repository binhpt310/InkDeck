package dev.inkdeck.market

import dev.inkdeck.market.data.Candle
import dev.inkdeck.market.data.Direction
import dev.inkdeck.market.data.Quote
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/**
 * Number and time formatting for the market surfaces.
 *
 * All of it is `Locale.US` and grouped with commas on purpose: the UI is English-only (shared
 * brief), and a device locale that groups with `.` would render `108.412,50` next to a Binance
 * price the user can cross-check on a phone showing `108,412.50`. Matching the source beats
 * matching the ROM here.
 *
 * Formatters are shared and `DecimalFormat` is not thread-safe, but every caller is a View on the
 * main thread. That is stated rather than defended with a ThreadLocal that would never be
 * contended.
 */
object MarketFormat {

    private val symbols = DecimalFormatSymbols(Locale.US)

    private val price2 = DecimalFormat("#,##0.00", symbols)
    private val price4 = DecimalFormat("#,##0.0000", symbols)
    private val pct = DecimalFormat("0.00", symbols)
    private val whole = DecimalFormat("#,##0", symbols)

    /**
     * Two decimals normally, four below 1.00. A sub-dollar crypto pair at two decimals shows
     * `0.00` and looks like a dead feed.
     */
    fun price(value: Double): String = when {
        value.isNaN() -> EM_DASH
        abs(value) < 1.0 -> price4.format(value)
        else -> price2.format(value)
    }

    /** Signed, with §2.3's `+` / `−` (U+2212, not a hyphen — it aligns with the digits). */
    fun signed(value: Double, direction: Direction): String = when {
        value.isNaN() -> EM_DASH
        else -> direction.sign + price(abs(value))
    }

    fun signedPercent(value: Double, direction: Direction): String = when {
        value.isNaN() -> EM_DASH
        else -> direction.sign + pct.format(abs(value)) + "%"
    }

    /** `42,180` / `1.2M` / `3.4B`. Volumes span nine orders of magnitude across these sources. */
    fun volume(value: Double): String = when {
        value.isNaN() -> EM_DASH
        abs(value) >= 1_000_000_000 -> pct.format(value / 1_000_000_000) + "B"
        abs(value) >= 1_000_000 -> pct.format(value / 1_000_000) + "M"
        else -> whole.format(value)
    }

    /** `HH:mm`, 24-hour. Written by hand because `SimpleDateFormat` is not thread-safe and this
     *  is three integers. */
    fun clock(epochMs: Long): String {
        if (epochMs <= 0L) return EM_DASH
        val c = Calendar.getInstance()
        c.timeInMillis = epochMs
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        return "${if (h < 10) "0" else ""}$h:${if (m < 10) "0" else ""}$m"
    }

    /** How long a candle series covers: `24h`, `7d`, `96d`. Empty when it cannot be told. */
    fun spanLabel(candles: List<Candle>): String? {
        if (candles.size < 2) return null
        val first = candles.first().openTimeMs
        val last = candles.last().openTimeMs
        if (first <= 0L || last <= first) return null
        // Rounded, not truncated. A 96-point series at a 15-minute step spans 95 intervals, so
        // truncation labels one calendar day of candles "23h" — technically true and reads as a
        // bug next to a change figure the exchange quotes over 24 h.
        val hours = (last - first + 1_800_000L) / 3_600_000L
        return when {
            hours < 48L -> "${hours.coerceAtLeast(1)}h"
            else -> "${(last - first + 43_200_000L) / 86_400_000L}d"
        }
    }

    /** `▲ +2.14%  +2,271.30` — the change line of design.md §9.1. */
    fun changeLine(quote: Quote): String {
        val d = quote.direction
        return "${d.glyph} ${signedPercent(quote.changePct, d)}  ${signed(quote.change, d)}"
    }

    /**
     * Spoken form of the change line. Canvas text is invisible to TalkBack, and the glyphs are
     * announced as "black up-pointing triangle" at best, so cards get this instead.
     */
    fun changeSpoken(quote: Quote): String {
        val word = when (quote.direction) {
            Direction.UP -> "up"
            Direction.DOWN -> "down"
            Direction.FLAT -> "unchanged"
        }
        return "$word ${pct.format(abs(quote.changePct))} percent"
    }

    const val EM_DASH = "—"
}
