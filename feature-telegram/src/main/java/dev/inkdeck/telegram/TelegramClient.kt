package dev.inkdeck.telegram

import android.util.Log
import dev.inkdeck.net.InkHttp
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * The Telegram Bot API, as much of it as InkDeck needs — Plan.md §7.1.
 *
 * ### Why the app is the client
 *
 * There is no GMS on this device, so FCM is impossible, and it sits behind NAT, so a webhook
 * cannot reach it. `getUpdates` long polling is the only transport left. That is a constraint,
 * not a preference.
 *
 * ### The token is not a field
 *
 * [token] is a *supplier*, invoked once per request and dropped. The Bot API puts the token in
 * the URL path, so the one place it appears is the `Request` object OkHttp is about to send —
 * never in a log line, never in a query string, never cached across the ~50 s a long poll is
 * parked. [redact] exists so failures can still be diagnosed: every log line here names the
 * method, never the URL.
 *
 * ### org.json, not a parser dependency
 *
 * `org.json` ships with the framework and the payloads are three fields deep. A JSON library
 * would be a new Gradle dependency for nothing (AGENT_BRIEF: no new dependencies).
 *
 * ### Failures return null
 *
 * Same contract as [InkHttp.getText] and for the same reason: every caller is a poll loop whose
 * correct response to a failure is to back off, not to crash a foreground service.
 */
internal class TelegramClient(private val token: () -> String?) {

    /**
     * One inbound text message. Deliberately flat — channel posts, media, inline queries and
     * edits all reach us as "something that is not a text message from the paired chat", and the
     * router's answer to all of them is the same.
     */
    data class Update(
        val updateId: Long,
        val chatId: Long,
        val messageId: Long,
        val text: String,
        val fromUsername: String?,
        val fromName: String,
    )

    /**
     * Long-poll for updates. Blocks for up to [timeoutSeconds] plus the connect time.
     *
     * [InkHttp]'s read timeout is 75 s, comfortably above the 50 s used here — a read timeout
     * below the poll timeout turns every quiet minute into a spurious network error and an
     * exponential backoff on a connection that was working.
     *
     * @return the updates, or null if the request failed. An empty list is a *successful* poll
     *   that timed out with nothing to say, and must not trigger a backoff.
     */
    fun getUpdates(offset: Long, timeoutSeconds: Int = POLL_TIMEOUT_S): List<Update>? {
        val body = FormBody.Builder()
            .add("offset", offset.toString())
            .add("timeout", timeoutSeconds.toString())
            // Everything else — edits, channel posts, callbacks — is noise this app cannot act
            // on, and asking for it only widens what has to be skipped over.
            .add("allowed_updates", """["message"]""")
            .build()

        val result = call("getUpdates", body) ?: return null
        val array = result.optJSONArray("result") ?: JSONArray()
        return (0 until array.length()).mapNotNull { parseUpdate(array.optJSONObject(it)) }
    }

    /** @return the sent message id, or null on failure. */
    fun sendMessage(chatId: Long, text: String): Long? {
        val body = FormBody.Builder()
            .add("chat_id", chatId.toString())
            // No parse_mode on purpose. Replies quote user input — a task title, a base URL, an
            // error string — and Markdown would need every one of those escaped, with a stray
            // underscore in a model name turning the reply into a 400. Plain text always sends.
            .add("text", text.take(MAX_MESSAGE_CHARS))
            .add("disable_web_page_preview", "true")
            .build()
        val result = call("sendMessage", body) ?: return null
        return result.optJSONObject("result")?.optLong("message_id")?.takeIf { it != 0L }
    }

    /**
     * Delete a message — Plan.md §7.4 mitigation 1, the reason this module exists in the shape it
     * does. @return true only if Telegram confirmed the deletion.
     *
     * Bots may delete an incoming message in a private chat only within 48 h, which is never a
     * constraint here: this is called seconds after the message arrives. The realistic failure is
     * no network, and the caller says so in its reply rather than letting the user assume the key
     * is gone from the history when it is not.
     */
    fun deleteMessage(chatId: Long, messageId: Long): Boolean {
        val body = FormBody.Builder()
            .add("chat_id", chatId.toString())
            .add("message_id", messageId.toString())
            .build()
        return call("deleteMessage", body)?.optBoolean("result") == true
    }

    /** The bot's `@username`, for the settings header. Null if the token or network is bad. */
    fun getMe(): String? =
        call("getMe", FormBody.Builder().build())
            ?.optJSONObject("result")
            ?.optString("username")
            ?.takeIf { it.isNotEmpty() }

    // ------------------------------------------------------------------ transport

    private fun call(method: String, body: FormBody): JSONObject? {
        val secret = token()
        if (secret.isNullOrBlank()) {
            Log.w(TAG, "$method: no bot token in the vault")
            return null
        }

        // The token is in the path because that is the Bot API's shape. It is built here, handed
        // straight to OkHttp, and never reaches a log — see the class doc.
        val request = Request.Builder()
            .url("$API_BASE$secret/$method")
            .post(body)
            .build()

        return try {
            InkHttp.client.newCall(request).execute().use { response ->
                val text = response.body?.string()
                if (!response.isSuccessful) {
                    // Telegram puts a readable reason in `description`; the HTTP code alone
                    // cannot tell "wrong token" from "chat not found".
                    Log.w(TAG, "$method -> HTTP ${response.code} ${describe(text)}")
                    return null
                }
                val json = text?.let { JSONObject(it) } ?: return null
                if (!json.optBoolean("ok")) {
                    Log.w(TAG, "$method -> not ok: ${json.optString("description")}")
                    return null
                }
                json
            }
        } catch (e: IOException) {
            // Expected constantly on a tablet that spends most of its life off wifi. The poll
            // loop's backoff is the response; a stack trace per minute is not.
            Log.d(TAG, "$method failed: ${e.javaClass.simpleName} ${redact(e.message)}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "$method failed: ${e.javaClass.simpleName} ${redact(e.message)}")
            null
        }
    }

    private fun describe(body: String?): String =
        runCatching { JSONObject(body.orEmpty()).optString("description") }
            .getOrDefault("")
            .ifEmpty { "(no description)" }

    /**
     * OkHttp and the JDK both put the failing URL in exception messages, and that URL contains
     * the token. Anything shaped like `bot<digits>:<base64ish>` is replaced before it can be
     * written to logcat, which is world-readable to anything holding READ_LOGS.
     */
    private fun redact(message: String?): String =
        message.orEmpty().replace(TOKEN_IN_TEXT, "bot<redacted>")

    private fun parseUpdate(json: JSONObject?): Update? {
        if (json == null) return null
        val message = json.optJSONObject("message") ?: return null
        val chat = message.optJSONObject("chat") ?: return null
        val text = message.optString("text")
        if (text.isEmpty()) return null

        val from = message.optJSONObject("from")
        return Update(
            updateId = json.optLong("update_id"),
            chatId = chat.optLong("id"),
            messageId = message.optLong("message_id"),
            text = text,
            fromUsername = from?.optString("username")?.takeIf { it.isNotEmpty() },
            fromName = listOfNotNull(
                from?.optString("first_name")?.takeIf { it.isNotEmpty() },
                from?.optString("last_name")?.takeIf { it.isNotEmpty() },
            ).joinToString(" ").ifEmpty { "unknown" },
        )
    }

    companion object {
        private const val TAG = "InkDeckTg"

        private const val API_BASE = "https://api.telegram.org/bot"

        /** Plan.md §7.3's foreground interval. */
        const val POLL_TIMEOUT_S = 50

        /** Telegram's hard limit. Truncating beats a 400 that loses the whole reply. */
        private const val MAX_MESSAGE_CHARS = 4096

        private val TOKEN_IN_TEXT = Regex("""bot\d{5,}:[A-Za-z0-9_-]+""")
    }
}
