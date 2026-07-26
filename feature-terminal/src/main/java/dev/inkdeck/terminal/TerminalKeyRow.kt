package dev.inkdeck.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.LinearLayout
import de.mud.terminal.vt320
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.widget.PressInvertView

/**
 * The terminal control-key row — design.md §7.1, §11.4.
 *
 * ```
 *  Esc │ Tab │ Ctrl │ Alt │ ← │ ↓ │ ↑ │ → │ | │ ~ │ / │ - │ ⌨
 * ```
 *
 * This is not a convenience. No Android IME — including Simple Keyboard, the only one installed
 * here — can produce Esc, Tab, Ctrl or Alt, so without this row there is no way to leave `vim`,
 * complete a path, or interrupt a process.
 *
 * Ctrl and Alt **latch for one keystroke** rather than requiring a hold. There is no physical
 * modifier to hold, and holding a soft key while pressing another gives no feedback the panel
 * can render in time to be useful.
 *
 * Note on sizing: thirteen keys across 572 dp is 44 dp each, under the 56 dp target minimum in
 * design.md §4. Kept because §7.1 specifies this exact row and 44 dp matches ordinary keyboard
 * key width; the 56 dp height carries the target in the axis where mis-taps actually happen.
 */
class TerminalKeyRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onVtKey(vtKey: Int)
        fun onText(text: String)

        /** For keys with no vt320 entry and no printable form, such as Tab. */
        fun onRaw(bytes: ByteArray)
        fun onModifierLatched(ctrl: Boolean, alt: Boolean)
        fun onToggleKeyboard()
    }

    var listener: Listener? = null

    private var ctrlKey: KeyView? = null
    private var altKey: KeyView? = null

    var ctrlLatched: Boolean = false
        private set
    var altLatched: Boolean = false
        private set

    private val dividerPaint = Paint().apply {
        color = EinkTheme.ink200(context)
        strokeWidth = EinkTheme.dp(context, 1f)
    }

    init {
        orientation = HORIZONTAL
        setBackgroundColor(EinkTheme.paper(context))
        setWillNotDraw(false)
        build()
    }

    private fun build() {
        // Esc and Tab go out as raw bytes: vt320 only handles Escape in keyTyped, and has no
        // Tab entry at all. See TerminalView.rawByteFor.
        addKey("Esc") { listener?.onRaw(byteArrayOf(TerminalView.ESC)) }
        addKey("Tab") { listener?.onRaw(byteArrayOf(TerminalView.TAB)) }

        ctrlKey = addKey("Ctrl") {
            ctrlLatched = !ctrlLatched
            ctrlKey?.latched = ctrlLatched
            listener?.onModifierLatched(ctrlLatched, altLatched)
        }
        altKey = addKey("Alt") {
            altLatched = !altLatched
            altKey?.latched = altLatched
            listener?.onModifierLatched(ctrlLatched, altLatched)
        }

        addKey("←") { listener?.onVtKey(vt320.KEY_LEFT) }
        addKey("↓") { listener?.onVtKey(vt320.KEY_DOWN) }
        addKey("↑") { listener?.onVtKey(vt320.KEY_UP) }
        addKey("→") { listener?.onVtKey(vt320.KEY_RIGHT) }

        addKey("|") { listener?.onText("|") }
        addKey("~") { listener?.onText("~") }
        addKey("/") { listener?.onText("/") }
        addKey("-") { listener?.onText("-") }

        addKey("⌨") { listener?.onToggleKeyboard() }
    }

    /** Clear the latches after the keystroke they applied to. */
    fun clearLatches() {
        if (!ctrlLatched && !altLatched) return
        ctrlLatched = false
        altLatched = false
        ctrlKey?.latched = false
        altKey?.latched = false
    }

    private fun addKey(label: String, onClick: () -> Unit): KeyView {
        val key = KeyView(context, label).apply { setOnClickListener { onClick() } }
        addView(key, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        return key
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        var x = 0f
        for (i in 0 until childCount - 1) {
            x += getChildAt(i).width
            canvas.drawLine(x, 0f, x, height.toFloat(), dividerPaint)
        }
    }
}

/**
 * One key. Press inverts for ≥120 ms (design.md §5.1); a latched modifier stays inverted until
 * it is used or tapped again, so the current state is readable at a glance rather than
 * remembered.
 */
private class KeyView(context: Context, private val label: String) : PressInvertView(context) {

    var latched: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = EinkTheme.sp(context, 16f)
        typeface = EinkTheme.uiTypeface(context)
    }
    private val fillPaint = Paint()

    init {
        contentDescription = label
    }

    override fun onDraw(canvas: Canvas) {
        // XOR so pressing a latched key previews the un-latched state.
        val filled = latched != inverted
        val ink = EinkTheme.ink900(context)
        val paper = EinkTheme.paper(context)

        if (filled) {
            fillPaint.color = ink
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        }
        textPaint.color = if (filled) paper else ink
        val fm = textPaint.fontMetrics
        canvas.drawText(label, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, textPaint)
    }
}
