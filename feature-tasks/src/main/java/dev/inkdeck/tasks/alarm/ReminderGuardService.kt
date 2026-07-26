package dev.inkdeck.tasks.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.inkdeck.tasks.R
import dev.inkdeck.tasks.TaskFormat

/**
 * Holds the process up so [ReminderTicker] can deliver reminders. On this device that ticker is
 * the *only* delivery path — see its class doc for why `AlarmManager` cannot be used at all.
 *
 * ### Why the process needs holding up
 *
 * The ROM force-stops cached processes. Roughly 30 s after boot:
 *
 * ```
 * D linfeifei: BEGIN_MOGU_KILL_APP kill dev.inkdeck
 * I ActivityManager: Force stopping dev.inkdeck appid=10054 user=0: from pid 1906
 * ```
 *
 * A foreground service does not make the app immortal — it was still killed at `adj 200` with
 * `MainActivity` alive during one boot sweep. But it measurably changes the ordinary case: with
 * the service running the app survived **10+ minutes of screen-off** without being touched,
 * where a cached process is swept in 30 s.
 *
 * ### Cost control
 *
 * The service runs only while at least one reminder is actually in the future, and stops itself
 * the moment none are — an empty or all-overdue task list costs no notification, no ticker and
 * no memory. The channel is `IMPORTANCE_MIN`: the notification is a requirement of the
 * foreground-service contract, not something anyone wants to read.
 */
class ReminderGuardService : Service() {

    private val ticker by lazy { ReminderTicker(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_COUNT, 0) ?: 0
        val nextAt = intent?.getLongExtra(EXTRA_NEXT_AT, 0L) ?: 0L

        if (count <= 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        ensureChannel(this)
        startForeground(NOTIFICATION_ID, buildNotification(count, nextAt))
        // This — not AlarmManager — is what actually delivers reminders here. See ReminderTicker
        // for the measurement behind that.
        ticker.start()

        // NOT_STICKY on purpose: a force-stop does not honour START_STICKY anyway, and every
        // path that should bring the service back (boot, app start, a task edit) already calls
        // sync(). A sticky restart would only add a second, racier path to the same place.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ticker.stop()
        super.onDestroy()
    }

    private fun buildNotification(count: Int, nextAt: Long): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = open?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val text = if (nextAt > 0) {
            getString(R.string.tasks_guard_next, TaskFormat.dueLine(this, nextAt))
        } else {
            resources.getQuantityString(R.plurals.tasks_guard_armed, count, count)
        }

        return NotificationCompat.Builder(this, CHANNEL_GUARD)
            .setSmallIcon(R.drawable.ic_notification_task)
            .setContentTitle(resources.getQuantityString(R.plurals.tasks_guard_armed, count, count))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GUARD,
                context.getString(R.string.tasks_channel_guard),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = context.getString(R.string.tasks_channel_guard_detail)
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_GUARD = "reminder_guard"
        private const val NOTIFICATION_ID = 0x1DEC
        private const val EXTRA_COUNT = "count"
        private const val EXTRA_NEXT_AT = "next_at"

        private const val TAG = "InkDeckAlarm"

        /**
         * Bring the service in line with how many reminders are armed. Safe to call from a
         * BroadcastReceiver during `BOOT_COMPLETED`, which is one of the cases where a
         * background service start is allowed on API 26+.
         */
        fun sync(context: Context, armedCount: Int, nextAt: Long?) {
            val intent = Intent(context, ReminderGuardService::class.java)
                .putExtra(EXTRA_COUNT, armedCount)
                .putExtra(EXTRA_NEXT_AT, nextAt ?: 0L)
            try {
                if (armedCount > 0) {
                    context.startForegroundService(intent)
                } else {
                    context.stopService(intent)
                }
            } catch (e: IllegalStateException) {
                // startForegroundService from a background context is refused in some states.
                // The alarms are already armed at this point; losing the guard only means the
                // process is killable again, which is the pre-existing behaviour.
                Log.w(TAG, "guard service refused", e)
            }
        }
    }
}
