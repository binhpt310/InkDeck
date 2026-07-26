package dev.inkdeck.eink.widget

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView
import kotlin.math.ceil

/**
 * NestedScrollView with momentum removed — design.md §5.5, §14 item 2.
 *
 * [fling] is deliberately a no-op rather than damped: a slow fling is still an animation, and
 * the panel would render it as a sequence of unrelated smears.
 */
class EinkScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : NestedScrollView(context, attrs, defStyleAttr), PagedScrollable {

    private var pageMetricsListener: (() -> Unit)? = null

    init {
        overScrollMode = OVER_SCROLL_NEVER
        isSmoothScrollingEnabled = false
        isFillViewport = true
    }

    override fun fling(velocityY: Int) {
        // No momentum. Drag still scrolls; releasing simply stops.
    }

    private val viewportHeight: Int
        get() = (height - paddingTop - paddingBottom).coerceAtLeast(1)

    private val contentHeight: Int
        get() = if (childCount > 0) getChildAt(0).height else 0

    override val pageCount: Int
        get() {
            val c = contentHeight
            if (c <= 0) return 1
            return ceil(c.toDouble() / viewportHeight).toInt().coerceAtLeast(1)
        }

    override val currentPage: Int
        get() {
            // Report the last page once scrolled to the bottom, rather than deriving purely from
            // scrollY / viewport. Content only a fraction over one viewport clamps its maximum
            // scroll below a full page, so the naive formula never leaves page 1 — the readout
            // stayed "1/2" at the end of the document and the up-arrow stayed disabled.
            val maxScroll = (contentHeight - viewportHeight).coerceAtLeast(0)
            if (maxScroll == 0) return 1
            if (scrollY >= maxScroll) return pageCount
            return (scrollY / viewportHeight + 1).coerceIn(1, pageCount)
        }

    override fun jumpPages(delta: Int) {
        val max = (contentHeight - viewportHeight).coerceAtLeast(0)
        scrollTo(0, (scrollY + delta * viewportHeight).coerceIn(0, max))
        pageMetricsListener?.invoke()
    }

    override fun setPageMetricsListener(listener: (() -> Unit)?) {
        pageMetricsListener = listener
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        pageMetricsListener?.invoke()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Notify on every layout, not just when `changed`. `changed` reports whether *this*
        // view's bounds moved — it stays false when only the child grows, which is exactly what
        // happens when content is loaded into an already-sized scroller. Gating on it left the
        // rail reading "1/1" over a document several pages long.
        pageMetricsListener?.invoke()
    }
}
