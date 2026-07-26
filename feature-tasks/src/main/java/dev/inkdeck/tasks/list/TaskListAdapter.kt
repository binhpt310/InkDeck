package dev.inkdeck.tasks.list

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.inkdeck.data.tasks.Task
import dev.inkdeck.tasks.list.TaskRowView.Companion.heightPx

/** A flat list of headers and rows — grouping is computed in [TaskGrouping], not here. */
sealed interface TaskListItem {
    data class Section(val label: String) : TaskListItem
    data class Row(val task: Task) : TaskListItem
}

/**
 * No `DiffUtil` and no `notifyItemChanged`.
 *
 * DiffUtil exists to animate insertions and moves, and every animation here is banned (§14
 * item 1) — `itemAnimator` is null on [dev.inkdeck.eink.widget.EinkRecyclerView] anyway, so a
 * diff would compute a set of moves that get applied instantly regardless. What matters instead
 * is which *panel* region is dirtied, and the caller handles that: [submit] repaints the list
 * and the fragment decides between `[P]` and `[F]`.
 */
class TaskListAdapter(
    private val onToggle: (Task) -> Unit,
    private val onOpen: (Task) -> Unit,
    private val onLongPress: (Task) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<TaskListItem> = emptyList()

    fun submit(newItems: List<TaskListItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): TaskListItem? = items.getOrNull(position)

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is TaskListItem.Section -> TYPE_SECTION
        is TaskListItem.Row -> TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        return if (viewType == TYPE_SECTION) {
            val view = TaskSectionView(context)
            view.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                TaskSectionView.heightPx(context),
            )
            SectionHolder(view)
        } else {
            val view = TaskRowView(context)
            // MATCH_PARENT explicitly: LinearLayoutManager's default LayoutParams are
            // WRAP_CONTENT, which leaves the row only as wide as its text and makes taps to the
            // right of the title miss the view entirely.
            view.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                heightPx(context),
            )
            RowHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is TaskListItem.Section -> (holder as SectionHolder).view.label = item.label
            is TaskListItem.Row -> (holder as RowHolder).view.apply {
                bind(item.task)
                onToggle = { this@TaskListAdapter.onToggle(item.task) }
                onOpen = { this@TaskListAdapter.onOpen(item.task) }
                onLongPress = { this@TaskListAdapter.onLongPress(item.task) }
            }
        }
    }

    private class SectionHolder(val view: TaskSectionView) : RecyclerView.ViewHolder(view)
    private class RowHolder(val view: TaskRowView) : RecyclerView.ViewHolder(view)

    private companion object {
        const val TYPE_SECTION = 0
        const val TYPE_ROW = 1
    }
}
