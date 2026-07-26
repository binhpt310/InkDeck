package dev.inkdeck.data.tasks

import android.content.Context
import dev.inkdeck.data.InkDeckDatabase
import kotlinx.coroutines.flow.Flow

/**
 * The one place task writes happen.
 *
 * Every mutation goes through here rather than through [TaskDao] directly, because a write that
 * changes `dueAt`, `reminderOffsets` or `status` must re-arm the alarm in the same breath. A
 * task saved with a new time and a stale alarm is the failure mode that makes a reminder app
 * useless, and it is invisible until the wrong minute arrives.
 *
 * [Rescheduler] is an interface so `:core-data` keeps no dependency on `AlarmManager` — the
 * implementation lives with the receivers in `:feature-tasks`.
 */
class TaskRepository(
    private val dao: TaskDao,
    private val rescheduler: Rescheduler,
) {

    fun interface Rescheduler {
        /** Re-arm (or cancel, when [task] is null) the alarms for [taskId]. */
        fun reschedule(taskId: Long, task: Task?)
    }

    fun observeOpen(): Flow<List<Task>> = dao.observeOpen()
    fun observeDone(): Flow<List<Task>> = dao.observeDone()

    suspend fun byId(id: Long): Task? = dao.byId(id)

    suspend fun save(task: Task): Long {
        val now = System.currentTimeMillis()
        return if (task.id == 0L) {
            val id = dao.insert(task.copy(createdAt = now, updatedAt = now))
            rescheduler.reschedule(id, dao.byId(id))
            id
        } else {
            val updated = task.copy(updatedAt = now)
            dao.update(updated)
            rescheduler.reschedule(updated.id, updated)
            updated.id
        }
    }

    suspend fun delete(id: Long) {
        rescheduler.reschedule(id, null)
        dao.deleteById(id)
    }

    /**
     * Tick the box — design.md §8.1, Plan.md §5.1.
     *
     * A one-off closes. A repeating task rolls its due date to the next occurrence and stays
     * open, which is what "compute the next occurrence on completion, do not pre-generate
     * instances" means in practice. [Result.rolledTo] is non-null in that case so the caller can
     * say where it went — otherwise ticking a repeating task looks like nothing happened.
     */
    suspend fun complete(id: Long): Result {
        val task = dao.byId(id) ?: return Result(null, null)
        val now = System.currentTimeMillis()

        // Rolled forward in the task's own zone: a daily task written as 14:00 UTC must stay
        // 14:00 UTC, not drift to whatever 14:00 UTC happens to be locally today.
        val next = task.dueAt?.let {
            task.repeat.nextAfter(fromMillis = it, afterMillis = now, zone = task.zone())
        }
        return if (next != null) {
            val rolled = task.copy(dueAt = next, completedAt = now, updatedAt = now)
            dao.update(rolled)
            rescheduler.reschedule(rolled.id, rolled)
            Result(rolled, next)
        } else {
            val done = task.copy(status = TaskStatus.DONE, completedAt = now, updatedAt = now)
            dao.update(done)
            rescheduler.reschedule(done.id, null)
            Result(done, null)
        }
    }

    /** Undo a completion. Reminders in the past are simply not re-armed by the scheduler. */
    suspend fun reopen(id: Long) {
        val task = dao.byId(id) ?: return
        val open = task.copy(
            status = TaskStatus.OPEN,
            completedAt = null,
            updatedAt = System.currentTimeMillis(),
        )
        dao.update(open)
        rescheduler.reschedule(open.id, open)
    }

    suspend fun clearCompleted() = dao.clearCompleted()

    /** Used by boot re-arm and by app start — see [dev.inkdeck.data.tasks.TaskRepository]. */
    suspend fun schedulable(): List<Task> = dao.schedulable()

    data class Result(val task: Task?, val rolledTo: Long?)

    companion object {
        /**
         * Built once and cached: the repository holds no state of its own, but the database
         * behind it must be a singleton and callers here include a BroadcastReceiver with no
         * Application to reach into.
         */
        @Volatile
        private var instance: TaskRepository? = null

        fun get(context: Context, rescheduler: Rescheduler): TaskRepository =
            instance ?: synchronized(this) {
                instance ?: TaskRepository(
                    InkDeckDatabase.get(context).taskDao(),
                    rescheduler,
                ).also { instance = it }
            }
    }
}
