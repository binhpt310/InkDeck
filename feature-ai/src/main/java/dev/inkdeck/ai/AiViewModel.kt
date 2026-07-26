package dev.inkdeck.ai

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.inkdeck.ai.provider.AiProvider
import dev.inkdeck.ai.provider.StreamHandle
import dev.inkdeck.ai.provider.StreamResult
import dev.inkdeck.ai.store.AiProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State for the AI tab — design.md §10.
 *
 * [streamingTick] exists so the Fragment can tell the two cases apart without diffing text: a new
 * message means the whole list changed (`[F]`), while a tick with the same message count means
 * only the last bubble grew (`[P]`, the ≤ 2 Hz case in design.md §13). Getting that wrong is the
 * difference between one flush per answer and one per chunk.
 */
data class AiUiState(
    val profiles: List<AiProfile> = emptyList(),
    val active: AiProfile? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isStreaming: Boolean = false,
    val streamingTick: Int = 0,
    /** 0–5, advanced one step per flushed chunk. Feeds the StepBar in the assistant bubble. */
    val workingStep: Int = 0,
)

class AiViewModel(app: Application) : AndroidViewModel(app) {

    private val profileStore = AiGraph.profiles(app)
    private val chatStore = AiGraph.chats(app)

    private val _state = MutableStateFlow(AiUiState())
    val state: StateFlow<AiUiState> = _state.asStateFlow()

    private var conversation: Conversation = Conversation.blank(System.currentTimeMillis())

    private var streamJob: Job? = null
    private var handle: StreamHandle? = null

    init {
        // The profile list is a few hundred bytes and is needed to draw the first frame, so it is
        // read here. The transcript is not: `SharedPreferences` parses its whole file on first
        // touch, the history budget allows 200 KB of it, and two ~1 GHz cores turn that into
        // visible jank at the moment the tab opens.
        _state.value = AiUiState(
            profiles = profileStore.all(),
            active = profileStore.active(),
        )
        viewModelScope.launch {
            val recent = withContext(Dispatchers.IO) { chatStore.mostRecent() } ?: return@launch
            // A user who typed before the read finished owns the screen; do not overwrite them.
            if (_state.value.messages.isNotEmpty() || _state.value.isStreaming) return@launch
            conversation = recent
            _state.value = _state.value.copy(messages = recent.messages)
        }
    }

    // ---------------------------------------------------------------- profiles

    fun reloadProfiles() {
        _state.value = _state.value.copy(
            profiles = profileStore.all(),
            active = profileStore.active(),
        )
    }

    fun setActiveProfile(id: String) {
        profileStore.setActive(id)
        reloadProfiles()
    }

    // ---------------------------------------------------------------- conversations

    fun conversations(): List<Conversation> = chatStore.all()

    fun newConversation() {
        if (_state.value.isStreaming) stop()
        persist()
        conversation = Conversation.blank(System.currentTimeMillis())
        _state.value = _state.value.copy(messages = emptyList(), workingStep = 0)
    }

    fun openConversation(id: String) {
        if (_state.value.isStreaming) stop()
        persist()
        conversation = chatStore.find(id) ?: return
        _state.value = _state.value.copy(messages = conversation.messages, workingStep = 0)
    }

    fun deleteConversation(id: String) {
        chatStore.delete(id)
        if (conversation.id == id) newConversation()
    }

    // ---------------------------------------------------------------- sending

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.isStreaming) return

        val profile = _state.value.active ?: run {
            appendError(getApplication<Application>().getString(R.string.ai_error_no_profile))
            return
        }

        val now = System.currentTimeMillis()
        val history = _state.value.messages +
            ChatMessage(Role.USER, prompt, now) +
            ChatMessage(Role.ASSISTANT, "", now)

        _state.value = _state.value.copy(
            messages = history,
            isStreaming = true,
            streamingTick = 0,
            workingStep = 0,
        )

        val buffer = ChunkBuffer()
        val streamHandle = StreamHandle()
        handle = streamHandle

        streamJob = viewModelScope.launch {
            // The pacing driver. It ticks faster than the buffer will ever emit — ChunkBuffer's
            // own 500 ms gate decides the cadence, this only decides the granularity of noticing.
            val pump = launch {
                while (isActive) {
                    delay(ChunkBuffer.POLL_INTERVAL_MS)
                    buffer.poll(SystemClock.uptimeMillis())?.let { appendToLast(it) }
                }
            }

            val result = withContext(Dispatchers.IO) {
                runStream(profile, contextFor(history), buffer, streamHandle)
            }

            pump.cancel()
            buffer.poll(SystemClock.uptimeMillis(), finish = true)?.let { appendToLast(it) }
            finish(result)
        }
    }

    /** Interrupt in flight. Whatever arrived stays on screen and is persisted. */
    fun stop() {
        handle?.cancel()
    }

    /**
     * Reads the key from the vault, makes the call, zeroes the key. The array exists for the
     * duration of one request and is never held in a field — Plan.md §4.3 and the brief's
     * non-negotiables.
     */
    private fun runStream(
        profile: AiProfile,
        messages: List<ChatMessage>,
        buffer: ChunkBuffer,
        streamHandle: StreamHandle,
    ): StreamResult {
        val app = getApplication<Application>()

        var key: ByteArray? = null
        if (profile.hasKey) {
            if (!AiGraph.ensureUnlocked(app)) {
                return StreamResult.Failed(app.getString(R.string.ai_error_vault_locked))
            }
            key = runCatching { AiGraph.vault(app).get(profile.keyVaultId) }.getOrNull()
                ?: return StreamResult.Failed(app.getString(R.string.ai_error_key_missing))
        }

        return try {
            AiProvider.of(profile.kind).stream(profile, messages, key, streamHandle) { delta ->
                buffer.append(delta, SystemClock.uptimeMillis())
            }
        } catch (e: Exception) {
            // Never the message alone — a provider exception can echo the request. Log the class,
            // show a generic line.
            Log.w(TAG, "stream aborted: ${e.javaClass.simpleName}")
            StreamResult.Failed(app.getString(R.string.ai_error_generic))
        } finally {
            key?.fill(0)
        }
    }

    /**
     * The history actually sent. Capped rather than unbounded because every turn re-uploads the
     * whole transcript: on this device that is both the request size and the bill.
     */
    private fun contextFor(messages: List<ChatMessage>): List<ChatMessage> =
        messages.filterNot { it.isError || it.text.isBlank() }.takeLast(CONTEXT_MESSAGES)

    private fun appendToLast(chunk: String) {
        val messages = _state.value.messages
        val last = messages.lastOrNull() ?: return
        if (last.role != Role.ASSISTANT) return

        _state.value = _state.value.copy(
            messages = messages.dropLast(1) + last.copy(text = last.text + chunk),
            streamingTick = _state.value.streamingTick + 1,
            // One step per chunk, wrapping. Not an animation: it advances only when real text
            // arrives, at most twice a second, which is the same budget as the text itself.
            workingStep = (_state.value.workingStep + 1) % (STEP_BAR_STEPS + 1),
        )
    }

    private fun finish(result: StreamResult) {
        val messages = _state.value.messages.toMutableList()
        val last = messages.lastOrNull()

        when (result) {
            is StreamResult.Failed -> {
                val note = ChatMessage(Role.ASSISTANT, result.message, System.currentTimeMillis(), isError = true)
                if (last != null && last.role == Role.ASSISTANT && last.text.isEmpty()) {
                    messages[messages.lastIndex] = note
                } else {
                    messages += note
                }
            }

            StreamResult.Cancelled -> {
                if (last != null && last.role == Role.ASSISTANT && last.text.isEmpty()) {
                    messages[messages.lastIndex] = last.copy(
                        text = getApplication<Application>().getString(R.string.ai_stopped),
                        isError = true,
                    )
                }
            }

            StreamResult.Ok -> {
                if (last != null && last.role == Role.ASSISTANT && last.text.isEmpty()) {
                    messages[messages.lastIndex] = last.copy(
                        text = getApplication<Application>().getString(R.string.ai_empty_answer),
                        isError = true,
                    )
                }
            }
        }

        handle = null
        streamJob = null
        _state.value = _state.value.copy(
            messages = messages,
            isStreaming = false,
            workingStep = 0,
        )
        persist()
    }

    private fun appendError(message: String) {
        _state.value = _state.value.copy(
            messages = _state.value.messages +
                ChatMessage(Role.ASSISTANT, message, System.currentTimeMillis(), isError = true)
        )
    }

    /**
     * Written after every completed turn rather than only in `onCleared`. The ROM force-stops
     * this package about 30 s after it backgrounds (Plan.md §5.1b-2) and `onCleared` is not
     * called when that happens — a conversation saved only at teardown would routinely vanish.
     */
    private fun persist() {
        val snapshot = snapshot()
        // Serialising and committing up to 200 KB of JSON is not main-thread work, and this runs
        // at the end of every turn.
        viewModelScope.launch(Dispatchers.IO) { chatStore.upsert(snapshot) }
    }

    private fun snapshot(): Conversation {
        conversation = conversation.copy(
            messages = _state.value.messages,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        return conversation
    }

    override fun onCleared() {
        stop()
        // Inline, not via [persist]: viewModelScope is already cancelled by the time this runs,
        // so a launched write would never happen.
        chatStore.upsert(snapshot())
        super.onCleared()
    }

    companion object {
        const val STEP_BAR_STEPS = 5
        private const val CONTEXT_MESSAGES = 20
        private const val TAG = "InkDeckAi"
    }
}
