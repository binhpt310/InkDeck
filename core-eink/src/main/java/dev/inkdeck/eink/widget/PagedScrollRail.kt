package dev.inkdeck.eink.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.R
import dev.inkdeck.eink.refresh.EinkRefresher

/**
 * The paged-scroll rail from design.md §5.5 — page up / position / page down, pinned to the
 * right edge of any scrollable surface.
 *
 * ```
 *   ┌────┐
 *   │ ▲  │  page up
 *   ├────┤
 *   │3/7 │  position
 *   ├────┤
 *   │ ▼  │  page down
 *   └────┘
 * ```
 *
 * Drawn at 56 dp rather than §5.5's 48 dp so the buttons clear the 56 dp touch minimum in both
 * axes.
 *
 * A page jump is `[F]` in design.md §13 — the whole viewport is replaced, so a partial update
 * would leave the previous page ghosted underneath. [refresher] is wired so callers cannot
 * forget that.
 */
class PagedScrollRail @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    var refresher: EinkRefresher? = null

    /** Extra hook for hosts that need to react to a page jump. */
    var onPageJump: ((delta: Int) -> Unit)? = null

    private var target: PagedScrollable? = null

    private val upButton = EinkIconButton(context)
    private val downButton = EinkIconButton(context)
    private val readout = TextView(context)

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = EinkTheme.dp(context, 1.5f)
        color = EinkTheme.ink900(context)
    }

    private val dividerPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = EinkTheme.dp(context, 1f)
        color = EinkTheme.ink200(context)
    }

    private val radius = EinkTheme.dp(context, 4f)
    private val borderRect = RectF()

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        setBackgroundColor(EinkTheme.paper(context))

        val buttonHeight = resources.getDimensionPixelSize(R.dimen.ink_rail_button_height)
        val readoutHeight = resources.getDimensionPixelSize(R.dimen.ink_rail_readout_height)
        val railWidth = resources.getDimensionPixelSize(R.dimen.ink_rail_width)

        upButton.apply {
            setIconResource(R.drawable.ic_page_up)
            contentDescription = context.getString(R.string.ink_page_up)
            setOnClickListener { jump(-1) }
        }
        downButton.apply {
            setIconResource(R.drawable.ic_page_down)
            contentDescription = context.getString(R.string.ink_page_down)
            setOnClickListener { jump(1) }
        }
        readout.apply {
            gravity = Gravity.CENTER
            setTextAppearance(R.style.TextAppearance_InkDeck_Caption)
            includeFontPadding = false
        }

        addView(upButton, LayoutParams(railWidth, buttonHeight))
        addView(readout, LayoutParams(railWidth, readoutHeight))
        addView(downButton, LayoutParams(railWidth, buttonHeight))
    }

    /** Bind the rail to a scrollable surface. Safe to call again to re-bind. */
    fun attach(scrollable: PagedScrollable) {
        target?.setPageMetricsListener(null)
        target = scrollable
        scrollable.setPageMetricsListener { updateReadout() }
        post { updateReadout() }
    }

    private fun jump(delta: Int) {
        val t = target ?: return
        t.jumpPages(delta)
        updateReadout()
        refresher?.flush("page-jump delta=$delta")
        onPageJump?.invoke(delta)
    }

    private fun updateReadout() {
        val t = target ?: return
        val pages = t.pageCount
        val page = t.currentPage
        val label = "$page/$pages"
        if (readout.text != label) readout.text = label

        // A single-page surface has nothing to jump to. Disabled rather than hidden: a rail
        // that appears and disappears would shift the content width under the reader.
        val multi = pages > 1
        upButton.isEnabled = multi && page > 1
        downButton.isEnabled = multi && page < pages
        upButton.iconTint = tintFor(upButton.isEnabled)
        downButton.iconTint = tintFor(downButton.isEnabled)
    }

    private fun tintFor(enabled: Boolean): Int =
        if (enabled) EinkTheme.ink900(context) else EinkTheme.ink300(context)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = borderPaint.strokeWidth / 2f
        borderRect.set(inset, inset, width - inset, height - inset)
        canvas.drawRoundRect(borderRect, radius, radius, borderPaint)

        // Dividers sit between the three cells.
        var y = 0f
        for (i in 0 until childCount - 1) {
            y += getChildAt(i).height
            canvas.drawLine(0f, y, width.toFloat(), y, dividerPaint)
        }
    }
}
