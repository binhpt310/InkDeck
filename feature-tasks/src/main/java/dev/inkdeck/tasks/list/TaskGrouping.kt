package dev.inkdeck.tasks.list

import android.content.Context
import dev.inkdeck.data.tasks.Task
import dev.inkdeck.tasks.R
import dev.inkdeck.tasks.TaskFormat
import java.time.LocalDate
import java.time.ZoneId

/**
 * The `Today | Week | All | Done` filter and the `OVERDUE / TODAY / …` grouping from
 * design.md §8.1.
 *
 * Pure functions over an already-sorted list — the DAO does the ordering, this only slices and
 * inserts headers. Keeping it free of Android state is what makes the section boundaries
 * testable without a device.
 *
 * **Sections are always in the device zone, even for a task written in UTC.** The headers answer
 * "what is on my plate today", and the reader's today is the local one. The row itself carries a
 * `UTC` suffix so the clock face is never ambiguous — see [TaskFormat.zoneSuffix].
 */
object TaskGrouping {

    enum class Filter { TODAY, WEEK, ALL, DONE }

    /**
     * The same slice as [build] but without section headers — the 2×2 board (design.md §8.1)
     * gives each pane its own title, so an `OVERDUE` header inside a pane called *Today* would
     * be a heading under a heading in a 286 dp column.
     */
    fun rows(
        filter: Filter,
        tasks: List<Task>,
        now: Long = System.currentTimeMillis(),
    ): List<Task> {
        // Derived from `now` rather than read from the clock again, so all four panes of one
        // emission agree on where the day boundary is.
        val today = TaskFormat.dateOf(now)
        return tasks.filter { keep(it, filter, today) }
    }

    fun build(
        context: Context,
        filter: Filter,
        tasks: List<Task>,
        now: Long = System.currentTimeMillis(),
    ): List<TaskListItem> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        if (filter == Filter.DONE) {
            // Completed work has no useful due-date grouping; it is a reverse-chronological log.
            return tasks.map { TaskListItem.Row(it) }
        }

        val visible = tasks.filter { keep(it, filter, today) }
        val out = ArrayList<TaskListItem>(visible.size + 5)
        var currentSection: String? = null

        for (task in visible) {
            val section = sectionFor(context, task, today, now)
            if (section != currentSection) {
                out += TaskListItem.Section(section)
                currentSection = section
            }
            out += TaskListItem.Row(task)
        }
        return out
    }

    private fun keep(task: Task, filter: Filter, today: LocalDate): Boolean {
        val due = task.dueAt ?: return filter == Filter.ALL
        val date = TaskFormat.dateOf(due)
        return when (filter) {
            // Overdue work stays visible under Today rather than sinking into All. A reminder
            // app that hides what you already missed is worse than no reminder app.
            Filter.TODAY -> !date.isAfter(today)
            Filter.WEEK -> !date.isAfter(today.plusDays(6))
            Filter.ALL -> true
            Filter.DONE -> false
        }
    }

    private fun sectionFor(context: Context, task: Task, today: LocalDate, now: Long): String {
        val due = task.dueAt ?: return context.getString(R.string.tasks_section_someday)
        val date = TaskFormat.dateOf(due)
        return when {
            // Overdue is a comparison against the clock, not against the date. A task due at
            // 09:00 is overdue at 09:05 — leaving it under TODAY until midnight would hide the
            // one thing the section exists to surface, and it is already carrying the 4 dp
            // overdue bar from §8.1 by then.
            due < now -> context.getString(R.string.tasks_section_overdue)
            date == today -> context.getString(R.string.tasks_section_today)
            date == today.plusDays(1) -> context.getString(R.string.tasks_section_tomorrow)
            !date.isAfter(today.plusDays(6)) -> context.getString(R.string.tasks_section_this_week)
            else -> context.getString(R.string.tasks_section_later)
        }
    }
}
