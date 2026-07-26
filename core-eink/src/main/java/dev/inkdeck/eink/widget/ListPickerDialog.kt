package dev.inkdeck.eink.widget

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.inkdeck.eink.EinkAnim
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.R
import dev.inkdeck.eink.refresh.EinkRefresher

/**
 * The stepped list picker that replaces every spinner wheel — design.md §8.2, §14 item 9.
 *
 * A wheel is a fling with detents: continuous motion, no stable intermediate frames, and at
 * 16 fps it renders as a vertical smear that settles on whatever value happened to be under the
 * finger. A list of discrete 56 dp rows has one legible state per row and pages with the rail.
 *
 * Used for date, time, and any other "pick one of many" that is too long for a
 * [SegmentedControl].
 */
class ListPickerDialog(
    context: Context,
    private val title: String,
    private val options: List<String>,
    private val selected: Int,
    private val refresher: EinkRefresher?,
    private val onPick: (index: Int) -> Unit,
) : Dialog(context, R.style.Theme_InkDeck_Dialog) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(EinkTheme.paper(context))
        }

        root.addView(
            TextView(context).apply {
                text = title
                setTextAppearance(R.style.TextAppearance_InkDeck_Title2)
                gravity = Gravity.CENTER_VERTICAL
                val pad = resources.getDimensionPixelSize(R.dimen.ink_screen_margin)
                setPadding(pad, 0, pad, 0)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.resources.getDimensionPixelSize(R.dimen.ink_bar_height),
            ),
        )
        root.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dividerHeight()))

        val list = EinkRecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = Adapter()
        }
        val rail = PagedScrollRail(context).apply {
            this.refresher = this@ListPickerDialog.refresher
            attach(list)
        }

        val stack = FrameLayout(context)
        stack.addView(
            list,
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
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = EinkTheme.dp(context, 8f).toInt()
            },
        )
        // Rows must clear the floating rail or their tails sit underneath it.
        list.setPadding(0, 0, context.resources.getDimensionPixelSize(R.dimen.ink_rail_width) +
            EinkTheme.dp(context, 16f).toInt(), 0)
        list.clipToPadding = true

        root.addView(
            stack,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dividerHeight()))
        root.addView(
            EinkButton(context).apply {
                text = context.getString(R.string.ink_cancel)
                setOnClickListener { dismiss() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.resources.getDimensionPixelSize(R.dimen.ink_touch_min),
            ).apply {
                val m = context.resources.getDimensionPixelSize(R.dimen.ink_space_3)
                setMargins(m, m, m, m)
            },
        )

        setContentView(root)
        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setWindowAnimations(0)
        }
        EinkAnim.strip(root)

        // Land on the current value rather than at the top: scrolling 40 rows to find "14:30"
        // when it is already selected is the whole problem with long pickers.
        if (selected in options.indices) {
            list.post {
                (list.layoutManager as LinearLayoutManager)
                    .scrollToPositionWithOffset(selected, EinkTheme.dp(context, 96f).toInt())
            }
        }
        refresher?.flush("list-picker")
    }

    override fun dismiss() {
        // A full-screen dialog vanishing leaves its whole frame ghosted over the screen beneath.
        refresher?.flush("list-picker-dismiss")
        super.dismiss()
    }

    private fun divider() = View(context).apply { setBackgroundColor(EinkTheme.ink200(context)) }
    private fun dividerHeight() = context.resources.getDimensionPixelSize(R.dimen.ink_divider)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount(): Int = options.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(RowView(parent.context))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.row.label = options[position]
            holder.row.isSelectedRow = position == selected
            holder.row.setOnClickListener {
                onPick(position)
                dismiss()
            }
        }
    }

    private inner class Holder(val row: RowView) : RecyclerView.ViewHolder(row) {
        init {
            row.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.resources.getDimensionPixelSize(R.dimen.ink_touch_min),
            )
        }
    }

    /**
     * One row. The selected row is marked by the 4 dp left bar from design.md §5.3 plus emphasis
     * weight — not by a grey fill, which would be a second near-paper tone.
     */
    private class RowView(context: Context) : PressInvertView(context) {

        var label: String = ""
            set(value) {
                field = value
                // Canvas text is invisible to TalkBack and to uiautomator. Every drawn label in
                // this project has to be mirrored here or the row is an unlabelled box.
                contentDescription = value
                invalidate()
            }

        var isSelectedRow: Boolean = false
            set(value) {
                field = value
                isSelected = value
                invalidate()
            }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = EinkTheme.sp(context, 18f)
        }
        private val barPaint = Paint()
        private val dividerPaint = Paint().apply { strokeWidth = EinkTheme.dp(context, 1f) }

        private val margin = EinkTheme.dp(context, 16f)
        private val barWidth = EinkTheme.dp(context, 4f)

        override fun onDraw(canvas: Canvas) {
            val ink = EinkTheme.ink900(context)
            val paper = EinkTheme.paper(context)

            canvas.drawColor(if (inverted) ink else paper)

            if (isSelectedRow) {
                barPaint.color = if (inverted) paper else ink
                canvas.drawRect(0f, 0f, barWidth, height.toFloat(), barPaint)
            }

            textPaint.color = if (inverted) paper else ink
            textPaint.typeface = if (isSelectedRow) {
                EinkTheme.uiEmphasisTypeface(context)
            } else {
                EinkTheme.uiTypeface(context)
            }
            val metrics = textPaint.fontMetrics
            val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(label, margin, baseline, textPaint)

            dividerPaint.color = EinkTheme.ink200(context)
            canvas.drawLine(margin, height - 1f, width.toFloat(), height - 1f, dividerPaint)
        }
    }
}
