package dev.inkdeck.tasks.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.inkdeck.data.tasks.Priority
import dev.inkdeck.data.tasks.Task
import dev.inkdeck.tasks.R
import dev.inkdeck.tasks.TaskFormat

/**
 * Local notification delivery — Plan.md §5.1 path 1. Path 2 (Telegram) is Phase 5 and reads the
 * same `telegramNotify` flag.
 *
 * Channels are mandatory from API 26 and this is minSdk 26, so there is no pre-O branch. The
 * channel is created on every post rather than once at startup: an alarm can start the process
 * cold, and `createNotificationChannel` is idempotent and cheap.
 */
object TaskNotifications {

    private const val CHANNEL_REMINDERS = "task_reminders"

    /**
     * The notification id is the task id. One live notification per task means a repeating task
     * replaces its own reminder instead of stacking a new one every day.
     */
    private fun notificationId(taskId: Long): Int = (taskId and 0x7fffffff).toInt()

    fun post(context: Context, task: Task) {
        val manager = NotificationManagerCompat.from(context)
        ensureChannel(context)

        val open = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_TASK_ID, task.id)
            }
        val contentIntent = open?.let {
            PendingIntent.getActivity(
                context,
                notificationId(task.id),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val completeIntent = PendingIntent.getBroadcast(
            context,
            notificationId(task.id) + COMPLETE_OFFSET,
            Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_COMPLETE
                data = android.net.Uri.parse("inkdeck://task/${task.id}/complete")
                putExtra(TaskScheduler.EXTRA_TASK_ID, task.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val subtitle = buildString {
            task.dueAt?.let { append(TaskFormat.dueLine(context, it, task.zone())) }
            if (task.repeat.repeats) {
                if (isNotEmpty()) append(" · ")
                append(task.repeat.describe())
            }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification_task)
            .setContentTitle(task.title)
            .setContentText(subtitle)
            .setPriority(
                // P1 is the only level that earns a heads-up. Everything else posts quietly;
                // a reader that flashes the panel for a P3 gets silenced by its owner.
                if (task.priority == Priority.P1) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setShowWhen(true)
            .addAction(0, context.getString(R.string.tasks_notify_done), completeIntent)

        if (task.notes.isNotBlank()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(task.notes))
        }
        contentIntent?.let { builder.setContentIntent(it) }

        try {
            manager.notify(notificationId(task.id), builder.build())
        } catch (e: SecurityException) {
            // Only possible if targetSdk is raised to 33+ without POST_NOTIFICATIONS. Swallowing
            // it would turn every reminder into silence with no trace.
            android.util.Log.e("InkDeckAlarm", "notification refused for task=${task.id}", e)
        }
    }

    fun cancel(context: Context, taskId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(taskId))
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_REMINDERS,
            context.getString(R.string.tasks_channel_reminders),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.tasks_channel_reminders_detail)
            // The panel cannot show a colour; the LED, if this device has one, is not ours to
            // guess at. Sound and vibration are the only channels that reach a reader in a bag.
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    const val EXTRA_OPEN_TASK_ID = "dev.inkdeck.tasks.OPEN_TASK_ID"
    private const val COMPLETE_OFFSET = 1 shl 24
}
