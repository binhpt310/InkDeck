package dev.inkdeck.data.tasks

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** design.md §8.1: `▲ P1` high · `– P2` normal · `▼ P3` low. */
enum class Priority(val glyph: String) {
    P1("▲"),
    P2("–"),
    P3("▼"),
    ;

    companion object {
        fun of(ordinal: Int): Priority = entries.getOrElse(ordinal) { P2 }
    }
}

enum class TaskStatus { OPEN, DONE }

/**
 * A task — Plan.md §5.1.
 *
 * Two deliberate departures from the model sketched there, both recorded in Plan.md §5.1:
 *
 * - **`remindAt[]` is stored as offsets before [dueAt], not absolute instants.** design.md §8.2
 *   presents REMIND as `none · 10m · 1h · 1d · cust`, which is a lead time. Offsets are also the
 *   only form that survives a repeat: roll a weekly task forward and an absolute reminder is
 *   stranded in the past, while "1 h before" still means the same thing.
 * - **A repeating task rolls forward instead of closing.** Plan.md §5.1 says to compute the next
 *   occurrence on completion rather than pre-generating instances, so completing one is an edit
 *   to [dueAt], not a transition to [TaskStatus.DONE].
 *
 * Times are epoch millis in UTC; everything user-facing converts through the device zone at the
 * point of display. Storing local wall-clock would silently shift every reminder on a DST or
 * timezone change.
 */
@Entity(
    tableName = "tasks",
    indices = [Index("dueAt"), Index("status")],
)
data class Task(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val title: String,
    val notes: String = "",

    /** Null means "no date": the task shows under All and never schedules an alarm. */
    val dueAt: Long? = null,

    /**
     * The zone the due time was **entered** in, and the one it is displayed in. Empty means the
     * device zone.
     *
     * [dueAt] is a zone-independent instant either way — this does not change *when* the alarm
     * fires. It changes what the user reads and what they typed. A market task written as
     * "14:00 UTC" must keep saying 14:00 UTC when the device is in `+07`, and a repeating one
     * must roll forward against UTC midnight, not Saigon midnight.
     */
    val zoneId: String = "",

    /** Minutes before [dueAt]. Empty = no reminder. Ignored entirely when [dueAt] is null. */
    @ColumnInfo(name = "reminderOffsets")
    val reminderOffsets: List<Int> = emptyList(),

    val repeat: RepeatRule = RepeatRule.NONE,

    val priority: Priority = Priority.P2,

    val tags: List<String> = emptyList(),

    val status: TaskStatus = TaskStatus.OPEN,

    /** When the most recent completion happened — kept across a repeat roll-forward. */
    val completedAt: Long? = null,

    /**
     * design.md §8.2 `✈ Notify Telegram`.
     *
     * **Defaults on.** It was drafted as an opt-in mirror of a local notification, which is the
     * right shape on a phone. On this device the bot is the only channel that reaches the owner
     * when the tablet is asleep in a bag — `AlarmManager` is refused and nothing can wake the
     * screen (Plan.md §5.1b, §5.1d) — so opting out is the unusual choice, not opting in.
     */
    val telegramNotify: Boolean = true,

    val createdAt: Long,
    val updatedAt: Long,
) {
    val isOverdue: Boolean
        get() = status == TaskStatus.OPEN && dueAt != null && dueAt < System.currentTimeMillis()

    /** Falls back to the device zone, including when a stored id no longer resolves. */
    fun zone(): java.time.ZoneId = when {
        zoneId.isEmpty() -> java.time.ZoneId.systemDefault()
        else -> runCatching { java.time.ZoneId.of(zoneId) }
            .getOrElse { java.time.ZoneId.systemDefault() }
    }

    /** Absolute reminder instants, past ones included — filtering is the scheduler's job. */
    fun reminderInstants(): List<Long> {
        val due = dueAt ?: return emptyList()
        return reminderOffsets.map { due - it * 60_000L }
    }
}
