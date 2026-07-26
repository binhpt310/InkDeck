package dev.inkdeck.telegram

import android.content.Context
import dev.inkdeck.data.tasks.Task
import dev.inkdeck.tasks.TaskFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Outbound notification path — Plan.md §5.1 "notification path 2", the half Phase 4 deliberately
 * left unwired ("Telegram delivery is stored but not sent").
 *
 * `:feature-tasks` calls [notifyTask] from `ReminderReceiver`; nothing here calls into tasks, so
 * the dependency stays one-way and this module can be dropped from the build without touching
 * the reminder path.
 *
 * ### Enqueue, and what the Boolean therefore means
 *
 * `ReminderDelivery` demoted the local notification to a fallback, so this is now the primary
 * route and its answer decides whether a reminder gets a second chance. But the caller is a
 * BroadcastReceiver with a ~10 s budget: blocking it on an HTTPS round trip to Telegram would
 * spend most of that window on the network and, on a device that is usually offline, would spend
 * it failing.
 *
 * So [notifyTask] writes the message to [OutboundQueue] and returns **accepted for delivery**.
 * The queue is persisted and drained at the top of every poll cycle, which is what makes that
 * acceptance honest: the send no longer has to succeed in the same instant the reminder fires, it
 * has to succeed at some point in the next 12 h while [TelegramService] is looping. A device that
 * was offline when the reminder came due now gets it on reconnect instead of losing it.
 *
 * The predicate behind the Boolean ([canNotify]) stays conservative: it must never say true for a
 * configuration that cannot *ever* reach Telegram, because `ReminderDelivery.dispatch` stops at
 * the first success and there is no way to un-consume a reminder once the local fallback has been
 * skipped. It answers "is this bot set up", not "is the network up" — with a queue behind it the
 * second question no longer has to be asked, which is just as well, since it cannot be answered
 * cheaply or reliably from a receiver.
 *
 * What is still not covered: a device that never reconnects within [OutboundQueue.TTL_MS]. That
 * reminder is dropped, on purpose — see the queue's TTL note.
 */
object TelegramNotifier {

    private const val TAG = "InkDeckTg"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Can Telegram take a reminder right now? Synchronous, cheap, and safe from a
     * BroadcastReceiver — a preferences read and a `File.exists`, nothing more.
     *
     * True means enabled, a chat is paired, and a bot token exists in the vault. It does **not**
     * mean the network is up or the token is valid; see the class doc for why it cannot.
     */
    @JvmStatic
    fun canNotify(context: Context): Boolean {
        val store = TelegramStore(context.applicationContext)
        return store.enabled && store.pairedHint && store.hasStoredToken()
    }

    /**
     * Send [task] to the paired chat, if the user asked for it and the bot is set up.
     *
     * Returns immediately. The [Task.telegramNotify] check is here rather than at the call site
     * so wiring this in is one unconditional line.
     *
     * @return true if the reminder was **accepted for delivery** — queued, which is what
     *   `ReminderDelivery` means by a successful route. False means this route declined and the
     *   caller should fall through to the next one, so it is returned for an opted-out task, a
     *   disabled feature, an unpaired chat, or a missing token.
     */
    @JvmStatic
    fun notifyTask(context: Context, task: Task): Boolean {
        if (!task.telegramNotify) return false
        val app = context.applicationContext
        if (!canNotify(app)) return false

        val text = buildString {
            append("Reminder: ${task.title}")
            task.dueAt?.let { append("\ndue ${TaskFormat.dueLine(app, it, task.zone())}") }
            if (task.repeat.repeats) append(" · ${task.repeat.describe()}")
            if (task.notes.isNotBlank()) append("\n\n${task.notes}")
            append("\n\n/task done ${task.id}")
        }

        // Synchronous: the queue write is a preferences commit, and the caller is already on IO
        // inside goAsync. Deferring it to another coroutine would race the process being killed
        // the moment the receiver finishes — which is precisely what the queue exists to survive.
        OutboundQueue.enqueue(app, reminderKey(task), text)

        // Nudge the poller. If the service is already up this is a no-op; if the process was
        // started cold by the ticker it starts the loop, which drains the queue before its first
        // getUpdates. Without this a queued reminder would wait for the user to open the app.
        TelegramGraph.start(app)
        return true
    }

    /**
     * `taskId:dueAt:slot`, the key shape `ReminderTicker` fires on.
     *
     * The slot has to be **reconstructed**: `ReminderDelivery.deliver` carries only the task, so
     * which of the reminder offsets fired is not passed down. Taking the offset whose instant is
     * nearest now recovers it — and getting this right matters. A task with reminders at 1 h
     * before and on time produces two dispatches with an identical task, and keying on
     * `taskId:dueAt` alone would dedupe the second away and silently lose the on-time reminder.
     */
    private fun reminderKey(task: Task): String {
        val now = System.currentTimeMillis()
        val slot = task.reminderInstants()
            .withIndex()
            .minByOrNull { (_, at) -> kotlin.math.abs(at - now) }
            ?.index
            ?: 0
        return "${task.id}:${task.dueAt}:$slot"
    }

    /**
     * Free-text message to the paired chat. Exposed for anything else that wants to reach the
     * user's phone from the device; same queued, fire-and-forget contract.
     *
     * Keyed on the send time so two identical messages are never mistaken for a duplicate — a
     * caller sending the same line twice on purpose means it twice.
     */
    @JvmStatic
    fun notifyText(context: Context, text: String) {
        val app = context.applicationContext
        if (!canNotify(app)) return
        OutboundQueue.enqueue(app, "text:${System.currentTimeMillis()}", text)
        TelegramGraph.start(app)
    }

    /**
     * Send everything waiting. Called by [TelegramService] at the top of each poll cycle, on the
     * loop's own IO dispatcher — the sends are blocking and deliberately so, since the drain must
     * finish before the 50 s long poll parks the thread.
     *
     * The token and chat id are resolved **here**, at send time, not when the message was queued.
     * A message queued before a re-pair goes to the chat that is paired now, and nothing
     * credential-shaped was ever written to the queue file.
     */
    internal fun drainQueue(context: Context): Int {
        val app = context.applicationContext
        if (OutboundQueue.size(app) == 0) return 0

        val store = TelegramStore(app)
        val vault = store.openVault() ?: return 0
        val chatId = store.pairedChatId(vault) ?: return 0
        if (!store.hasToken(vault)) return 0

        val client = TelegramClient { store.token(vault) }
        return OutboundQueue.drain(app) { text -> client.sendMessage(chatId, text) != null }
    }
}
