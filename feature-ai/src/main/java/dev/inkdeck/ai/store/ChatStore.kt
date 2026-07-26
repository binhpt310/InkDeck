package dev.inkdeck.ai.store

import android.content.Context
import dev.inkdeck.ai.ChatMessage
import dev.inkdeck.ai.Conversation
import org.json.JSONArray

/**
 * Persisted chat history.
 *
 * ## Why this is capped, and hard
 *
 * The device has ~550 MB free and 2 cores (Plan.md §0). `SharedPreferences` reads its whole file
 * into a map on first touch and keeps it there for the process lifetime, so an uncapped history
 * is a permanent heap cost that grows every time the user asks a long question — and an LLM
 * answer is the longest text this app ever holds. Three limits, each of which alone would be
 * insufficient:
 *
 *  - [MAX_MESSAGE_CHARS] — one runaway answer cannot dominate the file.
 *  - [MAX_MESSAGES] — one long conversation cannot dominate it either.
 *  - [TOTAL_CHAR_BUDGET] — the real ceiling. Oldest conversations are dropped until the whole
 *    document fits, so the worst case is bounded regardless of how the other two are hit.
 *
 * Trimming is silent by design at the message level (a truncated answer is marked in the text)
 * and total at the conversation level: a conversation that falls off the end is gone. That is
 * the honest trade for not having a database, and it is stated in the history picker.
 */
class ChatStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Newest first. */
    fun all(): List<Conversation> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .map { Conversation.fromJson(arr.getJSONObject(it)) }
                .sortedByDescending { it.updatedAtEpochMs }
        }.getOrDefault(emptyList())
    }

    fun find(id: String): Conversation? = all().firstOrNull { it.id == id }

    fun mostRecent(): Conversation? = all().firstOrNull()

    /** Insert or replace, then apply every cap. An empty conversation is never stored. */
    fun upsert(conversation: Conversation) {
        if (conversation.isEmpty) return
        val trimmed = trim(conversation)
        val merged = (listOf(trimmed) + all().filterNot { it.id == trimmed.id })
            .sortedByDescending { it.updatedAtEpochMs }
        write(applyBudget(merged))
    }

    fun delete(id: String) {
        write(all().filterNot { it.id == id })
    }

    fun clear() {
        prefs.edit().remove(KEY_HISTORY).commit()
    }

    private fun trim(conversation: Conversation): Conversation {
        val messages = conversation.messages
            .takeLast(MAX_MESSAGES)
            .map { truncate(it) }
        return conversation.copy(messages = messages)
    }

    private fun truncate(message: ChatMessage): ChatMessage =
        if (message.text.length <= MAX_MESSAGE_CHARS) {
            message
        } else {
            message.copy(text = message.text.take(MAX_MESSAGE_CHARS) + TRUNCATION_NOTE)
        }

    private fun applyBudget(conversations: List<Conversation>): List<Conversation> {
        val kept = ArrayList<Conversation>(conversations.size)
        var chars = 0
        for (c in conversations.take(MAX_CONVERSATIONS)) {
            val size = c.messages.sumOf { it.text.length }
            if (kept.isNotEmpty() && chars + size > TOTAL_CHAR_BUDGET) break
            kept += c
            chars += size
        }
        return kept
    }

    private fun write(conversations: List<Conversation>) {
        val arr = JSONArray()
        conversations.forEach { arr.put(it.toJson()) }
        // commit(), not apply() — see the note in ProfileStore. The ROM can force-stop this
        // process 30 s after it backgrounds and a scheduled write would go with it.
        prefs.edit().putString(KEY_HISTORY, arr.toString()).commit()
    }

    companion object {
        private const val PREFS = "inkdeck_ai"
        private const val KEY_HISTORY = "history"

        const val MAX_CONVERSATIONS = 10
        const val MAX_MESSAGES = 40
        const val MAX_MESSAGE_CHARS = 4_000
        const val TOTAL_CHAR_BUDGET = 200_000

        private const val TRUNCATION_NOTE = "\n\n[truncated — InkDeck stores 4 000 characters per message]"
    }
}
