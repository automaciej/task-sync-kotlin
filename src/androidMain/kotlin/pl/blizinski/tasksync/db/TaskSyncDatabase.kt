package pl.blizinski.tasksync.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Shared Room *schema*, not a shared database file — each consuming source library
 * (`google-tasks-kotlin`, a future `microsoft-todo-kotlin`) builds its own separate database
 * instance from this class (its own file name, typically one per connected account), so two
 * accounts/sources never share storage despite sharing this schema.
 */
@Database(
    entities = [SyncedRecordEntity::class, SyncedListEntity::class, PendingOpEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TaskSyncDatabase : RoomDatabase() {
    abstract fun recordsDao(): SyncedRecordsDao
    abstract fun listsDao(): SyncedListsDao
    abstract fun pendingOpsDao(): PendingOpsDao
}
