package dev.inkdeck.tasks.list

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.inkdeck.data.tasks.Task
import dev.inkdeck.data.tasks.TaskStatus
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.R as EinkR
import dev.inkdeck.eink.widget.EinkCheckbox
import dev.inkdeck.tasks.TaskFormat

/**
 * One task row — design.md §8.1.
 *
 * ```
 *  ▐│ ☐  Rotate Binance API key                    ▲ P1 │
 *  ▐│    Yesterday 18:00 · ✈                            │
 * ```
 *
 * A container with a real [EinkCheckbox] child plus one `onDraw` for everything else. Splitting
 * it that way is deliberate: the checkbox is the only part with its own press state and its own
 * hit region, and giving it a real view keeps that logic in one place. The title, subtitle,
 * priority and overdue bar are static text and rules — four more child views would cost a
 * measure pass per row for nothing.
 *
 * Completing a task is `[P]` on this row only (§13), which is why the checkbox has its own
 * click listener rather than the whole row toggling.
 */
class TaskRowView(context: Context) : FrameLayout(context) {

    var onToggle: (() -> Unit)? = null
    var onOpen: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    private val checkbox = EinkCheckbox(context)

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = EinkTheme.sp(context, 18f)
    }
    private val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = EinkTheme.sp(context, 14f)
    }
    private val priorityPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = EinkTheme.sp(context, 14f)
        textAlign = Paint.Align.RIGHT
    }
    private val barPaint = Paint()
    private val dividerPaint = Paint().apply { strokeWidth = EinkTheme.dp(context, 1f) }
    private val strikePaint = Paint().apply { strokeWidth = EinkTheme.dp(context, 1.5f) }

    private val overdueBar = EinkTheme.dp(context, 4f)
    private val checkboxWidth = resources.getDimensionPixelSize(EinkR.dimen.ink_touch_min)
    private val endMargin = EinkTheme.dp(context, 16f)
    private val priorityWidth = EinkTheme.dp(context, 44f)

    private var task: Task? = null
    private var title = ""
    private var subtitle = ""
    private var priorityLabel = ""
    private var overdue = false
    private var done = false

    init {
        setWillNotDraw(false)
        isClickable = true
        isLongClickable = true
        addView(
            checkbox,
            LayoutParams(checkboxWidth, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        checkbox.setOnClickListener { onToggle?.invoke() }

        setOnClickListener { onOpen?.invoke() }
        setOnLongClickListener {
            onLongPress?.invoke()
            true
        }
    }

    fun bind(task: Task) {
        this.task = task
        title = task.title
        done = task.status == TaskStatus.DONE
        overdue = task.isOverdue
        checkbox.checked = done
        priorityLabel = "${task.priority.glyph} ${task.priority.name}"

        subtitle = buildString {
            task.dueAt?.let { append(TaskFormat.dueLine(context, it, task.zone())) }
            if (task.repeat.repeats) {
                if (isNotEmpty()) append(" · ")
                append(task.repeat.describe())
            }
            if (done) {
                task.completedAt?.let {
                    if (isNotEmpty()) append(" · ")
                    append(context.getString(dev.inkdeck.tasks.R.string.tasks_done_at, TaskFormat.time(it)))
                }
            }
            if (task.telegramNotify) {
                if (isNotEmpty()) append(" · ")
                append(TELEGRAM_GLYPH)
            }
        }

        // Everything above is Canvas text and therefore invisible to TalkBack and uiautomator.
        contentDescription = "$title, $subtitle, ${task.priority.name}"
        checkbox.contentDescription = context.getString(
            if (done) {
                dev.inkdeck.tasks.R.string.tasks_a11y_reopen
            } else {
                dev.inkdeck.tasks.R.string.tasks_a11y_complete
            },
            title,
        )
        invalidate()
    }

    /**
     * The checkbox draws its own press invert; letting the row invert as well would flash the
     * whole 72 dp strip for a tap on a 28 dp box. So the row itself has no pressed state, and
     * the tap that opens the editor is acknowledged by the editor appearing.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    override fun onDraw(canvas: Canvas) {
        val ink = EinkTheme.ink900(context)
        val secondary = EinkTheme.ink500(context)

        if (overdue) {
            barPaint.color = ink
            canvas.drawRect(0f, 0f, overdueBar, height.toFloat(), barPaint)
        }

        val left = checkboxWidth.toFloat()
        val right = width - endMargin

        priorityPaint.color = if (done) secondary else ink
        priorityPaint.typeface = EinkTheme.uiTypeface(context)

        val titleRight = right - priorityWidth
        titlePaint.color = if (done) secondary else ink
        titlePaint.typeface = EinkTheme.uiTypeface(context)

        val titleBaseline = height * 0.42f
        val clipped = TextUtils.ellipsize(
            title, titlePaint, (titleRight - left).coerceAtLeast(0f), TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(clipped, left, titleBaseline, titlePaint)

        if (done) {
            // design.md §8.1 strikes through a completed title. Drawn rather than set as a
            // paint flag so it stops at the text and not at the ellipsis padding.
            strikePaint.color = secondary
            val w = titlePaint.measureText(clipped)
            val y = titleBaseline - titlePaint.textSize * 0.3f
            canvas.drawLine(left, y, left + w, y, strikePaint)
        }

        subtitlePaint.color = secondary
        subtitlePaint.typeface = EinkTheme.uiTypeface(context)
        val subClipped = TextUtils.ellipsize(
            subtitle, subtitlePaint, (right - left).coerceAtLeast(0f), TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(subClipped, left, height * 0.75f, subtitlePaint)

        canvas.drawText(priorityLabel, right, titleBaseline, priorityPaint)

        dividerPaint.color = EinkTheme.ink200(context)
        canvas.drawLine(left, height - 1f, width.toFloat(), height - 1f, dividerPaint)
    }

    companion object {
        /** `✈` in design.md §8.1 — "will notify Telegram". */
        const val TELEGRAM_GLYPH = "✈"

        /** Two text lines plus breathing room; above the 56 dp touch minimum. */
        fun heightPx(context: Context): Int = EinkTheme.dp(context, 72f).toInt()
    }
}

/** `OVERDUE` / `TODAY` — the caption-weight group headers in design.md §8.1. */
class TaskSectionView(context: Context) : View(context) {

    var label: String = ""
        set(value) {
            field = value
            contentDescription = value
            invalidate()
        }

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = EinkTheme.sp(context, 14f)
        letterSpacing = 0.08f
    }
    private val dividerPaint = Paint().apply { strokeWidth = EinkTheme.dp(context, 1f) }
    private val margin = EinkTheme.dp(context, 16f)

    override fun onDraw(canvas: Canvas) {
        paint.color = EinkTheme.ink500(context)
        paint.typeface = EinkTheme.uiEmphasisTypeface(context)
        canvas.drawText(label, margin, height * 0.72f, paint)

        dividerPaint.color = EinkTheme.ink200(context)
        canvas.drawLine(0f, height - 1f, width.toFloat(), height - 1f, dividerPaint)
    }

    companion object {
        fun heightPx(context: Context): Int = EinkTheme.dp(context, 36f).toInt()
    }
}
