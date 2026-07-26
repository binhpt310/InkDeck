package dev.inkdeck.eink.widget

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The floating action menu — design.md §11.
 *
 * ```
 *   COLLAPSED          RESTING (idle 10 s)        EXPANDED
 *     ╭───╮                  ┆ ╭─╮ ┆          ┌──────┬──────┬──────┐
 *     │ ✦ │ 56dp             ┆ │✦│ ┆ drawn    │  ↻   │  ⌨   │  ✦   │
 *     ╰───╯                  ┆ ╰─╯ ┆ 32dp     │Flush │ Keys │  AI  │
 *                            └─────┘ hit 56dp ├──────┼──────┼──────┤ …
 * ```
 *
 * In-app only. A system-wide overlay would need `SYSTEM_ALERT_WINDOW`, which is Plan.md's open
 * question 5 and not a permission to take without being asked.
 *
 * Three rules from §11.2 that shape the implementation:
 *
 * - **Tap is one repaint.** The grid appears complete — no fan-out, no scale, no fade. At 16 fps
 *   a staggered reveal is just a sequence of half-drawn frames.
 * - **Long-press flushes immediately**, skipping the menu, because that is the single most
 *   frequent action on this hardware and burying it two taps deep would be perverse.
 * - **The grid opens away from the nearest edge.** A radial menu loses items in corners on a
 *   572 dp screen; a rectangular grid anchored away from the edge never clips.
 */
class FloatingMenu @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    class Item(
        @DrawableRes val icon: Int,
        val label: String,
        val enabled: Boolean = true,
        val onSelected: () -> Unit,
    )

    /** Long-press anywhere on the puck. Wire this to a full panel flush. */
    var onFlush: (() -> Unit)? = null

    /** Called whenever the menu opens or closes, so the host can classify the repaint. */
    var onVisibilityChanged: ((expanded: Boolean) -> Unit)? = null

    var items: List<Item> = emptyList()
        set(value) {
            field = value
            if (expanded) buildGrid()
        }

    private val puck = PuckView(context)
    private val grid = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(EinkTheme.paper(context))
        visibility = GONE
    }

    private var expanded = false

    private val prefs: SharedPreferences =
        context.getSharedPreferences("inkdeck.floating", Context.MODE_PRIVATE)

    private val puckSize = resources.getDimensionPixelSize(R.dimen.ink_puck)
    private val restingSize = resources.getDimensionPixelSize(R.dimen.ink_puck_collapsed)
    private val cell = EinkTheme.dp(context, 72f).toInt()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val idleRunnable = Runnable { if (!expanded) puck.resting = true }

    init {
        // The container spans the screen so the grid can be positioned anywhere and taps outside
        // it can be caught; it is only touchable where a child is.
        addView(grid, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(puck, LayoutParams(puckSize, puckSize))
        restorePosition()
        scheduleIdle()
    }

    // ---------------------------------------------------------------- open / close

    fun toggle() = if (expanded) collapse() else expand()

    fun expand() {
        if (expanded || items.isEmpty()) return
        expanded = true
        puck.resting = false
        removeCallbacks(idleRunnable)
        buildGrid()
        positionGrid()
        grid.visibility = VISIBLE
        onVisibilityChanged?.invoke(true)
    }

    fun collapse() {
        if (!expanded) return
        expanded = false
        grid.visibility = GONE
        scheduleIdle()
        onVisibilityChanged?.invoke(false)
    }

    val isExpanded: Boolean get() = expanded

    /** Swallow taps outside the grid while it is open, and use them to close it (§11.2). */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!expanded) return false
        if (ev.actionMasked != MotionEvent.ACTION_DOWN) return false
        if (hits(grid, ev) || hits(puck, ev)) return false
        collapse()
        return true
    }

    /** Translation-aware: the grid is laid out at 0,0 and moved by [positionGrid]. */
    private fun hits(view: View, ev: MotionEvent): Boolean {
        if (view.visibility != VISIBLE) return false
        val left = view.left + view.translationX
        val top = view.top + view.translationY
        return ev.x >= left && ev.x <= left + view.width &&
            ev.y >= top && ev.y <= top + view.height
    }

    // ---------------------------------------------------------------- grid

    private fun buildGrid() {
        grid.removeAllViews()
        val border = EinkTheme.dp(context, 1.5f).toInt()
        grid.setPadding(border, border, border, border)

        items.chunked(COLUMNS).forEach { row ->
            val rowView = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { item ->
                rowView.addView(
                    CellView(context, item).apply {
                        setOnClickListener {
                            if (!item.enabled) return@setOnClickListener
                            collapse()
                            item.onSelected()
                        }
                    },
                    LinearLayout.LayoutParams(cell, cell),
                )
            }
            grid.addView(
                rowView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    /**
     * Anchor the grid on the side of the puck with the most room, so it never clips (§11.2).
     *
     * Positioned with translation rather than layout margins. Margins only take effect on the
     * next layout pass, and the grid is measured and shown inside a single [expand] call — the
     * first attempt set margins and the grid rendered at 0,0 every time. Translation applies at
     * draw time, which is also one fewer layout on a 16 fps panel.
     */
    private fun positionGrid() {
        grid.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST),
        )
        val gw = grid.measuredWidth
        val gh = grid.measuredHeight

        // puck.left/top are where it actually is, whether it got there from restored prefs or
        // from a drag.
        val puckLeft = puck.left
        val puckTop = puck.top
        val openLeft = puckLeft + puck.width / 2 > width / 2
        val openUp = puckTop + puck.height / 2 > height / 2

        val x = if (openLeft) puckLeft - gw else puckLeft + puck.width
        val y = if (openUp) puckTop + puck.height - gh else puckTop

        grid.translationX = x.coerceIn(0, (width - gw).coerceAtLeast(0)).toFloat()
        grid.translationY = y.coerceIn(0, (height - gh).coerceAtLeast(0)).toFloat()
    }

    // ---------------------------------------------------------------- position

    private fun scheduleIdle() {
        removeCallbacks(idleRunnable)
        postDelayed(idleRunnable, IDLE_SHRINK_MS)
    }

    /**
     * The bottom of what is actually on screen.
     *
     * This container fills `android.R.id.content`, which on this device is taller than the
     * visible area — Plan.md §0 records `mFrame` 758×1024 against `appBounds` 758×960, and
     * [AppBoundsLinearLayout] exists for the same reason. A puck dragged into that gap is simply
     * gone, with no way to get it back.
     */
    private fun usableHeight(): Int {
        val configured = (resources.configuration.screenHeightDp * resources.displayMetrics.density)
            .roundToInt()
        return if (configured in 1 until height) configured else height
    }

    private fun maxLeft() = (width - puckSize).coerceAtLeast(0)
    private fun maxTop() = (usableHeight() - puckSize).coerceAtLeast(0)

    private fun restorePosition() {
        post {
            val params = puck.layoutParams as LayoutParams
            // Coerced, not trusted. A fraction saved under a different window size — or by an
            // older build that parked the puck off the edge — must not strand it out of reach.
            params.leftMargin = (prefs.getFloat(KEY_X, 1f) * maxLeft()).roundToInt()
                .coerceIn(0, maxLeft())
            params.topMargin = (prefs.getFloat(KEY_Y, 0.7f) * maxTop()).roundToInt()
                .coerceIn(0, maxTop())
            puck.layoutParams = params
        }
    }

    private fun savePosition() {
        val params = puck.layoutParams as LayoutParams
        prefs.edit()
            .putFloat(KEY_X, params.leftMargin.toFloat() / maxLeft().coerceAtLeast(1))
            .putFloat(KEY_Y, params.topMargin.toFloat() / maxTop().coerceAtLeast(1))
            .apply()
    }

    /** Snap to whichever vertical edge is nearer — §11.2. Horizontal position is kept. */
    private fun snapToEdge() {
        val params = puck.layoutParams as LayoutParams
        params.leftMargin = if (params.leftMargin + puckSize / 2 < width / 2) 0 else maxLeft()
        params.topMargin = params.topMargin.coerceIn(0, maxTop())
        puck.layoutParams = params
        savePosition()
    }

    // ---------------------------------------------------------------- the puck

    private inner class PuckView(context: Context) : View(context) {

        /**
         * Idle state — design.md §11.2, with one deliberate change.
         *
         * §11.2 draws the resting tab at 32 dp and **half off the edge**. Shipped that way it was
         * unusable: at a screen width of 758 px the view sat at x 737..779, the FrameLayout
         * clipped everything past 758, and what remained was a 21 px — 16 dp — sliver. That is
         * well under this project's own 56 dp touch minimum (§4), which exists precisely because
         * ~60 ms panel latency makes a missed tap indistinguishable from a slow one.
         *
         * So the shrink is now **paint-only**. The view keeps its 56 dp bounds and stays fully on
         * screen; only the drawn puck shrinks, hugging the outer edge so it still reads as a tab
         * tucked away. Visual weight drops exactly as §11.2 intended; the target does not move.
         */
        var resting: Boolean = false
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        /** Where the puck is drawn inside its fixed 56 dp bounds. */
        private fun drawnRect(out: RectF) {
            val size = if (resting) restingSize.toFloat() else puckSize.toFloat()
            val inset = ring.strokeWidth
            val top = (height - size) / 2f
            // Hug whichever edge the puck is parked against, so the shrink reads as "moved out of
            // the way" rather than "floating in the middle of a gap".
            val onLeft = (layoutParams as LayoutParams).leftMargin + width / 2 <
                this@FloatingMenu.width / 2
            val left = if (onLeft) 0f else width - size
            out.set(left + inset, top + inset, left + size - inset, top + size - inset)
        }

        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = EinkTheme.dp(context, 1.5f)
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = EinkTheme.uiEmphasisTypeface(context)
        }
        private val bounds = RectF()

        private var downX = 0f
        private var downY = 0f
        private var startLeft = 0
        private var startTop = 0
        private var dragging = false
        private var longPressFired = false

        private val longPress = Runnable {
            longPressFired = true
            // §11.2: the most frequent action, reachable without opening anything.
            onFlush?.invoke()
        }

        init {
            isClickable = true
        }

        override fun onDraw(canvas: Canvas) {
            drawnRect(bounds)
            val radius = EinkTheme.dp(context, 4f)

            fill.color = EinkTheme.paper(context)
            canvas.drawRoundRect(bounds, radius, radius, fill)
            ring.color = EinkTheme.ink900(context)
            canvas.drawRoundRect(bounds, radius, radius, ring)

            glyph.color = EinkTheme.ink900(context)
            glyph.textSize = bounds.height() * 0.5f
            val fm = glyph.fontMetrics
            canvas.drawText(
                "✦",
                bounds.centerX(),
                bounds.centerY() - (fm.ascent + fm.descent) / 2f,
                glyph,
            )
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val params = layoutParams as LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startLeft = params.leftMargin
                    startTop = params.topMargin
                    dragging = false
                    longPressFired = false
                    resting = false
                    removeCallbacks(idleRunnable)
                    postDelayed(longPress, LONG_PRESS_MS)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        removeCallbacks(longPress)
                        if (expanded) collapse()
                    }
                    if (dragging) {
                        params.leftMargin = (startLeft + dx).toInt().coerceIn(0, maxLeft())
                        params.topMargin = (startTop + dy).toInt().coerceIn(0, maxTop())
                        layoutParams = params
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longPress)
                    when {
                        longPressFired -> Unit
                        dragging -> snapToEdge()
                        else -> toggle()
                    }
                    scheduleIdle()
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    scheduleIdle()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }

    /** One 72 dp grid cell: icon over label. §11.2 — icon-only menus are unreadable dithered. */
    private class CellView(context: Context, item: Item) : LinearLayout(context) {

        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = EinkTheme.dp(context, 1f)
            color = EinkTheme.ink200(context)
        }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            isClickable = item.enabled
            setWillNotDraw(false)
            setBackgroundColor(EinkTheme.paper(context))

            val tint = if (item.enabled) EinkTheme.ink900(context) else EinkTheme.ink300(context)

            addView(
                EinkIconButton(context).apply {
                    icon = ContextCompat.getDrawable(context, item.icon)
                    iconTint = tint
                    isEnabled = false // the whole cell is the target, not the glyph
                },
                LayoutParams(
                    EinkTheme.dp(context, 28f).toInt(),
                    EinkTheme.dp(context, 28f).toInt(),
                ),
            )
            addView(
                TextView(context).apply {
                    text = item.label
                    setTextAppearance(R.style.TextAppearance_InkDeck_Caption)
                    setTextColor(
                        if (item.enabled) EinkTheme.ink900(context) else EinkTheme.ink500(context)
                    )
                    gravity = Gravity.CENTER
                    isSingleLine = true
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), border)
        }
    }

    private companion object {
        const val COLUMNS = 3
        const val IDLE_SHRINK_MS = 10_000L
        const val LONG_PRESS_MS = 500L
        const val KEY_X = "x"
        const val KEY_Y = "y"
    }
}
