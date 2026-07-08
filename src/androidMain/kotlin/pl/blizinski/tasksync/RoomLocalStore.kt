package pl.blizinski.tasksync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import pl.blizinski.tasksync.db.PendingOpsDao
import pl.blizinski.tasksync.db.SyncedListEntity
import pl.blizinski.tasksync.db.SyncedListsDao
import pl.blizinski.tasksync.db.SyncedRecordEntity
import pl.blizinski.tasksync.db.SyncedRecordsDao

/**
 * Generic [LocalStore] wrapper over the shared, non-generic Room DAOs. This is the only place
 * genericity and Room meet: it JSON-marshals [T]/[TList] via the caller-supplied serializers on
 * the way in/out of the opaque `contentJson` columns; the DAOs/entities themselves never see
 * [T]/[TList].
 */
class RoomLocalStore<T, TList>(
    private val recordsDao: SyncedRecordsDao,
    private val listsDao: SyncedListsDao,
    private val pendingOpsDao: PendingOpsDao,
    private val recordSerializer: KSerializer<T>,
    private val listSerializer: KSerializer<TList>,
) : LocalStore<T, TList> {

    private val json = Json { ignoreUnknownKeys = true }

    private fun SyncedRecordEntity.toDomain() = SyncedRecord(
        localId = localId,
        remoteId = remoteId,
        listLocalId = listLocalId,
        content = json.decodeFromString(recordSerializer, contentJson),
        isCompleted = isCompleted,
        isDeleted = isDeleted,
        lastSyncedAt = lastSyncedAt,
        remoteUpdatedAt = remoteUpdatedAt,
        lastSyncedContent = lastSyncedContentJson?.let { json.decodeFromString(recordSerializer, it) },
    )

    private fun SyncedRecord<T>.toEntity() = SyncedRecordEntity(
        localId = localId,
        remoteId = remoteId,
        listLocalId = listLocalId,
        contentJson = json.encodeToString(recordSerializer, content),
        isCompleted = isCompleted,
        isDeleted = isDeleted,
        lastSyncedAt = lastSyncedAt,
        remoteUpdatedAt = remoteUpdatedAt,
        lastSyncedContentJson = lastSyncedContent?.let { json.encodeToString(recordSerializer, it) },
    )

    private fun SyncedListEntity.toDomain() = SyncedListRecord(
        localId = localId,
        remoteId = remoteId,
        content = json.decodeFromString(listSerializer, contentJson),
        isDeleted = isDeleted,
        lastSyncedAt = lastSyncedAt,
        position = position,
    )

    private fun SyncedListRecord<TList>.toEntity() = SyncedListEntity(
        localId = localId,
        remoteId = remoteId,
        contentJson = json.encodeToString(listSerializer, content),
        isDeleted = isDeleted,
        lastSyncedAt = lastSyncedAt,
        position = position,
    )

    override fun records(listLocalId: String): Flow<List<SyncedRecord<T>>> =
        recordsDao.observeByList(listLocalId).map { entities -> entities.map { it.toDomain() } }

    override fun lists(): Flow<List<SyncedListRecord<TList>>> =
        listsDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun pendingOpCount(): Flow<Int> = pendingOpsDao.observeCount()

    override suspend fun getRecordByLocalId(localId: String): SyncedRecord<T>? =
        recordsDao.getByLocalId(localId)?.toDomain()

    override suspend fun getRecordByRemoteId(remoteId: String): SyncedRecord<T>? =
        recordsDao.getByRemoteId(remoteId)?.toDomain()

    override suspend fun getAllRecordsForList(listLocalId: String): List<SyncedRecord<T>> =
        recordsDao.getAllForList(listLocalId).map { it.toDomain() }

    override suspend fun upsertRecord(record: SyncedRecord<T>) =
        recordsDao.upsert(record.toEntity())

    override suspend fun updateRecordRemoteId(localId: String, remoteId: String) =
        recordsDao.updateRemoteId(localId, remoteId)

    override suspend fun updateRecordSyncedState(
        localId: String,
        content: T,
        isCompleted: Boolean,
        lastSyncedAt: Long,
        remoteUpdatedAt: Long?,
        lastSyncedContent: T,
    ) = recordsDao.updateSyncedState(
        localId = localId,
        contentJson = json.encodeToString(recordSerializer, content),
        isCompleted = isCompleted,
        lastSyncedAt = lastSyncedAt,
        remoteUpdatedAt = remoteUpdatedAt,
        lastSyncedContentJson = json.encodeToString(recordSerializer, lastSyncedContent),
    )

    override suspend fun reassignRecord(localId: String, newListLocalId: String) {
        recordsDao.updateListId(localId, newListLocalId)
        pendingOpsDao.updateListIdForEntity(localId, newListLocalId)
    }

    override suspend fun softDeleteRecord(localId: String) = recordsDao.softDelete(localId)

    override suspend fun hardDeleteRecord(localId: String) = recordsDao.hardDelete(localId)

    override suspend fun getListByLocalId(localId: String): SyncedListRecord<TList>? =
        listsDao.getByLocalId(localId)?.toDomain()

    override suspend fun getListByRemoteId(remoteId: String): SyncedListRecord<TList>? =
        listsDao.getByRemoteId(remoteId)?.toDomain()

    override suspend fun getAllLists(): List<SyncedListRecord<TList>> =
        listsDao.getAll().map { it.toDomain() }

    override suspend fun upsertList(list: SyncedListRecord<TList>) =
        listsDao.upsert(list.toEntity())

    override suspend fun hardDeleteList(localId: String) {
        val remaining = recordsDao.getAllForList(localId)
        for (record in remaining) {
            pendingOpsDao.deleteByEntity(record.localId)
            recordsDao.hardDelete(record.localId)
        }
        listsDao.hardDelete(localId)
    }

    override suspend fun getAllPendingOps(): List<PendingOp> =
        pendingOpsDao.getAll().map { it.toDomain() }

    override suspend fun getPendingOpsForEntity(entityLocalId: String): List<PendingOp> =
        pendingOpsDao.getByEntity(entityLocalId).map { it.toDomain() }

    override suspend fun enqueuePendingOp(op: PendingOp) = pendingOpsDao.insert(op.toEntity())

    override suspend fun removePendingOp(id: String) = pendingOpsDao.delete(id)

    override suspend fun removeAllPendingOpsForEntity(entityLocalId: String) =
        pendingOpsDao.deleteByEntity(entityLocalId)

    override suspend fun recordPendingOpAttempt(id: String, status: OpStatus) =
        pendingOpsDao.recordAttempt(id, status)
}

private fun pl.blizinski.tasksync.db.PendingOpEntity.toDomain() = PendingOp(
    id = id,
    type = type,
    entityLocalId = entityLocalId,
    listLocalId = listLocalId,
    contentJson = contentJson,
    createdAt = createdAt,
    attemptCount = attemptCount,
    status = status,
)

private fun PendingOp.toEntity() = pl.blizinski.tasksync.db.PendingOpEntity(
    id = id,
    type = type,
    entityLocalId = entityLocalId,
    listLocalId = listLocalId,
    contentJson = contentJson,
    createdAt = createdAt,
    attemptCount = attemptCount,
    status = status,
)
