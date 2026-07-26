package dev.inkdeck.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * The chat data model — deliberately not a Room entity.
 *
 * `:core-data` owns the database and is not this module's to edit (docs/AGENT_BRIEF.md), and a
 * conversation list is the wrong shape for Room anyway: it is read whole, written whole, and
 * never queried. [ChatStore] persists these as one JSON document.
 */
enum class Role {
    USER,
    ASSISTANT;

    companion object {
        fun parse(name: String?): Role = if (name == ASSISTANT.name) ASSISTANT else USER
    }
}

data class ChatMessage(
    val role: Role,
    val text: String,
    val atEpochMs: Long,
    /**
     * A failed turn is kept in the transcript rather than discarded — on a device that sleeps
     * mid-request, "the answer never arrived" is information, and a bubble that silently
     * vanishes reads as a UI bug. Error bubbles are never sent back as context.
     */
    val isError: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ROLE, role.name)
        put(KEY_TEXT, text)
        put(KEY_AT, atEpochMs)
        if (isError) put(KEY_ERROR, true)
    }

    companion object {
        private const val KEY_ROLE = "role"
        private const val KEY_TEXT = "text"
        private const val KEY_AT = "at"
        private const val KEY_ERROR = "error"

        fun fromJson(o: JSONObject): ChatMessage = ChatMessage(
            role = Role.parse(o.optString(KEY_ROLE)),
            text = o.optString(KEY_TEXT),
            atEpochMs = o.optLong(KEY_AT),
            isError = o.optBoolean(KEY_ERROR, false),
        )
    }
}

data class Conversation(
    val id: String,
    val messages: List<ChatMessage>,
    val updatedAtEpochMs: Long,
) {
    /**
     * Derived, never stored: the first user line is what the conversation is about, and keeping
     * a separate editable title would be a rename UI nobody asked for.
     */
    val title: String
        get() = messages.firstOrNull { it.role == Role.USER }
            ?.text
            ?.replace('\n', ' ')
            ?.trim()
            ?.take(TITLE_CHARS)
            ?.ifEmpty { null }
            ?: UNTITLED

    val isEmpty: Boolean get() = messages.none { it.text.isNotBlank() }

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_UPDATED, updatedAtEpochMs)
        put(KEY_MESSAGES, JSONArray().also { arr -> messages.forEach { arr.put(it.toJson()) } })
    }

    companion object {
        const val UNTITLED = "New chat"
        private const val TITLE_CHARS = 60

        private const val KEY_ID = "id"
        private const val KEY_UPDATED = "updated"
        private const val KEY_MESSAGES = "messages"

        fun blank(nowMs: Long): Conversation =
            Conversation(id = "c${nowMs}_${(0..0xFFFF).random().toString(16)}", messages = emptyList(), updatedAtEpochMs = nowMs)

        fun fromJson(o: JSONObject): Conversation {
            val arr = o.optJSONArray(KEY_MESSAGES) ?: JSONArray()
            return Conversation(
                id = o.optString(KEY_ID),
                updatedAtEpochMs = o.optLong(KEY_UPDATED),
                messages = (0 until arr.length()).map { ChatMessage.fromJson(arr.getJSONObject(it)) },
            )
        }
    }
}
