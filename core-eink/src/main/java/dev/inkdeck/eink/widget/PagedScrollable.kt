package dev.inkdeck.eink.widget

/**
 * A scrollable surface that moves a whole viewport at a time — design.md §5.5.
 *
 * Fling scrolling at 16 fps is a smear with no defined end state: the user cannot read during
 * it and cannot predict where it stops. A page jump replaces it with one legible transition
 * and one flush.
 *
 * Drag-scroll still works for fine adjustment on the implementations below; only momentum is
 * removed.
 */
interface PagedScrollable {

    /** At least 1, even when the content is shorter than the viewport. */
    val pageCount: Int

    /** 1-based, for the "3/7" readout on the rail. */
    val currentPage: Int

    /** Move by whole viewports; clamped at both ends. */
    fun jumpPages(delta: Int)

    /** Fired whenever [currentPage] or [pageCount] may have changed. */
    fun setPageMetricsListener(listener: (() -> Unit)?)
}
