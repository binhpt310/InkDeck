package dev.inkdeck.tasks.alarm

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.inkdeck.data.tasks.Task
import dev.inkdeck.tasks.TaskGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Delivers reminders **without `AlarmManager`**, because on this ROM `AlarmManager` does not
 * work for this app at all.
 *
 * ### The measurement
 *
 * Every `set*` call is refused by a vendor patch. At the moment of the call the framework logs:
 *
 * ```
 * D linfeifei: setApplicationFreeze package_name ->dev.inkdeck, flag ->1   (from com.moan.launcher)
 * D linfeifei: dev.inkdeck,  isFreeze not allown set Alarm
 * ```
 *
 * The call returns normally, throws nothing, and the alarm **never appears in `dumpsys alarm`**.
 * Not deferred, not batched — absent. Confirmed with:
 *
 * - `setExactAndAllowWhileIdle` and `setAlarmClock`; both dropped, and
 *   `Next alarm clock information:` stays empty.
 * - the app in the foreground with the screen on, so it is not a Doze or background-start rule;
 * - the package on the Doze whitelist, so it is not battery optimisation;
 * - launching from the OEM launcher rather than `adb`, in case the freeze cleared on use. It
 *   does not.
 * - a scan of the whole alarm table: the only packages holding alarms are `android` and
 *   `com.abupdate.fota_demo_iot`, the vendor OTA app. **No third-party package on this device
 *   holds a single alarm.**
 *
 * ### What this does instead
 *
 * A plain polling tick inside [ReminderGuardService], which is a foreground service and so
 * survives screen-off (measured: 10+ minutes with no kill, where a cached process is swept in
 * 30 s). Each tick compares the wall clock against the pending reminder instants and broadcasts
 * to [ReminderReceiver] — the same receiver `AlarmManager` would have invoked, so notification,
 * repeat and Telegram behaviour all stay in one place.
 *
 * ### What this cannot do
 *
 * **Wake the device from deep sleep.** That is the one thing only `AlarmManager` can do. If the
 * CPU is suspended when a reminder comes due, the tick fires as soon as the device next runs,
 * and the task is already sitting under `OVERDUE` in the meantime. [TICK_MS] is a compromise:
 * short enough that a reminder is not visibly late while the device is up, long enough not to
 * matter for battery next to the panel.
 */
class ReminderTicker(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * `taskId:dueAt:slot` for reminders already delivered this process lifetime.
     *
     * Keyed on `dueAt` so editing a task's time re-arms it naturally, and rolling a repeating
     * task forward produces a new key rather than being suppressed as a duplicate.
     *
     * Deliberately not persisted. If the process dies and restarts inside [GRACE_MS], a reminder
     * may be delivered twice — which is the right way round to fail for something whose whole
     * job is not to be missed.
     */
    private val fired = HashSet<String>()

    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            scope.launch { sweep() }
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        Log.i(TAG, "ticker start")
        handler.post(tick)
    }

    fun stop() {
        if (!running) return
        running = false
        Log.i(TAG, "ticker stop")
        handler.removeCallbacks(tick)
    }

    private suspend fun sweep() {
        val now = System.currentTimeMillis()
        val tasks = TaskGraph.repository(context).schedulable()
        for (task in tasks) {
            task.reminderInstants().forEachIndexed { slot, at ->
                if (at > now) return@forEachIndexed
                // The grace window covers a slow tick or a short sleep, nothing more. Anything
                // older came due while the app was not running, and firing it on next launch
                // would mean a buzz about this morning at the moment you pick the device up —
                // the OVERDUE section already carries that, calmly and at a glance.
                if (now - at > GRACE_MS) return@forEachIndexed
                val key = key(task, slot)
                if (!fired.add(key)) return@forEachIndexed
                Log.i(TAG, "tick fires task=${task.id} slot=$slot late=${now - at}ms")
                deliver(task, slot)
            }
        }
    }

    private fun deliver(task: Task, slot: Int) {
        context.sendBroadcast(
            Intent(context, ReminderReceiver::class.java).apply {
                action = TaskScheduler.ACTION_REMIND
                data = android.net.Uri.parse("inkdeck://task/${task.id}/$slot")
                putExtra(TaskScheduler.EXTRA_TASK_ID, task.id)
                putExtra(TaskScheduler.EXTRA_SLOT, slot)
            }
        )
    }

    private fun key(task: Task, slot: Int) = "${task.id}:${task.dueAt}:$slot"

    private companion object {
        const val TAG = "InkDeckAlarm"

        /**
         * 30 s. A reminder can therefore be up to 30 s late while the device is awake, which is
         * below the threshold anyone notices on a task due "at 14:00", and it is one indexed
         * query against a table with a handful of rows.
         */
        const val TICK_MS = 30_000L

        /**
         * 5 min. Deliberately short, and it has to be: [ReminderGuardService] is sized from
         * *future* reminders only, so a long grace window would describe reminders the service
         * has already stopped for — a catch-up that can never happen, dressed up as one that
         * can. Five minutes is what a sleep gap or a missed tick actually costs.
         */
        const val GRACE_MS = 5 * 60 * 1000L
    }
}
