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
    version = TaskSyncDatabase.CURRENT_VERSION,
    exportSchema = false,
)
abstract class TaskSyncDatabase : RoomDatabase() {
    abstract fun recordsDao(): SyncedRecordsDao
    abstract fun listsDao(): SyncedListsDao
    abstract fun pendingOpsDao(): PendingOpsDao

    companion object {
        // Deliberately starts above 5, not 1: google-tasks-kotlin's pre-rewrite
        // GoogleTasksDatabase used this same on-disk file name at version 5. Starting below
        // that made Room treat every existing install as an unmigrated downgrade (crash),
        // which was "fixed" by a destructive fallback that silently wiped every local task,
        // list, and pending op, orphaning TaskCompass's own comparison and workspace-membership
        // tables (which reference those local ids) with no way to reconcile them afterward. See
        // google-tasks-kotlin's Migration(5, 6) for the real fix, and
        // TaskSyncDatabaseVersionTest, which fails the build if this ever regresses below the
        // version any consuming source has already shipped on disk. A source starting from a
        // fresh install has no prior schema and is simply created at this version directly —
        // no migration runs for it.
        const val CURRENT_VERSION = 6
    }
}
