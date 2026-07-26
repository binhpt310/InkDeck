package dev.inkdeck.telegram.command

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Lenient due-date parsing for `/task add <title> | <due> | <repeat>`.
 *
 * The task editor (design.md §8.2) has pickers; the bot has a person typing on a phone with one
 * thumb. Being strict here is the wrong trade — Plan.md §7.2 asks for a specific error over a
 * silent failure, and this returns one, but it accepts every shorthand it reasonably can first:
 *
 * ```
 *   today 14:00      tomorrow 9am        2026-07-30 14:00      14:00
 *   fri 18:00        30/07 14:00         2026-07-30           tomorrow
 *   14:00 UTC        tomorrow 9am utc    2026-07-30 14:00 Asia/Tokyo
 * ```
 *
 * ### The zone suffix is not cosmetic
 *
 * `Task.zoneId` (Plan.md §5.1c) records the zone the time was *entered* in, and the trading
 * server this device exists to watch publishes in UTC. "14:00" typed from a laptop in UTC and
 * stored as +07 is seven hours wrong, and nothing on the task row would say so. A trailing zone
 * token sets both the instant and `zoneId`; without one the device zone is used and `zoneId`
 * stays empty, which §5.1c defines as "follow the device".
 *
 * ### Bare times roll forward
 *
 * `14:00` at 15:00 means tomorrow. Interpreting it as today would create a task that is overdue
 * the instant it is written, which is never what someone typing a bare time meant.
 */
internal object DueParser {

    data class Result(
        /** Epoch millis, or null when the input asked for no due date. */
        val millis: Long?,
        /** [dev.inkdeck.data.tasks.Task.zoneId]: empty means "follow the device". */
        val zoneId: String,
        /** Non-null when nothing could be parsed; already phrased for the reply. */
        val error: String? = null,
    ) {
        companion object {
            val NONE = Result(null, "")
            fun error(message: String) = Result(null, "", message)
        }
    }

    private val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-M-d", Locale.US)

    private val WEEKDAYS = mapOf(
        "mon" to DayOfWeek.MONDAY, "monday" to DayOfWeek.MONDAY,
        "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY, "tuesday" to DayOfWeek.TUESDAY,
        "wed" to DayOfWeek.WEDNESDAY, "weds" to DayOfWeek.WEDNESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY, "thur" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "thursday" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "friday" to DayOfWeek.FRIDAY,
        "sat" to DayOfWeek.SATURDAY, "saturday" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY, "sunday" to DayOfWeek.SUNDAY,
    )

    private val NO_DATE = setOf("", "-", "none", "no", "never", "someday", "nodate")

    /** 09:00, matching what a person means by "tomorrow" with no time attached. */
    private val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)

    fun parse(raw: String, now: Instant = Instant.now()): Result {
        val text = raw.trim()
        if (text.lowercase(Locale.US) in NO_DATE) return Result.NONE

        var tokens = text.split(WHITESPACE).filter { it.isNotEmpty() }

        // Zone first: it is always the last token, and stripping it keeps the date/time grammar
        // below from having to know it exists.
        val (zone, zoneId, rest) = takeZone(tokens)
        tokens = rest
        if (tokens.isEmpty()) return Result.error("give a time as well as a zone, e.g. '14:00 UTC'")

        val today = LocalDate.now(zone)
        var date: LocalDate? = null

        val head = tokens.first().lowercase(Locale.US)
        when {
            head == "today" || head == "tod" -> {
                date = today; tokens = tokens.drop(1)
            }
            head == "tomorrow" || head == "tmr" || head == "tom" -> {
                date = today.plusDays(1); tokens = tokens.drop(1)
            }
            WEEKDAYS.containsKey(head) -> {
                date = nextWeekday(today, WEEKDAYS.getValue(head)); tokens = tokens.drop(1)
            }
            else -> parseDate(head, today)?.let { date = it; tokens = tokens.drop(1) }
        }

        val timeText = tokens.joinToString(" ")
        val time = when {
            timeText.isEmpty() -> null
            else -> parseTime(timeText) ?: return Result.error(
                "could not read the time '$timeText'. Try 14:00, 9am, or 2026-07-30 14:00"
            )
        }

        if (date == null && time == null) {
            return Result.error(
                "could not read the due date '$text'. Try 'today 14:00', 'tomorrow 9am', " +
                    "'2026-07-30 14:00', or '-' for none"
            )
        }

        val resolvedDate = date ?: today
        var instant = resolvedDate.atTime(time ?: DEFAULT_TIME).atZone(zone).toInstant()

        // A bare time already gone today means tomorrow. Only for a bare time: an explicit date
        // in the past is a correction the user should see, not one to quietly move a day.
        if (date == null && !instant.isAfter(now)) {
            instant = resolvedDate.plusDays(1).atTime(time ?: DEFAULT_TIME).atZone(zone).toInstant()
        }

        return Result(instant.toEpochMilli(), zoneId)
    }

    // ------------------------------------------------------------------ pieces

    private data class Zone(val zone: ZoneId, val zoneId: String, val rest: List<String>)

    /**
     * `UTC`/`Z`/`GMT`, or any id `ZoneId` recognises (`Asia/Tokyo`, `+07:00`).
     *
     * An unrecognised trailing word is left in the token list rather than rejected — it is far
     * more likely to be part of a time than a mistyped zone, and letting the time parser produce
     * the error message gives a better one.
     */
    private fun takeZone(tokens: List<String>): Zone {
        val device = Zone(ZoneId.systemDefault(), "", tokens)
        val last = tokens.lastOrNull() ?: return device
        val upper = last.uppercase(Locale.US)

        if (upper == "UTC" || upper == "Z" || upper == "GMT") {
            return Zone(ZoneId.of("UTC"), "UTC", tokens.dropLast(1))
        }
        // Only try ZoneId.of on something that looks like an id. Without the guard, "9AM" is a
        // perfectly valid short zone id on some JDKs and the time would vanish into the zone slot.
        if (last.contains('/') || last.startsWith('+') || last.startsWith('-')) {
            runCatching { ZoneId.of(last) }.getOrNull()?.let {
                return Zone(it, it.id, tokens.dropLast(1))
            }
        }
        return device
    }

    /** `2026-07-30`, `2026/7/30`, `30/07` and `30/07/2026`. */
    private fun parseDate(token: String, today: LocalDate): LocalDate? {
        val normalised = token.replace('/', '-').replace('.', '-')
        val parts = normalised.split('-').filter { it.isNotEmpty() }
        return when {
            parts.size == 3 && parts[0].length == 4 ->
                runCatching { LocalDate.parse(normalised, ISO_DATE) }.getOrNull()

            // Day-first with a year: unambiguous only because the four-digit group is last.
            parts.size == 3 -> runCatching {
                LocalDate.of(parts[2].toInt(), parts[1].toInt(), parts[0].toInt())
            }.getOrNull()

            // `30/07` — day/month, rolled into next year if it has already passed. Month/day
            // would be the other reading; day-first matches the ISO ordering used everywhere
            // else in this app and in the writer's locale.
            parts.size == 2 -> runCatching {
                val day = parts[0].toInt()
                val month = parts[1].toInt()
                val candidate = LocalDate.of(today.year, month, day)
                if (candidate.isBefore(today)) candidate.plusYears(1) else candidate
            }.getOrNull()

            else -> null
        }
    }

    /** `14:00`, `9`, `9am`, `9 am`, `9:30pm`, `0930`. */
    private fun parseTime(raw: String): LocalTime? {
        val text = raw.lowercase(Locale.US).replace(" ", "").replace(".", "")
        val meridiem = when {
            text.endsWith("am") -> "am"
            text.endsWith("pm") -> "pm"
            else -> null
        }
        val digits = meridiem?.let { text.dropLast(2) } ?: text

        val (hourText, minuteText) = when {
            digits.contains(':') -> digits.substringBefore(':') to digits.substringAfter(':')
            digits.length == 4 && digits.all { it.isDigit() } -> digits.take(2) to digits.drop(2)
            else -> digits to "0"
        }

        var hour = hourText.toIntOrNull() ?: return null
        val minute = minuteText.toIntOrNull() ?: return null

        when (meridiem) {
            "am" -> { if (hour == 12) hour = 0 }
            "pm" -> { if (hour != 12) hour += 12 }
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    /** The next [target], today included — "fri 18:00" typed on a Friday morning means today. */
    private fun nextWeekday(today: LocalDate, target: DayOfWeek): LocalDate {
        var date = today
        while (date.dayOfWeek != target) date = date.plusDays(1)
        return date
    }

    private val WHITESPACE = Regex("\\s+")
}
