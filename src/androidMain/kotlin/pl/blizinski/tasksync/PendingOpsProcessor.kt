package pl.blizinski.tasksync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Flushes locally-queued mutations to the network in [PendingOp.createdAt] order, applying
 * op-merging rules before sending.
 *
 * Pending record ops carry the *full* new content at the time of the edit (not a per-field
 * diff) — the caller enqueuing an UPDATE_RECORD op is responsible for building the complete
 * new [T], not just the changed fields. This is what keeps op-merging trivial: two queued
 * updates to the same record just collapse to "keep the latest one."
 *
 * Returns a list of [SyncError]s for any ops that failed.
 */
class PendingOpsProcessor<T, TList>(
    private val store: LocalStore<T, TList>,
    private val network: NetworkSource<T, TList>,
    private val recordSerializer: KSerializer<T>,
    private val errorClassifier: SyncErrorClassifier,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun flush(): List<SyncError> {
        val ops = store.getAllPendingOps()
        if (ops.isEmpty()) return emptyList()

        val errors = mutableListOf<SyncError>()
        val byEntity = ops.groupBy { it.entityLocalId } // getAll returns ASC by createdAt

        for ((entityLocalId, entityOps) in byEntity) {
            val merged = merge(entityOps)
            if (merged.isEmpty()) {
                // CREATE + DELETE cancelled each other — clean up local state
                store.removeAllPendingOpsForEntity(entityLocalId)
                if (entityOps.any { it.isListOp() }) store.hardDeleteList(entityLocalId)
                else store.hardDeleteRecord(entityLocalId)
                continue
            }
            errors += flushEntityOps(entityLocalId, merged)
        }

        return errors
    }

    // -----------------------------------------------------------------------
    // Op merging
    // -----------------------------------------------------------------------

    /**
     * Reduces a per-entity op list before sending to the network.
     *
     * Rules (applied in sequence):
     * - CREATE + DELETE -> empty (never touch the server)
     * - Any DELETE -> [DELETE] only (skip preceding UPDATE/COMPLETE)
     * - No DELETE -> the last UPDATE (if any) plus all COMPLETEs, in creation order
     */
    internal fun merge(ops: List<PendingOp>): List<PendingOp> {
        if (ops.isEmpty()) return emptyList()

        if (ops.first().isListOp()) return mergeListOps(ops)

        val hasCreate = ops.any { it.type == OpType.CREATE_RECORD }
        val hasDelete = ops.any { it.type == OpType.DELETE_RECORD }

        if (hasCreate && hasDelete) return emptyList()

        val result = mutableListOf<PendingOp>()
        if (hasCreate) result += ops.first { it.type == OpType.CREATE_RECORD }

        if (hasDelete) {
            result += ops.last { it.type == OpType.DELETE_RECORD }
            return result
        }

        ops.lastOrNull { it.type == OpType.UPDATE_RECORD }?.let { result += it }
        ops.filter { it.type == OpType.COMPLETE_RECORD }.forEach { result += it }

        return result.sortedBy { it.createdAt }
    }

    private fun mergeListOps(ops: List<PendingOp>): List<PendingOp> {
        val hasCreate = ops.any { it.type == OpType.CREATE_LIST }
        val hasDelete = ops.any { it.type == OpType.DELETE_LIST }

        if (hasCreate && hasDelete) return emptyList()

        val result = mutableListOf<PendingOp>()
        if (hasCreate) result += ops.first { it.type == OpType.CREATE_LIST }

        if (hasDelete) {
            result += ops.last { it.type == OpType.DELETE_LIST }
            return result
        }

        ops.lastOrNull { it.type == OpType.UPDATE_LIST }?.let { result += it }
        return result
    }

    // -----------------------------------------------------------------------
    // Per-entity flush
    // -----------------------------------------------------------------------

    private suspend fun flushEntityOps(entityLocalId: String, ops: List<PendingOp>): List<SyncError> {
        val errors = mutableListOf<SyncError>()

        for (op in ops) {
            val error = executeOp(op)
            if (error != null) {
                errors += error
                store.recordPendingOpAttempt(op.id, OpStatus.FAILED)
                // Abort remaining ops for this entity — they may depend on prior ops
                // succeeding (e.g. COMPLETE requires remoteId from CREATE).
                break
            } else {
                store.removePendingOp(op.id)
            }
        }

        return errors
    }

    private suspend fun executeOp(op: PendingOp): SyncError? {
        return try {
            when (op.type) {
                OpType.CREATE_RECORD -> executeCreate(op)
                OpType.UPDATE_RECORD -> executeUpdate(op)
                OpType.COMPLETE_RECORD -> executeComplete(op)
                OpType.DELETE_RECORD -> executeDelete(op)
                OpType.CREATE_LIST -> executeCreateList(op)
                OpType.UPDATE_LIST -> executeUpdateList(op)
                OpType.DELETE_LIST -> executeDeleteList(op)
            }
            null
        } catch (e: Exception) {
            SyncError(
                occurredAt = System.currentTimeMillis(),
                kind = errorClassifier.classify(e),
                entityLocalId = op.entityLocalId,
                httpStatus = errorClassifier.httpStatus(e),
                message = e.message ?: "Unknown error",
            )
        }
    }

    private fun decodeContent(op: PendingOp): T =
        json.decodeFromString(recordSerializer, requireNotNull(op.contentJson) {
            "Pending op ${op.id} (${op.type}) has no content"
        })

    private suspend fun executeCreate(op: PendingOp) {
        val listEntity = store.getListByLocalId(op.listLocalId) ?: return
        val remoteListId = listEntity.remoteId ?: return
        val content = decodeContent(op)

        val remote = network.createRecord(remoteListId, content)
        store.updateRecordRemoteId(op.entityLocalId, remote.remoteId)
    }

    private suspend fun executeUpdate(op: PendingOp) {
        val entity = store.getRecordByLocalId(op.entityLocalId) ?: return
        val remoteId = entity.remoteId ?: return  // no remoteId means CREATE not yet flushed
        val listEntity = store.getListByLocalId(op.listLocalId) ?: return
        val remoteListId = listEntity.remoteId ?: return

        network.updateRecord(remoteListId, remoteId, decodeContent(op))
    }

    private suspend fun executeComplete(op: PendingOp) {
        val entity = store.getRecordByLocalId(op.entityLocalId) ?: return
        val remoteId = entity.remoteId ?: return
        val listEntity = store.getListByLocalId(op.listLocalId) ?: return
        val remoteListId = listEntity.remoteId ?: return

        network.completeRecord(remoteListId, remoteId)
    }

    private suspend fun executeDelete(op: PendingOp) {
        val entity = store.getRecordByLocalId(op.entityLocalId) ?: return
        val remoteId = entity.remoteId
        if (remoteId == null) {
            // Record was never synced — nothing to delete on the server
            store.hardDeleteRecord(op.entityLocalId)
            return
        }
        val listEntity = store.getListByLocalId(op.listLocalId) ?: return
        val remoteListId = listEntity.remoteId ?: return

        network.deleteRecord(remoteListId, remoteId)
        store.hardDeleteRecord(op.entityLocalId)
    }

    private suspend fun executeCreateList(op: PendingOp) {
        val entity = store.getListByLocalId(op.entityLocalId) ?: return
        val remote = network.createList(entity.content)
        store.upsertList(entity.copy(remoteId = remote.remoteId))
    }

    private suspend fun executeUpdateList(op: PendingOp) {
        val entity = store.getListByLocalId(op.entityLocalId) ?: return
        val remoteId = entity.remoteId ?: return  // CREATE not yet flushed; skip
        network.updateList(remoteId, entity.content)
    }

    private suspend fun executeDeleteList(op: PendingOp) {
        // remoteId is saved in contentJson at deletion time so it survives local removal.
        val remoteId = op.contentJson
            ?: store.getListByLocalId(op.entityLocalId)?.remoteId
            ?: return
        network.deleteList(remoteId)
        store.hardDeleteList(op.entityLocalId)
    }
}
