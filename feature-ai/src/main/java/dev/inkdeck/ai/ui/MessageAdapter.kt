package dev.inkdeck.ai.ui

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import dev.inkdeck.ai.ChatMessage

/**
 * The transcript.
 *
 * No `DiffUtil`, for the reason design.md §8.3 already records: DiffUtil exists to animate
 * insertions and moves, the item animator is null on every `EinkRecyclerView`, and what actually
 * matters here is *which panel region is dirtied*. That is decided by the caller, which knows
 * whether a message was added (`[F]`) or the last one grew (`[P]`).
 */
class MessageAdapter : RecyclerView.Adapter<MessageAdapter.Holder>() {

    private val items = ArrayList<ChatMessage>()

    var isStreaming: Boolean = false
    var workingStep: Int = 0

    /** Full replacement — a new turn, or a conversation switch. */
    fun submit(messages: List<ChatMessage>) {
        items.clear()
        items.addAll(messages)
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    /**
     * A streamed chunk landed. Rebinds one row, which is the `[P]` in design.md §13 — and the
     * reason this is a separate entry point from [submit].
     */
    fun updateLast(message: ChatMessage) {
        if (items.isEmpty()) return
        items[items.lastIndex] = message
        notifyItemChanged(items.lastIndex)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(MessageRowView(parent.context))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val last = position == items.lastIndex
        holder.row.bind(
            message = items[position],
            streaming = isStreaming && last,
            workingStep = workingStep,
        )
    }

    class Holder(val row: MessageRowView) : RecyclerView.ViewHolder(row) {
        init {
            row.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }
}
