package dev.inkdeck.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import de.mud.terminal.VDUBuffer
import de.mud.terminal.VDUDisplay
import de.mud.terminal.VDUInput
import de.mud.terminal.vt320
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.refresh.EinkRefresher
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Canvas renderer for a [VDUBuffer] — Plan.md §4.1, §4.4.
 *
 * A `TextView` cannot do this job. The whole refresh policy depends on repainting *only the
 * cells that changed*, and a TextView re-lays-out and repaints its entire content on every
 * change, which on this panel means the screen flickers whenever a character arrives.
 *
 * Three things carry the design:
 *
 *  - **Repaints are throttled to ≤ 8 Hz** ([MIN_REPAINT_INTERVAL_MS]) no matter how fast output
 *    arrives. A `yes`-flood must cost the same eight repaints per second as a slow log tail.
 *  - **Only dirty rows are invalidated.** vt320 marks changed lines in [VDUBuffer.update]; the
 *    band between the first and last dirty row is what gets handed to `invalidate`.
 *  - **A full-buffer update is a flush, not a partial.** design.md §13 classes a screen clear or
 *    a `vim` redraw as `[F]`, because every cell changing is exactly the case a partial waveform
 *    renders as a smeared double image.
 *
 * Colour is discarded. The panel is grayscale, and design.md §14 item 3 forbids colour as the
 * sole carrier of meaning — so ANSI colours would dither into indistinguishable greys and cost
 * legibility for nothing. Text renders at full contrast; `LOW` dims to ink_700, `BOLD` switches
 * weight, `INVERT` swaps ink and paper.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr), VDUDisplay {

    /** Notified when the cell grid changes, so the PTY can be renegotiated (SIGWINCH). */
    fun interface GridListener {
        fun onGridChanged(cols: Int, rows: Int)
    }

    var gridListener: GridListener? = null
    var refresher: EinkRefresher? = null

    /** Bytes the user typed, ready for the SSH channel. */
    var input: VDUInput? = null

    private var vdu: VDUBuffer? = null

    /** design.md §3.2 `mono-term`, user-cyclable 11–17 sp. */
    var fontSizeSp: Float = 13f
        set(value) {
            field = value.coerceIn(11f, 17f)
            applyFontMetrics()
            recomputeGrid(notify = true)
            invalidate()
        }

    var cols: Int = 0
        private set
    var rows: Int = 0
        private set

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint()

    private var cellWidth = 0f
    private var cellHeight = 0f
    private var cellAscent = 0f

    private val handler = Handler(Looper.getMainLooper())
    private var repaintScheduled = false
    private var lastRepaintAt = 0L
    private val clip = Rect()

    // Reused per row so a fast-scrolling session does not allocate on every repaint.
    private val runBuffer = CharArray(MAX_COLS)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(EinkTheme.paper(context))
        applyFontMetrics()
    }

    private fun applyFontMetrics() {
        val mono = EinkTheme.monoTypeface(context)
        val size = EinkTheme.sp(context, fontSizeSp)
        textPaint.apply {
            typeface = mono
            textSize = size
            isSubpixelText = false
        }
        boldPaint.apply {
            typeface = android.graphics.Typeface.create(mono, android.graphics.Typeface.BOLD)
            textSize = size
            isSubpixelText = false
        }
        // Monospace: every advance is identical, so one measurement defines the grid.
        cellWidth = textPaint.measureText("M")
        val fm = textPaint.fontMetrics
        cellHeight = ceil((fm.descent - fm.ascent).toDouble()).toFloat()
        cellAscent = -fm.ascent
    }

    // ------------------------------------------------------------------ VDUDisplay

    override fun setVDUBuffer(buffer: VDUBuffer?) {
        vdu = buffer
        if (buffer != null && cols > 0 && rows > 0) {
            buffer.setScreenSize(cols, rows, true)
        }
        invalidate()
    }

    override fun getVDUBuffer(): VDUBuffer? = vdu

    /** Called from the SSH read thread. */
    override fun redraw() {
        scheduleRepaint()
    }

    override fun updateScrollBar() = Unit

    override fun setColor(index: Int, red: Int, green: Int, blue: Int) = Unit

    override fun resetColors() = Unit

    // ------------------------------------------------------------------ repaint throttle

    private fun scheduleRepaint() {
        if (repaintScheduled) return
        repaintScheduled = true
        val now = SystemClock.uptimeMillis()
        val due = max(now, lastRepaintAt + MIN_REPAINT_INTERVAL_MS)
        handler.postAtTime(repaintRunnable, due)
    }

    private val repaintRunnable = Runnable {
        repaintScheduled = false
        lastRepaintAt = SystemClock.uptimeMillis()
        invalidateDirty()
    }

    private fun invalidateDirty() {
        val buffer = vdu ?: return
        val update = buffer.update

        // update[0] means "everything changed" — a clear, a scroll, a full-screen app redraw.
        if (update == null || update.isEmpty() || update[0]) {
            refresher?.flush("terminal-full-redraw")
            invalidate()
            return
        }

        var first = -1
        var last = -1
        for (row in 0 until min(rows, update.size - 1)) {
            if (update[row + 1]) {
                if (first < 0) first = row
                last = row
            }
        }
        if (first < 0) return

        refresher?.notePartial(EinkRefresher.SURFACE_TERMINAL, "rows $first..$last")
        invalidate(
            0,
            (first * cellHeight).toInt(),
            width,
            ceil(((last + 1) * cellHeight).toDouble()).toInt(),
        )
    }

    // ------------------------------------------------------------------ measure / draw

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeGrid(notify = true)
    }

    private fun recomputeGrid(notify: Boolean) {
        if (cellWidth <= 0f || cellHeight <= 0f || width == 0 || height == 0) return
        val newCols = floor(width / cellWidth).toInt().coerceIn(1, MAX_COLS)
        val newRows = floor(height / cellHeight).toInt().coerceAtLeast(1)
        if (newCols == cols && newRows == rows) return

        cols = newCols
        rows = newRows
        vdu?.setScreenSize(cols, rows, true)
        if (notify) gridListener?.onGridChanged(cols, rows)
    }

    override fun onDraw(canvas: Canvas) {
        val buffer = vdu ?: return
        if (cellHeight <= 0f) return

        val paper = EinkTheme.paper(context)
        val ink = EinkTheme.ink900(context)
        val dim = EinkTheme.ink700(context)

        canvas.getClipBounds(clip)
        val firstRow = max(0, floor(clip.top / cellHeight).toInt())
        val lastRow = min(rows - 1, ceil(clip.bottom / cellHeight).toInt())
        if (lastRow < firstRow) return

        fillPaint.color = paper
        canvas.drawRect(clip, fillPaint)

        val cursorCol = buffer.cursorColumn
        val cursorRow = buffer.cursorRow + buffer.screenBase - buffer.windowBase

        for (row in firstRow..lastRow) {
            val line = buffer.windowBase + row
            if (line < 0 || line >= buffer.charArray.size) continue
            val chars = buffer.charArray[line] ?: continue
            val attrs = buffer.charAttributes[line] ?: continue

            val top = row * cellHeight
            var col = 0
            while (col < min(cols, chars.size)) {
                val attr = attrs[col]
                // Batch the longest run sharing one attribute into a single drawText. Typical
                // lines are one or two runs; per-cell drawing would be ~100 calls per row and
                // this device has two cores.
                var end = col
                var length = 0
                while (end < min(cols, chars.size) && attrs[end] == attr && length < MAX_COLS) {
                    runBuffer[length++] = chars[end]
                    end++
                }

                val isCursor = buffer.isCursorVisible &&
                    row == cursorRow &&
                    cursorCol in col until end

                drawRun(canvas, runBuffer, length, col, top, attr, ink, dim, paper)

                if (isCursor) {
                    drawCursorCell(canvas, chars, cursorCol, top, ink, paper)
                }
                col = end
            }
        }

        // Flags are cleared only for what was actually painted, so a row dirtied while this
        // draw was in flight is not lost.
        buffer.update?.let { update ->
            if (update.isNotEmpty()) {
                update[0] = false
                for (row in firstRow..lastRow) {
                    if (row + 1 < update.size) update[row + 1] = false
                }
            }
        }
    }

    private fun drawRun(
        canvas: Canvas,
        chars: CharArray,
        length: Int,
        startCol: Int,
        top: Float,
        attr: Long,
        ink: Int,
        dim: Int,
        paper: Int,
    ) {
        if (length == 0) return
        val left = startCol * cellWidth
        val right = left + length * cellWidth
        val inverted = (attr and VDUBuffer.INVERT) != 0L
        val invisible = (attr and VDUBuffer.INVISIBLE) != 0L
        val bold = (attr and VDUBuffer.BOLD) != 0L
        val low = (attr and VDUBuffer.LOW) != 0L

        if (inverted) {
            fillPaint.color = ink
            canvas.drawRect(left, top, right, top + cellHeight, fillPaint)
        }
        if (invisible) return

        val paint = if (bold) boldPaint else textPaint
        paint.color = when {
            inverted -> paper
            low -> dim
            else -> ink
        }
        canvas.drawText(chars, 0, length, left, top + cellAscent, paint)

        if ((attr and VDUBuffer.UNDERLINE) != 0L) {
            val y = top + cellAscent + EinkTheme.dp(context, 1.5f)
            paint.strokeWidth = EinkTheme.dp(context, 1f)
            canvas.drawLine(left, y, right, y, paint)
        }
    }

    /** Block cursor: a filled cell with the character knocked out. No blink — that is animation. */
    private fun drawCursorCell(
        canvas: Canvas,
        chars: CharArray,
        col: Int,
        top: Float,
        ink: Int,
        paper: Int,
    ) {
        val left = col * cellWidth
        fillPaint.color = ink
        canvas.drawRect(left, top, left + cellWidth, top + cellHeight, fillPaint)
        if (col < chars.size) {
            textPaint.color = paper
            canvas.drawText(chars, col, 1, left, top + cellAscent, textPaint)
        }
    }

    // ------------------------------------------------------------------ input

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Not TYPE_NULL. TYPE_NULL asks the IME for raw key events, and the first version of this
        // relied on it — but an IME that honours it routes committed text through
        // BaseInputConnection's dummy mode, which arrives as a KeyEvent.ACTION_MULTIPLE and
        // therefore lands in onKeyMultiple, not onKeyDown. Typing on Simple Keyboard produced
        // nothing at all. (`adb shell input text` injects real ACTION_DOWN events straight to the
        // View, so it works either way and hides the bug.)
        //
        // TYPE_TEXT_VARIATION_VISIBLE_PASSWORD is the long-standing terminal-app trick: it tells
        // the IME not to run autocorrect, suggestions or a composing region, so keystrokes arrive
        // as plain commitText calls that [TerminalInputConnection] can forward one character at a
        // time. Composing text in a terminal is meaningless anyway — the remote shell, not the
        // IME, decides what a partially typed word means.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        outAttrs.initialSelStart = -1
        outAttrs.initialSelEnd = -1
        return TerminalInputConnection()
    }

    /**
     * Forwards IME input straight to the SSH channel.
     *
     * A terminal has no editable buffer to reconcile against — every character is sent the moment
     * it is typed and the remote echoes it back. So this deliberately implements the minimum and
     * refuses to pretend otherwise: no composing region, no surrounding-text queries, no undo.
     */
    private inner class TerminalInputConnection : BaseInputConnection(this@TerminalView, false) {

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            text ?: return true
            // Per character, so a latched Ctrl applies to the first one and clears — matching
            // what pressing Ctrl then a letter on a physical keyboard does.
            text.forEach { sendChar(it) }
            return true
        }

        /**
         * Some IMEs still open a composing region despite the flags above. Committing on every
         * update would resend the whole word each keystroke, so only the newly appended tail is
         * sent and a shortened composition is walked back with backspaces.
         */
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val next = text?.toString().orEmpty()
            val shared = next.commonPrefixWith(composing).length
            repeat(composing.length - shared) { input?.write(byteArrayOf(DEL)) }
            next.substring(shared).forEach { sendChar(it) }
            composing = next
            return true
        }

        override fun finishComposingText(): Boolean {
            composing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { input?.write(byteArrayOf(DEL)) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            // Route through the same path as a hardware key so Esc/Tab/arrows and the modifier
            // latches behave identically whether they came from the key row or the IME.
            if (event.action == KeyEvent.ACTION_DOWN) return onKeyDown(event.keyCode, event)
            return true
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            input?.write(byteArrayOf(CR))
            consumeLatches()
            return true
        }
    }

    private var composing: String = ""

    /**
     * The other half of the IME path: `BaseInputConnection` in dummy mode packs multi-character
     * commits into a single [KeyEvent.ACTION_MULTIPLE] with [KeyEvent.KEYCODE_UNKNOWN], which
     * never reaches [onKeyDown]. Handled here so input still works if an IME takes that route.
     */
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            val characters = event.characters
            if (!characters.isNullOrEmpty()) {
                characters.forEach { sendChar(it) }
                return true
            }
        }
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val target = input ?: return super.onKeyDown(keyCode, event)

        var modifiers = 0
        if (event.isShiftPressed) modifiers = modifiers or VDUInput.KEY_SHIFT
        if (event.isCtrlPressed || ctrlLatched) modifiers = modifiers or VDUInput.KEY_CONTROL
        if (event.isAltPressed || altLatched) modifiers = modifiers or VDUInput.KEY_ALT

        // vt320 splits its key handling across two methods: arrows and navigation keys are in
        // keyPressed (which is what encodes application-cursor mode correctly, and the reason
        // to go through vt320 at all), but Enter and Escape are only in keyTyped, and Tab is in
        // neither. Those four have no mode-dependent encoding worth preserving, so they go out
        // as raw bytes rather than routing through a second vt320 entry point.
        rawByteFor(keyCode)?.let { raw ->
            target.write(byteArrayOf(raw))
            consumeLatches()
            return true
        }

        vtKeyFor(keyCode)?.let { vtKey ->
            target.keyPressed(vtKey, ' ', modifiers)
            consumeLatches()
            return true
        }

        val ch = event.unicodeChar
        if (ch != 0) {
            sendChar(ch.toChar(), modifiers)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Ctrl and Alt from the key row latch for exactly one keystroke, the way a sticky-keys
     * modifier does. There is no physical Ctrl to hold down, and holding a soft key while
     * pressing another is not a gesture this panel can give feedback for.
     */
    var ctrlLatched: Boolean = false
    var altLatched: Boolean = false

    /** Lets the key row drop its inverted Ctrl/Alt cells once the modifier has been spent. */
    var onLatchesConsumed: (() -> Unit)? = null

    private fun consumeLatches() {
        if (!ctrlLatched && !altLatched) return
        ctrlLatched = false
        altLatched = false
        onLatchesConsumed?.invoke()
    }

    /**
     * Tapping the terminal focuses it and opens the keyboard. Without this the only way to get an
     * IME up is the key row's ⌨ button, which is not where anyone looks first.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            requestFocus()
            showKeyboard()
            performClick()
            return true
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /**
     * Tracked here rather than in the Fragment because the keyboard can be raised by tapping the
     * terminal as well as by the ⌨ key, and a flag the Fragment owns would go stale the first
     * time someone taps the screen — leaving the ⌨ button doing nothing.
     */
    var keyboardShowing: Boolean = false
        private set

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        keyboardShowing = true
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
        keyboardShowing = false
    }

    fun toggleKeyboard() {
        if (keyboardShowing) hideKeyboard() else showKeyboard()
    }

    fun sendChar(c: Char, modifiers: Int = modifierMask()) {
        val target = input ?: return
        if (modifiers and VDUInput.KEY_CONTROL != 0) {
            // Ctrl-A..Ctrl-Z and the C0 punctuation range.
            val upper = c.uppercaseChar()
            val code = when (upper) {
                in '@'..'_' -> upper.code - 64
                '?' -> 127
                else -> c.code
            }
            target.write(byteArrayOf(code.toByte()))
        } else if (modifiers and VDUInput.KEY_ALT != 0) {
            // Meta as ESC prefix, which is what every shell and vim expects.
            target.write(byteArrayOf(0x1b, c.code.toByte()))
        } else {
            target.write(c.toString().toByteArray(Charsets.UTF_8))
        }
        consumeLatches()
    }

    fun sendVtKey(vtKey: Int) {
        input?.keyPressed(vtKey, ' ', modifierMask())
        consumeLatches()
    }

    fun sendBytes(bytes: ByteArray) {
        input?.write(bytes)
        consumeLatches()
    }

    private fun modifierMask(): Int {
        var m = 0
        if (ctrlLatched) m = m or VDUInput.KEY_CONTROL
        if (altLatched) m = m or VDUInput.KEY_ALT
        return m
    }

    private fun rawByteFor(keyCode: Int): Byte? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER -> CR
        KeyEvent.KEYCODE_ESCAPE -> ESC
        KeyEvent.KEYCODE_TAB -> TAB
        // 0x7f, not 0x08: what every modern terminal sends for Backspace.
        KeyEvent.KEYCODE_DEL -> DEL
        else -> null
    }

    private fun vtKeyFor(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> vt320.KEY_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> vt320.KEY_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> vt320.KEY_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> vt320.KEY_RIGHT
        KeyEvent.KEYCODE_FORWARD_DEL -> vt320.KEY_DELETE
        KeyEvent.KEYCODE_MOVE_HOME -> vt320.KEY_HOME
        KeyEvent.KEYCODE_MOVE_END -> vt320.KEY_END
        KeyEvent.KEYCODE_PAGE_UP -> vt320.KEY_PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> vt320.KEY_PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> vt320.KEY_INSERT
        else -> null
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(repaintRunnable)
        super.onDetachedFromWindow()
    }

    companion object {
        /** Plan.md §4.4: ≤ 8 repaints per second regardless of data rate. */
        const val MIN_REPAINT_INTERVAL_MS = 125L

        /** Landscape at 11 sp gives ~99 columns; this is headroom, not a target. */
        private const val MAX_COLS = 256

        const val TAB: Byte = 0x09
        const val CR: Byte = 0x0d
        const val ESC: Byte = 0x1b
        const val DEL: Byte = 0x7f
    }
}
