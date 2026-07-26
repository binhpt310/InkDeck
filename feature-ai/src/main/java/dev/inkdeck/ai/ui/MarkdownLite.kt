package dev.inkdeck.ai.ui

/**
 * Fenced code blocks, and nothing else.
 *
 * design.md §10 draws exactly one piece of structure inside an assistant bubble: a code block on
 * an `ink-200` fill. That is the only markdown worth rendering here —
 *
 *  - **bold/italic**: §14 item 5 bans italics outright and emphasis is weight, so inline styling
 *    would collapse to one weight change that dithers into the body text anyway;
 *  - headings and lists: they read fine as the literal `##` and `-` the model wrote;
 *  - links: there is nothing to tap through to on this device.
 *
 * A full markdown parser would be a dependency (banned) or a large amount of code, in exchange
 * for distinctions the panel cannot draw.
 */
object MarkdownLite {

    sealed class Segment {
        data class Text(val text: String) : Segment()
        data class Code(val text: String) : Segment()
    }

    /**
     * An **unterminated** fence is treated as code all the way to the end. That is the normal
     * case while streaming: the opening ``` arrives half a second before the closing one, and
     * re-rendering the block as plain text and then as code would repaint the bubble twice for
     * the same content.
     */
    fun split(raw: String): List<Segment> {
        if (!raw.contains(FENCE)) return listOf(Segment.Text(raw)).filterNot { it.text.isEmpty() }

        val out = ArrayList<Segment>()
        var index = 0
        var inCode = false

        while (index <= raw.length) {
            val next = raw.indexOf(FENCE, index)
            if (next < 0) {
                add(out, raw.substring(index), inCode)
                break
            }
            add(out, raw.substring(index, next), inCode)

            index = next + FENCE.length
            if (!inCode) {
                // Skip the info string (```kotlin). It names a language we do not highlight —
                // §7.6: colour carries no meaning here, so highlighting costs legibility for
                // decoration.
                val eol = raw.indexOf('\n', index)
                index = if (eol < 0) raw.length else eol + 1
            }
            inCode = !inCode
        }
        return out
    }

    private fun add(out: MutableList<Segment>, text: String, code: Boolean) {
        val trimmed = if (code) text.trimEnd('\n') else text.trim('\n')
        if (trimmed.isEmpty()) return
        out += if (code) Segment.Code(trimmed) else Segment.Text(trimmed)
    }

    private const val FENCE = "```"
}
