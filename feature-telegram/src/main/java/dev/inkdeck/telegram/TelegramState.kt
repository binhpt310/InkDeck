package dev.inkdeck.telegram

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the poll loop is currently doing, for design.md §12's status line.
 *
 * A process-wide singleton rather than a bound service or a `LiveData` on the Service: the
 * settings screen and the loop are in the same process but have unrelated lifetimes — the ROM
 * force-stops the whole package (Plan.md §5.1b-2) so *both* die together, and binding would only
 * add a connection callback to something that is already a single object in a single heap.
 *
 * The phases are what the user can act on, which is why "no token" and "vault locked" are
 * separate: one is fixed by pushing a `.env` over USB, the other by unlocking the vault. A
 * generic "not connected" would send them looking in the wrong place.
 */
object TelegramState {

    enum class Phase {
        /** Service not running — the feature is off, or nothing has started it yet. */
        STOPPED,

        /** Running, but there is no `TELEGRAM_BOT_TOKEN` in the vault. */
        NO_TOKEN,

        /** Running, but the vault needs a passphrase this service cannot ask for. */
        VAULT_LOCKED,

        /** Token good, nobody paired yet. The settings screen shows the code. */
        UNPAIRED,

        /** Long-polling normally. */
        CONNECTED,

        /** Network failed; sleeping out a backoff. */
        RETRYING,
    }

    data class Status(
        val phase: Phase = Phase.STOPPED,
        val botUsername: String? = null,
        val chatId: Long? = null,
        /** Seconds until the next attempt — only meaningful in [Phase.RETRYING]. */
        val retryInSeconds: Int = 0,
        /** Wall-clock millis of the last update actually handled, 0 if none this run. */
        val lastMessageAt: Long = 0L,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    internal fun update(transform: (Status) -> Status) {
        _status.value = transform(_status.value)
    }

    internal fun set(phase: Phase) = update { it.copy(phase = phase, retryInSeconds = 0) }
}
