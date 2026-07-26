package dev.inkdeck.tasks.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.inkdeck.data.tasks.TaskStatus
import dev.inkdeck.tasks.TaskGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fires a reminder, and handles the `Done` action posted with it.
 *
 * The database read cannot happen on the main thread, and `onReceive` has roughly ten seconds
 * before the process becomes killable — so [goAsync] holds the wakelock while the coroutine
 * runs and [PendingResult.finish] releases it. Dropping the `goAsync` result on the floor is
 * the classic way to get reminders that work on a warm process and vanish on a cold one.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(TaskScheduler.EXTRA_TASK_ID, -1L)
        if (taskId <= 0) return

        val app = context.applicationContext
        val pending = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                Log.i(TAG, "receive ${intent.action} task=$taskId")
                when (intent.action) {
                    TaskScheduler.ACTION_REMIND -> remind(app, taskId)
                    ACTION_COMPLETE -> complete(app, taskId)
                    else -> Log.w(TAG, "unknown action ${intent.action}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "reminder failed for task=$taskId", e)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun remind(context: Context, taskId: Long) {
        val task = TaskGraph.repository(context).byId(taskId)
        if (task == null) {
            Log.i(TAG, "task=$taskId gone; alarm was stale")
            return
        }
        // A task completed between the alarm being armed and it firing should stay quiet. The
        // scheduler cancels on completion, but a race here is cheap to rule out.
        if (task.status != TaskStatus.OPEN) return

        // Telegram first if the bot is paired, local notification otherwise — see
        // [ReminderDelivery] for why the local one is the fallback and not the design.
        ReminderDelivery.dispatch(context, task)
        // Logged because there is no other way to tell a reminder that fired from one the ROM
        // swept away before it could: a force-stop also clears the app's posted notifications,
        // so an empty `dumpsys notification` afterwards proves nothing either way.
        Log.i(TAG, "delivered reminder for task=$taskId \"${task.title}\"")

        // This reminder has now been consumed; re-evaluate what is still pending.
        TaskGraph.rearmAsync(context)
    }

    private suspend fun complete(context: Context, taskId: Long) {
        val result = TaskGraph.repository(context).complete(taskId)
        if (result.rolledTo == null) {
            TaskNotifications.cancel(context, taskId)
        } else {
            // A repeating task rolled forward rather than closing, so replace the notification
            // with one showing the new date instead of leaving the old time on screen.
            result.task?.let { TaskNotifications.post(context, it) }
        }
    }

    companion object {
        const val ACTION_COMPLETE = "dev.inkdeck.tasks.COMPLETE"
        private const val TAG = "InkDeckAlarm"
    }
}
