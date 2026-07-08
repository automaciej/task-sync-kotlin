package pl.blizinski.tasksync.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import pl.blizinski.tasksync.OpStatus
import pl.blizinski.tasksync.OpType

/**
 * One concrete, non-generic Room entity shared by every source — [contentJson] holds a
 * source's own content type serialized via its `KSerializer`, never inspected by Room or by
 * [pl.blizinski.tasksync.SyncEngine]/[pl.blizinski.tasksync.PendingOpsProcessor]. Each
 * consuming source library (`google-tasks-kotlin`, a future `microsoft-todo-kotlin`) builds
 * its own separate Room database instance from [pl.blizinski.tasksync.db.TaskSyncDatabase] —
 * there's no shared database file, only a shared schema — so two accounts/sources can never
 * collide on `localId`/`remoteId` even though this schema has no per-account column.
 */
@Entity(tableName = "synced_records")
data class SyncedRecordEntity(
    @PrimaryKey val localId: String,
    val remoteId: String?,
    val listLocalId: String,
    val contentJson: String,
    val isCompleted: Boolean = false,
    val isDeleted: Boolean = false,
    val lastSyncedAt: Long? = null,
    val remoteUpdatedAt: Long? = null,
    /** Merge-base snapshot as of the last successful pull, for three-way conflict detection. */
    val lastSyncedContentJson: String? = null,
)

@Entity(tableName = "synced_lists")
data class SyncedListEntity(
    @PrimaryKey val localId: String,
    val remoteId: String?,
    val contentJson: String,
    val isDeleted: Boolean = false,
    val lastSyncedAt: Long? = null,
    val position: Int = 0,
)

@Entity(tableName = "pending_ops")
data class PendingOpEntity(
    @PrimaryKey val id: String,
    val type: OpType,
    val entityLocalId: String,
    val listLocalId: String,
    val contentJson: String? = null,
    val createdAt: Long,
    val attemptCount: Int = 0,
    val status: OpStatus = OpStatus.PENDING,
)
