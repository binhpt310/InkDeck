package dev.inkdeck.tasks.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.inkdeck.tasks.TaskGraph

/**
 * Re-arms every pending reminder after the device restarts or the app is replaced — Plan.md
 * §5.1, and the Phase 4 exit criterion ("reminder fires on time after reboot").
 *
 * `MY_PACKAGE_REPLACED` is here as well as `BOOT_COMPLETED` because installing a new APK over
 * the old one also clears the package's alarms. During development that happens far more often
 * than a reboot, and without it every `adb install` would silently disarm the test.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> {
                Log.i(TAG, "re-arming after ${intent.action}")
                TaskGraph.rearmAsync(context)
            }
        }
    }

    private companion object {
        const val TAG = "InkDeckAlarm"
    }
}
