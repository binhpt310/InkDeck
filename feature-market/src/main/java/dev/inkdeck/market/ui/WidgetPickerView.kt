package dev.inkdeck.market.ui

import android.content.Context
import android.text.InputFilter
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.R as EinkR
import dev.inkdeck.eink.refresh.EinkRefresher
import dev.inkdeck.eink.widget.EinkIconButton
import dev.inkdeck.eink.widget.EinkScrollView
import dev.inkdeck.eink.widget.PagedScrollRail
import dev.inkdeck.eink.widget.SegmentedControl
import dev.inkdeck.market.MarketAsset
import dev.inkdeck.market.MarketCategory
import dev.inkdeck.market.MarketPrefs
import dev.inkdeck.market.MarketProviders
import dev.inkdeck.market.R

/**
 * design.md §9.2 — the widget picker.
 *
 * A sibling overlay inside the Market tab rather than a fragment transaction or a dialog, for the
 * same reason `TaskEditorView` is: a fragment swap animates by default and the tab bar underneath
 * must not move. Opening and closing it is `[F]` — the whole viewport is replaced.
 *
 * On/off uses a two-cell [SegmentedControl] rather than a switch. design.md §5.2 is explicit that
 * a switch is a bad fit here: the thumb is a small shape whose position is the only state
 * carrier, and after ghosting a user cannot tell which side it sits on. A filled `ON` cell can
 * only be read one way.
 */
class WidgetPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onSelectionChanged()
        fun onRefreshIntervalChanged(minutes: Int)
        fun onClose()
    }

    var listener: Listener? = null
    var refresher: EinkRefresher? = null

    private lateinit var prefs: MarketPrefs
    private lateinit var providers: MarketProviders

    private val body = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroller = EinkScrollView(context)
    private val rail = PagedScrollRail(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(EinkTheme.paper(context))
        // Swallow taps so they cannot reach the grid underneath while the picker is up.
        isClickable = true

        addView(buildHeader(), LayoutParams(
            LayoutParams.MATCH_PARENT,
            resources.getDimensionPixelSize(EinkR.dimen.ink_bar_height),
        ))
        addView(divider(), LayoutParams(LayoutParams.MATCH_PARENT, dividerHeight()))

        val stack = FrameLayout(context)
        scroller.addView(
            body,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        // Rows must stop short of the rail or their right edge sits underneath it.
        scroller.setPadding(
            0, 0,
            resources.getDimensionPixelSize(EinkR.dimen.ink_rail_width) +
                EinkTheme.dp(context, 12f).toInt(),
            0,
        )
        stack.addView(
            scroller,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        stack.addView(
            rail,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ),
        )
        addView(stack, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun buildHeader(): View {
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            EinkIconButton(context).apply {
                setIconResource(R.drawable.ic_market_back)
                contentDescription = context.getString(R.string.market_back)
                setOnClickListener { listener?.onClose() }
            },
            LayoutParams(
                resources.getDimensionPixelSize(EinkR.dimen.ink_touch_min),
                resources.getDimensionPixelSize(EinkR.dimen.ink_touch_min),
            ),
        )
        header.addView(
            TextView(context).apply {
                setTextAppearance(EinkR.style.TextAppearance_InkDeck_Title1)
                text = context.getString(R.string.market_widgets)
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            EinkIconButton(context).apply {
                setIconResource(R.drawable.ic_market_add)
                contentDescription = context.getString(R.string.market_add_symbol)
                setOnClickListener { promptForSymbol() }
            },
            LayoutParams(
                resources.getDimensionPixelSize(EinkR.dimen.ink_touch_min),
                resources.getDimensionPixelSize(EinkR.dimen.ink_touch_min),
            ),
        )
        return header
    }

    fun bind(prefs: MarketPrefs, providers: MarketProviders) {
        this.prefs = prefs
        this.providers = providers
        rail.refresher = refresher
        rail.attach(scroller)
        rebuild()
    }

    private fun rebuild() {
        body.removeAllViews()

        val enabled = prefs.enabledIds.toHashSet()
        val assets = prefs.allAssets()

        for (category in MarketCategory.entries) {
            val inCategory = assets.filter { it.category == category }
            if (inCategory.isEmpty()) continue
            body.addView(sectionHeader(categoryTitle(category), categoryNote(category)))
            inCategory.forEach { body.addView(assetRow(it, enabled.contains(it.id))) }
        }

        body.addView(sectionHeader(context.getString(R.string.market_section_refresh), null))
        body.addView(refreshRow())
        body.addView(
            TextView(context).apply {
                setTextAppearance(EinkR.style.TextAppearance_InkDeck_Caption)
                text = context.getString(R.string.market_refresh_note)
                val pad = resources.getDimensionPixelSize(EinkR.dimen.ink_screen_margin)
                setPadding(pad, resources.getDimensionPixelSize(EinkR.dimen.ink_space_2), pad,
                    resources.getDimensionPixelSize(EinkR.dimen.ink_space_8))
            },
        )
    }

    private fun categoryTitle(category: MarketCategory): String = context.getString(
        when (category) {
            MarketCategory.CRYPTO -> R.string.market_section_crypto
            MarketCategory.US -> R.string.market_section_us
            MarketCategory.VN -> R.string.market_section_vn
        }
    )

    /**
     * The right-hand note names the source and its standing. Plan.md §5.2 is candid about the
     * VN endpoints in the plan; saying it in the plan and not in the app would be the wrong way
     * round, so the warning is on the section the user actually turns on.
     */
    private fun categoryNote(category: MarketCategory): String = context.getString(
        if (providers.isUnofficial(category)) {
            R.string.market_note_unofficial
        } else when (category) {
            MarketCategory.CRYPTO -> R.string.market_note_crypto
            MarketCategory.US -> R.string.market_note_us
            MarketCategory.VN -> R.string.market_note_unofficial
        }
    )

    private fun sectionHeader(title: String, note: String?): View {
        val wrap = LinearLayout(context).apply { orientation = VERTICAL }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
            val pad = resources.getDimensionPixelSize(EinkR.dimen.ink_screen_margin)
            setPadding(pad, resources.getDimensionPixelSize(EinkR.dimen.ink_space_6), pad,
                resources.getDimensionPixelSize(EinkR.dimen.ink_space_2))
        }
        row.addView(
            TextView(context).apply {
                setTextAppearance(EinkR.style.TextAppearance_InkDeck_Caption_Emphasis)
                text = title
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        if (note != null) {
            row.addView(
                TextView(context).apply {
                    setTextAppearance(EinkR.style.TextAppearance_InkDeck_Caption)
                    text = note
                    gravity = Gravity.END
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
        }
        wrap.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        wrap.addView(divider(), LayoutParams(LayoutParams.MATCH_PARENT, dividerHeight()))
        return wrap
    }

    private fun assetRow(asset: MarketAsset, enabled: Boolean): View {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = resources.getDimensionPixelSize(EinkR.dimen.ink_screen_margin)
            setPadding(pad, 0, pad, 0)
            minimumHeight = resources.getDimensionPixelSize(EinkR.dimen.ink_row_min)
        }
        row.addView(
            TextView(context).apply {
                setTextAppearance(EinkR.style.TextAppearance_InkDeck_BodyLarge)
                text = asset.display
                maxLines = 1
            },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            SegmentedControl(context).apply {
                segments = listOf(
                    context.getString(R.string.market_on),
                    context.getString(R.string.market_off),
                )
                selectedIndex = if (enabled) 0 else 1
                // SegmentedControl announces "on, on off" by itself, which is unambiguous about
                // the state and says nothing about which symbol it belongs to. The row label is a
                // separate view, so the symbol has to be repeated here or the picker is a column
                // of nine identical announcements.
                describe(asset.display, selectedIndex == 0)
                onSelected = { index ->
                    describe(asset.display, index == 0)
                    prefs.setEnabled(asset, index == 0)
                    listener?.onSelectionChanged()
                }
            },
            LayoutParams(
                EinkTheme.dp(context, 140f).toInt(),
                resources.getDimensionPixelSize(EinkR.dimen.ink_touch_min),
            ),
        )
        return row
    }

    private fun refreshRow(): View {
        val wrap = FrameLayout(context).apply {
            val pad = resources.getDimensionPixelSize(EinkR.dimen.ink_screen_margin)
            setPadding(pad, resources.getDimensionPixelSize(EinkR.dimen.ink_space_2), pad, 0)
        }
        val control = SegmentedControl(context).apply {
            segments = MarketPrefs.REFRESH_CHOICES.map { minutes ->
                if (minutes == 0) context.getString(R.string.market_refresh_manual)
                else context.getString(R.string.market_refresh_minutes, minutes)
            }
            selectedIndex = MarketPrefs.REFRESH_CHOICES
                .indexOfFirst { it == prefs.refreshMinutes }.coerceAtLeast(0)
            onSelected = { index ->
                val minutes = MarketPrefs.REFRESH_CHOICES[index]
                prefs.refreshMinutes = minutes
                listener?.onRefreshIntervalChanged(minutes)
            }
        }
        wrap.addView(
            control,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(EinkR.dimen.ink_touch_min),
            ),
        )
        return wrap
    }

    /**
     * `✚ symbol` from §9.2's header.
     *
     * Category first, because the symbol alone does not say which provider chain to use — `FPT`
     * is a HOSE ticker and also a plausible US one. Both controls live in one dialog rather than
     * two steps: two dialogs is two full-screen `[F]` flushes for one decision.
     */
    private fun promptForSymbol() {
        val pad = resources.getDimensionPixelSize(EinkR.dimen.ink_screen_margin)
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(pad, pad, pad, 0)
        }

        val category = SegmentedControl(context).apply {
            segments = MarketCategory.entries.map { categoryTitle(it) }
            selectedIndex = 0
        }
        content.addView(
            category,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(EinkR.dimen.ink_touch_min),
            ),
        )

        val input = EditText(context).apply {
            hint = context.getString(R.string.market_add_symbol_hint)
            setSingleLine()
            // Uppercase on the way in: every provider here wants an uppercase ticker, and
            // normalising at the edge means no adapter has to remember.
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(16))
            setTextAppearance(EinkR.style.TextAppearance_InkDeck_MonoUi)
        }
        content.addView(
            input,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).also {
                it.topMargin = resources.getDimensionPixelSize(EinkR.dimen.ink_space_4)
            },
        )

        AlertDialog.Builder(context)
            .setTitle(R.string.market_add_symbol)
            .setView(content)
            .setPositiveButton(R.string.market_add) { _, _ ->
                val symbol = input.text.toString().trim()
                if (symbol.isEmpty()) return@setPositiveButton
                val chosen = MarketCategory.entries[category.selectedIndex]
                val asset = MarketAsset(
                    chosen, symbol, MarketAsset.defaultDisplay(chosen, symbol),
                )
                prefs.addCustom(asset)
                prefs.setEnabled(asset, true)
                rebuild()
                listener?.onSelectionChanged()
                refresher?.flush("market-symbol-added")
            }
            .setNegativeButton(EinkR.string.ink_cancel, null)
            .show()
            // §14 item 1: no animation, anywhere, including the ones the framework adds for you.
            .also { it.window?.setWindowAnimations(0) }
    }

    private fun SegmentedControl.describe(display: String, on: Boolean) {
        contentDescription = context.getString(
            if (on) R.string.market_toggle_on_desc else R.string.market_toggle_off_desc,
            display,
        )
    }

    private fun divider(): View = View(context).apply {
        setBackgroundColor(EinkTheme.ink200(context))
    }

    private fun dividerHeight(): Int = resources.getDimensionPixelSize(EinkR.dimen.ink_divider)
}
