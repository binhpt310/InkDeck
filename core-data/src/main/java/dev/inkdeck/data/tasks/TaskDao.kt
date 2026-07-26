package dev.inkdeck.data.tasks

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Ordering is fixed in SQL rather than in the UI so every screen agrees: overdue and dated work
 * first in time order, undated work last, then priority, then title. `dueAt IS NULL` sorts to
 * the end explicitly because SQLite would otherwise put NULLs first on an ASC sort.
 */
@Dao
interface TaskDao {

    @Query(
        """
        SELECT * FROM tasks WHERE status = 0
        ORDER BY (dueAt IS NULL), dueAt ASC, priority ASC, title COLLATE NOCASE ASC
        """
    )
    fun observeOpen(): Flow<List<Task>>

    @Query(
        """
        SELECT * FROM tasks WHERE status = 1
        ORDER BY completedAt DESC, updatedAt DESC
        LIMIT :limit
        """
    )
    fun observeDone(limit: Int = 200): Flow<List<Task>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun byId(id: Long): Task?

    /** Everything the scheduler needs to re-arm: open, dated, with at least one reminder. */
    @Query("SELECT * FROM tasks WHERE status = 0 AND dueAt IS NOT NULL AND reminderOffsets != ''")
    suspend fun schedulable(): List<Task>

    @Query("SELECT COUNT(*) FROM tasks WHERE status = 0 AND dueAt IS NOT NULL AND dueAt < :now")
    fun observeOverdueCount(now: Long): Flow<Int>

    @Insert
    suspend fun insert(task: Task): Long

    @Update
    suspend fun update(task: Task)

    @Delete
    suspend fun delete(task: Task)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM tasks WHERE status = 1")
    suspend fun clearCompleted()
}
