package dev.inkdeck.telegram.command

import dev.inkdeck.data.tasks.RepeatRule
import java.time.DayOfWeek
import java.util.Locale

/**
 * The `<repeat>` field of `/task add`, in the words a person types.
 *
 * [RepeatRule.parse] already reads the stored form (`weekly:1,3,5`, `everyN:3`) and is tried
 * first, so anything copied out of `/task list` round-trips. This adds the spoken forms on top:
 *
 * ```
 *   daily · every day        weekdays              weekly · weekly mon,wed · every monday
 *   monthly · monthly 15     every 3 days · 3d     none · -
 * ```
 *
 * Unlike [RepeatRule.parse], an unreadable rule is an **error**, not a silent [RepeatRule.NONE].
 * Tolerance is right for a database column read at startup; here the user is watching, and
 * "repeats weekdays" quietly becoming "never repeats" is exactly the silent failure Plan.md §7.2
 * calls the worst outcome.
 */
internal object RepeatParser {

    data class Result(val rule: RepeatRule, val error: String? = null)

    private val WEEKDAYS = mapOf(
        "mon" to DayOfWeek.MONDAY, "monday" to DayOfWeek.MONDAY,
        "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY, "tuesday" to DayOfWeek.TUESDAY,
        "wed" to DayOfWeek.WEDNESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY, "thursday" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "friday" to DayOfWeek.FRIDAY,
        "sat" to DayOfWeek.SATURDAY, "saturday" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY, "sunday" to DayOfWeek.SUNDAY,
    )

    private val NONE = setOf("", "-", "none", "no", "never", "once")

    fun parse(raw: String): Result {
        val text = raw.trim().lowercase(Locale.US)
        if (text in NONE) return Result(RepeatRule.NONE)

        // The serialised form first, so `/task list` output can be pasted straight back.
        if (text.contains(':') || text == "daily" || text == "weekdays") {
            val stored = RepeatRule.parse(text)
            if (stored.repeats) return Result(stored)
        }

        val words = text.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }

        return when {
            text == "every day" || text == "everyday" -> Result(RepeatRule(RepeatRule.Kind.DAILY))

            text == "weekday" || text == "weekdays" || text == "every weekday" ->
                Result(RepeatRule(RepeatRule.Kind.WEEKDAYS))

            words.first() == "weekly" ||
                (words.first() == "every" && WEEKDAYS.containsKey(words.getOrNull(1))) ->
                weekly(words.drop(1))

            words.first() == "monthly" -> Result(
                RepeatRule(
                    RepeatRule.Kind.MONTHLY,
                    // No day given means "the day this task is already due on", which
                    // RepeatRule.nextMonthly does when monthDay is out of 1..31.
                    monthDay = words.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..31 } ?: 0,
                )
            )

            // `every 3 days`, `3d`, `every 3d`
            else -> everyN(words)
                ?: Result(
                    RepeatRule.NONE,
                    "could not read the repeat '$raw'. Try: daily, weekdays, " +
                        "weekly mon,wed, monthly 15, every 3 days, or '-' for none",
                )
        }
    }

    private fun weekly(rest: List<String>): Result {
        val days = rest.mapNotNull { WEEKDAYS[it] }.toSet()
        // Empty is legal: RepeatRule falls back to the weekday of the current due date, which is
        // what "weekly" with no days can only mean.
        return Result(RepeatRule(RepeatRule.Kind.WEEKLY, weekdays = days))
    }

    private fun everyN(words: List<String>): Result? {
        val digits = words.firstNotNullOfOrNull { word ->
            word.removeSuffix("d").removeSuffix("days").removeSuffix("day").toIntOrNull()
        } ?: return null
        if (digits < 1) return null
        return Result(RepeatRule(RepeatRule.Kind.EVERY_N_DAYS, intervalDays = digits))
    }
}
