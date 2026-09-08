package pl.blizinski.tasksync.model

/**
 * Dual identifier for a task exposed on the public [Task] model: a device-local id (always set,
 * stable for the lifetime of this installation) and the server-assigned id (populated after the
 * first successful sync, stable across devices and installations once assigned).
 *
 * Named [TaskRef] rather than `TaskId` to keep it distinct from the sync engine's own
 * identity handling on [pl.blizinski.tasksync.SyncedRecord] (`localId`/`remoteId` there are
 * plain strings on the storage envelope). This is the shape every provider library previously
 * re-declared as its own `models.TaskId`.
 */
data class TaskRef(
    val localId: String,
    val remoteId: String? = null,
) {
    /**
     * Returns true if both refer to the same underlying task: remoteId comparison when both
     * sides have one, else localId.
     */
    fun matches(other: TaskRef): Boolean = when {
        remoteId != null && other.remoteId != null -> remoteId == other.remoteId
        else -> localId == other.localId
    }

    /** The most stable available identifier: remoteId once synced, localId before that. */
    val effectiveId: String get() = remoteId ?: localId
}
