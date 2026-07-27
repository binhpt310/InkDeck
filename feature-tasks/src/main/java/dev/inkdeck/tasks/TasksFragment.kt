package dev.inkdeck.tasks

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import dev.inkdeck.data.tasks.Task
import dev.inkdeck.eink.EinkAnim
import dev.inkdeck.eink.EinkTheme
import dev.inkdeck.eink.R as EinkR
import dev.inkdeck.eink.refresh.EinkRefresher
import dev.inkdeck.eink.refresh.RefresherHost
import dev.inkdeck.eink.widget.EinkIconButton
import dev.inkdeck.eink.widget.EinkRecyclerView
import dev.inkdeck.eink.widget.PagedScrollRail
import dev.inkdeck.tasks.editor.TaskEditorView
import dev.inkdeck.tasks.list.TaskGrouping
import dev.inkdeck.tasks.list.TaskListAdapter
import dev.inkdeck.tasks.list.TaskListItem
import dev.inkdeck.tasks.list.TaskPaneView
import kotlinx.coroutines.launch

/**
 * The Tasks tab — design.md §8.1 as revised into a 2×2 board.
 *
 * Three overlays live in this one fragment rather than in the back stack: the expanded pane and
 * the editor are siblings drawn over the board, for the same reason the file viewer is
 * (design.md §7.6) — a fragment transaction is a window animation the panel renders as a wipe,
 * and the tab bar underneath must not move.
 */
class TasksFragment : Fragment(R.layout.fragment_tasks) {

    private val viewModel: TasksViewModel by viewModels()

    private lateinit var pendingLabel: TextView
    private lateinit var editor: TaskEditorView

    private lateinit var expanded: FrameLayout
    private lateinit var expandedTitle: TextView
    private lateinit var expandedList: EinkRecyclerView
    private lateinit var expandedRail: PagedScrollRail
    private lateinit var expandedAdapter: TaskListAdapter

    private lateinit var panes: List<Pane>

    private var expandedPane: TaskGrouping.Filter? = null
    private var board = TasksViewModel.Board()

    private class Pane(
        val filter: TaskGrouping.Filter,
        val view: TaskPaneView,
        val labelRes: Int,
        val emptyRes: Int,
    )

    private val refresher: EinkRefresher?
        get() = (activity as? RefresherHost)?.refresher

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        pendingLabel = view.findViewById(R.id.pendingLabel)
        editor = view.findViewById(R.id.editor)

        view.findViewById<EinkIconButton>(R.id.actionAdd).apply {
            setIconResource(R.drawable.ic_add)
            setOnClickListener { openEditor(null) }
        }

        panes = listOf(
            Pane(
                TaskGrouping.Filter.TODAY, view.findViewById(R.id.paneToday),
                R.string.tasks_filter_today, R.string.tasks_pane_empty_today,
            ),
            Pane(
                TaskGrouping.Filter.WEEK, view.findViewById(R.id.paneWeek),
                R.string.tasks_filter_week, R.string.tasks_pane_empty_week,
            ),
            Pane(
                TaskGrouping.Filter.ALL, view.findViewById(R.id.paneAll),
                R.string.tasks_filter_all, R.string.tasks_pane_empty_all,
            ),
            Pane(
                TaskGrouping.Filter.DONE, view.findViewById(R.id.paneDone),
                R.string.tasks_filter_done, R.string.tasks_pane_empty_done,
            ),
        )
        panes.forEach { pane ->
            pane.view.setCallbacks(toggle = ::toggle, open = ::openEditor)
            pane.view.onExpand = { expand(pane) }
        }

        setUpExpanded(view)
        setUpEditor()

        EinkAnim.strip(view)
        observe()
    }

    // ------------------------------------------------------------------ board

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    // Phase 9 item 7: show the StepBar in each pane until the board's first real
                    // emission. Cleared by [render] on every subsequent emit too — a re-emit
                    // (e.g. after a task toggle) is a real result, never a loading state.
                    viewModel.isFirstEmit.collect { first ->
                        panes.forEach { it.view.loading = first }
                    }
                }
                launch { viewModel.board.collect { render(it) } }
                launch {
                    viewModel.rolled.collect { rolled ->
                        if (rolled == null) return@collect
                        val (title, next) = rolled
                        toast(
                            getString(
                                R.string.tasks_rolled_forward,
                                title,
                                TaskFormat.dueLine(requireContext(), next),
                            )
                        )
                        viewModel.consumeRolled()
                    }
                }
            }
        }
    }

    private fun render(next: TasksViewModel.Board) {
        board = next

        // design.md §8.1: the header answers "how much is hanging over me" without counting
        // rows. Overdue is called out separately because it is the number that changes what you
        // do next; a bare total hides it.
        pendingLabel.text = when {
            next.overdue > 0 ->
                getString(R.string.tasks_pending_with_overdue, next.pending, next.overdue)
            else -> resources.getQuantityString(R.plurals.tasks_pending, next.pending, next.pending)
        }

        panes.forEach { pane ->
            pane.view.bind(
                label = getString(pane.labelRes),
                tasks = tasksFor(pane.filter),
                emptyText = getString(pane.emptyRes),
            )
        }
        if (expanded.visibility == View.VISIBLE) renderExpanded()
    }

    private fun tasksFor(filter: TaskGrouping.Filter): List<Task> = when (filter) {
        TaskGrouping.Filter.TODAY -> board.today
        TaskGrouping.Filter.WEEK -> board.week
        TaskGrouping.Filter.ALL -> board.all
        TaskGrouping.Filter.DONE -> board.done
    }

    /**
     * Ticking a box changes one row, but a completed task also leaves its pane and may enter
     * another, so the dirty region is not knowable up front. Note a partial and let the ghost
     * budget decide — `notePartial` returns true when it has already flushed, so there is
     * nothing to do with the result.
     */
    private fun toggle(task: Task) {
        viewModel.toggle(task)
        refresher?.notePartial(SURFACE_BOARD, "task-toggle")
    }

    // ------------------------------------------------------------------ expanded pane

    private fun setUpExpanded(view: View) {
        expanded = view.findViewById(R.id.expanded)
        expandedTitle = view.findViewById(R.id.expandedTitle)
        expandedList = view.findViewById(R.id.expandedList)
        expandedRail = view.findViewById(R.id.expandedRail)

        view.findViewById<EinkIconButton>(R.id.expandedBack).apply {
            setIconResource(R.drawable.ic_back)
            setOnClickListener { collapseExpanded() }
        }

        expandedAdapter = TaskListAdapter(
            onToggle = ::toggle,
            onOpen = ::openEditor,
            onLongPress = ::openEditor,
        )
        expandedList.layoutManager = LinearLayoutManager(requireContext())
        expandedList.adapter = expandedAdapter
        // Rows must end before the floating rail or their right edge sits under it.
        expandedList.setPadding(
            0, 0,
            resources.getDimensionPixelSize(EinkR.dimen.ink_rail_width) +
                EinkTheme.dp(requireContext(), 12f).toInt(),
            0,
        )
        expandedRail.refresher = refresher
        expandedRail.attach(expandedList)
    }

    private fun expand(pane: Pane) {
        expandedPane = pane.filter
        expandedTitle.text = getString(pane.labelRes)
        renderExpanded()
        expanded.visibility = View.VISIBLE
        refresher?.flush("tasks-expand=${pane.filter}")
    }

    private fun renderExpanded() {
        val filter = expandedPane ?: return
        // The expanded view is the one place with room for section headers, so it uses the full
        // grouped list rather than the board's flat rows.
        expandedAdapter.submit(
            TaskGrouping.build(requireContext(), filter, tasksFor(filter))
        )
    }

    private fun collapseExpanded() {
        if (expanded.visibility != View.VISIBLE) return
        expanded.visibility = View.GONE
        expandedPane = null
        refresher?.flush("tasks-collapse")
    }

    // ------------------------------------------------------------------ editor

    private fun setUpEditor() {
        editor.refresher = refresher
        editor.listener = object : TaskEditorView.Listener {
            override fun onSave(task: Task) {
                viewModel.save(task)
                closeEditor()
            }

            override fun onDelete(task: Task) {
                viewModel.delete(task)
                closeEditor()
            }

            override fun onClose() = closeEditor()
        }
    }

    private fun openEditor(task: Task?) {
        editor.bind(task)
        editor.visibility = View.VISIBLE
        refresher?.resetSurface(SURFACE_BOARD)
    }

    private fun closeEditor() {
        if (editor.visibility != View.VISIBLE) return
        editor.clearInputFocus()
        editor.visibility = View.GONE
        hideKeyboard()
        refresher?.flush("task-editor-close")
    }

    /**
     * Called by the host's Back handling; true means the press was consumed. Innermost overlay
     * first — Back should peel one layer, not jump straight out of the app.
     */
    fun closeEditorIfOpen(): Boolean {
        if (editor.visibility == View.VISIBLE) {
            closeEditor()
            return true
        }
        if (expanded.visibility == View.VISIBLE) {
            collapseExpanded()
            return true
        }
        return false
    }

    /** Entry point for the floating menu's `✚ Quick` item — design.md §11.3. */
    fun quickCapture() {
        openEditor(null)
    }

    private fun hideKeyboard() {
        val imm = requireContext()
            .getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(requireView().windowToken, 0)
    }

    private fun toast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val SURFACE_BOARD = "tasks-board"
    }
}
