package dev.inkdeck.data.tasks

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Repeat rules from Plan.md §5.1: none · daily · weekdays · weekly(days) · monthly(day) ·
 * every N days.
 *
 * Serialised to a single readable column rather than five nullable ones — the whole rule is
 * meaningless split apart, and `weekly:1,3,5` is something you can read in a `sqlite3` dump.
 *
 * All arithmetic is done in [LocalDateTime] at the device zone, not by adding milliseconds.
 * "Daily at 08:00" must stay 08:00 across a DST boundary, and adding 86 400 000 ms would move
 * it by an hour.
 */
data class RepeatRule(
    val kind: Kind = Kind.NONE,
    /** [Kind.WEEKLY] only. Empty falls back to the weekday of the current due date. */
    val weekdays: Set<DayOfWeek> = emptySet(),
    /** [Kind.MONTHLY] only, 1..31. Clamped to the length of the target month. */
    val monthDay: Int = 0,
    /** [Kind.EVERY_N_DAYS] only. */
    val intervalDays: Int = 0,
) {
    enum class Kind { NONE, DAILY, WEEKDAYS, WEEKLY, MONTHLY, EVERY_N_DAYS }

    val repeats: Boolean get() = kind != Kind.NONE

    /**
     * The first occurrence strictly after [afterMillis], keeping the time of day of [fromMillis].
     *
     * Anchoring the clock to [fromMillis] rather than to now is what makes a task completed
     * three days late still fire at its usual hour instead of at whatever minute the box was
     * ticked.
     */
    fun nextAfter(fromMillis: Long, afterMillis: Long = fromMillis, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!repeats) return null
        val from = LocalDateTime.ofInstant(Instant.ofEpochMilli(fromMillis), zone)
        val after = LocalDateTime.ofInstant(Instant.ofEpochMilli(afterMillis), zone)
        val time = from.toLocalTime()

        var candidate = when (kind) {
            Kind.NONE -> return null
            Kind.DAILY -> from.plusDays(1)
            Kind.EVERY_N_DAYS -> from.plusDays(intervalDays.coerceAtLeast(1).toLong())
            Kind.WEEKDAYS -> nextWeekday(from)
            Kind.WEEKLY -> nextWeekly(from, days = weekdays.ifEmpty { setOf(from.dayOfWeek) })
            Kind.MONTHLY -> nextMonthly(from, time)
        }

        // A task ignored for a month must not resurface with a due date still in the past, so
        // keep stepping. The bound is a guard against a rule that somehow never advances, not
        // an expected path.
        var guard = 0
        while (!candidate.isAfter(after) && guard++ < MAX_STEPS) {
            candidate = when (kind) {
                Kind.DAILY -> candidate.plusDays(1)
                Kind.EVERY_N_DAYS -> candidate.plusDays(intervalDays.coerceAtLeast(1).toLong())
                Kind.WEEKDAYS -> nextWeekday(candidate)
                Kind.WEEKLY -> nextWeekly(candidate, weekdays.ifEmpty { setOf(from.dayOfWeek) })
                Kind.MONTHLY -> nextMonthly(candidate, time)
                Kind.NONE -> return null
            }
        }
        if (guard >= MAX_STEPS) return null
        return candidate.atZone(zone).toInstant().toEpochMilli()
    }

    private fun nextWeekday(from: LocalDateTime): LocalDateTime {
        var d = from.plusDays(1)
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
        return d
    }

    private fun nextWeekly(from: LocalDateTime, days: Set<DayOfWeek>): LocalDateTime {
        var d = from.plusDays(1)
        while (d.dayOfWeek !in days) d = d.plusDays(1)
        return d
    }

    /**
     * Clamped, not rolled over: "the 31st" in February means the 28th (or 29th), never 2 March.
     * Rolling over would drift the task forward one month at a time.
     */
    private fun nextMonthly(from: LocalDateTime, time: java.time.LocalTime): LocalDateTime {
        val target = monthDay.takeIf { it in 1..31 } ?: from.dayOfMonth
        val nextMonth = from.plusMonths(1).with(TemporalAdjusters.firstDayOfMonth())
        val day = target.coerceAtMost(nextMonth.toLocalDate().lengthOfMonth())
        return nextMonth.withDayOfMonth(day).with(time)
    }

    fun serialize(): String = when (kind) {
        Kind.NONE -> "none"
        Kind.DAILY -> "daily"
        Kind.WEEKDAYS -> "weekdays"
        Kind.WEEKLY -> "weekly:" + weekdays.sortedBy { it.value }.joinToString(",") { it.value.toString() }
        Kind.MONTHLY -> "monthly:$monthDay"
        Kind.EVERY_N_DAYS -> "everyN:$intervalDays"
    }

    /** A short line for the list subtitle: "repeats weekly" in design.md §8.1. */
    fun describe(): String = when (kind) {
        Kind.NONE -> ""
        Kind.DAILY -> "repeats daily"
        Kind.WEEKDAYS -> "repeats weekdays"
        Kind.WEEKLY -> "repeats weekly"
        Kind.MONTHLY -> "repeats monthly"
        Kind.EVERY_N_DAYS -> "repeats every $intervalDays d"
    }

    companion object {
        val NONE = RepeatRule()

        private const val MAX_STEPS = 2000

        /** Tolerant by design: an unreadable rule degrades to "no repeat", never to a crash. */
        fun parse(raw: String?): RepeatRule {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty() || text == "none") return NONE
            val name = text.substringBefore(':')
            val arg = text.substringAfter(':', "")
            return when (name) {
                "daily" -> RepeatRule(Kind.DAILY)
                "weekdays" -> RepeatRule(Kind.WEEKDAYS)
                "weekly" -> RepeatRule(
                    Kind.WEEKLY,
                    weekdays = arg.split(',')
                        .mapNotNull { it.trim().toIntOrNull() }
                        .filter { it in 1..7 }
                        .map { DayOfWeek.of(it) }
                        .toSet(),
                )
                "monthly" -> RepeatRule(Kind.MONTHLY, monthDay = arg.toIntOrNull() ?: 1)
                "everyN" -> RepeatRule(Kind.EVERY_N_DAYS, intervalDays = arg.toIntOrNull() ?: 1)
                else -> NONE
            }
        }
    }
}
