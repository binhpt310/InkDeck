package dev.inkdeck.telegram

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Starts the poll loop after a reboot or an app replace, without waiting for `MainActivity` to
 * run — mirrors `dev.inkdeck.tasks.alarm.BootReceiver` for the same reason.
 *
 * ### Why this exists and not just `MainActivity.onResume`
 *
 * `TelegramGraph.registerReminderRoute` — which is what makes `ReminderDelivery` try Telegram
 * before the local notification — only happens inside `startIfEnabled`, and until Phase 5 the
 * only caller of that was the Activity. A device that reboots and is never physically opened
 * (the whole point of the reminder path being Telegram in the first place, per Plan.md §5.1d)
 * would fire `ReminderTicker` in a process where the route was never registered, and every
 * reminder would silently fall back to the local notification it exists to replace.
 *
 * `TaskGraph.rearmAsync` already starts a process on `BOOT_COMPLETED` for the same reason
 * (Plan.md §5.1b-2); this receiver rides the same broadcast to register Telegram's route in
 * that same cold start rather than needing a second one.
 */
class TelegramBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> {
                Log.i(TAG, "starting after ${intent.action}")
                TelegramGraph.startIfEnabled(context)
            }
        }
    }

    private companion object {
        const val TAG = "InkDeckTg"
    }
}
