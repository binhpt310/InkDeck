package dev.inkdeck.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.inkdeck.data.tasks.Converters
import dev.inkdeck.data.tasks.Task
import dev.inkdeck.data.tasks.TaskDao

/**
 * The single app database. Later phases add tables here rather than opening their own files —
 * one connection, one WAL, one place migrations live.
 *
 * Note what is *not* in here: SSH keys and API tokens. Those stay in the AES-256-GCM vault
 * (Plan.md §4.3), which is a separate encrypted file. A Room database is plaintext SQLite, and
 * an `adb pull` of the app data directory on a rooted device would read it straight out.
 */
@Database(
    entities = [Task::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class InkDeckDatabase : RoomDatabase() {

    abstract fun taskDao(): TaskDao

    companion object {
        @Volatile
        private var instance: InkDeckDatabase? = null

        fun get(context: Context): InkDeckDatabase = instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

        /**
         * v1 → v2: per-task time zone. Empty means the device zone, so every existing row keeps
         * behaving exactly as it did — the column is additive and the default is the old
         * behaviour.
         */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN zoneId TEXT NOT NULL DEFAULT ''")
            }
        }

        private fun build(context: Context): InkDeckDatabase =
            Room.databaseBuilder(context, InkDeckDatabase::class.java, "inkdeck.db")
                .addMigrations(MIGRATION_1_2)
                // No fallbackToDestructiveMigration. Tasks are user data with reminders attached;
                // silently dropping the table on a schema mismatch is worse than a crash that
                // says so.
                .build()
    }
}
