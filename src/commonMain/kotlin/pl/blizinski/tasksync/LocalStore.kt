package pl.blizinski.tasksync

import kotlinx.coroutines.flow.Flow

/**
 * Local cache contract [SyncEngine]/[PendingOpsProcessor] operate against. Schema-agnostic:
 * [T]/[TList] are opaque content, never inspected here either — a concrete implementation
 * (e.g. Room-backed) is responsible for persisting them however it likes (a serialized column,
 * typically), and for [T]/[TList] equality/copy semantics via their own `equals()`.
 */
interface LocalStore<T, TList> {

    // --- Read streams ---
    fun records(listLocalId: String): Flow<List<SyncedRecord<T>>>
    fun lists(): Flow<List<SyncedListRecord<TList>>>
    fun pendingOpCount(): Flow<Int>

    // --- Record queries ---
    suspend fun getRecordByLocalId(localId: String): SyncedRecord<T>?
    suspend fun getRecordByRemoteId(remoteId: String): SyncedRecord<T>?
    suspend fun getAllRecordsForList(listLocalId: String): List<SyncedRecord<T>>

    // --- Record mutations ---
    suspend fun upsertRecord(record: SyncedRecord<T>)
    suspend fun updateRecordRemoteId(localId: String, remoteId: String)
    suspend fun updateRecordSyncedState(
        localId: String,
        content: T,
        isCompleted: Boolean,
        lastSyncedAt: Long,
        remoteUpdatedAt: Long?,
        lastSyncedContent: T,
    )
    suspend fun reassignRecord(localId: String, newListLocalId: String)
    suspend fun softDeleteRecord(localId: String)
    suspend fun hardDeleteRecord(localId: String)

    // --- List queries ---
    suspend fun getListByLocalId(localId: String): SyncedListRecord<TList>?
    suspend fun getListByRemoteId(remoteId: String): SyncedListRecord<TList>?
    suspend fun getAllLists(): List<SyncedListRecord<TList>>

    // --- List mutations ---
    suspend fun upsertList(list: SyncedListRecord<TList>)
    /** Hard-deletes the list and every record still in it (and their pending ops). */
    suspend fun hardDeleteList(localId: String)

    // --- Pending ops ---
    suspend fun getAllPendingOps(): List<PendingOp>
    suspend fun getPendingOpsForEntity(entityLocalId: String): List<PendingOp>
    suspend fun enqueuePendingOp(op: PendingOp)
    suspend fun removePendingOp(id: String)
    suspend fun removeAllPendingOpsForEntity(entityLocalId: String)
    suspend fun recordPendingOpAttempt(id: String, status: OpStatus)
}
