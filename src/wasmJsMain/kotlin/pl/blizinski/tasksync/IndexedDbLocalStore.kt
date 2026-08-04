package pl.blizinski.tasksync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val STORE_RECORDS = "records"
private const val STORE_LISTS = "lists"
private const val STORE_PENDING_OPS = "pendingOps"
private const val DB_VERSION = 1

/**
 * IndexedDB-backed [LocalStore] — the wasmJs counterpart of [RoomLocalStore], giving web the
 * same durability across page reloads Android already gets from Room. See TaskCompass's
 * Docs/designs/2026-08-02-web-indexeddb-persistence.md.
 *
 * Structurally this is [InMemoryLocalStore] with a write-through IndexedDB backing, not a
 * direct IndexedDB-as-source-of-truth implementation: every record/list/pending-op is kept in
 * an in-memory [MutableStateFlow] map (exactly like [InMemoryLocalStore]), and every mutation
 * both updates that map *and* persists to IndexedDB via [IndexedDbHelper]. All reads — including
 * [getRecordByRemoteId], which would otherwise want an index lookup — are served from the
 * in-memory map, since the whole store's contents are loaded into memory once up front (see
 * [ensureLoaded]) and IndexedDB access is comparatively slow (each read/write is a full
 * browser-async round trip). This keeps read latency and `Flow` semantics identical to
 * [InMemoryLocalStore] (collectors don't wait on IndexedDB at all), with IndexedDB purely as a
 * durability layer underneath.
 *
 * [ensureLoaded] is called at the top of every method (mirroring [IndexedDbHelper.ensureOpen]'s
 * own lazy-idempotent pattern) rather than eagerly in an init block, so this class introduces no
 * unstructured background coroutine of its own — the load only actually runs the first time
 * something touches this store, whether that's a `Flow` collector or a suspend call.
 *
 * Mirrors [RoomLocalStore]'s constructor shape: caller-supplied `KSerializer`s marshal `content`
 * (and every other [SyncedRecord]/[SyncedListRecord]/[PendingOp] field, since IndexedDB's rows
 * here are a single opaque JSON blob per row, unlike Room's per-column storage) to/from JSON —
 * this class never inspects [T]/[TList] itself.
 */
class IndexedDbLocalStore<T, TList>(
    dbName: String,
    private val recordSerializer: KSerializer<T>,
    private val listSerializer: KSerializer<TList>,
) : LocalStore<T, TList> {

    private val db = IndexedDbHelper(
        dbName = dbName,
        version = DB_VERSION,
        // No indexes declared: every read here is served from the in-memory cache below, never
        // via an IndexedDB index query — see this class's doc comment.
        stores = listOf(
            IdbStoreSpec(STORE_RECORDS),
            IdbStoreSpec(STORE_LISTS),
            IdbStoreSpec(STORE_PENDING_OPS),
        ),
    )
    private val jsonCodec = Json { ignoreUnknownKeys = true }

    private val recordsByLocalId = MutableStateFlow<Map<String, SyncedRecord<T>>>(emptyMap())
    private val listsByLocalId = MutableStateFlow<Map<String, SyncedListRecord<TList>>>(emptyMap())
    private val pendingOpsById = MutableStateFlow<Map<String, PendingOp>>(emptyMap())

    private var loaded = false
    private val loadMutex = Mutex()

    private suspend fun ensureLoaded() {
        if (loaded) return
        loadMutex.withLock {
            if (loaded) return@withLock
            recordsByLocalId.value = db.getAll(STORE_RECORDS).associate { it.key to it.toSyncedRecord() }
            listsByLocalId.value = db.getAll(STORE_LISTS).associate { it.key to it.toSyncedListRecord() }
            pendingOpsById.value = db.getAll(STORE_PENDING_OPS).associate { it.key to it.toPendingOp() }
            loaded = true
        }
    }

    // -----------------------------------------------------------------------
    // Row <-> domain marshaling — see this class's doc comment on why every field beyond
    // key/index1/index2 lives inside one JSON blob, unlike Room's per-column entities.
    // -----------------------------------------------------------------------

    @Serializable
    private data class RecordRowDto(
        val localId: String,
        val remoteId: String?,
        val listLocalId: String,
        val contentJson: String,
        val isCompleted: Boolean = false,
        val isDeleted: Boolean = false,
        val lastSyncedAt: Long? = null,
        val remoteUpdatedAt: Long? = null,
        val lastSyncedContentJson: String? = null,
    )

    @Serializable
    private data class ListRowDto(
        val localId: String,
        val remoteId: String?,
        val contentJson: String,
        val isDeleted: Boolean = false,
        val lastSyncedAt: Long? = null,
        val position: Int = 0,
    )

    @Serializable
    private data class PendingOpDto(
        val id: String,
        val type: OpType,
        val entityLocalId: String,
        val listLocalId: String,
        val contentJson: String? = null,
        val createdAt: Long,
        val attemptCount: Int = 0,
        val status: OpStatus = OpStatus.PENDING,
    )

    private fun SyncedRecord<T>.toRow(): IdbRow {
        val dto = RecordRowDto(
            localId = localId,
            remoteId = remoteId,
            listLocalId = listLocalId,
            contentJson = jsonCodec.encodeToString(recordSerializer, content),
            isCompleted = isCompleted,
            isDeleted = isDeleted,
            lastSyncedAt = lastSyncedAt,
            remoteUpdatedAt = remoteUpdatedAt,
            lastSyncedContentJson = lastSyncedContent?.let { jsonCodec.encodeToString(recordSerializer, it) },
        )
        return IdbRow(key = localId, json = jsonCodec.encodeToString(RecordRowDto.serializer(), dto))
    }

    private fun IdbRow.toSyncedRecord(): SyncedRecord<T> {
        val dto = jsonCodec.decodeFromString(RecordRowDto.serializer(), this.json)
        return SyncedRecord(
            localId = dto.localId,
            remoteId = dto.remoteId,
            listLocalId = dto.listLocalId,
            content = jsonCodec.decodeFromString(recordSerializer, dto.contentJson),
            isCompleted = dto.isCompleted,
            isDeleted = dto.isDeleted,
            lastSyncedAt = dto.lastSyncedAt,
            remoteUpdatedAt = dto.remoteUpdatedAt,
            lastSyncedContent = dto.lastSyncedContentJson?.let { jsonCodec.decodeFromString(recordSerializer, it) },
        )
    }

    private fun SyncedListRecord<TList>.toRow(): IdbRow {
        val dto = ListRowDto(
            localId = localId,
            remoteId = remoteId,
            contentJson = jsonCodec.encodeToString(listSerializer, content),
            isDeleted = isDeleted,
            lastSyncedAt = lastSyncedAt,
            position = position,
        )
        return IdbRow(key = localId, json = jsonCodec.encodeToString(ListRowDto.serializer(), dto))
    }

    private fun IdbRow.toSyncedListRecord(): SyncedListRecord<TList> {
        val dto = jsonCodec.decodeFromString(ListRowDto.serializer(), this.json)
        return SyncedListRecord(
            localId = dto.localId,
            remoteId = dto.remoteId,
            content = jsonCodec.decodeFromString(listSerializer, dto.contentJson),
            isDeleted = dto.isDeleted,
            lastSyncedAt = dto.lastSyncedAt,
            position = dto.position,
        )
    }

    private fun PendingOp.toRow(): IdbRow {
        val dto = PendingOpDto(
            id = id, type = type, entityLocalId = entityLocalId, listLocalId = listLocalId,
            contentJson = contentJson, createdAt = createdAt, attemptCount = attemptCount, status = status,
        )
        return IdbRow(key = id, json = jsonCodec.encodeToString(PendingOpDto.serializer(), dto))
    }

    private fun IdbRow.toPendingOp(): PendingOp {
        val dto = jsonCodec.decodeFromString(PendingOpDto.serializer(), this.json)
        return PendingOp(
            id = dto.id, type = dto.type, entityLocalId = dto.entityLocalId, listLocalId = dto.listLocalId,
            contentJson = dto.contentJson, createdAt = dto.createdAt, attemptCount = dto.attemptCount, status = dto.status,
        )
    }

    // -----------------------------------------------------------------------
    // Read streams
    // -----------------------------------------------------------------------

    override fun records(listLocalId: String): Flow<List<SyncedRecord<T>>> = flow {
        ensureLoaded()
        emitAll(recordsByLocalId.map { records ->
            records.values.filter { it.listLocalId == listLocalId && !it.isDeleted }
        })
    }

    override fun lists(): Flow<List<SyncedListRecord<TList>>> = flow {
        ensureLoaded()
        emitAll(listsByLocalId.map { lists -> lists.values.sortedBy { it.position } })
    }

    override fun pendingOpCount(): Flow<Int> = flow {
        ensureLoaded()
        emitAll(pendingOpsById.map { ops -> ops.values.count { it.status == OpStatus.PENDING } })
    }

    override fun failedOpCount(): Flow<Int> = flow {
        ensureLoaded()
        emitAll(pendingOpsById.map { ops -> ops.values.count { it.status == OpStatus.FAILED } })
    }

    // -----------------------------------------------------------------------
    // Record queries
    // -----------------------------------------------------------------------

    override suspend fun getRecordByLocalId(localId: String): SyncedRecord<T>? {
        ensureLoaded()
        return recordsByLocalId.value[localId]
    }

    override suspend fun getRecordByRemoteId(remoteId: String): SyncedRecord<T>? {
        ensureLoaded()
        return recordsByLocalId.value.values.firstOrNull { it.remoteId == remoteId }
    }

    override suspend fun getAllRecordsForList(listLocalId: String): List<SyncedRecord<T>> {
        ensureLoaded()
        return recordsByLocalId.value.values.filter { it.listLocalId == listLocalId && !it.isDeleted }
    }

    // -----------------------------------------------------------------------
    // Record mutations
    // -----------------------------------------------------------------------

    override suspend fun upsertRecord(record: SyncedRecord<T>) {
        ensureLoaded()
        recordsByLocalId.update { it + (record.localId to record) }
        db.put(STORE_RECORDS, record.toRow())
    }

    override suspend fun upsertRecords(records: List<SyncedRecord<T>>) {
        ensureLoaded()
        recordsByLocalId.update { it + records.associateBy { r -> r.localId } }
        // One IndexedDB write per record (not one batched transaction) — simplest correct
        // option for now; fine for this app's data sizes, flagged as a possible future
        // optimization for very large first syncs.
        for (record in records) db.put(STORE_RECORDS, record.toRow())
    }

    override suspend fun updateRecordRemoteId(localId: String, remoteId: String) {
        ensureLoaded()
        val existing = recordsByLocalId.value[localId] ?: return
        val updated = existing.copy(remoteId = remoteId)
        recordsByLocalId.update { it + (localId to updated) }
        db.put(STORE_RECORDS, updated.toRow())
    }

    override suspend fun updateRecordSyncedState(
        localId: String,
        content: T,
        isCompleted: Boolean,
        lastSyncedAt: Long,
        remoteUpdatedAt: Long?,
        lastSyncedContent: T,
    ) {
        ensureLoaded()
        val existing = recordsByLocalId.value[localId] ?: return
        val updated = existing.copy(
            content = content,
            isCompleted = isCompleted,
            lastSyncedAt = lastSyncedAt,
            remoteUpdatedAt = remoteUpdatedAt,
            lastSyncedContent = lastSyncedContent,
        )
        recordsByLocalId.update { it + (localId to updated) }
        db.put(STORE_RECORDS, updated.toRow())
    }

    override suspend fun reassignRecord(localId: String, newListLocalId: String) {
        ensureLoaded()
        val existing = recordsByLocalId.value[localId] ?: return
        val updated = existing.copy(listLocalId = newListLocalId)
        recordsByLocalId.update { it + (localId to updated) }
        db.put(STORE_RECORDS, updated.toRow())
        for (op in pendingOpsById.value.values.filter { it.entityLocalId == localId }) {
            val updatedOp = op.copy(listLocalId = newListLocalId)
            pendingOpsById.update { it + (op.id to updatedOp) }
            db.put(STORE_PENDING_OPS, updatedOp.toRow())
        }
    }

    override suspend fun softDeleteRecord(localId: String) {
        ensureLoaded()
        val existing = recordsByLocalId.value[localId] ?: return
        val updated = existing.copy(isDeleted = true)
        recordsByLocalId.update { it + (localId to updated) }
        db.put(STORE_RECORDS, updated.toRow())
    }

    override suspend fun hardDeleteRecord(localId: String) {
        ensureLoaded()
        recordsByLocalId.update { it - localId }
        db.delete(STORE_RECORDS, localId)
        removeAllPendingOpsForEntity(localId)
    }

    // -----------------------------------------------------------------------
    // List queries
    // -----------------------------------------------------------------------

    override suspend fun getListByLocalId(localId: String): SyncedListRecord<TList>? {
        ensureLoaded()
        return listsByLocalId.value[localId]
    }

    override suspend fun getListByRemoteId(remoteId: String): SyncedListRecord<TList>? {
        ensureLoaded()
        return listsByLocalId.value.values.firstOrNull { it.remoteId == remoteId }
    }

    override suspend fun getAllLists(): List<SyncedListRecord<TList>> {
        ensureLoaded()
        return listsByLocalId.value.values.toList()
    }

    // -----------------------------------------------------------------------
    // List mutations
    // -----------------------------------------------------------------------

    override suspend fun upsertList(list: SyncedListRecord<TList>) {
        ensureLoaded()
        listsByLocalId.update { it + (list.localId to list) }
        db.put(STORE_LISTS, list.toRow())
    }

    override suspend fun upsertLists(lists: List<SyncedListRecord<TList>>) {
        ensureLoaded()
        listsByLocalId.update { it + lists.associateBy { l -> l.localId } }
        for (list in lists) db.put(STORE_LISTS, list.toRow())
    }

    override suspend fun hardDeleteList(localId: String) {
        ensureLoaded()
        val recordIdsInList = recordsByLocalId.value.values.filter { it.listLocalId == localId }.map { it.localId }.toSet()
        recordsByLocalId.update { current -> current - recordIdsInList }
        for (recordId in recordIdsInList) {
            db.delete(STORE_RECORDS, recordId)
            removeAllPendingOpsForEntity(recordId)
        }
        listsByLocalId.update { it - localId }
        db.delete(STORE_LISTS, localId)
        removeAllPendingOpsForEntity(localId)
    }

    // -----------------------------------------------------------------------
    // Pending ops
    // -----------------------------------------------------------------------

    override suspend fun getAllPendingOps(): List<PendingOp> {
        ensureLoaded()
        return pendingOpsById.value.values.sortedBy { it.createdAt }
    }

    override suspend fun getPendingOpsForEntity(entityLocalId: String): List<PendingOp> {
        ensureLoaded()
        return pendingOpsById.value.values.filter { it.entityLocalId == entityLocalId }.sortedBy { it.createdAt }
    }

    override suspend fun enqueuePendingOp(op: PendingOp) {
        ensureLoaded()
        pendingOpsById.update { it + (op.id to op) }
        db.put(STORE_PENDING_OPS, op.toRow())
    }

    override suspend fun removePendingOp(id: String) {
        ensureLoaded()
        pendingOpsById.update { it - id }
        db.delete(STORE_PENDING_OPS, id)
    }

    override suspend fun removeAllPendingOpsForEntity(entityLocalId: String) {
        ensureLoaded()
        val ids = pendingOpsById.value.values.filter { it.entityLocalId == entityLocalId }.map { it.id }
        pendingOpsById.update { current -> current.filterValues { it.entityLocalId != entityLocalId } }
        for (id in ids) db.delete(STORE_PENDING_OPS, id)
    }

    override suspend fun recordPendingOpAttempt(id: String, status: OpStatus) {
        ensureLoaded()
        val existing = pendingOpsById.value[id] ?: return
        val updated = existing.copy(attemptCount = existing.attemptCount + 1, status = status)
        pendingOpsById.update { it + (id to updated) }
        db.put(STORE_PENDING_OPS, updated.toRow())
    }
}
