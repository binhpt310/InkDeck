package dev.inkdeck.telegram

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Outbound messages waiting for a working connection.
 *
 * ### Why a queue is worth having here, when a retry queue generally is not
 *
 * Plan.md §5.1 sketches WorkManager for retryable delivery, and that is genuinely impossible on
 * this device: `AlarmManager` is refused (§5.1b) and `JobScheduler`, which WorkManager sits on,
 * is cancelled by the force-stop sweep (§5.1b-2). A queue that needs waking cannot be woken.
 *
 * **This one does not need waking.** The only window in which a send to Telegram can succeed at
 * all is the window in which [TelegramService] is already looping, and the loop drains this at
 * the top of every cycle. So the queue covers the whole of the reachable window without any
 * scheduler — it converts "the network was down for the 200 ms the send took" from a lost
 * reminder into a 50-second delay, and it survives the MOGU sweep killing a send in flight.
 *
 * ### TTL is short, and shorter than you would pick for a mail queue
 *
 * 12 h. A reminder delivered two days late is worse than one never delivered: it arrives as a
 * live alert for something long past, and the task is already sitting under `OVERDUE` where it
 * can be read calmly. This is the same argument `ReminderTicker.GRACE_MS` makes, at a different
 * scale — 5 minutes there covers a missed 30 s tick on a running device, 12 h here covers a
 * device that was off overnight and is worth catching up on when it comes back.
 *
 * ### What is in an entry, and what is deliberately not
 *
 * The rendered text and a dedupe key. **No token and no chat id** — both are read from the vault
 * at send time by [TelegramNotifier], so a queue file that outlives a re-pair cannot deliver to
 * the old chat, and nothing credential-shaped is ever written to preferences. The text is a task
 * title, which is user content and no more sensitive than the Room database it came from.
 */
internal object OutboundQueue {

    private const val TAG = "InkDeckTg"

    private const val PREFS = "telegram_outbound"
    private const val KEY_QUEUE = "queue"

    private const val FIELD_KEY = "k"
    private const val FIELD_TEXT = "t"
    private const val FIELD_AT = "at"

    /** 12 h — see the class doc. */
    const val TTL_MS = 12 * 60 * 60 * 1000L

    /**
     * Oldest-first eviction past this. ~550 MB free on this device and nobody reads the 33rd
     * backlogged reminder; the newest are the ones still worth arriving.
     */
    const val MAX_ENTRIES = 32

    data class Entry(val key: String, val text: String, val at: Long)

    /**
     * Add a message unless [key] is already waiting.
     *
     * Dedupe is **within the queue only**, deliberately. `ReminderTicker` keeps its own
     * `fired` set and states outright that it does not persist it, so a reminder may fire twice
     * if the process restarts inside its grace window — "the right way round to fail for
     * something whose whole job is not to be missed". A persisted sent-key set here would quietly
     * override that decision from another module.
     *
     * @return true if the queue now holds this message (including when it already did).
     */
    @Synchronized
    fun enqueue(context: Context, key: String, text: String, now: Long = System.currentTimeMillis()): Boolean {
        val entries = read(context, now).toMutableList()

        if (entries.any { it.key == key }) {
            Log.d(TAG, "outbound already queued key=$key")
            return true
        }

        entries += Entry(key, text, now)
        while (entries.size > MAX_ENTRIES) {
            val dropped = entries.removeAt(0)
            Log.w(TAG, "outbound queue full, dropped key=${dropped.key}")
        }

        write(context, entries)
        // Phase 9 item 6: was Log.i on every enqueue. The queue drains at the top of every poll
        // cycle (Plan §5.1e), so a single reminder can produce one queued-log + one drain-log +
        // one sent-log in close succession. Demoted to Log.d so the audit trail survives in
        // `logcat -d` without drowning live observation.
        Log.d(TAG, "outbound queued key=$key depth=${entries.size}")
        return true
    }

    @Synchronized
    fun size(context: Context): Int = read(context).size

    /**
     * Try to send everything waiting, oldest first.
     *
     * [send] returns false when the message did not go out; the drain **stops there** rather than
     * working through the rest. A failure is almost always no network, and the remaining sends
     * would each burn a connect timeout inside the poll loop for a guaranteed failure.
     *
     * The lock is not held across [send]. Each success is committed before the next attempt, so a
     * sweep mid-drain loses at most the message in flight — and re-sending that one on the next
     * cycle is the correct way round to fail.
     *
     * @return how many were sent.
     */
    fun drain(context: Context, send: (String) -> Boolean): Int {
        val pending = synchronized(this) { read(context, System.currentTimeMillis()) }
        if (pending.isEmpty()) return 0

        // Phase 9 item 6: drain start/sent are routine; promote to Log.d so a busy reminder
        // channel does not flood Log.i. The "drain stopped after N" line is unchanged — that is
        // the actual signal of a stuck network, and is the one a reader of the log wants.
        Log.d(TAG, "outbound drain start depth=${pending.size}")
        var sent = 0
        for (entry in pending) {
            if (!send(entry.text)) {
                Log.d(TAG, "outbound drain stopped after $sent, ${pending.size - sent} left")
                break
            }
            synchronized(this) { remove(context, entry.key) }
            sent++
        }
        if (sent > 0) Log.d(TAG, "outbound drain sent=$sent")
        return sent
    }

    @Synchronized
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_QUEUE).apply()
    }

    // ------------------------------------------------------------------ storage

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Read, dropping anything past [TTL_MS]. Pruning on read rather than on a timer is the whole
     * reason this needs no scheduler — every path that touches the queue is already awake.
     */
    private fun read(context: Context, now: Long = System.currentTimeMillis()): List<Entry> {
        val raw = prefs(context).getString(KEY_QUEUE, null) ?: return emptyList()

        val parsed = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                val text = json.optString(FIELD_TEXT)
                if (text.isEmpty()) return@mapNotNull null
                Entry(json.optString(FIELD_KEY), text, json.optLong(FIELD_AT))
            }
        }.getOrElse {
            // A corrupt queue file is not worth crashing a foreground service over, and there is
            // nothing to recover from half a JSON array.
            Log.w(TAG, "outbound queue unreadable, discarding", it)
            prefs(context).edit().remove(KEY_QUEUE).apply()
            return emptyList()
        }

        val live = parsed.filter { now - it.at <= TTL_MS }
        if (live.size != parsed.size) {
            // Key and age only. The text is a task title and does not belong in logcat.
            // Phase 9 item 6: was Log.i; the prune is on a per-read basis and fires for every
            // entry older than TTL_MS each time the queue is read. Demoted to Log.d.
            parsed.filterNot { it in live }.forEach {
                Log.d(TAG, "outbound expired key=${it.key} age=${(now - it.at) / 60_000}min")
            }
            write(context, live)
        }
        return live
    }

    private fun write(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject()
                    .put(FIELD_KEY, it.key)
                    .put(FIELD_TEXT, it.text)
                    .put(FIELD_AT, it.at)
            )
        }
        // commit, not apply: the caller is often a BroadcastReceiver about to let its process be
        // killed, and an async write is exactly what the MOGU sweep would lose.
        prefs(context).edit().putString(KEY_QUEUE, array.toString()).commit()
    }

    private fun remove(context: Context, key: String) {
        write(context, read(context).filterNot { it.key == key })
    }
}
