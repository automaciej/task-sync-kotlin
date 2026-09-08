package pl.blizinski.tasksync.model

import pl.blizinski.tasksync.SyncErrorKind

/**
 * Public sync status surfaced by a [pl.blizinski.tasksync.store.TaskStore]. This replaces the
 * four identical `models.SyncStatus` declarations across the provider libraries.
 */
data class SyncStatus(
    val isSyncing: Boolean = false,
    val lastSyncedAt: Long? = null,
    val pendingOpCount: Int = 0,
    /** Ops that have failed at least once — still retried every cycle, tracked separately so a
     *  permanently-failing op is distinguishable from one still in normal flight. */
    val failedOpCount: Int = 0,
    val recentErrors: List<PublicSyncError> = emptyList(),
    /**
     * Non-null when the last sync failed because the user must grant OAuth consent. On Android
     * this is an `android.content.Intent`; kept as [Any] so this model carries no platform type
     * (the consumer casts before launching).
     */
    val consentIntent: Any? = null,
    /**
     * Non-null when the local database itself could not be opened — a condition retrying or
     * background sync cannot recover from. When set, reads emit empty lists and further
     * reads/writes keep failing the same way until the app is updated or reinstalled.
     */
    val fatalStorageError: FatalStorageError? = null,
)

/**
 * A sync error as surfaced to a consumer. Field-for-field the engine-internal
 * [pl.blizinski.tasksync.SyncError] with `entityLocalId` renamed to [taskLocalId]; the rename
 * map that used to live in each provider's `internal/SyncErrorMappers.kt` is now [toPublic].
 */
data class PublicSyncError(
    val occurredAt: Long,
    val kind: SyncErrorKind,
    val taskLocalId: String? = null,
    val httpStatus: Int? = null,
    val message: String,
)

fun pl.blizinski.tasksync.SyncError.toPublic(): PublicSyncError = PublicSyncError(
    occurredAt = occurredAt,
    kind = kind,
    taskLocalId = entityLocalId,
    httpStatus = httpStatus,
    message = message,
)

data class FatalStorageError(
    val occurredAt: Long,
    /** Short, human-readable summary (typically the exception's message or class name). */
    val summary: String,
    /** Full diagnostic text (stack trace) — meant to be shown on-screen for the user to
     *  screenshot or copy, since this failure mode has no automatic recovery. */
    val details: String,
)
