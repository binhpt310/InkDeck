package dev.inkdeck.ai.provider

import android.util.Log
import dev.inkdeck.ai.ChatMessage
import dev.inkdeck.ai.Role
import dev.inkdeck.ai.store.AiProfile
import dev.inkdeck.net.InkHttp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Everything the two providers share: build a POST, execute it on [InkHttp.client], and walk the
 * `text/event-stream` response.
 *
 * What is *not* shared is the whole point of the split — auth header, body shape and event
 * vocabulary differ between OpenAI-compatible endpoints and Anthropic, and every one of those
 * differences lives in a subclass so nothing above this package has to know which is in use.
 */
abstract class SseChatProvider : AiProvider {

    /** Full URL to POST to, derived from the profile's base URL. */
    protected abstract fun endpoint(profile: AiProfile): String

    /** Add the provider's auth and version headers. Called with the key still in a byte array. */
    protected abstract fun authorise(builder: Request.Builder, apiKey: ByteArray?)

    protected abstract fun body(profile: AiProfile, messages: List<ChatMessage>): JSONObject

    /**
     * @return the text delta in this event, or null if the event carries none. Most events in
     *   both protocols carry none — pings, role announcements, usage and stop reasons.
     */
    protected abstract fun deltaOf(event: String?, data: JSONObject): String?

    /** True when this event ends the stream cleanly. */
    protected abstract fun isTerminal(event: String?, data: JSONObject): Boolean

    /** A provider-shaped error delivered *inside* a 200 stream, if this protocol does that. */
    protected open fun streamError(event: String?, data: JSONObject): String? = null

    final override fun stream(
        profile: AiProfile,
        messages: List<ChatMessage>,
        apiKey: ByteArray?,
        handle: StreamHandle,
        onDelta: (String) -> Unit,
    ): StreamResult {
        val url = endpoint(profile)
        val request = try {
            Request.Builder()
                .url(url)
                .post(body(profile, messages).toString().toRequestBody(JSON))
                .header("Accept", "text/event-stream")
                .also { authorise(it, apiKey) }
                .build()
        } catch (e: IllegalArgumentException) {
            // Almost always a base URL the user typed by hand.
            return StreamResult.Failed("That base URL is not a valid HTTP address.")
        }

        val call = InkHttp.client.newCall(request)
        handle.attach(call)

        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    StreamResult.Failed(httpError(response))
                } else {
                    consume(response, handle, onDelta)
                }
            }
        } catch (e: IOException) {
            if (handle.isCancelled) {
                StreamResult.Cancelled
            } else {
                // Host only. A full URL is harmless here but the habit of logging request lines
                // is how keys end up in logcat, and this class handles keys.
                Log.w(TAG, "stream failed host=${request.url.host}", e)
                StreamResult.Failed(networkError(e))
            }
        }
    }

    /**
     * The SSE frame loop.
     *
     * Server-sent events are `field: value` lines terminated by a blank line. Both protocols use
     * only `event:` and `data:`, and a frame may carry several `data:` lines that concatenate.
     * Anything else — `id:`, `retry:`, `:` heartbeat comments — is skipped rather than treated as
     * an error, because a proxy in front of a self-hosted endpoint is free to insert them.
     */
    private fun consume(
        response: Response,
        handle: StreamHandle,
        onDelta: (String) -> Unit,
    ): StreamResult {
        val source = response.body?.source()
            ?: return StreamResult.Failed("The endpoint returned an empty response.")

        var event: String? = null
        val data = StringBuilder()

        while (true) {
            if (handle.isCancelled) return StreamResult.Cancelled

            val line = source.readUtf8Line() ?: break

            when {
                line.isEmpty() -> {
                    val outcome = dispatch(event, data.toString(), onDelta)
                    event = null
                    data.setLength(0)
                    if (outcome != null) return outcome
                }

                line.startsWith(FIELD_DATA) -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring(FIELD_DATA.length).removePrefix(" "))
                }

                line.startsWith(FIELD_EVENT) ->
                    event = line.substring(FIELD_EVENT.length).trim()
            }
        }

        // A stream that ends without its terminal event is not an error: several
        // OpenAI-compatible servers simply close the socket after the last chunk.
        dispatch(event, data.toString(), onDelta)
        return StreamResult.Ok
    }

    /** @return non-null to stop reading. */
    private fun dispatch(event: String?, raw: String, onDelta: (String) -> Unit): StreamResult? {
        val payload = raw.trim()
        if (payload.isEmpty()) return null
        if (payload == DONE_SENTINEL) return StreamResult.Ok

        val json = runCatching { JSONObject(payload) }.getOrNull()
            ?: return null // a fragment we do not understand is not worth failing the answer over

        streamError(event, json)?.let { return StreamResult.Failed(it) }
        deltaOf(event, json)?.let { if (it.isNotEmpty()) onDelta(it) }
        return if (isTerminal(event, json)) StreamResult.Ok else null
    }

    // ---------------------------------------------------------------- errors

    /**
     * Both protocols report failures as `{"error": {"message": …}}`, and that message is the only
     * thing that tells a user whether they pasted the wrong key, named a model that does not
     * exist, or ran out of credit. Status code alone is useless to them.
     */
    private fun httpError(response: Response): String {
        // An error body is small and is not a stream, so read it whole — capped, because a
        // misconfigured base URL can point at something that answers with a web page.
        val body = runCatching { response.body?.string()?.take(MAX_ERROR_BODY) }.getOrNull()
        val detail = body
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?.optJSONObject("error")
            ?.optString("message")
            ?.takeIf { it.isNotEmpty() }

        return when {
            detail != null -> "HTTP ${response.code} — ${detail.take(MAX_ERROR_CHARS)}"
            response.code == 401 || response.code == 403 ->
                "HTTP ${response.code} — the endpoint rejected the API key."
            response.code == 404 ->
                "HTTP 404 — check the base URL and the model id."
            response.code == 429 ->
                "HTTP 429 — rate limited. Wait and retry."
            else -> "HTTP ${response.code} from the endpoint."
        }
    }

    private fun networkError(e: IOException): String {
        val reason = e.message?.take(MAX_ERROR_CHARS).orEmpty()
        return when {
            reason.contains("timeout", ignoreCase = true) ->
                "The endpoint stopped responding. E-ink readers sleep aggressively — try again."
            reason.contains("Unable to resolve host", ignoreCase = true) ->
                "Can't resolve that host. Check wifi and the base URL."
            reason.isEmpty() -> "Network error."
            else -> reason
        }
    }

    // ---------------------------------------------------------------- helpers for subclasses

    /**
     * Join a user-typed base URL to a versioned path without doubling the version segment.
     *
     * A user pastes whichever of these their provider's docs showed them, and both must work:
     * `https://api.anthropic.com` and `http://192.168.1.20:11434/v1` (the design.md §10.1
     * example). The base URL is the API *root*, never a full endpoint path.
     */
    protected fun versionedUrl(base: String, tail: String): String {
        val b = base.trim().trimEnd('/')
        return if (b.endsWith("/$VERSION_SEGMENT")) "$b/$tail" else "$b/$VERSION_SEGMENT/$tail"
    }

    /** `[{role, content}]`, error bubbles dropped — they are our text, not the model's. */
    protected fun messagesArray(messages: List<ChatMessage>): JSONArray {
        val arr = JSONArray()
        messages
            .filterNot { it.isError || it.text.isBlank() }
            .forEach { m ->
                arr.put(
                    JSONObject()
                        .put("role", if (m.role == Role.USER) "user" else "assistant")
                        .put("content", m.text)
                )
            }
        return arr
    }

    protected companion object {
        const val TAG = "InkDeckAi"

        val JSON = "application/json; charset=utf-8".toMediaType()

        private const val FIELD_DATA = "data:"
        private const val FIELD_EVENT = "event:"
        private const val DONE_SENTINEL = "[DONE]"
        private const val VERSION_SEGMENT = "v1"
        private const val MAX_ERROR_CHARS = 300
        private const val MAX_ERROR_BODY = 4_000
    }
}
