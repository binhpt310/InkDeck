package dev.inkdeck.terminal.sftp

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.refresh.EinkRefresher
import dev.inkdeck.eink.widget.EinkIconButton
import dev.inkdeck.eink.widget.EinkScrollView
import dev.inkdeck.eink.widget.PagedScrollRail
import dev.inkdeck.terminal.R

/**
 * Read-only viewer for a remote file.
 *
 * Full-bleed rather than rendered inside the sidebar: at 220 dp in landscape a file would be
 * ~28 characters wide, which is not reading. Opening over the whole content area also makes the
 * paged rail (§5.5) worth its 56 dp, and this is exactly the "sustained reading" case the
 * 16 sp body floor in §3.2 exists for.
 *
 * Two things it deliberately does not do:
 *
 * - **No editing.** A text editor needs a cursor, selection and an undo stack, none of which
 *   have been designed for this panel. `vim` over the terminal already does the job well.
 * - **No syntax highlighting.** Colour carries no meaning on a grayscale panel (§14 item 3), and
 *   the greys it would dither to cost legibility for decoration.
 */
class FileViewerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onClose()
        fun onDownload()
    }

    var listener: Listener? = null
    var refresher: EinkRefresher? = null

    private val titleView = TextView(context)
    private val subtitleView = TextView(context)
    private val body = TextView(context)
    private val scroll = EinkScrollView(context)
    private val rail = PagedScrollRail(context)
    // Phase 9 item 2: design.md §5.7 loading silhouette in the body region. A 5-state bar
    // beats a one-line "Loading…" — at 16 fps a stepped bar is a real signal, a text label is
    // the same thing the empty state would render if the file happened to be empty.
    private val loadingBar = dev.inkdeck.eink.widget.StepBar(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(EinkTheme.paper(context))
        // Opaque overlay: nothing underneath should receive taps.
        isClickable = true

        addView(buildHeader(), LayoutParams(LayoutParams.MATCH_PARENT, barHeight()))
        addView(
            View(context).apply { setBackgroundColor(EinkTheme.ink200(context)) },
            LayoutParams(LayoutParams.MATCH_PARENT, dividerHeight()),
        )

        body.apply {
            setTextAppearance(dev.inkdeck.eink.R.style.TextAppearance_InkDeck_MonoUi)
            setTextColor(EinkTheme.ink900(context))
            setTextIsSelectable(true)
            val pad = EinkTheme.dp(context, 12f).toInt()
            // The rail floats over the right edge and is opaque, so the text column has to end
            // before it — otherwise every line's tail is hidden behind the page buttons.
            val railGutter = resources.getDimensionPixelSize(dev.inkdeck.eink.R.dimen.ink_rail_width) +
                EinkTheme.dp(context, 16f).toInt()
            setPadding(pad, pad, railGutter, pad)
        }
        scroll.addView(
            body,
            FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ),
        )

        val stack = FrameLayout(context)
        stack.addView(
            scroll,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        rail.attach(scroll)
        stack.addView(
            rail,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = EinkTheme.dp(context, 8f).toInt()
            },
        )
        // The loading bar overlays the (empty) body, centred, in the body area only. It is
        // hidden the moment show() runs and the real content fills the body.
        loadingBar.visibility = GONE
        stack.addView(
            loadingBar,
            FrameLayout.LayoutParams(
                EinkTheme.dp(context, 160f).toInt(),
                EinkTheme.dp(context, 12f).toInt(),
            ).apply {
                gravity = Gravity.CENTER
            },
        )
        addView(stack, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun buildHeader(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(
            EinkIconButton(context).apply {
                setIconResource(R.drawable.ic_back)
                contentDescription = context.getString(R.string.viewer_close)
                setOnClickListener { listener?.onClose() }
            },
            LayoutParams(barHeight(), barHeight()),
        )

        val labels = LinearLayout(context).apply { orientation = VERTICAL }
        titleView.apply {
            setTextAppearance(dev.inkdeck.eink.R.style.TextAppearance_InkDeck_Title2)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        subtitleView.setTextAppearance(dev.inkdeck.eink.R.style.TextAppearance_InkDeck_Caption)
        labels.addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        labels.addView(subtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(labels, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        addView(
            EinkIconButton(context).apply {
                setIconResource(R.drawable.ic_download)
                contentDescription = context.getString(R.string.files_download)
                setOnClickListener { listener?.onDownload() }
            },
            LayoutParams(barHeight(), barHeight()),
        )
    }

    fun showLoading(name: String) {
        titleView.text = name
        subtitleView.text = context.getString(R.string.files_loading)
        // Phase 9 item 2: the structured §5.7 loading silhouette replaces the "Loading…" text.
        // The text label is still on screen (as the subtitle); the StepBar is the glyph.
        body.text = ""
        loadingBar.progress = 1
        loadingBar.visibility = VISIBLE
    }

    fun show(result: SftpBrowser.ReadResult) {
        titleView.text = result.name
        loadingBar.visibility = GONE
        rail.refresher = refresher

        val binary = looksBinary(result.bytes)
        body.text = if (binary) hexDump(result.bytes) else String(result.bytes, Charsets.UTF_8)
        body.typeface = EinkTheme.monoTypeface(context)

        subtitleView.text = buildString {
            append(formatSize(result.totalSize))
            append(if (binary) " · binary" else " · text")
            if (result.truncated) {
                append(context.getString(R.string.viewer_truncated, formatSize(result.bytes.size.toLong())))
            }
        }
        subtitleView.setTextColor(
            if (result.truncated) EinkTheme.ink900(context) else EinkTheme.ink500(context)
        )

        scroll.scrollTo(0, 0)
        // A whole new document replaces the viewport — [F] per §13.
        refresher?.flush("file-viewer")
    }

    fun showError(name: String, message: String) {
        titleView.text = name
        subtitleView.text = context.getString(R.string.viewer_failed)
        body.text = message
        loadingBar.visibility = GONE
    }

    /**
     * A NUL byte in the first few KB is the classic signal, and a high share of other
     * non-printables catches the rest. Getting this wrong in the safe direction (hex for
     * something textual) is recoverable; dumping a binary into a TextView is not.
     */
    private fun looksBinary(bytes: ByteArray): Boolean {
        val sample = minOf(bytes.size, BINARY_SNIFF_BYTES)
        if (sample == 0) return false
        var suspicious = 0
        for (i in 0 until sample) {
            val b = bytes[i].toInt() and 0xff
            if (b == 0) return true
            val printable = b >= 0x20 || b == 0x09 || b == 0x0a || b == 0x0d
            if (!printable) suspicious++
        }
        return suspicious * 100 / sample > BINARY_THRESHOLD_PERCENT
    }

    /** `offset  hex bytes  |ascii|` — the `hexdump -C` layout, because it is the one people read. */
    private fun hexDump(bytes: ByteArray): String {
        val limit = minOf(bytes.size, HEX_LIMIT_BYTES)
        val out = StringBuilder(limit * 4)
        var offset = 0
        while (offset < limit) {
            out.append(String.format("%08x  ", offset))
            val end = minOf(offset + 16, limit)
            for (i in offset until offset + 16) {
                if (i < end) out.append(String.format("%02x ", bytes[i])) else out.append("   ")
                if (i - offset == 7) out.append(' ')
            }
            out.append(" |")
            for (i in offset until end) {
                val c = bytes[i].toInt() and 0xff
                out.append(if (c in 0x20..0x7e) c.toChar() else '.')
            }
            out.append("|\n")
            offset = end
        }
        if (bytes.size > limit) {
            out.append(context.getString(R.string.viewer_hex_truncated))
        }
        return out.toString()
    }

    private fun formatSize(size: Long): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
        else -> String.format("%.1f MB", size / (1024.0 * 1024))
    }

    private fun barHeight() = resources.getDimensionPixelSize(dev.inkdeck.eink.R.dimen.ink_bar_height)
    private fun dividerHeight() = resources.getDimensionPixelSize(dev.inkdeck.eink.R.dimen.ink_divider)

    companion object {
        /**
         * 256 KB. The viewer lays the whole buffer out in one measure pass, so this is bounded by
         * what two ~1 GHz cores can do without the screen locking up, not by memory.
         */
        const val MAX_VIEW_BYTES = 256 * 1024

        private const val BINARY_SNIFF_BYTES = 8000
        private const val BINARY_THRESHOLD_PERCENT = 30
        private const val HEX_LIMIT_BYTES = 64 * 1024
    }
}
