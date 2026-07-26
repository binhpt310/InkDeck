package dev.inkdeck.ai.provider

import dev.inkdeck.ai.ChatMessage
import dev.inkdeck.ai.store.AiProfile
import okhttp3.Request
import org.json.JSONObject

/**
 * Anthropic's Messages API.
 *
 * ```
 *   POST {base}/v1/messages
 *   x-api-key: …
 *   anthropic-version: 2023-06-01
 *   { "model": …, "max_tokens": …, "stream": true, "messages": [ {role, content} … ] }
 *
 *   event: content_block_delta
 *   data: {"delta":{"type":"text_delta","text":"Hel"}}
 *   event: message_stop
 *   data: {"type":"message_stop"}
 * ```
 *
 * Four differences from the OpenAI shape, all of them contained here:
 *
 *  1. `x-api-key`, not `Authorization: Bearer`.
 *  2. `anthropic-version` is mandatory — omit it and every request is a 400.
 *  3. `max_tokens` is mandatory, where OpenAI treats it as optional.
 *  4. The stream is a sequence of **named** events with an explicit `message_stop`, rather than
 *     unnamed frames ending in `[DONE]`.
 */
object AnthropicProvider : SseChatProvider() {

    override fun endpoint(profile: AiProfile): String = versionedUrl(profile.baseUrl, "messages")

    override fun authorise(builder: Request.Builder, apiKey: ByteArray?) {
        builder.header(HEADER_VERSION, API_VERSION)
        if (apiKey == null || apiKey.isEmpty()) return
        // See the note in OpenAiCompatProvider: the header String is the one copy we cannot
        // avoid, and it is scoped to the request.
        builder.header(HEADER_KEY, String(apiKey, Charsets.UTF_8))
    }

    override fun body(profile: AiProfile, messages: List<ChatMessage>): JSONObject =
        JSONObject()
            .put("model", profile.model)
            .put("stream", true)
            // Required by the API. 4 096 rather than the model maximum on purpose: this panel
            // shows ~30 lines at a time and the history cap in ChatStore is 4 000 characters per
            // message, so a longer answer would be truncated on the way to disk anyway.
            .put("max_tokens", MAX_TOKENS)
            .put("messages", messagesArray(messages))

    override fun deltaOf(event: String?, data: JSONObject): String? {
        if (event != null && event != EVENT_DELTA) return null
        val delta = data.optJSONObject("delta") ?: return null
        // Only text_delta. `input_json_delta` (tool use) and `thinking_delta` also arrive on this
        // event and are not text the reader asked for; appending them would splice JSON into the
        // middle of the answer.
        if (delta.optString("type") != TYPE_TEXT_DELTA) return null
        return delta.optString("text").ifEmpty { null }
    }

    override fun isTerminal(event: String?, data: JSONObject): Boolean =
        event == EVENT_STOP || data.optString("type") == EVENT_STOP

    override fun streamError(event: String?, data: JSONObject): String? {
        if (event != EVENT_ERROR && data.optString("type") != EVENT_ERROR) return null
        return data.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
            ?: "The endpoint reported an error mid-response."
    }

    private const val HEADER_KEY = "x-api-key"
    private const val HEADER_VERSION = "anthropic-version"
    private const val API_VERSION = "2023-06-01"
    private const val MAX_TOKENS = 4_096

    private const val EVENT_DELTA = "content_block_delta"
    private const val EVENT_STOP = "message_stop"
    private const val EVENT_ERROR = "error"
    private const val TYPE_TEXT_DELTA = "text_delta"
}
