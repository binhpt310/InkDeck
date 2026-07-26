package dev.inkdeck.ai

/**
 * **The single most important class in this module.** Turns a token stream into at most two
 * repaints per second, preferring sentence boundaries — Plan.md §6.1, design.md §10 and §13.
 *
 * ## Why
 *
 * A model emits 20–60 tokens per second. The panel runs at 16 fps with ~60 ms of latency and no
 * way to repaint part of a paragraph without the previous state ghosting under it. Rendering
 * token by token asks for ~30 partial refreshes per second on hardware that can do 16, so the
 * text never settles: the bubble smears continuously for the whole response and the reader
 * cannot read a word of it until it stops. It is also a battery sink, because every partial
 * refresh drives the waveform.
 *
 * Two repaints per second is the number in design.md §10 and it is a ceiling, not a target — the
 * buffer emits *less* often when the text has no natural break.
 *
 * ## The rules, in priority order
 *
 *  1. **Never faster than [minIntervalMs].** Checked first, before anything else. Every other
 *     rule can only make an emission later.
 *  2. **Prefer a sentence boundary.** Break after `. ! ? … : ;` or a newline, so a chunk arrives
 *     as a readable unit rather than mid-clause. A paragraph that appears half a sentence at a
 *     time is worse than one that appears a sentence at a time, even though both are ≤ 2 Hz.
 *  3. **[hardChars] escape hatch.** Code blocks, tables and long URLs contain no sentence
 *     terminator at all. Without this, a 40-line code block would arrive in one flush at the end
 *     of the response and the screen would sit blank meanwhile.
 *  4. **[maxHoldMs] escape hatch.** A slow endpoint dribbling a few tokens per second hits
 *     neither of the above. Text that has been buffered this long goes out as-is.
 *
 * ## Shape
 *
 * Pure logic, no Handler, no coroutine, no clock of its own: [append] is called from the network
 * thread and [poll] from whatever is driving the UI, and the caller supplies the timestamp. That
 * makes the pacing testable off-device, which matters because the behaviour it encodes cannot be
 * seen in a screenshot (Plan.md §3.4) — only on the panel.
 */
class ChunkBuffer(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val maxHoldMs: Long = MAX_HOLD_MS,
    private val hardChars: Int = HARD_CHARS,
) {

    private val pending = StringBuilder()
    private var lastEmitAt = 0L
    private var hasEmitted = false
    private var oldestPendingAt = 0L

    /** Called from the network thread as deltas arrive. */
    @Synchronized
    fun append(text: String, nowMs: Long) {
        if (text.isEmpty()) return
        if (pending.isEmpty()) oldestPendingAt = nowMs
        pending.append(text)
    }

    /**
     * @param finish drain everything regardless of pacing — the stream has ended or been stopped,
     *   and holding the tail back would lose the end of the answer.
     * @return text to append to the visible bubble, or null to repaint nothing at all. Returning
     *   null is the normal case and is the whole point.
     */
    @Synchronized
    fun poll(nowMs: Long, finish: Boolean = false): String? {
        if (pending.isEmpty()) return null

        if (finish) return take(pending.length, nowMs)

        // Rule 1, first and unconditional. The `hasEmitted` guard is not decoration: callers pass
        // SystemClock.uptimeMillis(), and a zero sentinel would make the very first chunk of the
        // very first answer after a boot depend on how long the device had been up.
        if (hasEmitted && nowMs - lastEmitAt < minIntervalMs) return null

        val boundary = lastBoundary(pending)
        if (boundary > 0) return take(boundary, nowMs)

        if (pending.length >= hardChars || nowMs - oldestPendingAt >= maxHoldMs) {
            return take(pending.length, nowMs)
        }
        return null
    }

    @Synchronized
    fun reset() {
        pending.setLength(0)
        lastEmitAt = 0L
        hasEmitted = false
        oldestPendingAt = 0L
    }

    private fun take(count: Int, nowMs: Long): String {
        val out = pending.substring(0, count)
        pending.delete(0, count)
        lastEmitAt = nowMs
        hasEmitted = true
        oldestPendingAt = if (pending.isEmpty()) 0L else nowMs
        return out
    }

    /**
     * Index just past the last usable break, or 0 for none.
     *
     * A terminator only counts when whitespace follows it, so `strategy.py:` at the end of the
     * buffer is not treated as a sentence end while the next token is still in flight — cutting
     * there would put the colon on one line and its code block on the next repaint half a second
     * later. A newline is always a break: it is a break in the source text by definition.
     */
    private fun lastBoundary(text: CharSequence): Int {
        for (i in text.length - 1 downTo 0) {
            val c = text[i]
            if (c == '\n') return i + 1
            if (c in TERMINATORS && i + 1 < text.length && text[i + 1].isWhitespace()) return i + 2
        }
        return 0
    }

    companion object {
        /** design.md §10: "repaint at most twice per second". */
        const val MIN_INTERVAL_MS = 500L

        /** Longest a fragment may sit unseen when no boundary ever arrives. */
        const val MAX_HOLD_MS = 2_000L

        /**
         * ~4 lines at 16 sp across 572 dp. Chosen so a code block still lands in readable
         * instalments rather than as one wall at the end.
         */
        const val HARD_CHARS = 320

        /**
         * How often the driver should call [poll]. Four times the minimum interval so the 500 ms
         * gate is what actually decides the cadence, not the polling granularity.
         */
        const val POLL_INTERVAL_MS = 125L

        private const val TERMINATORS = ".!?;:…"
    }
}
