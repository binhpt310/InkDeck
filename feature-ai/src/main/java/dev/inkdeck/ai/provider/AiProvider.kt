package dev.inkdeck.ai.provider

import dev.inkdeck.ai.ChatMessage
import dev.inkdeck.ai.store.AiProfile
import dev.inkdeck.ai.store.ProviderKind
import okhttp3.Call

/**
 * One streamed completion.
 *
 * Blocking on purpose: OkHttp's own call is blocking, the SSE loop is a read loop, and wrapping
 * it in a callback API would only move the thread hop somewhere less obvious. Callers run it on
 * `Dispatchers.IO`.
 */
interface AiProvider {

    /**
     * @param apiKey read from the vault by the caller **immediately before this call** and zeroed
     *   immediately after. Null for a keyless endpoint (a LAN Ollama, for instance). Nothing here
     *   retains it, logs it, or puts it in a URL.
     * @param onDelta called from this thread for every text fragment the endpoint emits. It must
     *   be cheap — it feeds [dev.inkdeck.ai.ChunkBuffer], which is what actually paces the UI.
     */
    fun stream(
        profile: AiProfile,
        messages: List<ChatMessage>,
        apiKey: ByteArray?,
        handle: StreamHandle,
        onDelta: (String) -> Unit,
    ): StreamResult

    companion object {
        fun of(kind: ProviderKind): AiProvider = when (kind) {
            ProviderKind.ANTHROPIC -> AnthropicProvider
            ProviderKind.OPENAI_COMPATIBLE -> OpenAiCompatProvider
        }
    }
}

sealed class StreamResult {
    object Ok : StreamResult()

    /** The user pressed Stop. Whatever arrived stays on screen; this is not an error. */
    object Cancelled : StreamResult()

    /** [message] is safe to show: it never contains the key, and never a raw stack trace. */
    data class Failed(val message: String) : StreamResult()
}

/**
 * The Stop button, and the only way to interrupt a read that is blocked waiting for the next
 * token.
 *
 * Cancelling the coroutine is not enough — `source.readUtf8Line()` is a blocking socket read and
 * does not observe cancellation, so a stopped response would keep the connection and its thread
 * alive until the 75 s read timeout in `InkHttp`. `Call.cancel()` closes the socket underneath
 * it, which is what makes Stop instant.
 *
 * [attach] handles the race where Stop is pressed between building the call and starting it.
 */
class StreamHandle {

    @Volatile
    private var call: Call? = null

    @Volatile
    var isCancelled: Boolean = false
        private set

    fun attach(c: Call) {
        call = c
        if (isCancelled) c.cancel()
    }

    fun cancel() {
        isCancelled = true
        call?.cancel()
    }
}
