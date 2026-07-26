package dev.inkdeck.tasks

import android.content.Context
import dev.inkdeck.data.tasks.TaskRepository
import dev.inkdeck.tasks.alarm.TaskScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hand wiring for the tasks feature.
 *
 * No DI framework: this is three objects, and a BroadcastReceiver needs to reach them from a
 * process that may have been started cold by an alarm with no Activity anywhere. A plain
 * application-context singleton is the shape that actually survives that.
 */
object TaskGraph {

    @Volatile
    private var scheduler: TaskScheduler? = null

    fun scheduler(context: Context): TaskScheduler =
        scheduler ?: synchronized(this) {
            scheduler ?: TaskScheduler(context.applicationContext).also {
                // Any single-task write can change how many alarms are armed, which decides
                // whether ReminderGuardService should be running. Wiring it here means no
                // caller has to remember; the bulk flag in TaskScheduler stops the fan-out.
                val app = context.applicationContext
                it.onMutated = { rearmAsync(app) }
                scheduler = it
            }
        }

    fun repository(context: Context): TaskRepository =
        TaskRepository.get(context.applicationContext, scheduler(context))

    /**
     * Re-arm every pending reminder. Called from boot, from package replace, and from app start
     * — see the force-stop note in [TaskScheduler].
     *
     * Fire-and-forget on purpose: nothing on screen waits for this, and a caller that had to
     * await it would be a caller that could block the launch path.
     */
    fun rearmAsync(context: Context) {
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            scheduler(app).rescheduleAll(repository(app))
        }
    }
}
