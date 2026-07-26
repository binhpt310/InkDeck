package dev.inkdeck.eink

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ProgressBar
import androidx.recyclerview.widget.RecyclerView

/**
 * Animation suppression that themes cannot reach — Plan.md §3.4 item 5, design.md §14 item 1.
 *
 * Theme.InkDeck kills window transitions and ripples declaratively. Everything below is set on
 * live view instances: item animators, overscroll glow, layout transitions, and fling.
 *
 * At 16 fps a 300 ms ripple is five frames of smear and a fling is a smear with no defined end
 * state. There is no "reduced motion" here — the target is zero.
 */
object EinkAnim {

    /**
     * Recursively strip motion from [root] and everything under it. Call once after inflating
     * a hierarchy; it is cheap and idempotent.
     */
    fun strip(root: View) {
        root.overScrollMode = View.OVER_SCROLL_NEVER

        when (root) {
            is RecyclerView -> {
                // notifyItemChanged would otherwise cross-fade the row.
                root.itemAnimator = null
                noFling(root)
            }
            is AbsListView -> {
                root.isSmoothScrollbarEnabled = false
            }
            is ProgressBar -> {
                // design.md §14 item 9: no indeterminate progress, stepped bars only. An
                // indeterminate spinner burns a refresh every frame, forever.
                root.isIndeterminate = false
            }
        }

        if (root is ViewGroup) {
            root.layoutTransition = null
            root.isAnimationCacheEnabled = false
            for (i in 0 until root.childCount) strip(root.getChildAt(i))
        }
    }

    /** Momentum scrolling at 16 fps is an undirected smear. Paged jumps replace it (§5.5). */
    fun noFling(rv: RecyclerView) {
        rv.onFlingListener = object : RecyclerView.OnFlingListener() {
            override fun onFling(velocityX: Int, velocityY: Int): Boolean = true
        }
    }

    /**
     * Cancel the enter/exit transition for an Activity that is starting or finishing. The theme
     * already nulls the animation style; this covers Activities started with an explicit
     * options bundle and the back stack.
     */
    @Suppress("DEPRECATION")
    fun noTransition(activity: Activity) {
        activity.overridePendingTransition(0, 0)
    }
}
