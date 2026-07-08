package pl.blizinski.tasksync

import java.util.UUID

/**
 * Orchestrates a full sync cycle: flush pending ops then pull from the server.
 *
 * Pull rules (apply to both full and incremental modes):
 * - Records with pending ops are skipped (local wins).
 * - New server records are inserted with a fresh localId.
 * - Existing records without pending ops are updated to the server state.
 *
 * Full pull (first sync, updatedMin == null):
 * - Local records whose remoteId is absent from the server response are hard-deleted (server
 *   deleted them) — *unless* the local record is [SyncedRecord.isCompleted], since some
 *   sources' full-listing endpoint deliberately excludes completed items (that absence isn't
 *   a deletion signal).
 *
 * Incremental pull (updatedMin == lastSyncedAt of the list):
 * - Only records modified since updatedMin are returned by the source.
 * - Deleted records are returned with [RemoteRecord.isDeleted] == true and hard-deleted locally.
 * - Absence from the response means "unchanged", not "deleted".
 *
 * Schema-agnostic: never inspects [T]/[TList] beyond structural equality (`!=`) to detect
 * whether server content changed — see the shared-task-sync-engine design doc.
 */
class SyncEngine<T, TList>(
    private val store: LocalStore<T, TList>,
    private val network: NetworkSource<T, TList>,
    private val pendingOpsProcessor: PendingOpsProcessor<T, TList>,
    private val errorClassifier: SyncErrorClassifier,
) {

    data class SyncResult(
        val hasRemoteChanges: Boolean,
        val errors: List<SyncError>,
        /** Non-null when a sync call failed with a recoverable auth-consent error. */
        val consentIntent: Any? = null,
    )

    suspend fun sync(): SyncResult {
        // Snapshot pending entity IDs before flush so that records whose ops are successfully
        // pushed are still protected during the pull in this same cycle. Without this, a
        // record completed locally could be overwritten by stale server data if the server's
        // read replicas haven't caught up yet.
        val pendingEntityIds = store.getAllPendingOps().map { it.entityLocalId }.toSet()
        val pushErrors = pendingOpsProcessor.flush()

        return try {
            val pullResult = pull(pendingEntityIds)
            SyncResult(
                hasRemoteChanges = pullResult.hasRemoteChanges,
                errors = pushErrors + pullResult.errors,
                consentIntent = pullResult.consentIntent,
            )
        } catch (e: Exception) {
            val consentIntent = errorClassifier.extractConsentIntent(e)
            val pullError = SyncError(
                occurredAt = System.currentTimeMillis(),
                kind = if (consentIntent != null) SyncErrorKind.CONSENT_REQUIRED else (errorClassifier.classifySpecial(e) ?: SyncErrorKind.PULL_FAILED),
                entityLocalId = null,
                httpStatus = errorClassifier.httpStatus(e),
                message = e.message ?: "Unknown error during pull",
            )
            SyncResult(hasRemoteChanges = false, errors = pushErrors + pullError, consentIntent = consentIntent)
        }
    }

    // -----------------------------------------------------------------------
    // Pull
    // -----------------------------------------------------------------------

    private data class PullResult(
        val hasRemoteChanges: Boolean,
        val errors: List<SyncError>,
        val consentIntent: Any? = null,
    )

    private suspend fun pull(pendingEntityIds: Set<String>): PullResult {
        var hasRemoteChanges = false
        val errors = mutableListOf<SyncError>()
        val now = System.currentTimeMillis()

        val remoteLists = network.getLists()

        // Remote ids from full pulls, accumulated across all lists. Zombie detection is
        // deferred until all lists finish so a record absent from its source list isn't
        // deleted before we see it in the destination list (cross-list move).
        val fullPullRecordIds = mutableMapOf<String, Set<String>>() // listLocalId -> remoteIds

        for ((position, remoteList) in remoteLists.withIndex()) {
            if (syncList(remoteList, position, now)) hasRemoteChanges = true

            val localList = store.getListByRemoteId(remoteList.remoteId) ?: continue

            // Use the list's lastSyncedAt as updatedMin for incremental pulls. Use null (full
            // pull) on first sync or when the list has no local records (recovers lists whose
            // lastSyncedAt was set before records were pulled). Subtract a 60s buffer to close
            // the race window on incremental pulls.
            val hasLocalRecords = store.getAllRecordsForList(localList.localId).isNotEmpty()
            val updatedMin = if (!hasLocalRecords) null else localList.lastSyncedAt?.minus(60_000L)

            val remoteRecords = try {
                network.getRecords(remoteList.remoteId, updatedMin)
            } catch (e: Exception) {
                val consentIntent = errorClassifier.extractConsentIntent(e)
                if (consentIntent != null) {
                    val error = SyncError(
                        occurredAt = now,
                        kind = SyncErrorKind.CONSENT_REQUIRED,
                        entityLocalId = null,
                        httpStatus = errorClassifier.httpStatus(e),
                        message = e.message ?: "Consent required for list ${remoteList.remoteId}",
                    )
                    return PullResult(hasRemoteChanges, errors + error, consentIntent)
                }
                errors += SyncError(
                    occurredAt = now,
                    kind = (errorClassifier.classifySpecial(e) ?: SyncErrorKind.PULL_FAILED),
                    entityLocalId = null,
                    httpStatus = errorClassifier.httpStatus(e),
                    message = e.message ?: "Failed fetching records for list ${remoteList.remoteId}",
                )
                continue
            }

            for (remoteRecord in remoteRecords) {
                if (syncRecord(remoteRecord, localList.localId, pendingEntityIds, now)) {
                    hasRemoteChanges = true
                }
            }

            if (updatedMin == null) {
                fullPullRecordIds[localList.localId] = remoteRecords.mapTo(mutableSetOf()) { it.remoteId }
            }
            // Incremental: deletions signalled by remoteRecord.isDeleted, handled in syncRecord.

            // Advance lastSyncedAt so the next poll uses updatedMin.
            store.upsertList(localList.copy(lastSyncedAt = now))
        }

        // Deferred zombie detection for full-pulled lists. A record is only deleted if its
        // remoteId is absent from every full-pulled list — if it appears in another list's
        // response it moved rather than was deleted.
        if (fullPullRecordIds.isNotEmpty()) {
            val allSeenRemoteIds = fullPullRecordIds.values.flatten().toSet()
            for ((listLocalId, remoteIds) in fullPullRecordIds) {
                val localRecords = store.getAllRecordsForList(listLocalId)
                for (localRecord in localRecords) {
                    val remoteId = localRecord.remoteId ?: continue  // locally-created, not on server
                    if (remoteId in remoteIds) continue              // still in this list on server
                    if (remoteId in allSeenRemoteIds) continue       // moved to another list
                    if (localRecord.localId in pendingEntityIds) continue
                    // Some sources' full-listing endpoint excludes completed items by design —
                    // their absence there is not a deletion signal. Only a subsequent
                    // isDeleted=true from an incremental pull is a real deletion.
                    if (localRecord.isCompleted) continue
                    store.hardDeleteRecord(localRecord.localId)
                    hasRemoteChanges = true
                }
            }
        }

        // Detect zombie lists: local lists whose remoteId is absent from the server response.
        // Locally-created records (no remoteId) are reassigned to the default list (first
        // returned) so user data isn't lost. Already-synced records are hard-deleted with the list.
        val remoteListIds = remoteLists.mapTo(mutableSetOf()) { it.remoteId }
        val defaultList = remoteLists.firstOrNull()?.let { store.getListByRemoteId(it.remoteId) }

        for (localList in store.getAllLists()) {
            val remoteId = localList.remoteId ?: continue  // locally-created list, skip
            if (remoteId in remoteListIds) continue         // still on server, skip

            if (defaultList != null && defaultList.localId != localList.localId) {
                for (record in store.getAllRecordsForList(localList.localId)) {
                    if (record.remoteId == null) {
                        store.reassignRecord(record.localId, defaultList.localId)
                    }
                }
            }

            store.hardDeleteList(localList.localId)
            hasRemoteChanges = true
        }

        return PullResult(hasRemoteChanges, errors)
    }

    // -----------------------------------------------------------------------
    // Per-entity sync helpers
    // -----------------------------------------------------------------------

    /** Upserts a remote list into the local store. Returns true if the local state changed. */
    private suspend fun syncList(remoteList: RemoteListRecord<TList>, position: Int, now: Long): Boolean {
        val existing = store.getListByRemoteId(remoteList.remoteId)
        return if (existing == null) {
            store.upsertList(
                SyncedListRecord(
                    localId = UUID.randomUUID().toString(),
                    remoteId = remoteList.remoteId,
                    content = remoteList.content,
                    lastSyncedAt = null, // null -> full record pull on the first sync cycle
                    position = position,
                )
            )
            true
        } else if (existing.content != remoteList.content || existing.position != position) {
            store.upsertList(existing.copy(content = remoteList.content, position = position, lastSyncedAt = now))
            true
        } else {
            false
        }
    }

    /**
     * Syncs a single remote record into the local store. Returns true if the local state changed.
     *
     * - If [RemoteRecord.isDeleted]: hard-delete the local entity (if any).
     * - If no local entity exists: insert with a fresh localId.
     * - If the local entity has pending ops: skip (local wins).
     * - Otherwise: apply the remote state.
     */
    private suspend fun syncRecord(
        remoteRecord: RemoteRecord<T>,
        listLocalId: String,
        pendingEntityIds: Set<String>,
        now: Long,
    ): Boolean {
        if (remoteRecord.isDeleted) {
            val existing = store.getRecordByRemoteId(remoteRecord.remoteId) ?: return false
            if (existing.localId in pendingEntityIds) return false  // pending op; local wins
            // If already reassigned to a different list in this cycle (cross-list move), the
            // isDeleted signal is from the source list — skip.
            if (existing.listLocalId != listLocalId) return false
            store.hardDeleteRecord(existing.localId)
            return true
        }

        val existing = store.getRecordByRemoteId(remoteRecord.remoteId)

        return if (existing == null) {
            store.upsertRecord(
                SyncedRecord(
                    localId = UUID.randomUUID().toString(),
                    remoteId = remoteRecord.remoteId,
                    listLocalId = listLocalId,
                    content = remoteRecord.content,
                    isCompleted = remoteRecord.isCompleted,
                    lastSyncedAt = now,
                    remoteUpdatedAt = remoteRecord.remoteUpdatedAt,
                    lastSyncedContent = remoteRecord.content,
                )
            )
            true
        } else if (existing.localId in pendingEntityIds) {
            false  // pending ops — local wins; skip
        } else {
            val changed = existing.content != remoteRecord.content || existing.isCompleted != remoteRecord.isCompleted
            val listChanged = existing.listLocalId != listLocalId
            if (changed) {
                store.updateRecordSyncedState(
                    localId = existing.localId,
                    content = remoteRecord.content,
                    isCompleted = remoteRecord.isCompleted,
                    lastSyncedAt = now,
                    remoteUpdatedAt = remoteRecord.remoteUpdatedAt,
                    lastSyncedContent = remoteRecord.content,
                )
            }
            if (listChanged) {
                store.reassignRecord(existing.localId, listLocalId)
            }
            changed || listChanged
        }
    }
}
