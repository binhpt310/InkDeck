package dev.inkdeck.terminal

import de.mud.terminal.vt320
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * The vendored [vt320] state machine, wired to an outbound byte sink.
 *
 * vt320 is both halves of the emulator: [feed] drives the parser from server output, and the
 * key handling it inherits produces the correct escape sequences for arrows, function keys and
 * so on — including application-cursor mode, which is why key presses go through
 * [vt320.keyPressed] rather than being hand-encoded. Anything vt320 decides to send comes back
 * out through the overridden `write`, which is [sink].
 */
class TerminalEmulator(
    private val sink: (ByteArray) -> Unit,
    scrollback: Int = DEFAULT_SCROLLBACK,
) : vt320() {

    /**
     * Streaming UTF-8 decode. A chunk boundary lands mid-sequence often enough on an
     * interactive session that decoding each read independently produces visible replacement
     * characters; the decoder keeps the partial bytes in [carry] until the rest arrives.
     */
    private val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    private var carry: ByteArray = ByteArray(0)
    private val chars = CharBuffer.allocate(READ_BUFFER)

    init {
        // Must match SshSession.TERM_TYPE — this is what answers the server's Device Attributes
        // query, and disagreeing with $TERM is how you get an app probing for features the
        // emulator does not have.
        setTerminalID("vt220")
        setBufferSize(scrollback)
    }

    override fun write(b: ByteArray) {
        sink(b)
    }

    override fun write(b: Int) {
        sink(byteArrayOf(b.toByte()))
    }

    override fun debug(notice: String) {
        // Silent by design: vt320's debug output is per-escape-sequence and would be the
        // noisiest thing in logcat during any real session.
    }

    /** Feed [length] bytes of raw server output into the parser. */
    fun feed(bytes: ByteArray, length: Int) {
        val input = if (carry.isEmpty()) {
            ByteBuffer.wrap(bytes, 0, length)
        } else {
            ByteBuffer.allocate(carry.size + length).apply {
                put(carry)
                put(bytes, 0, length)
                flip()
            }
        }

        while (true) {
            chars.clear()
            val result = decoder.decode(input, chars, false)
            chars.flip()
            if (chars.hasRemaining()) {
                putString(chars.toString())
            }
            if (!result.isOverflow) break
        }

        carry = if (input.hasRemaining()) {
            ByteArray(input.remaining()).also { input.get(it) }
        } else {
            ByteArray(0)
        }
    }

    private companion object {
        /** Plan.md §4.4. Capped by memory pressure on a 550 MB-free device. */
        const val DEFAULT_SCROLLBACK = 5_000
        const val READ_BUFFER = 4096
    }
}
