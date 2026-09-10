package pl.blizinski.tasksync.store

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import pl.blizinski.tasksync.LocalStore
import pl.blizinski.tasksync.OpType
import pl.blizinski.tasksync.PendingOp
import pl.blizinski.tasksync.SyncEngine
import pl.blizinski.tasksync.SyncedListRecord
import pl.blizinski.tasksync.SyncedRecord
import pl.blizinski.tasksync.accumulateRecentErrors
import pl.blizinski.tasksync.model.FatalStorageError
import pl.blizinski.tasksync.model.StoreCapabilities
import pl.blizinski.tasksync.model.StoreConfig
import pl.blizinski.tasksync.model.SyncStatus
import pl.blizinski.tasksync.model.Task
import pl.blizinski.tasksync.model.TaskDraft
import pl.blizinski.tasksync.model.TaskList
import pl.blizinski.tasksync.model.toPublic

/**
 * The [TaskStore] wiring that was ~90% identical across `GoogleTasksStore`, `TodoistStore`,
 * `GitHubIssuesStore`, and `MicrosoftToDoStore`: optimistic-write → pending-op → poke-scheduler
 * bodies, the `_syncStatus` flow, `applySyncResult`, the fatal-storage guards, and the read-flow
 * mapping. Everything provider-specific arrives as constructor data — a [ContentAdapter], a
 * [StoreCapabilities], the already-built [LocalStore]/[SyncEngine], and a [StoreSyncScheduler].
 *
 * Built by [buildAndroidTaskStore] / [buildWasmTaskStore]; not constructed directly by callers.
 */
@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
class DefaultTaskStore<T, TList>(
    private val store: LocalStore<T, TList>,
    private val syncEngine: SyncEngine<T, TList>,
    private val recordSerializer: KSerializer<T>,
    private val adapter: ContentAdapter<T, TList>,
    override val capabilities: StoreCapabilities,
    private val config: StoreConfig,
    private val closeResources: () -> Unit,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val newId: () -> String = { Uuid.random().toString() },
    private val logError: (String, Throwable) -> Unit = { _, _ -> },
    schedulerFactory: (onSyncResult: (SyncEngine.SyncResult) -> Unit) -> StoreSyncScheduler,
) : TaskStore {

    private val json = Json { ignoreUnknownKeys = true }
    private val _syncStatus = MutableStateFlow(SyncStatus())
    private val scheduler: StoreSyncScheduler = schedulerFactory(::applySyncResult)

    init {
        scheduler.start()
    }

    // -------------------------------------------------------------------------
    // Status plumbing
    // -------------------------------------------------------------------------

    private fun applySyncResult(result: SyncEngine.SyncResult) {
        val ts = now()
        _syncStatus.update { current ->
            current.copy(
                isSyncing = false,
                lastSyncedAt = ts,
                recentErrors = accumulateRecentErrors(
                    previous = current.recentErrors,
                    new = result.errors.map { it.toPublic() },
                    max = config.maxRecentErrors,
                ),
                consentIntent = result.consentIntent,
            )
        }
    }

    private fun reportFatalStorageError(e: Throwable) {
        logError("Local storage unusable", e)
        _syncStatus.update { current ->
            if (current.fatalStorageError != null) current
            else current.copy(
                fatalStorageError = FatalStorageError(
                    occurredAt = now(),
                    summary = e.message ?: e::class.simpleName ?: "Unknown error",
                    details = e.stackTraceToString(),
                ),
            )
        }
    }

    private fun <R> Flow<R>.guardStorage(default: R): Flow<R> = catch { e ->
        reportFatalStorageError(e)
        emit(default)
    }

    /** See [SyncEngine.writeMutex] — every local write must hold it. */
    private suspend fun <R> guardWrite(onError: R, block: suspend () -> R): R = try {
        syncEngine.writeMutex.withLock { block() }
    } catch (e: Exception) {
        reportFatalStorageError(e)
        onError
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    override fun taskLists(): Flow<List<TaskList>> =
        store.lists().guardStorage(emptyList()).map { lists -> lists.map { adapter.toTaskList(it) } }

    override fun tasks(listLocalId: String): Flow<List<Task>> =
        store.records(listLocalId).guardStorage(emptyList()).map { records -> records.map { adapter.toTask(it) } }

    override fun syncStatus(): Flow<SyncStatus> = combine(
        _syncStatus,
        store.pendingOpCount().guardStorage(0),
        store.failedOpCount().guardStorage(0),
    ) { status, pending, failed -> status.copy(pendingOpCount = pending, failedOpCount = failed) }

    // -------------------------------------------------------------------------
    // Write
    // -------------------------------------------------------------------------

    override suspend fun createTask(listLocalId: String, draft: TaskDraft): String = guardWrite(onError = "") {
        val localId = newId()
        val ts = now()
        val content = adapter.newContent(draft, ts)
        store.upsertRecord(
            SyncedRecord(localId = localId, remoteId = null, listLocalId = listLocalId, content = content, isCompleted = false, lastSyncedAt = null),
        )
        store.enqueuePendingOp(
            PendingOp(
                id = newId(), type = OpType.CREATE_RECORD, entityLocalId = localId, listLocalId = listLocalId,
                contentJson = json.encodeToString(recordSerializer, content), createdAt = ts,
            ),
        )
        scheduler.onLocalWrite()
        localId
    }

    /**
     * The pre-edit copy of [entity], capturing a three-way merge base from its current content
     * when the record has a server counterpart but no stored base yet — rows written before
     * `lastSyncedContent` was persisted, or a record edited before its first clean pull-back.
     * Without this, a following offline edit reaches [SyncEngine] with no base and a concurrent
     * remote edit can only be reconciled by last-writer-wins.
     */
    private fun captureMergeBase(entity: SyncedRecord<T>): SyncedRecord<T> =
        if (entity.remoteId != null && entity.lastSyncedContent == null)
            entity.copy(lastSyncedContent = entity.content)
        else entity

    override suspend fun updateTask(localId: String, draft: TaskDraft): Unit = guardWrite(onError = Unit) {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val ts = now()
        val newContent = adapter.applyDraft(entity.content, draft)
        store.upsertRecord(captureMergeBase(entity).copy(content = newContent))
        store.enqueuePendingOp(
            PendingOp(
                id = newId(), type = OpType.UPDATE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId,
                contentJson = json.encodeToString(recordSerializer, newContent), createdAt = ts,
            ),
        )
        scheduler.onLocalWrite()
    }

    override suspend fun completeTask(localId: String): Unit = guardWrite(onError = Unit) {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val ts = now()
        store.upsertRecord(captureMergeBase(entity).copy(isCompleted = true, content = adapter.applyCompletion(entity.content, completed = true, at = ts)))
        store.enqueuePendingOp(
            PendingOp(id = newId(), type = OpType.COMPLETE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId, createdAt = ts),
        )
        scheduler.onLocalWrite()
    }

    override suspend fun uncompleteTask(localId: String): Unit = guardWrite(onError = Unit) {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val ts = now()
        store.upsertRecord(captureMergeBase(entity).copy(isCompleted = false, content = adapter.applyCompletion(entity.content, completed = false, at = null)))
        store.enqueuePendingOp(
            PendingOp(id = newId(), type = OpType.UNCOMPLETE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId, createdAt = ts),
        )
        scheduler.onLocalWrite()
    }

    override suspend fun deleteTask(localId: String): Unit = guardWrite(onError = Unit) {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val ts = now()
        store.softDeleteRecord(localId)
        store.enqueuePendingOp(
            PendingOp(id = newId(), type = OpType.DELETE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId, createdAt = ts),
        )
        scheduler.onLocalWrite()
    }

    override suspend fun moveTask(localId: String, destListLocalId: String): String? {
        if (!capabilities.supportsNativeMove) return null
        return guardWrite<String?>(onError = null) {
            val entity = store.getRecordByLocalId(localId) ?: return@guardWrite null
            val sourceListLocalId = entity.listLocalId
            if (sourceListLocalId == destListLocalId) return@guardWrite localId
            val ts = now()
            // reassignRecord also rewrites this entity's other pending ops' listLocalId, so a
            // still-unsynced task ends up created directly in the destination.
            store.reassignRecord(localId, destListLocalId)
            store.enqueuePendingOp(
                PendingOp(
                    id = newId(), type = OpType.MOVE_RECORD, entityLocalId = localId,
                    listLocalId = destListLocalId, contentJson = sourceListLocalId, createdAt = ts,
                ),
            )
            scheduler.onLocalWrite()
            localId
        }
    }

    override suspend fun createList(title: String): String {
        check(capabilities.supportsListCreation) { "This source does not support creating lists" }
        return guardWrite(onError = "") {
            val localId = newId()
            val ts = now()
            store.upsertList(
                SyncedListRecord(
                    localId = localId, remoteId = null, content = adapter.newListContent(title),
                    lastSyncedAt = null, position = Int.MAX_VALUE, // corrected to real position on next sync
                ),
            )
            store.enqueuePendingOp(
                PendingOp(id = newId(), type = OpType.CREATE_LIST, entityLocalId = localId, listLocalId = localId, createdAt = ts),
            )
            scheduler.onLocalWrite()
            localId
        }
    }

    override suspend fun updateList(localId: String, title: String) {
        check(capabilities.supportsListCreation) { "This source does not support renaming lists" }
        guardWrite(onError = Unit) {
            val entity = store.getListByLocalId(localId) ?: return@guardWrite
            val ts = now()
            store.upsertList(entity.copy(content = adapter.applyListTitle(entity.content, title)))
            store.enqueuePendingOp(
                PendingOp(id = newId(), type = OpType.UPDATE_LIST, entityLocalId = localId, listLocalId = localId, createdAt = ts),
            )
            scheduler.onLocalWrite()
        }
    }

    override suspend fun deleteList(localId: String) {
        check(capabilities.supportsListCreation) { "This source does not support deleting lists" }
        guardWrite(onError = Unit) {
            val entity = store.getListByLocalId(localId) ?: return@guardWrite
            val ts = now()
            // Cancel pending ops for all tasks in this list; remove locally-created tasks now.
            for (task in store.getAllRecordsForList(localId)) {
                store.removeAllPendingOpsForEntity(task.localId)
                if (task.remoteId == null) store.hardDeleteRecord(task.localId)
            }
            store.upsertList(entity.copy(isDeleted = true))
            if (entity.remoteId != null) {
                store.enqueuePendingOp(
                    PendingOp(
                        id = newId(), type = OpType.DELETE_LIST, entityLocalId = localId, listLocalId = localId,
                        contentJson = entity.remoteId, createdAt = ts,
                    ),
                )
                scheduler.onLocalWrite()
            } else {
                store.hardDeleteList(localId)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override suspend fun forceSync() {
        _syncStatus.update { it.copy(isSyncing = true, consentIntent = null) }
        try {
            applySyncResult(syncEngine.sync())
        } catch (e: Exception) {
            reportFatalStorageError(e)
            _syncStatus.update { it.copy(isSyncing = false) }
        }
    }

    override suspend fun fullSync() {
        _syncStatus.update { it.copy(isSyncing = true, consentIntent = null) }
        try {
            applySyncResult(syncEngine.fullSync())
        } catch (e: Exception) {
            reportFatalStorageError(e)
            _syncStatus.update { it.copy(isSyncing = false) }
        }
    }

    override fun close() {
        scheduler.cancel()
        closeResources()
    }
}
