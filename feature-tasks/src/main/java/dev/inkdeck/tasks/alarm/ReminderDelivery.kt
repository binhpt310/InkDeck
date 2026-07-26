package dev.inkdeck.tasks.alarm

import android.content.Context
import dev.inkdeck.data.tasks.Task

/**
 * Where a due reminder actually goes.
 *
 * Phase 4 shipped with one route — a local notification — because that is what a task app does
 * on a phone. This is not a phone. `AlarmManager` is refused for this package (Plan.md §5.1b),
 * so nothing can wake the device, and a local notification only lands if the reader happens to
 * be awake and in your hand. On this hardware the reliable channel is the one that does not
 * depend on the device at all: **the Telegram bot.**
 *
 * So delivery is a list of routes, tried in order of reliability, and the local notification
 * demoted to a fallback for when the bot is not paired. Registration is inverted —
 * `:feature-telegram` depends on `:feature-tasks`, not the other way round — so the task module
 * never learns that Telegram exists.
 */
fun interface ReminderDelivery {

    /** Return true if the reminder was handed off. False means "try the next route". */
    fun deliver(context: Context, task: Task): Boolean

    companion object {

        /**
         * The local notification. Kept, but last: it is the honest fallback when nothing better
         * is configured, not the design.
         */
        val LOCAL = ReminderDelivery { context, task ->
            TaskNotifications.post(context, task)
            true
        }

        private val routes = ArrayList<ReminderDelivery>()

        /**
         * Add a route ahead of the local notification. Called by `:feature-telegram` once the
         * bot is paired; idempotent registration is the caller's problem, and there are at most
         * two routes in practice.
         */
        @Synchronized
        fun register(delivery: ReminderDelivery) {
            routes.add(0, delivery)
        }

        @Synchronized
        fun clear() = routes.clear()

        /**
         * Try each registered route, then the local fallback. Stops at the first success —
         * a reminder that arrives twice is worse than one that arrives once.
         */
        @Synchronized
        fun dispatch(context: Context, task: Task) {
            for (route in routes) {
                if (runCatching { route.deliver(context, task) }.getOrDefault(false)) return
            }
            LOCAL.deliver(context, task)
        }
    }
}
