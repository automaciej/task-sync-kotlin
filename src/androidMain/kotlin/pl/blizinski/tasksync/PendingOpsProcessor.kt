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
        // destination list localId -> remoteId of the last record moved into it so far this
        // flush, so a batch of moves lands in the destination in the same relative order they
        // were moved in (chained via NetworkSource.moveRecord's previousRemoteId) instead of
        // each one landing at the top and reversing the batch.
        val lastMovedRemoteIdByDestList = mutableMapOf<String, String>()

        for ((entityLocalId, entityOps) in byEntity) {
            val merged = merge(entityOps)
            if (merged.isEmpty()) {
                // CREATE + DELETE cancelled each other — clean up local state
                store.removeAllPendingOpsForEntity(entityLocalId)
                if (entityOps.any { it.isListOp() }) store.hardDeleteList(entityLocalId)
                else store.hardDeleteRecord(entityLocalId)
                continue
            }
            errors += flushEntityOps(entityLocalId, merged, lastMovedRemoteIdByDestList)
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
     * - Any DELETE -> [DELETE] only (skip preceding UPDATE/COMPLETE/UNCOMPLETE)
     * - No DELETE -> the last UPDATE (if any) plus all COMPLETE/UNCOMPLETEs, in creation order
     *   (kept in full, not collapsed to the last one — sending redundant-but-ordered
     *   COMPLETE/UNCOMPLETE calls still converges to the correct final state)
     */
    internal fun merge(ops: List<PendingOp>): List<PendingOp> {
        if (ops.isEmpty()) return emptyList()

        if (ops.first().isListOp()) return mergeListOps(ops)

        val hasCreate = ops.any { it.type == OpType.CREATE_RECORD }
        val hasDelete = ops.any { it.type == OpType.DELETE_RECORD }
        val moves = ops.filter { it.type == OpType.MOVE_RECORD }

        if (hasCreate && hasDelete) return emptyList()

        val result = mutableListOf<PendingOp>()
        if (hasCreate) {
            // A record not yet created remotely can simply be created directly in its final
            // destination — fold any pending moves' destination into the CREATE instead of
            // emitting a separate MOVE (which would have nothing to move yet).
            val createOp = ops.first { it.type == OpType.CREATE_RECORD }
            result += if (moves.isEmpty()) createOp else createOp.copy(listLocalId = moves.last().listLocalId)
        }

        if (hasDelete) {
            result += ops.last { it.type == OpType.DELETE_RECORD }
            return result
        }

        ops.lastOrNull { it.type == OpType.UPDATE_RECORD }?.let { result += it }
        ops.filter { it.type == OpType.COMPLETE_RECORD || it.type == OpType.UNCOMPLETE_RECORD }.forEach { result += it }
        // Already-remote records: collapse a chain of moves (A->B->C) to a single A->C move,
        // combining the earliest known source with the latest destination.
        if (!hasCreate && moves.isNotEmpty()) {
            result += moves.last().copy(contentJson = moves.first().contentJson)
        }

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

    private suspend fun flushEntityOps(
        entityLocalId: String,
        ops: List<PendingOp>,
        lastMovedRemoteIdByDestList: MutableMap<String, String>,
    ): List<SyncError> {
        val errors = mutableListOf<SyncError>()

        for (op in ops) {
            val error = executeOp(op, lastMovedRemoteIdByDestList)
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

    private suspend fun executeOp(op: PendingOp, lastMovedRemoteIdByDestList: MutableMap<String, String>): SyncError? {
        return try {
            when (op.type) {
                OpType.CREATE_RECORD -> executeCreate(op)
                OpType.UPDATE_RECORD -> executeUpdate(op)
                OpType.COMPLETE_RECORD -> executeComplete(op)
                OpType.UNCOMPLETE_RECORD -> executeUncomplete(op)
                OpType.DELETE_RECORD -> executeDelete(op)
                OpType.MOVE_RECORD -> executeMove(op, lastMovedRemoteIdByDestList)
                OpType.CREATE_LIST -> executeCreateList(op)
                OpType.UPDATE_LIST -> executeUpdateList(op)
                OpType.DELETE_LIST -> executeDeleteList(op)
            }
            null
        } catch (e: Exception) {
            SyncError(
                occurredAt = System.currentTimeMillis(),
                kind = errorClassifier.classifySpecial(e) ?: SyncErrorKind.PUSH_FAILED,
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

    private suspend fun executeUncomplete(op: PendingOp) {
        val entity = store.getRecordByLocalId(op.entityLocalId) ?: return
        val remoteId = entity.remoteId ?: return
        val listEntity = store.getListByLocalId(op.listLocalId) ?: return
        val remoteListId = listEntity.remoteId ?: return

        network.uncompleteRecord(remoteListId, remoteId)
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

    /**
     * [op.listLocalId] is the destination list (as for CREATE/UPDATE/COMPLETE); [op.contentJson]
     * holds the source list's localId, captured at enqueue time — see [PendingOp]'s doc.
     * [lastMovedRemoteIdByDestList] chains a batch of moves into destination order — see [flush].
     */
    private suspend fun executeMove(op: PendingOp, lastMovedRemoteIdByDestList: MutableMap<String, String>) {
        val entity = store.getRecordByLocalId(op.entityLocalId) ?: return
        val remoteId = entity.remoteId ?: return  // not yet created remotely; CREATE already targets the final list (see merge)
        val sourceListLocalId = op.contentJson ?: return
        val sourceRemoteListId = store.getListByLocalId(sourceListLocalId)?.remoteId ?: return
        val destRemoteListId = store.getListByLocalId(op.listLocalId)?.remoteId ?: return
        if (sourceRemoteListId == destRemoteListId) return  // already there (e.g. moved back and forth locally)

        val previousRemoteId = lastMovedRemoteIdByDestList[op.listLocalId]
        network.moveRecord(sourceRemoteListId, remoteId, destRemoteListId, previousRemoteId)
        lastMovedRemoteIdByDestList[op.listLocalId] = remoteId
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
