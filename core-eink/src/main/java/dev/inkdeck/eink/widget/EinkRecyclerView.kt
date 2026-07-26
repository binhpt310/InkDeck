package dev.inkdeck.eink.widget

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.RecyclerView
import dev.inkdeck.eink.EinkAnim
import kotlin.math.ceil

/**
 * RecyclerView with the item animator, the overscroll glow and fling all removed, plus paged
 * jumps — design.md §5.5.
 *
 * The item animator matters more than it looks: the default cross-fades a changed row over
 * 250 ms, so checking a task box would cost four panel frames of half-drawn text instead of
 * one clean invert of that row.
 */
class EinkRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : RecyclerView(context, attrs, defStyleAttr), PagedScrollable {

    private var pageMetricsListener: (() -> Unit)? = null

    init {
        overScrollMode = OVER_SCROLL_NEVER
        itemAnimator = null
        setHasFixedSize(false)
        EinkAnim.noFling(this)
    }

    private val viewportHeight: Int
        get() = (height - paddingTop - paddingBottom).coerceAtLeast(1)

    override val pageCount: Int
        get() {
            val range = computeVerticalScrollRange()
            if (range <= 0) return 1
            return ceil(range.toDouble() / viewportHeight).toInt().coerceAtLeast(1)
        }

    override val currentPage: Int
        get() {
            // See the note in EinkScrollView: a list only a fraction taller than the viewport
            // can never reach a whole page of offset, so the bottom has to be detected directly.
            val offset = computeVerticalScrollOffset()
            val maxScroll = (computeVerticalScrollRange() - viewportHeight).coerceAtLeast(0)
            if (maxScroll == 0) return 1
            if (offset >= maxScroll) return pageCount
            return (offset / viewportHeight + 1).coerceIn(1, pageCount)
        }

    override fun jumpPages(delta: Int) {
        // RecyclerView clamps at both ends itself.
        scrollBy(0, delta * viewportHeight)
        pageMetricsListener?.invoke()
    }

    override fun setPageMetricsListener(listener: (() -> Unit)?) {
        pageMetricsListener = listener
    }

    override fun onScrolled(dx: Int, dy: Int) {
        super.onScrolled(dx, dy)
        pageMetricsListener?.invoke()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Always, not just when `changed` — see the note in EinkScrollView. A new adapter
        // dataset changes the scroll range without moving this view's bounds.
        pageMetricsListener?.invoke()
    }
}
