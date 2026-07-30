package pl.blizinski.tasksync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Generic in-memory [LocalStore] — no persistence across process restarts. Not
 * platform-specific: has no JS/browser/Room dependency at all, so it's usable anywhere a durable
 * cache isn't available (e.g. today's wasmJs targets, which have no IndexedDB-backed
 * implementation yet) or as a lightweight test double.
 */
class InMemoryLocalStore<T, TList> : LocalStore<T, TList> {

    private val recordsByLocalId = MutableStateFlow<Map<String, SyncedRecord<T>>>(emptyMap())
    private val listsByLocalId = MutableStateFlow<Map<String, SyncedListRecord<TList>>>(emptyMap())
    private val pendingOpsById = MutableStateFlow<Map<String, PendingOp>>(emptyMap())

    // --- Read streams ---

    override fun records(listLocalId: String): Flow<List<SyncedRecord<T>>> =
        recordsByLocalId.map { records -> records.values.filter { it.listLocalId == listLocalId } }

    override fun lists(): Flow<List<SyncedListRecord<TList>>> =
        listsByLocalId.map { lists -> lists.values.sortedBy { it.position } }

    override fun pendingOpCount(): Flow<Int> =
        pendingOpsById.map { ops -> ops.values.count { it.status == OpStatus.PENDING } }

    override fun failedOpCount(): Flow<Int> =
        pendingOpsById.map { ops -> ops.values.count { it.status == OpStatus.FAILED } }

    // --- Record queries ---

    override suspend fun getRecordByLocalId(localId: String): SyncedRecord<T>? =
        recordsByLocalId.value[localId]

    override suspend fun getRecordByRemoteId(remoteId: String): SyncedRecord<T>? =
        recordsByLocalId.value.values.firstOrNull { it.remoteId == remoteId }

    override suspend fun getAllRecordsForList(listLocalId: String): List<SyncedRecord<T>> =
        recordsByLocalId.value.values.filter { it.listLocalId == listLocalId }

    // --- Record mutations ---

    override suspend fun upsertRecord(record: SyncedRecord<T>) {
        recordsByLocalId.update { it + (record.localId to record) }
    }

    override suspend fun upsertRecords(records: List<SyncedRecord<T>>) {
        recordsByLocalId.update { it + records.associateBy { r -> r.localId } }
    }

    override suspend fun updateRecordRemoteId(localId: String, remoteId: String) {
        recordsByLocalId.update { current ->
            val existing = current[localId] ?: return@update current
            current + (localId to existing.copy(remoteId = remoteId))
        }
    }

    override suspend fun updateRecordSyncedState(
        localId: String,
        content: T,
        isCompleted: Boolean,
        lastSyncedAt: Long,
        remoteUpdatedAt: Long?,
        lastSyncedContent: T,
    ) {
        recordsByLocalId.update { current ->
            val existing = current[localId] ?: return@update current
            current + (localId to existing.copy(
                content = content,
                isCompleted = isCompleted,
                lastSyncedAt = lastSyncedAt,
                remoteUpdatedAt = remoteUpdatedAt,
                lastSyncedContent = lastSyncedContent,
            ))
        }
    }

    override suspend fun reassignRecord(localId: String, newListLocalId: String) {
        recordsByLocalId.update { current ->
            val existing = current[localId] ?: return@update current
            current + (localId to existing.copy(listLocalId = newListLocalId))
        }
    }

    override suspend fun softDeleteRecord(localId: String) {
        recordsByLocalId.update { current ->
            val existing = current[localId] ?: return@update current
            current + (localId to existing.copy(isDeleted = true))
        }
    }

    override suspend fun hardDeleteRecord(localId: String) {
        recordsByLocalId.update { it - localId }
        removeAllPendingOpsForEntity(localId)
    }

    // --- List queries ---

    override suspend fun getListByLocalId(localId: String): SyncedListRecord<TList>? =
        listsByLocalId.value[localId]

    override suspend fun getListByRemoteId(remoteId: String): SyncedListRecord<TList>? =
        listsByLocalId.value.values.firstOrNull { it.remoteId == remoteId }

    override suspend fun getAllLists(): List<SyncedListRecord<TList>> =
        listsByLocalId.value.values.toList()

    // --- List mutations ---

    override suspend fun upsertList(list: SyncedListRecord<TList>) {
        listsByLocalId.update { it + (list.localId to list) }
    }

    override suspend fun upsertLists(lists: List<SyncedListRecord<TList>>) {
        listsByLocalId.update { it + lists.associateBy { l -> l.localId } }
    }

    /** Hard-deletes the list and every record still in it (and their pending ops) — see [LocalStore.hardDeleteList]. */
    override suspend fun hardDeleteList(localId: String) {
        val recordIdsInList = recordsByLocalId.value.values.filter { it.listLocalId == localId }.map { it.localId }.toSet()
        recordsByLocalId.update { current -> current - recordIdsInList }
        for (recordId in recordIdsInList) removeAllPendingOpsForEntity(recordId)
        listsByLocalId.update { it - localId }
        removeAllPendingOpsForEntity(localId)
    }

    // --- Pending ops ---

    /** ASC by [PendingOp.createdAt] — [PendingOpsProcessor] relies on this ordering. */
    override suspend fun getAllPendingOps(): List<PendingOp> =
        pendingOpsById.value.values.sortedBy { it.createdAt }

    override suspend fun getPendingOpsForEntity(entityLocalId: String): List<PendingOp> =
        pendingOpsById.value.values.filter { it.entityLocalId == entityLocalId }.sortedBy { it.createdAt }

    override suspend fun enqueuePendingOp(op: PendingOp) {
        pendingOpsById.update { it + (op.id to op) }
    }

    override suspend fun removePendingOp(id: String) {
        pendingOpsById.update { it - id }
    }

    override suspend fun removeAllPendingOpsForEntity(entityLocalId: String) {
        pendingOpsById.update { current -> current.filterValues { it.entityLocalId != entityLocalId } }
    }

    override suspend fun recordPendingOpAttempt(id: String, status: OpStatus) {
        pendingOpsById.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to existing.copy(attemptCount = existing.attemptCount + 1, status = status))
        }
    }
}
