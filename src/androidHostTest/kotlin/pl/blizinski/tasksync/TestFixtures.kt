package pl.blizinski.tasksync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.Serializable

/** Fake, deliberately minimal content types — proves SyncEngine/PendingOpsProcessor never
 * need anything beyond structural equality on these, unlike google-tasks-kotlin's real
 * Google-Tasks-shaped Task/TaskList. */
@Serializable
internal data class FakeContent(val title: String)

@Serializable
internal data class FakeListContent(val title: String)

internal fun remoteList(id: String, title: String = "List $id") =
    RemoteListRecord(remoteId = id, content = FakeListContent(title))

internal fun remoteRecord(
    id: String,
    title: String = "Task $id",
    isCompleted: Boolean = false,
    isDeleted: Boolean = false,
) = RemoteRecord(
    remoteId = id,
    content = FakeContent(title),
    isCompleted = isCompleted,
    isDeleted = isDeleted,
)

internal fun localList(
    localId: String,
    remoteId: String?,
    lastSyncedAt: Long? = null,
) = SyncedListRecord(
    localId = localId,
    remoteId = remoteId,
    // Content matches what remoteList() generates for the same id, so syncList doesn't treat
    // a content "change" as a reason to update lastSyncedAt.
    content = FakeListContent("List ${remoteId ?: localId}"),
    lastSyncedAt = lastSyncedAt,
)

internal fun localRecord(
    localId: String,
    listLocalId: String,
    remoteId: String? = null,
    title: String = "Task $localId",
    isCompleted: Boolean = false,
) = SyncedRecord(
    localId = localId,
    remoteId = remoteId,
    listLocalId = listLocalId,
    content = FakeContent(title),
    isCompleted = isCompleted,
)

internal fun pendingOp(
    id: String,
    entityLocalId: String,
    listLocalId: String,
    type: OpType = OpType.UPDATE_RECORD,
) = PendingOp(
    id = id,
    type = type,
    entityLocalId = entityLocalId,
    listLocalId = listLocalId,
    createdAt = 0L,
)

/**
 * In-memory [LocalStore] for unit tests. All state is public so tests can seed/inspect it.
 */
internal class FakeLocalStore : LocalStore<FakeContent, FakeListContent> {

    val lists = mutableMapOf<String, SyncedListRecord<FakeListContent>>()  // key = localId
    val records = mutableMapOf<String, SyncedRecord<FakeContent>>()        // key = localId
    val pendingOps = mutableMapOf<String, PendingOp>()                     // key = op.id

    override fun records(listLocalId: String): Flow<List<SyncedRecord<FakeContent>>> = flowOf(emptyList())
    override fun lists(): Flow<List<SyncedListRecord<FakeListContent>>> = flowOf(emptyList())
    override fun pendingOpCount(): Flow<Int> = flowOf(0)
    override fun failedOpCount(): Flow<Int> = flowOf(0)

    override suspend fun getRecordByLocalId(localId: String): SyncedRecord<FakeContent>? =
        records[localId]?.takeIf { !it.isDeleted }

    override suspend fun getRecordByRemoteId(remoteId: String): SyncedRecord<FakeContent>? =
        records.values.firstOrNull { it.remoteId == remoteId && !it.isDeleted }

    override suspend fun getAllRecordsForList(listLocalId: String): List<SyncedRecord<FakeContent>> =
        records.values.filter { it.listLocalId == listLocalId && !it.isDeleted }

    override suspend fun getListByLocalId(localId: String): SyncedListRecord<FakeListContent>? =
        lists[localId]?.takeIf { !it.isDeleted }

    override suspend fun getListByRemoteId(remoteId: String): SyncedListRecord<FakeListContent>? =
        lists.values.firstOrNull { it.remoteId == remoteId && !it.isDeleted }

    override suspend fun getAllLists(): List<SyncedListRecord<FakeListContent>> =
        lists.values.filter { !it.isDeleted }

    override suspend fun upsertRecord(record: SyncedRecord<FakeContent>) {
        records[record.localId] = record
    }

    override suspend fun upsertRecords(records: List<SyncedRecord<FakeContent>>) {
        records.forEach { upsertRecord(it) }
    }

    override suspend fun updateRecordRemoteId(localId: String, remoteId: String) {
        records[localId]?.let { records[localId] = it.copy(remoteId = remoteId) }
    }

    override suspend fun updateRecordSyncedState(
        localId: String,
        content: FakeContent,
        isCompleted: Boolean,
        lastSyncedAt: Long,
        remoteUpdatedAt: Long?,
        lastSyncedContent: FakeContent,
    ) {
        records[localId]?.let {
            records[localId] = it.copy(
                content = content,
                isCompleted = isCompleted,
                lastSyncedAt = lastSyncedAt,
                remoteUpdatedAt = remoteUpdatedAt,
                lastSyncedContent = lastSyncedContent,
            )
        }
    }

    override suspend fun reassignRecord(localId: String, newListLocalId: String) {
        records[localId]?.let { records[localId] = it.copy(listLocalId = newListLocalId) }
        pendingOps.values
            .filter { it.entityLocalId == localId }
            .forEach { op -> pendingOps[op.id] = op.copy(listLocalId = newListLocalId) }
    }

    override suspend fun softDeleteRecord(localId: String) {
        records[localId]?.let { records[localId] = it.copy(isDeleted = true) }
    }

    override suspend fun hardDeleteRecord(localId: String) {
        records.remove(localId)
    }

    override suspend fun upsertList(list: SyncedListRecord<FakeListContent>) {
        lists[list.localId] = list
    }

    override suspend fun hardDeleteList(localId: String) {
        val remaining = records.values.filter { it.listLocalId == localId }.map { it.localId }
        for (recordLocalId in remaining) {
            pendingOps.keys.removeAll { pendingOps[it]?.entityLocalId == recordLocalId }
            records.remove(recordLocalId)
        }
        lists.remove(localId)
    }

    override suspend fun getAllPendingOps(): List<PendingOp> =
        pendingOps.values.sortedBy { it.createdAt }

    override suspend fun getPendingOpsForEntity(entityLocalId: String): List<PendingOp> =
        pendingOps.values.filter { it.entityLocalId == entityLocalId }.sortedBy { it.createdAt }

    override suspend fun enqueuePendingOp(op: PendingOp) {
        pendingOps[op.id] = op
    }

    override suspend fun removePendingOp(id: String) {
        pendingOps.remove(id)
    }

    override suspend fun removeAllPendingOpsForEntity(entityLocalId: String) {
        pendingOps.keys.removeAll { pendingOps[it]?.entityLocalId == entityLocalId }
    }

    override suspend fun recordPendingOpAttempt(id: String, status: OpStatus) {
        pendingOps[id]?.let { pendingOps[id] = it.copy(attemptCount = it.attemptCount + 1, status = status) }
    }
}

/**
 * In-memory [NetworkSource] for unit tests.
 *
 * [listsResponse]/[recordsResponse] are keyed by remoteListId and contain the data the fake
 * server returns. [updatedMinCapture] records the updatedMin passed to [getRecords] per list.
 * [failingListIds] causes [createRecord] to throw for those remoteListIds, simulating a 404.
 */
internal class FakeNetworkSource : NetworkSource<FakeContent, FakeListContent> {

    var listsResponse: List<RemoteListRecord<FakeListContent>> = emptyList()
    val recordsResponse: MutableMap<String, List<RemoteRecord<FakeContent>>> = mutableMapOf()
    val updatedMinCapture: MutableMap<String, Long?> = mutableMapOf()
    val failingListIds = mutableSetOf<String>()
    /** [moveRecord] calls, in call order. */
    val moveCalls: MutableList<MoveCall> = mutableListOf()
    internal data class MoveCall(val source: String, val remoteId: String, val dest: String, val previous: String?)
    /** Test hook invoked at the start of every [getLists] call — e.g. to detect/force overlap. */
    var onGetLists: (suspend () -> Unit)? = null

    override suspend fun getLists(): List<RemoteListRecord<FakeListContent>> {
        onGetLists?.invoke()
        return listsResponse
    }

    override suspend fun createList(content: FakeListContent): RemoteListRecord<FakeListContent> =
        RemoteListRecord(remoteId = "created-list-${content.title.take(8)}", content = content)

    override suspend fun updateList(remoteListId: String, content: FakeListContent) = Unit
    override suspend fun deleteList(remoteListId: String) = Unit

    override suspend fun getRecords(remoteListId: String, updatedMin: Long?): List<RemoteRecord<FakeContent>> {
        updatedMinCapture[remoteListId] = updatedMin
        return recordsResponse[remoteListId] ?: emptyList()
    }

    override suspend fun createRecord(remoteListId: String, content: FakeContent): RemoteRecord<FakeContent> {
        if (remoteListId in failingListIds) {
            throw IllegalStateException("404 Not Found: list $remoteListId does not exist")
        }
        return RemoteRecord(remoteId = "created-remote-${content.title.take(8)}", content = content)
    }

    override suspend fun updateRecord(remoteListId: String, remoteId: String, content: FakeContent) {
        if (remoteListId in failingListIds) {
            throw IllegalStateException("404 Not Found: list $remoteListId does not exist")
        }
    }

    override suspend fun completeRecord(remoteListId: String, remoteId: String) = Unit
    override suspend fun uncompleteRecord(remoteListId: String, remoteId: String) = Unit
    override suspend fun deleteRecord(remoteListId: String, remoteId: String) = Unit

    override suspend fun moveRecord(sourceRemoteListId: String, remoteId: String, destRemoteListId: String, previousRemoteId: String?) {
        moveCalls += MoveCall(sourceRemoteListId, remoteId, destRemoteListId, previousRemoteId)
    }
}

internal class FakeSyncErrorClassifier : SyncErrorClassifier {
    override fun classifySpecial(e: Exception): SyncErrorKind? = null
    override fun httpStatus(e: Exception): Int? = null
    override fun extractConsentIntent(e: Exception): Any? = null
}
