package dev.inkdeck.tasks.list

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.inkdeck.data.tasks.Task
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.R as EinkR
import dev.inkdeck.eink.widget.EinkRecyclerView
import dev.inkdeck.tasks.R

/**
 * One pane of the 2×2 task board — design.md §8.1 as revised.
 *
 * ```
 * ┌─────────────────────────────┐
 * │ TODAY                    3  │  36 dp, count right-aligned
 * ├─────────────────────────────┤
 * │ ▐ ☐ Rotate Binance key      │  52 dp rows, compact
 * │     01:45                   │
 * │   ☐ Review bot logs         │
 * │     17:00                   │
 * └─────────────────────────────┘
 * ```
 *
 * The board replaced a `Today | Week | All | Done` tab strip. Tabs cost a tap and a full-screen
 * `[F]` flush to answer "is there anything else", and on e-ink that question is expensive enough
 * that you stop asking it. Four panes answer it at a glance; the price is that each is ~286 dp
 * wide, which is what drives the compact row below.
 *
 * **The pane is scrollable but carries no paged rail.** §5.5 puts a 56 dp rail on scrollable
 * surfaces; four of them would eat 224 dp of a 572 dp screen to scroll lists that are usually
 * three rows long. The count in the header is what tells you there is more, and the header is
 * tappable to open the pane full-screen when there genuinely is.
 */
class TaskPaneView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    var onExpand: (() -> Unit)? = null

    private val header = HeaderView(context)
    private val list = EinkRecyclerView(context)
    private val loadingBar = dev.inkdeck.eink.widget.StepBar(context)
    private val adapter: CompactAdapter

    private var onToggle: (Task) -> Unit = {}
    private var onOpen: (Task) -> Unit = {}

    init {
        orientation = VERTICAL
        setBackgroundColor(EinkTheme.paper(context))

        header.setOnClickListener { onExpand?.invoke() }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, HeaderView.heightPx(context)))

        adapter = CompactAdapter({ onToggle(it) }, { onOpen(it) })
        list.layoutManager = LinearLayoutManager(context)
        list.adapter = adapter
        list.clipToPadding = true
        addView(list, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // StepBar floats over the (hidden) list while loading. Wrap in a frame so it centres.
        loadingBar.visibility = View.GONE
        val loadHolder = android.widget.FrameLayout(context).apply {
            addView(
                loadingBar,
                android.widget.FrameLayout.LayoutParams(
                    EinkTheme.dp(context, 120f).toInt(),
                    EinkTheme.dp(context, 12f).toInt(),
                    android.view.Gravity.CENTER,
                ),
            )
        }
        addView(loadHolder, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    fun setCallbacks(toggle: (Task) -> Unit, open: (Task) -> Unit) {
        onToggle = toggle
        onOpen = open
    }

    fun bind(label: String, tasks: List<Task>, emptyText: String) {
        header.label = label
        header.count = tasks.size
        adapter.submit(tasks, emptyText)
        // A real emission always clears the loading bar; the loading bar is also cleared on the
        // first emit even if the resulting list is empty (an empty pane is still a result).
        loading = false
    }

    /**
     * Phase 9 item 7. While [loading] is true the list is hidden and a centred [StepBar] is
     * shown — design.md §5.7's LOADING silhouette for the tasks board. Four panes loading at
     * once would otherwise flash empty for the few hundred ms Room takes to deliver on first
     * subscription.
     */
    var loading: Boolean = false
        set(value) {
            field = value
            list.visibility = if (value) View.GONE else View.VISIBLE
            loadingBar.visibility = if (value) View.VISIBLE else View.GONE
            invalidate()
        }

    /** `TODAY   3` — caption weight, count right-aligned so the eye can scan the column. */
    private class HeaderView(context: Context) : View(context) {

        var label: String = ""
            set(value) {
                field = value
                syncDescription()
                invalidate()
            }

        var count: Int = 0
            set(value) {
                field = value
                syncDescription()
                invalidate()
            }

        private fun syncDescription() {
            contentDescription = "$label, $count"
        }

        private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = EinkTheme.sp(context, 14f)
            letterSpacing = 0.08f
        }
        private val countPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = EinkTheme.sp(context, 14f)
            textAlign = Paint.Align.RIGHT
        }
        private val rulePaint = Paint().apply { strokeWidth = EinkTheme.dp(context, 1f) }
        private val margin = EinkTheme.dp(context, 12f)

        init {
            isClickable = true
        }

        override fun onDraw(canvas: Canvas) {
            val baseline = height * 0.68f

            labelPaint.color = EinkTheme.ink500(context)
            labelPaint.typeface = EinkTheme.uiEmphasisTypeface(context)
            canvas.drawText(label, margin, baseline, labelPaint)

            // The count is ink_900 while the label is ink_500: the number is the information,
            // the label is just what it counts.
            countPaint.color = EinkTheme.ink900(context)
            countPaint.typeface = EinkTheme.uiEmphasisTypeface(context)
            canvas.drawText(count.toString(), width - margin, baseline, countPaint)

            rulePaint.color = EinkTheme.ink900(context)
            canvas.drawLine(0f, height - 1f, width.toFloat(), height - 1f, rulePaint)
        }

        companion object {
            fun heightPx(context: Context) = EinkTheme.dp(context, 36f).toInt()
        }
    }

    private class CompactAdapter(
        private val onToggle: (Task) -> Unit,
        private val onOpen: (Task) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var tasks: List<Task> = emptyList()
        private var emptyText: String = ""

        fun submit(items: List<Task>, empty: String) {
            tasks = items
            emptyText = empty
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = if (tasks.isEmpty()) 1 else tasks.size

        override fun getItemViewType(position: Int): Int =
            if (tasks.isEmpty()) TYPE_EMPTY else TYPE_ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val context = parent.context
            return if (viewType == TYPE_EMPTY) {
                val text = android.widget.TextView(context).apply {
                    setTextAppearance(EinkR.style.TextAppearance_InkDeck_Caption)
                    gravity = Gravity.CENTER
                    // MATCH_PARENT: LinearLayoutManager's default is WRAP_CONTENT, which would
                    // leave the placeholder hugging its own text in the corner.
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        EinkTheme.dp(context, 72f).toInt(),
                    )
                }
                object : RecyclerView.ViewHolder(text) {}
            } else {
                val row = CompactRowView(context)
                row.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    CompactRowView.heightPx(context),
                )
                object : RecyclerView.ViewHolder(row) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (tasks.isEmpty()) {
                (holder.itemView as android.widget.TextView).text = emptyText
                return
            }
            val task = tasks[position]
            (holder.itemView as CompactRowView).apply {
                bind(task)
                onToggleTapped = { this@CompactAdapter.onToggle(task) }
                onRowTapped = { this@CompactAdapter.onOpen(task) }
            }
        }

        private companion object {
            const val TYPE_EMPTY = 0
            const val TYPE_ROW = 1
        }
    }
}

/**
 * A 52 dp row for the board panes: checkbox, one-line title, time beneath.
 *
 * The full-width row (`TaskRowView`) carries a priority column and a repeat clause. At 286 dp
 * neither survives — the title would be cut to a dozen characters to make room for `▲ P1`. So
 * priority moves into the **title's weight**: P1 is drawn in the emphasis face, P2 and P3 plain.
 * That is the one signal worth keeping and it costs no width. Colour is not an option here (§14
 * item 3) and neither is a second glyph column.
 *
 * The checkbox is drawn rather than composed so the whole row is one view and one measure pass;
 * with up to four panes on screen the row count is what the layout budget is spent on.
 */
class CompactRowView(context: Context) : View(context) {

    var onToggleTapped: (() -> Unit)? = null
    var onRowTapped: (() -> Unit)? = null

    private var title = ""
    private var subtitle = ""
    private var checked = false
    private var overdue = false
    private var high = false

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = EinkTheme.sp(context, 15f)
    }
    private val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = EinkTheme.sp(context, 13f)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = EinkTheme.dp(context, 1.5f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rulePaint = Paint().apply { strokeWidth = EinkTheme.dp(context, 1f) }

    private val boxSize = EinkTheme.dp(context, 20f)
    private val boxColumn = EinkTheme.dp(context, 40f)
    private val overdueBar = EinkTheme.dp(context, 3f)
    private val endPad = EinkTheme.dp(context, 8f)
    private val box = android.graphics.RectF()
    private val tick = android.graphics.Path()

    init {
        isClickable = true
    }

    fun bind(task: Task) {
        title = task.title
        checked = task.status == dev.inkdeck.data.tasks.TaskStatus.DONE
        overdue = task.isOverdue
        high = task.priority == dev.inkdeck.data.tasks.Priority.P1
        subtitle = task.dueAt?.let {
            dev.inkdeck.tasks.TaskFormat.dueLine(context, it, task.zone())
        } ?: context.getString(R.string.tasks_no_date)
        // Canvas text is invisible to TalkBack and uiautomator.
        contentDescription = "$title, $subtitle"
        invalidate()
    }

    /**
     * The checkbox column is its own hit region; the rest of the row opens the editor. Both are
     * handled here rather than with a child view so the row stays a single `View`.
     */
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
            if (event.x <= boxColumn) onToggleTapped?.invoke() else onRowTapped?.invoke()
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        val ink = EinkTheme.ink900(context)
        val muted = EinkTheme.ink500(context)
        val paper = EinkTheme.paper(context)

        if (overdue) {
            fillPaint.color = ink
            canvas.drawRect(0f, 0f, overdueBar, height.toFloat(), fillPaint)
        }

        val cx = boxColumn / 2f
        val cy = height / 2f
        box.set(cx - boxSize / 2, cy - boxSize / 2, cx + boxSize / 2, cy + boxSize / 2)
        if (checked) {
            fillPaint.color = ink
            canvas.drawRoundRect(box, 3f, 3f, fillPaint)
            strokePaint.color = paper
            tick.reset()
            val w = box.width()
            tick.moveTo(box.left + w * 0.24f, box.top + w * 0.52f)
            tick.lineTo(box.left + w * 0.43f, box.top + w * 0.71f)
            tick.lineTo(box.left + w * 0.77f, box.top + w * 0.30f)
            canvas.drawPath(tick, strokePaint)
        } else {
            strokePaint.color = ink
            canvas.drawRoundRect(box, 3f, 3f, strokePaint)
        }

        val left = boxColumn
        val available = (width - left - endPad).coerceAtLeast(0f)

        titlePaint.color = if (checked) muted else ink
        // Priority survives as weight, not as a column. See the class note.
        titlePaint.typeface = if (high && !checked) {
            EinkTheme.uiEmphasisTypeface(context)
        } else {
            EinkTheme.uiTypeface(context)
        }
        val clipped = TextUtils.ellipsize(title, titlePaint, available, TextUtils.TruncateAt.END)
        canvas.drawText(clipped.toString(), left, height * 0.44f, titlePaint)

        subPaint.color = muted
        subPaint.typeface = EinkTheme.uiTypeface(context)
        val sub = TextUtils.ellipsize(subtitle, subPaint, available, TextUtils.TruncateAt.END)
        canvas.drawText(sub.toString(), left, height * 0.80f, subPaint)

        rulePaint.color = EinkTheme.ink200(context)
        canvas.drawLine(left, height - 1f, width.toFloat(), height - 1f, rulePaint)
    }

    companion object {
        fun heightPx(context: Context) = EinkTheme.dp(context, 52f).toInt()
    }
}
