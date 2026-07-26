package dev.inkdeck.ai.provider

import dev.inkdeck.ai.ChatMessage
import dev.inkdeck.ai.store.AiProfile
import okhttp3.Request
import org.json.JSONObject

/**
 * Any endpoint that speaks the OpenAI chat-completions protocol — OpenAI itself, Ollama's `/v1`
 * shim, vLLM, LM Studio, OpenRouter, a llama.cpp server.
 *
 * ```
 *   POST {base}/v1/chat/completions
 *   Authorization: Bearer …
 *   { "model": …, "stream": true, "messages": [ {role, content} … ] }
 *
 *   data: {"choices":[{"delta":{"content":"Hel"}}]}
 *   data: {"choices":[{"delta":{"content":"lo"}}]}
 *   data: [DONE]
 * ```
 *
 * There are no named events in this protocol — everything arrives as an unnamed `data:` frame and
 * the stream ends with the literal `[DONE]` sentinel, handled in [SseChatProvider].
 */
object OpenAiCompatProvider : SseChatProvider() {

    override fun endpoint(profile: AiProfile): String =
        versionedUrl(profile.baseUrl, "chat/completions")

    override fun authorise(builder: Request.Builder, apiKey: ByteArray?) {
        if (apiKey == null || apiKey.isEmpty()) return
        // A local endpoint often wants no key at all, which is why this is optional rather than
        // an error. The header String below is the one unavoidable copy: OkHttp's header API
        // takes a String and there is no way to hand it bytes. It dies with the request.
        builder.header("Authorization", "Bearer " + String(apiKey, Charsets.UTF_8))
    }

    override fun body(profile: AiProfile, messages: List<ChatMessage>): JSONObject =
        JSONObject()
            .put("model", profile.model)
            .put("stream", true)
            .put("messages", messagesArray(messages))

    override fun deltaOf(event: String?, data: JSONObject): String? {
        val choices = data.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val choice = choices.optJSONObject(0) ?: return null
        // `delta` while streaming; `message` is the non-streaming shape, accepted because some
        // self-hosted servers ignore "stream" and answer in one frame. Without this fallback
        // those endpoints look like they returned nothing at all.
        return choice.optJSONObject("delta")?.optString("content")?.ifEmpty { null }
            ?: choice.optJSONObject("message")?.optString("content")?.ifEmpty { null }
    }

    override fun isTerminal(event: String?, data: JSONObject): Boolean = false

    override fun streamError(event: String?, data: JSONObject): String? =
        data.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }
}
