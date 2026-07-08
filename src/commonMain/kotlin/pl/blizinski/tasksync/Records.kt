package pl.blizinski.tasksync

/**
 * A synced record (e.g. a task) as stored locally. [content] is fully opaque to the sync
 * engine — it never inspects or compares individual fields of [T], only structural equality
 * (via [T]'s own `equals()`). [isCompleted] is the one content-adjacent field promoted into
 * the generic envelope rather than left inside [T]: every target source has some boolean-ish
 * completion concept, and the engine's zombie-detection needs it to disambiguate "absent from
 * a full pull because completed" from "absent because deleted" (see [SyncEngine]).
 */
data class SyncedRecord<T>(
    val localId: String,
    val remoteId: String?,
    val listLocalId: String,
    val content: T,
    val isCompleted: Boolean = false,
    val isDeleted: Boolean = false,
    val lastSyncedAt: Long? = null,
    /** Server's own last-updated timestamp (epoch ms), used as the remote side's timestamp. */
    val remoteUpdatedAt: Long? = null,
    /** Merge-base snapshot as of the last successful pull, for three-way conflict detection.
     * Null for records created locally and not yet synced. */
    val lastSyncedContent: T? = null,
)

/** A synced task-list record. [position] is generic list ordering (0-based, as returned by the API). */
data class SyncedListRecord<TList>(
    val localId: String,
    val remoteId: String?,
    val content: TList,
    val isDeleted: Boolean = false,
    val lastSyncedAt: Long? = null,
    val position: Int = 0,
)

/** A record as returned by a [NetworkSource] pull. */
data class RemoteRecord<T>(
    val remoteId: String,
    val content: T,
    val isCompleted: Boolean = false,
    val isDeleted: Boolean = false,
    val remoteUpdatedAt: Long? = null,
)

/** A task list as returned by a [NetworkSource] pull. */
data class RemoteListRecord<TList>(
    val remoteId: String,
    val content: TList,
)
