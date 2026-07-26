package dev.inkdeck.tasks.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.inkdeck.data.tasks.Task
import dev.inkdeck.data.tasks.TaskRepository
import dev.inkdeck.data.tasks.TaskStatus

/**
 * Arms and cancels reminder alarms — Plan.md §5.1.
 *
 * ⚠️ **On this device none of these alarms are ever registered.** The ROM refuses every
 * `AlarmManager.set*` call from this package; [ReminderTicker] is what actually delivers
 * reminders here, and Plan.md §5.1b has the measurement. This class is kept, and still called on
 * every write, for three reasons: it is correct code against the platform contract; it decides
 * *which* reminders are pending, which is what [ReminderGuardService] is sized from; and if the
 * package is ever taken off the vendor's freeze list, exact wake-from-sleep delivery starts
 * working with no further change.
 *
 * `setExactAndAllowWhileIdle` is the right call on this device and needs no permission at
 * API 27 (`SCHEDULE_EXACT_ALARM` landed in 31). "AllowWhileIdle" matters more than exactness
 * here: an e-ink reader spends nearly all its life in Doze, and a plain `setExact` would be
 * deferred to the next maintenance window — which on this ROM can be over an hour.
 *
 * ### Two ways alarms are lost, and what covers each
 *
 * 1. **Reboot** — `AlarmManager` keeps nothing. [BootReceiver] calls [rescheduleAll].
 * 2. **Force-stop** — the OEM `MOGU_KILL_APP` sweep (Plan.md §0) force-stops background apps,
 *    and Android cancels every alarm of a force-stopped package. Nothing can be received to
 *    learn this happened, so [rescheduleAll] also runs on every app start. It is one indexed
 *    query and a handful of `set` calls; cheap enough to do unconditionally.
 */
class TaskScheduler(private val context: Context) : TaskRepository.Rescheduler {

    private val alarms: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /**
     * Invoked after a single-task write so the guard service can be brought in line. Set by
     * [dev.inkdeck.tasks.TaskGraph]; null during a bulk re-arm, which syncs once at the end
     * rather than once per task.
     */
    var onMutated: (() -> Unit)? = null

    private var bulk = false

    override fun reschedule(taskId: Long, task: Task?) {
        cancel(taskId)
        if (task != null && task.status == TaskStatus.OPEN) arm(task)
        if (!bulk) onMutated?.invoke()
    }

    /**
     * The reminder slots that are worth arming for [task], as `(slot, instant)`.
     *
     * The *index* is bounded, not the surviving count: [cancel] sweeps slots `0 until
     * MAX_REMINDERS`, so an alarm armed at a higher slot could never be cancelled again.
     *
     * Past reminders are dropped rather than fired immediately — editing a task at 15:00 with a
     * reminder set for 14:00 should not buzz on save.
     */
    private fun pendingSlots(task: Task, now: Long): List<IndexedValue<Long>> =
        if (task.status != TaskStatus.OPEN) {
            emptyList()
        } else {
            task.reminderInstants()
                .withIndex()
                .filter { (index, at) -> index < MAX_REMINDERS && at > now }
        }

    private fun arm(task: Task) {
        val am = alarms ?: return
        pendingSlots(task, System.currentTimeMillis())
            .forEach { (index, at) ->
                // Non-null for every flag except NO_CREATE, which is only used by cancel().
                val pi = pendingIntent(task.id, index, PendingIntent.FLAG_UPDATE_CURRENT)
                    ?: return@forEach
                try {
                    // setAlarmClock, not setExactAndAllowWhileIdle.
                    //
                    // This ROM's patched AlarmManager silently discards ordinary alarms from
                    // this package — it logs `linfeifei: dev.inkdeck, isFreeze not allown set
                    // Alarm` at the moment of the call, returns normally, and the alarm never
                    // appears in `dumpsys alarm` at all. Not late, not deferred: absent. Verified
                    // with the app in the foreground and the screen on, so it is not a Doze or
                    // background-execution rule.
                    //
                    // setAlarmClock takes a different path: it is the user-facing alarm-clock
                    // contract, surfaces in `Next alarm clock information`, and is the one thing
                    // an aggressive ROM is least willing to drop. The showIntent opens the app.
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showIntent()), pi)
                    Log.i(TAG, "armed task=${task.id} slot=$index at=$at")
                } catch (e: SecurityException) {
                    // Only reachable if targetSdk is raised past 30 without adding the
                    // permission. Log loudly rather than silently losing the reminder.
                    Log.e(TAG, "exact alarm refused for task=${task.id}", e)
                }
            }
    }

    fun cancel(taskId: Long) {
        val am = alarms ?: return
        for (index in 0 until MAX_REMINDERS) {
            // NO_CREATE returns null when nothing is armed, so this is not creating the very
            // intents it then cancels.
            pendingIntent(taskId, index, PendingIntent.FLAG_NO_CREATE)?.let {
                am.cancel(it)
                it.cancel()
            }
        }
    }

    /**
     * Re-arm everything and bring [ReminderGuardService] in line with the result.
     *
     * The count that matters is *armed alarms in the future*, not schedulable tasks: a list full
     * of overdue reminders arms nothing, and holding a foreground service open for it would be
     * a permanent notification guarding nothing.
     */
    suspend fun rescheduleAll(repository: TaskRepository) {
        val now = System.currentTimeMillis()
        val tasks = repository.schedulable()
        bulk = true
        try {
            tasks.forEach { reschedule(it.id, it) }
        } finally {
            bulk = false
        }

        // Counted through the same predicate that decides what gets armed, so the guard
        // service can never be running for alarms that do not exist.
        val future = tasks.flatMap { task -> pendingSlots(task, now).map { it.value } }
        Log.i(TAG, "re-armed ${future.size} alarm(s) across ${tasks.size} task(s)")

        ReminderGuardService.sync(context, future.size, future.minOrNull())
    }

    /**
     * Where the system's own "next alarm" affordance should send the user. Not the thing that
     * delivers the reminder — that is the broadcast in [pendingIntent].
     */
    private fun showIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        return PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun pendingIntent(taskId: Long, index: Int, flags: Int): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND
            // The request code alone identifies the alarm; the data URI is what makes
            // filterEquals() distinguish two intents, since extras are not compared.
            data = android.net.Uri.parse("inkdeck://task/$taskId/$index")
            putExtra(EXTRA_TASK_ID, taskId)
            putExtra(EXTRA_SLOT, index)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(taskId, index),
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Request codes must be Int. Packing the id into the high bits keeps ids distinct up to
     * ~268 M tasks, which is not a limit this device will meet.
     */
    private fun requestCode(taskId: Long, index: Int): Int =
        ((taskId.toInt() shl SLOT_BITS) or index)

    companion object {
        const val ACTION_REMIND = "dev.inkdeck.tasks.REMIND"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_SLOT = "slot"

        /** design.md §8.2 offers one reminder chip; the model allows several. Four is plenty. */
        const val MAX_REMINDERS = 4
        private const val SLOT_BITS = 3

        private const val TAG = "InkDeckAlarm"
    }
}
