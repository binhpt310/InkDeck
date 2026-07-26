package dev.inkdeck.eink.refresh

import android.app.Activity
import android.util.Log

/**
 * The refresh policy from Plan.md §3.4, in one place.
 *
 *  1. Partial by default. Invalidate the smallest rect that changed; typing a terminal
 *     character must not repaint the screen.
 *  2. Ghost budget. Count partials per surface. After [ghostBudget] of them on any one
 *     surface, flush.
 *  3. Flush outright on the interactions design.md §13 classifies `[F]` — tab switch, page
 *     jump, drawer, theme, rotation.
 *
 * Counting is per surface rather than global because the surfaces refresh at wildly different
 * rates: a terminal streaming output would otherwise spend the whole budget and force flushes
 * on a task list that has not changed since it was drawn.
 *
 * N = 8 is design.md §15's starting guess and is explicitly flagged as needing device tuning.
 * Every decision here is logged under [REFRESH_LOG]; watch it while using the app with
 *   adb logcat -s InkDeckRefresh
 * and move [ghostBudget] until ghosting is tolerable at the fewest flushes.
 */
class EinkRefresher(
    private val activity: Activity,
    var strategy: FlushStrategy,
    var ghostBudget: Int = DEFAULT_GHOST_BUDGET,
) {

    private val partials = HashMap<String, Int>()

    /** Total flushes since construction — surfaced in EinkLabActivity for tuning. */
    var flushCount: Int = 0
        private set

    /**
     * Record a `[P]` partial repaint on [surface]. Returns true if it tripped the budget and a
     * flush was performed.
     */
    fun notePartial(surface: String, reason: String = ""): Boolean {
        val n = (partials[surface] ?: 0) + 1
        partials[surface] = n
        if (n >= ghostBudget) {
            flush("ghost-budget surface=$surface n=$n")
            return true
        }
        Log.v(REFRESH_LOG, "partial surface=$surface n=$n/$ghostBudget $reason")
        return false
    }

    /** Perform an `[F]` flush now and reset every surface's budget. */
    fun flush(reason: String) {
        flushCount++
        Log.d(REFRESH_LOG, "FLUSH #$flushCount reason=$reason strategy=${strategy.id}")
        partials.clear()
        strategy.flush(activity)
    }

    /**
     * Drop the accumulated count for a surface without flushing. For when a surface is
     * destroyed or fully redrawn by something that already caused a flush.
     */
    fun resetSurface(surface: String) {
        partials.remove(surface)
    }

    fun partialCount(surface: String): Int = partials[surface] ?: 0

    companion object {
        /** design.md §15 open question 5: a starting guess, must be tuned on hardware. */
        const val DEFAULT_GHOST_BUDGET = 8

        // Surface ids. Kept here so the logcat tuning output has a stable vocabulary.
        const val SURFACE_TERMINAL = "terminal"
        const val SURFACE_LIST = "list"
        const val SURFACE_SHELL = "shell"
        const val SURFACE_LAB = "lab"
    }
}

/** Implemented by the Activity so Fragments and widgets can reach the shared refresher. */
interface RefresherHost {
    val refresher: EinkRefresher
}
