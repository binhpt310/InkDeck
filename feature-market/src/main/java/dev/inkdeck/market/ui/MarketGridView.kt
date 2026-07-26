package dev.inkdeck.market.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import dev.inkdeck.eink.R as EinkR
import dev.inkdeck.market.MarketAsset
import dev.inkdeck.market.MarketSnapshot

/**
 * The two-column card grid of design.md §9.1.
 *
 * ### Why not a RecyclerView with a GridLayoutManager
 *
 * Recycling exists to keep a long list off the heap. The grid here holds at most a dozen cards,
 * all of which the user chose, and recycling would actively get in the way of the one requirement
 * that matters: §13 says an auto-refresh repaints **only the cards whose values changed**. Through
 * an adapter that means `notifyItemChanged`, which rebinds and redraws the whole row. Holding the
 * views directly lets [update] call [MarketCardView.update] on each card and repaint exactly the
 * ones that answered true.
 *
 * Rows are built once per selection change and reused across refreshes.
 */
class MarketGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    var onCardClick: ((MarketAsset) -> Unit)? = null

    private val cards = LinkedHashMap<String, MarketCardView>()

    private val gap = resources.getDimensionPixelSize(EinkR.dimen.ink_space_3)

    init {
        orientation = VERTICAL
    }

    /** Rebuild the card views. Cheap enough to do wholesale — it only runs on a picker change. */
    fun setAssets(assets: List<MarketAsset>) {
        if (assets.map { it.id } == cards.keys.toList()) return

        removeAllViews()
        cards.clear()

        var row: LinearLayout? = null
        assets.forEachIndexed { index, asset ->
            if (index % COLUMNS == 0) {
                row = LinearLayout(context).apply { orientation = HORIZONTAL }
                addView(
                    row,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also {
                        if (index > 0) it.topMargin = gap
                    },
                )
            }

            val card = MarketCardView(context).apply {
                setOnClickListener { onCardClick?.invoke(asset) }
            }
            cards[asset.id] = card
            row?.addView(
                card,
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).also {
                    if (index % COLUMNS != 0) it.marginStart = gap
                },
            )
        }

        // An odd card count leaves a hole. Filling it with a weighted spacer keeps the last card
        // the same width as every other card — without it the row stretches one card to full
        // width and the grid stops looking like a grid.
        if (assets.size % COLUMNS != 0) {
            row?.addView(android.view.View(context), LayoutParams(0, 1, 1f))
        }
    }

    /**
     * Push new data into the existing cards.
     *
     * @return the number of cards that actually changed. The caller uses it to decide between
     *   noting a partial and doing nothing at all — see design.md §13.
     */
    fun update(snapshots: List<MarketSnapshot>, staleAfterMs: Long): Int {
        var changed = 0
        for (snapshot in snapshots) {
            val card = cards[snapshot.asset.id] ?: continue
            if (card.update(snapshot, staleAfterMs)) changed++
        }
        return changed
    }

    private companion object {
        /** §9.1: "2 columns × 270 dp cards" — 2 × 270 + 12 gap fits the 572 dp content column. */
        const val COLUMNS = 2
    }
}
