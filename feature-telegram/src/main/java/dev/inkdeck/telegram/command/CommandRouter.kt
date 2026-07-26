package dev.inkdeck.telegram.command

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.util.Log
import dev.inkdeck.data.tasks.Task
import dev.inkdeck.data.tasks.TaskStatus
import dev.inkdeck.data.vault.SecretVault
import dev.inkdeck.tasks.TaskFormat
import dev.inkdeck.tasks.TaskGraph
import dev.inkdeck.telegram.TelegramClient
import dev.inkdeck.telegram.TelegramStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.Locale

/**
 * Turns an inbound message into an action — Plan.md §7.2, design.md §12.
 *
 * ### The allowlist is the first thing that happens, and it is silent
 *
 * Plan.md §7.4 mitigation 2: only the paired chat is honoured. Anything else is dropped **without
 * a reply**. That is deliberate and worth stating, because "unknown chat" is exactly the case
 * where an error message feels helpful: a reply confirms the bot exists, is running, and is
 * attached to a device — which is everything an attacker who guessed a bot name wanted to learn.
 * A stranger sees a bot that never answers.
 *
 * Before pairing, `/pair <code>` is the *only* command any chat can run, and only with the
 * six-digit code currently on the settings screen.
 *
 * ### Everything answers
 *
 * Inside the allowlist the rule inverts. Plan.md §7.2: "a silent failure on a device you are not
 * holding is the worst outcome." Every honoured command replies with a confirmation or a specific
 * error, including the ones that failed for boring reasons like no network.
 *
 * ### Secrets are deleted, then confirmed redacted
 *
 * `/llm` and `/key` call [TelegramClient.deleteMessage] the moment the value is in the vault
 * (§7.4 mitigation 1) and reply with `sk-…7f3a`. If the delete fails the reply *says so* — the
 * user has to know the key is still sitting in the chat history, because the whole argument for
 * allowing keys over Telegram is that it is not.
 */
internal class CommandRouter(
    context: Context,
    private val store: TelegramStore,
    private val client: TelegramClient,
) {

    private val app = context.applicationContext

    /** Set when a command changes something the settings screen shows (pairing, mostly). */
    var onStateChanged: (() -> Unit)? = null

    /**
     * Handle one update. Suspending because the task repository is, and because this runs on the
     * poll loop's IO dispatcher where a blocking Room call would park the poller.
     *
     * @param vault already unlocked by the caller — the loop holds it open for the poll cycle
     *   rather than unwrapping the data key once per message.
     */
    suspend fun handle(update: TelegramClient.Update, vault: SecretVault) {
        val paired = store.pairedChatId(vault)

        if (paired == null) {
            tryPair(update, vault)
            return
        }
        if (update.chatId != paired) {
            // Not even a log of the text: an unpaired chat's message is somebody else's content.
            Log.i(TAG, "ignored a message from an unpaired chat")
            return
        }

        val text = update.text.trim()
        val command = text.substringBefore(' ').substringBefore('@').lowercase(Locale.US)
        val rest = text.substringAfter(' ', "").trim()

        when (command) {
            "/start", "/help" -> reply(update, HELP)
            "/pair" -> reply(update, "Already paired with this chat. Use Re-pair on the device to move it.")
            "/task" -> handleTask(update, rest)
            "/note" -> handleNote(update, rest)
            "/status" -> reply(update, status(vault, paired))
            "/llm" -> handleLlm(update, rest, vault)
            "/key" -> handleKey(update, rest, vault)
            else -> reply(
                update,
                "Unknown command '$command'.\n$USAGE"
            )
        }
    }

    // ------------------------------------------------------------------ /pair

    /**
     * The only command an unpaired chat can run. The first chat to send the right code owns the
     * bot; the code rotates after [TelegramStore.notePairFailure]'s attempt limit.
     *
     * A wrong code still gets an answer, unlike an unpaired chat post-pairing — at this point the
     * bot has already been found and is by definition waiting to be claimed, so refusing to say
     * "wrong code" only punishes the owner who fat-fingered it.
     */
    private fun tryPair(update: TelegramClient.Update, vault: SecretVault) {
        val text = update.text.trim()
        if (!text.lowercase(Locale.US).startsWith("/pair")) {
            Log.i(TAG, "ignored a non-/pair message while unpaired")
            return
        }

        val code = text.substringAfter(' ', "").trim()
        if (code == store.pairingCode()) {
            store.setPairedChatId(vault, update.chatId)
            onStateChanged?.invoke()
            Log.i(TAG, "paired")
            reply(
                update,
                "Paired. This chat is now the only one InkDeck will listen to.\n\n$HELP"
            )
            return
        }

        val rotated = store.notePairFailure()
        onStateChanged?.invoke()
        reply(
            update,
            if (rotated) {
                "Wrong code. Too many attempts — InkDeck has generated a new one; " +
                    "read it off the Telegram settings screen."
            } else {
                "Wrong code. The six-digit code is on the device's Telegram settings screen."
            }
        )
    }

    // ------------------------------------------------------------------ /task

    private suspend fun handleTask(update: TelegramClient.Update, rest: String) {
        val sub = rest.substringBefore(' ').lowercase(Locale.US)
        val args = rest.substringAfter(' ', "").trim()

        when (sub) {
            "add" -> taskAdd(update, args)
            "list", "" -> taskList(update, args)
            "done" -> taskDone(update, args)
            "del", "delete", "rm" -> taskDelete(update, args)
            else -> reply(update, "Unknown /task subcommand '$sub'.\n$TASK_USAGE")
        }
    }

    private suspend fun taskAdd(update: TelegramClient.Update, args: String) {
        if (args.isBlank()) {
            reply(update, TASK_USAGE)
            return
        }

        // Pipe-separated, not positional-with-quotes: a task title is prose and contains spaces,
        // and design.md's own command reference writes it this way.
        val parts = args.split('|').map { it.trim() }
        val title = parts.getOrNull(0).orEmpty()
        if (title.isEmpty()) {
            reply(update, "A title is required.\n$TASK_USAGE")
            return
        }

        val due = DueParser.parse(parts.getOrNull(1).orEmpty())
        if (due.error != null) {
            reply(update, "Not added: ${due.error}")
            return
        }

        val repeat = RepeatParser.parse(parts.getOrNull(2).orEmpty())
        if (repeat.error != null) {
            reply(update, "Not added: ${repeat.error}")
            return
        }
        if (repeat.rule.repeats && due.millis == null) {
            // A repeat with nothing to repeat from silently never fires. Refusing is the only
            // honest answer; RepeatRule.nextAfter needs an anchor instant.
            reply(update, "Not added: a repeating task needs a due date to repeat from.")
            return
        }

        val now = System.currentTimeMillis()
        val task = Task(
            title = title,
            dueAt = due.millis,
            zoneId = due.zoneId,
            // Plan.md §5.1a item 2: giving a task a date selects the `on time` reminder. A task
            // pushed from Telegram with a time and no reminder would be a note with a date on it.
            reminderOffsets = if (due.millis != null) listOf(0) else emptyList(),
            repeat = repeat.rule,
            // It came from Telegram, so it goes back to Telegram. The editor can turn this off.
            telegramNotify = true,
            createdAt = now,
            updatedAt = now,
        )

        val id = TaskGraph.repository(app).save(task)
        reply(update, "Added #$id  ${describe(task.copy(id = id))}")
    }

    private suspend fun taskList(update: TelegramClient.Update, filter: String) {
        val open = TaskGraph.repository(app).observeOpen().first()
        val now = System.currentTimeMillis()
        val dayMs = 24 * 60 * 60 * 1000L

        val selected = when (filter.lowercase(Locale.US)) {
            "today" -> open.filter { it.dueAt != null && it.dueAt!! < endOfToday() }
            "week" -> open.filter { it.dueAt != null && it.dueAt!! < now + 7 * dayMs }
            "overdue" -> open.filter { it.isOverdue }
            else -> open
        }

        if (selected.isEmpty()) {
            reply(update, if (filter.isBlank()) "No open tasks." else "No open tasks in '$filter'.")
            return
        }

        // Overdue first, then by due date, then the undated. A bot reply is read top-down on a
        // phone; burying the late ones under next month's would defeat the point of asking.
        val ordered = selected.sortedWith(
            compareBy({ it.dueAt == null }, { it.dueAt ?: Long.MAX_VALUE })
        )

        val shown = ordered.take(MAX_LIST_ROWS)
        val body = shown.joinToString("\n") { task ->
            val mark = if (task.isOverdue) "!" else "·"
            "$mark #${task.id}  ${describe(task)}"
        }
        val more = (ordered.size - shown.size)
            .takeIf { it > 0 }
            ?.let { "\n… and $it more" }
            .orEmpty()

        reply(update, "${ordered.size} open\n$body$more")
    }

    private suspend fun taskDone(update: TelegramClient.Update, args: String) {
        val id = taskId(args) ?: run {
            reply(update, "Which one? /task done <id> — ids come from /task list.")
            return
        }
        val repository = TaskGraph.repository(app)
        if (repository.byId(id) == null) {
            reply(update, "No task #$id.")
            return
        }

        val result = repository.complete(id)
        val task = result.task
        reply(
            update,
            when {
                task == null -> "No task #$id."
                // A repeating task rolls forward and stays open (Plan.md §5.1a item 3). Saying
                // "done" and leaving it in the list is the confusing outcome; say where it went.
                result.rolledTo != null ->
                    "Done. “${task.title}” repeats — next ${dueText(task)}"
                else -> "Done: “${task.title}”"
            }
        )
    }

    private suspend fun taskDelete(update: TelegramClient.Update, args: String) {
        val id = taskId(args) ?: run {
            reply(update, "Which one? /task del <id> — ids come from /task list.")
            return
        }
        val repository = TaskGraph.repository(app)
        val task = repository.byId(id)
        if (task == null) {
            reply(update, "No task #$id.")
            return
        }
        repository.delete(id)
        reply(update, "Deleted #$id “${task.title}”")
    }

    private fun taskId(args: String): Long? =
        args.trim().removePrefix("#").substringBefore(' ').toLongOrNull()

    // ------------------------------------------------------------------ /note

    /**
     * Plan.md §7.2 "append a note". Stored as a task with no due date, which puts it in the NO
     * DATE section of design.md §8.1 rather than inventing a second store the UI cannot show.
     */
    private suspend fun handleNote(update: TelegramClient.Update, rest: String) {
        val text = rest.trim()
        if (text.isEmpty()) {
            reply(update, "Usage: /note <text>")
            return
        }

        // The first line is the title so the list row is readable; the rest becomes notes.
        val title = text.lineSequence().first().take(MAX_TITLE_CHARS)
        val body = text.removePrefix(title).trim()
        val now = System.currentTimeMillis()

        val id = TaskGraph.repository(app).save(
            Task(title = title, notes = body, createdAt = now, updatedAt = now)
        )
        reply(update, "Noted #$id  $title")
    }

    // ------------------------------------------------------------------ /llm, /key

    /**
     * `/llm <provider> <base_url> <model> <key>` — Plan.md §7.2.
     *
     * The key goes into the vault. The other three are not secret and are written to a plain
     * preferences file so Phase 8's BYOK screen can read a profile the user pushed from a phone
     * without having to unlock the vault to list what exists.
     */
    private fun handleLlm(update: TelegramClient.Update, rest: String, vault: SecretVault) {
        val parts = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size < 4) {
            reply(update, "Usage: /llm <provider> <base_url> <model> <key>")
            // Nothing was stored, but the message still contains whatever they did type — which
            // on a mistyped /llm is usually the key. Delete it anyway.
            scrub(update, stored = false)
            return
        }

        val provider = sanitiseId(parts[0])
        val baseUrl = parts[1]
        val model = parts[2]
        val key = parts.drop(3).joinToString(" ")

        if (provider.isEmpty()) {
            reply(update, "Provider name must contain letters or digits.")
            scrub(update, stored = false)
            return
        }
        if (!baseUrl.startsWith("https://")) {
            // http:// would send the key in the clear on the next request. Refuse rather than
            // store something that leaks on use.
            reply(update, "base_url must start with https:// — nothing stored.")
            scrub(update, stored = false)
            return
        }

        val secretId = "${LLM_PREFIX}${provider.uppercase(Locale.US)}$LLM_KEY_SUFFIX"
        val stored = runCatching {
            vault.putString(secretId, key)
            writeLlmProfile(provider, baseUrl, model, secretId)
        }.isSuccess

        val deleted = scrub(update, stored = true)
        reply(
            update,
            buildString {
                if (stored) {
                    append("Stored LLM profile '$provider'\n")
                    append("  base_url  $baseUrl\n")
                    append("  model     $model\n")
                    append("  key       ${redact(key)}\n")
                } else {
                    append("Could not store the profile — the vault refused the write.\n")
                }
                append(deleteNote(deleted))
            }
        )
    }

    /** `/key <name> <value>` — anything else that belongs in the vault. */
    private fun handleKey(update: TelegramClient.Update, rest: String, vault: SecretVault) {
        val name = rest.substringBefore(' ').trim()
        val value = rest.substringAfter(' ', "").trim()
        if (name.isEmpty() || value.isEmpty()) {
            reply(update, "Usage: /key <name> <value>")
            scrub(update, stored = false)
            return
        }

        val id = sanitiseId(name)
        if (id.isEmpty()) {
            reply(update, "Key name must contain letters or digits.")
            scrub(update, stored = false)
            return
        }

        val stored = runCatching { vault.putString(id, value) }.isSuccess
        val deleted = scrub(update, stored = true)
        reply(
            update,
            buildString {
                if (stored) append("Stored '$id' = ${redact(value)}\n")
                else append("Could not store '$id' — the vault refused the write.\n")
                append(deleteNote(deleted))
            }
        )
    }

    /**
     * Delete the incoming message — Plan.md §7.4 mitigation 1, and the single most important
     * behaviour in this module.
     *
     * @return true if Telegram confirmed. Null-ish outcomes are never reported as success: the
     *   user's decision to keep using `/llm` rests on this working, so a failure has to be loud.
     */
    private fun scrub(update: TelegramClient.Update, stored: Boolean): Boolean {
        if (!store.autoDelete) return false
        val deleted = client.deleteMessage(update.chatId, update.messageId)
        Log.i(TAG, "secret ingest: stored=$stored autoDeleted=$deleted")
        return deleted
    }

    private fun deleteNote(deleted: Boolean): String = when {
        !store.autoDelete ->
            "Auto-delete is OFF, so your message with the key in it is still in this chat's " +
                "history. Turn it on in InkDeck > Telegram, and delete that message yourself."
        deleted -> "Your message was deleted from this chat."
        else ->
            "WARNING: InkDeck could not delete your message — the key is still in this chat's " +
                "history on Telegram's servers. Delete it manually, and rotate the key if you " +
                "cannot."
    }

    /**
     * `sk-…7f3a`, the redaction Plan.md §7.4 specifies. Short values are replaced outright rather
     * than shown at half length; four characters of an eight-character secret is not a hint.
     */
    private fun redact(secret: String): String =
        if (secret.length < 12) "(stored)"
        else "${secret.take(3)}…${secret.takeLast(4)}"

    private fun writeLlmProfile(provider: String, baseUrl: String, model: String, secretId: String) {
        val prefs = app.getSharedPreferences(LLM_PREFS, Context.MODE_PRIVATE)
        val profile = JSONObject()
            .put("provider", provider)
            .put("baseUrl", baseUrl)
            .put("model", model)
            .put("keyVaultId", secretId)
            .put("source", "telegram")
            .put("updatedAt", System.currentTimeMillis())
        prefs.edit().putString(provider.lowercase(Locale.US), profile.toString()).apply()
    }

    /** Same rule as `SecretVault.fileFor` enforces, applied before it can throw. */
    private fun sanitiseId(raw: String): String =
        raw.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("")
            .trim('_', '.')
            .take(48)

    // ------------------------------------------------------------------ /status

    /**
     * Plan.md §7.2: battery, wifi, pending tasks. SSH state is Phase 2's and is not reachable
     * from here without a cross-feature dependency the brief forbids — noted in the report.
     */
    private suspend fun status(vault: SecretVault, chatId: Long): String {
        val open = runCatching { TaskGraph.repository(app).observeOpen().first() }.getOrDefault(emptyList())
        val overdue = open.count { it.isOverdue }
        val next = open.filter { it.dueAt != null && !it.isOverdue }.minByOrNull { it.dueAt!! }

        return buildString {
            append("InkDeck\n")
            append("battery   ${battery()}\n")
            append("network   ${network()}\n")
            append("tasks     ${open.size} open, $overdue overdue\n")
            next?.let { append("next      ${describe(it)}\n") }
            append("chat      $chatId\n")
            append("vault     ${vault.protection?.name?.lowercase(Locale.US) ?: "unknown"}\n")
            append("delete    ${if (store.autoDelete) "auto-delete ON" else "auto-delete OFF"}")
        }
    }

    private fun battery(): String {
        val intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "unknown"
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level < 0 || scale <= 0) return "unknown"
        return "${level * 100 / scale}%${if (plugged) ", charging" else ""}"
    }

    @Suppress("DEPRECATION")
    private fun network(): String {
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "unknown"
        // activeNetworkInfo is deprecated but is the API that exists on 27 and reports the
        // transport in one call. NetworkCapabilities would need a registered callback to be
        // meaningfully better, for one line of a status message.
        val info = manager.activeNetworkInfo ?: return "offline"
        return if (info.isConnected) info.typeName.lowercase(Locale.US) else "offline"
    }

    private fun endOfToday(): Long =
        java.time.LocalDate.now(TaskFormat.deviceZone)
            .plusDays(1)
            .atStartOfDay(TaskFormat.deviceZone)
            .toInstant()
            .toEpochMilli()

    // ------------------------------------------------------------------ shared

    private fun describe(task: Task): String = buildString {
        append(task.title)
        if (task.dueAt != null) append("  — ${dueText(task)}")
        if (task.repeat.repeats) append(", ${task.repeat.describe()}")
        if (task.status == TaskStatus.DONE) append(" (done)")
    }

    /** Rendered in the task's own zone, with the suffix §5.1c adds when it is not the device's. */
    private fun dueText(task: Task): String {
        val due = task.dueAt ?: return "no date"
        return TaskFormat.dueLine(app, due, task.zone())
    }

    private fun reply(update: TelegramClient.Update, text: String) {
        if (client.sendMessage(update.chatId, text) == null) {
            // Nothing to fall back to. Logged so a silent bot has an explanation in logcat.
            Log.w(TAG, "reply failed for update ${update.updateId}")
        }
    }

    companion object {
        private const val TAG = "InkDeckTg"

        private const val MAX_LIST_ROWS = 30
        private const val MAX_TITLE_CHARS = 80

        private const val LLM_PREFIX = "LLM_"
        private const val LLM_KEY_SUFFIX = "_API_KEY"

        /**
         * Non-secret half of a BYOK profile, for Phase 8. Kept out of the vault deliberately: a
         * base URL and a model name are not credentials, and putting them behind the data key
         * would make "which providers are configured?" a question the settings screen could only
         * answer with the vault open.
         */
        const val LLM_PREFS = "telegram_llm_profiles"

        private const val TASK_USAGE =
            "/task add <title> | <due> | <repeat>\n" +
                "/task list [today|week|overdue]\n" +
                "/task done <id>\n" +
                "/task del <id>\n" +
                "due:    today 14:00 | tomorrow 9am | 2026-07-30 14:00 | 14:00 | add UTC\n" +
                "repeat: daily | weekdays | weekly mon,wed | monthly 15 | every 3 days"

        private const val USAGE = "Send /help for the command list."

        /** design.md §12's COMMANDS block, as the bot's own `/help`. */
        const val HELP =
            "InkDeck commands\n\n" +
                "$TASK_USAGE\n\n" +
                "/note <text>\n" +
                "/llm <provider> <base_url> <model> <key>\n" +
                "/key <name> <value>\n" +
                "/status\n" +
                "/help\n\n" +
                "/llm and /key messages are deleted from this chat as soon as they are read. " +
                "Use keys you can rotate, and push .pem files over USB instead."
    }
}
