package dev.inkdeck.tasks.editor

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.inkdeck.data.tasks.Priority
import dev.inkdeck.data.tasks.RepeatRule
import dev.inkdeck.data.tasks.Task
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.R as EinkR
import dev.inkdeck.eink.refresh.EinkRefresher
import dev.inkdeck.eink.widget.EinkButton
import dev.inkdeck.eink.widget.EinkIconButton
import dev.inkdeck.eink.widget.EinkScrollView
import dev.inkdeck.eink.widget.ListPickerDialog
import dev.inkdeck.eink.widget.SegmentedControl
import dev.inkdeck.tasks.R
import dev.inkdeck.tasks.TaskFormat
import java.time.LocalDate
import java.time.ZoneId

/**
 * The task editor — design.md §8.2.
 *
 * A full-bleed overlay inside the Tasks tab rather than a second Activity or a back-stack
 * Fragment: an Activity transition is a window animation the panel renders as a wipe, and the
 * tab bar underneath must stay put. Same pattern as the file viewer in §7.6.
 *
 * Built in code, not XML, because every field's visibility depends on another field (the
 * weekday row only exists when REPEAT is weekly, the whole time block only when a date is set)
 * and expressing that across a layout file plus a binding class is more moving parts than the
 * layout is worth.
 */
class TaskEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onSave(task: Task)
        fun onDelete(task: Task)
        fun onClose()
    }

    var listener: Listener? = null
    var refresher: EinkRefresher? = null

    private val headerTitle = TextView(context)
    private val titleInput = buildInput(R.string.tasks_field_title_hint, singleLine = true)
    private val notesInput = buildInput(R.string.tasks_field_notes_hint, singleLine = false)

    private val dateField = FieldButton(context)
    private val timeField = FieldButton(context)
    private val zoneControl = SegmentedControl(context)
    private val remindControl = SegmentedControl(context)
    private val repeatControl = SegmentedControl(context)
    private val weekdayPicker = WeekdayPicker(context)
    private val weekdayLabel = buildSectionLabel(R.string.tasks_field_repeat_days)
    private val priorityControl = SegmentedControl(context)
    private val telegramControl = SegmentedControl(context)
    private val deleteButton = EinkIconButton(context)

    /** Working copy. The row on the list behind is not touched until Save. */
    private var editing: Task? = null
    private var dueDate: LocalDate? = null
    private var dueMinutes: Int = DEFAULT_MINUTES

    /** The zone the date and time fields are expressed in. See [Task.zoneId]. */
    private var zone: ZoneId = TaskFormat.deviceZone

    init {
        orientation = VERTICAL
        setBackgroundColor(EinkTheme.paper(context))
        isClickable = true

        addView(buildHeader(), LayoutParams(LayoutParams.MATCH_PARENT, barHeight()))
        addView(divider(), LayoutParams(LayoutParams.MATCH_PARENT, dividerHeight()))

        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            val m = resources.getDimensionPixelSize(EinkR.dimen.ink_screen_margin)
            setPadding(m, m, m, m)
        }
        buildForm(column)

        val scroll = EinkScrollView(context)
        scroll.addView(
            column,
            FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    // ------------------------------------------------------------------ construction

    private fun buildHeader(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        addView(
            EinkIconButton(context).apply {
                setIconResource(R.drawable.ic_back)
                contentDescription = context.getString(R.string.tasks_close)
                setOnClickListener { listener?.onClose() }
            },
            LayoutParams(barHeight(), barHeight()),
        )

        headerTitle.setTextAppearance(EinkR.style.TextAppearance_InkDeck_Title2)
        headerTitle.isSingleLine = true
        addView(headerTitle, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        deleteButton.apply {
            setIconResource(R.drawable.ic_delete)
            contentDescription = context.getString(R.string.tasks_delete)
            setOnClickListener { confirmDelete() }
        }
        addView(deleteButton, LayoutParams(barHeight(), barHeight()))

        addView(
            EinkButton(context).apply {
                text = context.getString(R.string.tasks_save)
                variant = EinkButton.Variant.PRIMARY
                setOnClickListener { save() }
            },
            LayoutParams(EinkTheme.dp(context, 96f).toInt(), EinkTheme.dp(context, 44f).toInt())
                .apply { marginEnd = resources.getDimensionPixelSize(EinkR.dimen.ink_space_3) },
        )
    }

    private fun buildForm(column: LinearLayout) {
        column.addView(buildSectionLabel(R.string.tasks_field_title), labelParams(first = true))
        column.addView(titleInput, inputParams())

        column.addView(buildSectionLabel(R.string.tasks_field_notes), labelParams())
        column.addView(notesInput, inputParams(minHeight = EinkTheme.dp(context, 96f).toInt()))

        column.addView(buildSectionLabel(R.string.tasks_field_due), labelParams())
        val dueRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        dateField.setIconResource(R.drawable.ic_calendar)
        dateField.setOnClickListener { pickDate() }
        timeField.setIconResource(R.drawable.ic_clock)
        timeField.setOnClickListener { pickTime() }
        dueRow.addView(
            dateField,
            LayoutParams(0, resources.getDimensionPixelSize(EinkR.dimen.ink_touch_min), 1.6f),
        )
        dueRow.addView(
            timeField,
            LayoutParams(0, resources.getDimensionPixelSize(EinkR.dimen.ink_touch_min), 1f).apply {
                marginStart = resources.getDimensionPixelSize(EinkR.dimen.ink_space_2)
            },
        )
        column.addView(dueRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        zoneControl.segments = listOf(
            context.getString(R.string.tasks_zone_device, TaskFormat.shortZone(TaskFormat.deviceZone)),
            context.getString(R.string.tasks_zone_utc),
        )
        zoneControl.onSelected = { onZonePicked() }
        column.addView(
            zoneControl,
            controlParams().apply {
                topMargin = resources.getDimensionPixelSize(EinkR.dimen.ink_space_2)
            },
        )

        column.addView(buildSectionLabel(R.string.tasks_field_remind), labelParams())
        remindControl.segments = REMIND_OFFSETS.map {
            if (it == null) context.getString(R.string.tasks_remind_none) else TaskFormat.offsetLabel(it)
        }
        column.addView(remindControl, controlParams())

        column.addView(buildSectionLabel(R.string.tasks_field_repeat), labelParams())
        repeatControl.segments = listOf(
            context.getString(R.string.tasks_repeat_none),
            context.getString(R.string.tasks_repeat_daily),
            context.getString(R.string.tasks_repeat_weekdays),
            context.getString(R.string.tasks_repeat_weekly),
            context.getString(R.string.tasks_repeat_monthly),
        )
        repeatControl.onSelected = { syncRepeatVisibility() }
        column.addView(repeatControl, controlParams())

        column.addView(weekdayLabel, labelParams())
        column.addView(
            weekdayPicker,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        column.addView(buildSectionLabel(R.string.tasks_field_priority), labelParams())
        priorityControl.segments = Priority.entries.map { "${it.glyph} ${it.name}" }
        column.addView(priorityControl, controlParams())

        column.addView(buildSectionLabel(R.string.tasks_field_telegram), labelParams())
        telegramControl.segments = listOf(
            context.getString(R.string.tasks_telegram_on),
            context.getString(R.string.tasks_telegram_off),
        )
        column.addView(telegramControl, controlParams())
        column.addView(
            TextView(context).apply {
                setTextAppearance(EinkR.style.TextAppearance_InkDeck_Caption)
                setText(R.string.tasks_telegram_detail)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(EinkR.dimen.ink_space_2)
                bottomMargin = resources.getDimensionPixelSize(EinkR.dimen.ink_space_8)
            },
        )
    }

    // ------------------------------------------------------------------ binding

    fun bind(task: Task?) {
        val model = task ?: blank()
        editing = model

        headerTitle.setText(
            if (task == null) R.string.tasks_editor_new else R.string.tasks_editor_edit
        )
        deleteButton.visibility = if (task == null) View.INVISIBLE else View.VISIBLE

        titleInput.setText(model.title)
        notesInput.setText(model.notes)

        zone = model.zone()
        zoneControl.selectedIndex = if (zone.id == TaskFormat.UTC.id) 1 else 0
        dueDate = model.dueAt?.let { TaskFormat.dateOf(it, zone) }
        dueMinutes = model.dueAt?.let { TaskFormat.minutesOfDay(it, zone) } ?: DEFAULT_MINUTES
        syncDueFields()

        val offset = model.reminderOffsets.firstOrNull()
        remindControl.selectedIndex = REMIND_OFFSETS.indexOf(offset).takeIf { it >= 0 } ?: 0

        repeatControl.selectedIndex = REPEAT_KINDS.indexOf(model.repeat.kind).coerceAtLeast(0)
        weekdayPicker.selected = model.repeat.weekdays
        syncRepeatVisibility()

        priorityControl.selectedIndex = model.priority.ordinal
        telegramControl.selectedIndex = if (model.telegramNotify) 0 else 1

        // A whole new form replaces the viewport — [F] per §13.
        refresher?.flush("task-editor")
        titleInput.requestFocus()
    }

    private fun blank(): Task {
        val now = System.currentTimeMillis()
        return Task(title = "", createdAt = now, updatedAt = now)
    }

    private fun collect(): Task? {
        val base = editing ?: return null
        val title = titleInput.text.toString().trim()
        if (title.isEmpty()) {
            titleInput.error = context.getString(R.string.tasks_title_required)
            titleInput.requestFocus()
            return null
        }

        val date = dueDate
        val due = date?.let { TaskFormat.combine(it, dueMinutes, zone) }
        val offset = REMIND_OFFSETS.getOrNull(remindControl.selectedIndex)

        val kind = REPEAT_KINDS.getOrElse(repeatControl.selectedIndex) { RepeatRule.Kind.NONE }
        val repeat = when (kind) {
            RepeatRule.Kind.WEEKLY -> RepeatRule(kind, weekdays = weekdayPicker.selected)
            RepeatRule.Kind.MONTHLY -> RepeatRule(kind, monthDay = date?.dayOfMonth ?: 1)
            else -> RepeatRule(kind)
        }

        return base.copy(
            title = title,
            notes = notesInput.text.toString().trim(),
            dueAt = due,
            // Empty for the device zone rather than its resolved id: a task written "in local
            // time" should follow the device if it ever moves, not pin itself to the zone the
            // phone happened to be in when it was typed.
            zoneId = if (zone.id == TaskFormat.deviceZone.id) "" else zone.id,
            // A reminder with no due date has nothing to be relative to, so it is dropped
            // rather than stored as an offset from nothing.
            reminderOffsets = if (due != null && offset != null) listOf(offset) else emptyList(),
            repeat = repeat,
            priority = Priority.of(priorityControl.selectedIndex),
            telegramNotify = telegramControl.selectedIndex == 0,
        )
    }

    private fun save() {
        val task = collect() ?: return
        listener?.onSave(task)
    }

    private fun confirmDelete() {
        val task = editing ?: return
        if (task.id == 0L) {
            listener?.onClose()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(R.string.tasks_delete)
            .setMessage(context.getString(R.string.tasks_delete_confirm, task.title))
            .setPositiveButton(R.string.tasks_delete) { _, _ -> listener?.onDelete(task) }
            .setNegativeButton(EinkR.string.ink_cancel, null)
            .show()
            .also { it.window?.setWindowAnimations(0) }
    }

    // ------------------------------------------------------------------ pickers

    private fun pickDate() {
        // "Today" is the task's zone's today: in UTC at 01:00 +07 that is still yesterday, and
        // offering the local date would put the task a day out from what the user is thinking in.
        val today = LocalDate.now(zone)
        val dates = buildList {
            add(null) // "No date"
            for (i in 0 until DATE_HORIZON_DAYS) add(today.plusDays(i.toLong()))
        }
        val labels = dates.map {
            it?.let { d -> TaskFormat.dayLabel(context, d, zone) }
                ?: context.getString(R.string.tasks_no_date)
        }
        val current = dueDate?.let { dates.indexOf(it) }?.takeIf { it >= 0 } ?: 0

        ListPickerDialog(
            context,
            context.getString(R.string.tasks_field_due),
            labels,
            current,
            refresher,
        ) { index ->
            val wasUnset = dueDate == null
            dueDate = dates[index]
            // Giving a task a date and getting no reminder is the surprising outcome. Default
            // to "on time" on the first date, and never overwrite a choice already made.
            if (wasUnset && dueDate != null && remindControl.selectedIndex == 0) {
                remindControl.selectedIndex = REMIND_OFFSETS.indexOf(0)
            }
            syncDueFields()
        }.show()
    }

    private fun pickTime() {
        if (dueDate == null) {
            // A time with no date cannot be scheduled. Send the user to the date first rather
            // than storing a half-set due that silently never fires.
            pickDate()
            return
        }
        val slots = (0 until 24 * 60 step TIME_STEP_MINUTES).toList()
        val labels = slots.map { "%02d:%02d".format(it / 60, it % 60) }
        val current = slots.indexOfFirst { it >= dueMinutes }.coerceAtLeast(0)

        ListPickerDialog(
            context,
            context.getString(R.string.tasks_field_time),
            labels,
            current,
            refresher,
        ) { index ->
            dueMinutes = slots[index]
            syncDueFields()
        }.show()
    }

    /**
     * Switching zone keeps the **instant** and re-reads the clock face, rather than keeping the
     * clock face and moving the instant.
     *
     * Both are defensible; this one is the safer default. A user who set "21:00 local" and then
     * taps UTC is asking "what is that in UTC?" — answering 14:00 is informative. Silently
     * re-pointing the alarm to 21:00 UTC, seven hours later than what was already on screen,
     * would be a change they did not ask for and could not see.
     */
    private fun onZonePicked() {
        val next = if (zoneControl.selectedIndex == 1) TaskFormat.UTC else TaskFormat.deviceZone
        if (next.id == zone.id) return

        val date = dueDate
        if (date != null) {
            val instant = TaskFormat.combine(date, dueMinutes, zone)
            dueDate = TaskFormat.dateOf(instant, next)
            dueMinutes = TaskFormat.minutesOfDay(instant, next)
        }
        zone = next
        syncDueFields()
    }

    private fun syncDueFields() {
        val date = dueDate
        dateField.placeholder = date == null
        dateField.value = date?.let { TaskFormat.dayLabel(context, it, zone) }
            ?: context.getString(R.string.tasks_no_date)

        timeField.isEnabled = date != null
        timeField.placeholder = date == null
        timeField.value = if (date == null) {
            context.getString(R.string.tasks_no_time)
        } else {
            "%02d:%02d".format(dueMinutes / 60, dueMinutes % 60)
        }
    }

    private fun syncRepeatVisibility() {
        val weekly = REPEAT_KINDS.getOrNull(repeatControl.selectedIndex) == RepeatRule.Kind.WEEKLY
        val visibility = if (weekly) View.VISIBLE else View.GONE
        if (weekdayPicker.visibility == visibility) return
        weekdayPicker.visibility = visibility
        weekdayLabel.visibility = visibility
        // The form below shifts by 88 dp — a partial update would leave the old rows ghosted
        // through the new ones (§13).
        refresher?.flush("task-editor-repeat")
    }

    // ------------------------------------------------------------------ plumbing

    private fun buildInput(hintRes: Int, singleLine: Boolean): EditText = EditText(context).apply {
        setTextAppearance(EinkR.style.TextAppearance_InkDeck_BodyLarge)
        setHint(hintRes)
        setHintTextColor(EinkTheme.ink500(context))
        setTextColor(EinkTheme.ink900(context))
        background = null
        // 1.5 dp border drawn by the wrapping padding + a background rect would need a
        // drawable; a plain outline via setBackgroundResource keeps it one resource.
        setBackgroundResource(R.drawable.bg_input)
        val pad = EinkTheme.dp(context, 12f).toInt()
        setPadding(pad, pad, pad, pad)
        isSingleLine = singleLine
        gravity = if (singleLine) Gravity.CENTER_VERTICAL else Gravity.TOP or Gravity.START
        inputType = if (singleLine) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        // The blinking caret is a 500 ms animation that costs a panel refresh every half second
        // for as long as the field has focus.
        isCursorVisible = false
    }

    private fun buildSectionLabel(res: Int): TextView = TextView(context).apply {
        setTextAppearance(EinkR.style.TextAppearance_InkDeck_Caption)
        setText(res)
        letterSpacing = 0.08f
    }

    private fun labelParams(first: Boolean = false) =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = resources.getDimensionPixelSize(
                if (first) EinkR.dimen.ink_space_1 else EinkR.dimen.ink_section_gap
            )
            bottomMargin = resources.getDimensionPixelSize(EinkR.dimen.ink_space_2)
        }

    private fun inputParams(minHeight: Int = 0) =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            if (minHeight > 0) height = minHeight
        }

    private fun controlParams() =
        LayoutParams(LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(EinkR.dimen.ink_touch_min))

    private fun divider() = View(context).apply { setBackgroundColor(EinkTheme.ink200(context)) }
    private fun barHeight() = resources.getDimensionPixelSize(EinkR.dimen.ink_bar_height)
    private fun dividerHeight() = resources.getDimensionPixelSize(EinkR.dimen.ink_divider)

    /** Used by the host to decide whether Back should close the editor or leave the app. */
    fun hasFocusedInput(): Boolean = titleInput.hasFocus() || notesInput.hasFocus()

    fun clearInputFocus() {
        titleInput.clearFocus()
        notesInput.clearFocus()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE) clearInputFocus()
    }

    companion object {
        /**
         * design.md §8.2 draws `none · 10m · 1h · 1d · cust`. `cust` is replaced by **on time**
         * (offset 0), which is the value people actually reach for: a due time with no reminder
         * at that time is the surprising case, not the useful one. A free-form custom offset
         * would need its own picker for a case the four fixed steps already cover.
         */
        private val REMIND_OFFSETS: List<Int?> = listOf(null, 0, 10, 60, 60 * 24)

        private val REPEAT_KINDS = listOf(
            RepeatRule.Kind.NONE,
            RepeatRule.Kind.DAILY,
            RepeatRule.Kind.WEEKDAYS,
            RepeatRule.Kind.WEEKLY,
            RepeatRule.Kind.MONTHLY,
        )

        /** 09:00 — a default that is almost never right at 03:00 and almost always fine. */
        private const val DEFAULT_MINUTES = 9 * 60

        /** 15-minute granularity keeps the picker at 96 rows instead of 1 440. */
        private const val TIME_STEP_MINUTES = 15

        private const val DATE_HORIZON_DAYS = 180
    }
}
