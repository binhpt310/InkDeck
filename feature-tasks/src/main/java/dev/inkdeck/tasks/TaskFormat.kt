package dev.inkdeck.tasks

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Date and time strings for the tasks screens — design.md §8.1 shows `Yesterday 18:00`,
 * `14:30`, `done 08:42`.
 *
 * Everything is 24-hour and `Locale.US` for month and day names. The UI is English-only by
 * decision, and a locale-sensitive pattern here would produce a 12-hour clock on some device
 * configurations, which is wider, slower to read, and would reflow the row.
 *
 * **Every function takes a zone.** A task can be written in UTC (Task.zoneId), and rendering it
 * against the device zone would show a different clock face than the one the user typed —
 * which for a market reminder is not a cosmetic difference. The device zone is the default so
 * the common case reads unchanged.
 *
 * `java.time` on API 26 comes from core library desugaring (see the module build file). Without
 * it, none of this links.
 */
object TaskFormat {

    val deviceZone: ZoneId get() = ZoneId.systemDefault()

    val UTC: ZoneId = ZoneId.of("UTC")

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)
    private val dayYearFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.US)

    fun local(millis: Long, zone: ZoneId = deviceZone): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)

    fun time(millis: Long, zone: ZoneId = deviceZone): String = local(millis, zone).format(timeFmt)

    /** `Sat 25 Jul 2026` — the editor's date field, where the year has to be unambiguous. */
    fun fullDate(millis: Long, zone: ZoneId = deviceZone): String =
        local(millis, zone).toLocalDate().format(dayYearFmt)

    /**
     * A short marker appended whenever the task's zone is not the device's, so `14:00` can never
     * be read as local time when it isn't. Empty in the common case — the suffix is a warning,
     * and putting one on every row would train the eye to skip it.
     */
    fun zoneSuffix(zone: ZoneId): String =
        if (zone.id == deviceZone.id) "" else " ${shortZone(zone)}"

    /** `UTC`, or the offset for anything else — `Asia/Ho_Chi_Minh` is too wide for a task row. */
    fun shortZone(zone: ZoneId): String =
        if (zone.id == "UTC") "UTC" else Instant.now().atZone(zone).offset.id

    /**
     * The list subtitle. Today collapses to just the clock, because the section header already
     * says TODAY and repeating the date on every row wastes the width.
     */
    fun dueLine(context: Context, millis: Long, zone: ZoneId = deviceZone): String {
        val date = local(millis, zone).toLocalDate()
        val today = LocalDate.now(zone)
        val clock = time(millis, zone) + zoneSuffix(zone)
        return when (date) {
            today -> clock
            today.minusDays(1) -> context.getString(R.string.tasks_yesterday_at, clock)
            today.plusDays(1) -> context.getString(R.string.tasks_tomorrow_at, clock)
            else -> "${date.format(if (date.year == today.year) dayFmt else dayYearFmt)} $clock"
        }
    }

    /** Relative day label used by the date picker rows. */
    fun dayLabel(context: Context, date: LocalDate, zone: ZoneId = deviceZone): String {
        val today = LocalDate.now(zone)
        val name = date.format(dayFmt)
        return when (date) {
            today -> context.getString(R.string.tasks_today_named, name)
            today.plusDays(1) -> context.getString(R.string.tasks_tomorrow_named, name)
            else -> name
        }
    }

    fun combine(date: LocalDate, minutesOfDay: Int, zone: ZoneId = deviceZone): Long =
        date.atStartOfDay(zone).plusMinutes(minutesOfDay.toLong()).toInstant().toEpochMilli()

    fun minutesOfDay(millis: Long, zone: ZoneId = deviceZone): Int =
        local(millis, zone).toLocalTime().let { it.hour * 60 + it.minute }

    fun dateOf(millis: Long, zone: ZoneId = deviceZone): LocalDate =
        local(millis, zone).toLocalDate()

    /** `on time` / `10m` / `1h` / `1d` — the REMIND chips in design.md §8.2. */
    fun offsetLabel(minutes: Int): String = when {
        minutes <= 0 -> "on time"
        minutes % (60 * 24) == 0 -> "${minutes / (60 * 24)}d"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes}m"
    }
}
