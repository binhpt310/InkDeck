package dev.inkdeck.tasks

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.inkdeck.data.tasks.Task
import dev.inkdeck.data.tasks.TaskStatus
import dev.inkdeck.tasks.list.TaskGrouping
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State for the Tasks tab.
 *
 * All four panes are computed from **one pair of queries** and emitted together. The screen is a
 * 2×2 board now rather than a tab strip (design.md §8.1), so a per-pane flow would mean four
 * subscriptions re-deriving from the same two tables on every write, and four independent
 * emissions repainting the panel four times for one edit.
 */
class TasksViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = TaskGraph.repository(app)

    /**
     * @param pending open tasks that have a due date — the number in the header. Undated notes
     *   are excluded: they are a backlog, not something hanging over you.
     */
    data class Board(
        val today: List<Task> = emptyList(),
        val week: List<Task> = emptyList(),
        val all: List<Task> = emptyList(),
        val done: List<Task> = emptyList(),
        val overdue: Int = 0,
        val pending: Int = 0,
    )

    val board: StateFlow<Board> =
        combine(repository.observeOpen(), repository.observeDone()) { open, done ->
            val now = System.currentTimeMillis()
            Board(
                today = TaskGrouping.rows(TaskGrouping.Filter.TODAY, open, now),
                week = TaskGrouping.rows(TaskGrouping.Filter.WEEK, open, now),
                all = TaskGrouping.rows(TaskGrouping.Filter.ALL, open, now),
                done = done,
                overdue = open.count { task -> task.dueAt?.let { it < now } == true },
                pending = open.count { it.dueAt != null },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Board())

    /**
     * Phase 9 item 7: true until [board] has emitted at least one non-default value. The board
     * is a `StateFlow` that starts at the default `Board()`; on first subscription, Room's flow
     * takes a few hundred ms to deliver the real rows on two ~1 GHz cores. The fragment uses
     * this flag to show a `StepBar` in each pane for that window so the four panes do not flash
     * empty before they flash full.
     *
     * `WhileSubscribed(5_000)` keeps the upstream alive across a 5 s unsubscribe; the flag
     * stays `true` for the duration of that grace, which is what we want.
     */
    val isFirstEmit: StateFlow<Boolean> = board
        .map { it != Board() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Non-null for one collection after a repeating task rolls forward. */
    private val rolledFlow = MutableStateFlow<Pair<String, Long>?>(null)
    val rolled: StateFlow<Pair<String, Long>?> = rolledFlow

    fun save(task: Task) {
        viewModelScope.launch { repository.save(task) }
    }

    fun delete(task: Task) {
        viewModelScope.launch { repository.delete(task.id) }
    }

    fun toggle(task: Task) {
        viewModelScope.launch {
            if (task.status == TaskStatus.DONE) {
                repository.reopen(task.id)
            } else {
                val result = repository.complete(task.id)
                result.rolledTo?.let { rolledFlow.value = task.title to it }
            }
        }
    }

    fun consumeRolled() {
        rolledFlow.value = null
    }
}
