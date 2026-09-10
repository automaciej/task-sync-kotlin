package pl.blizinski.tasksync

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.blizinski.tasksync.store.ContentMerger

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
 *
 * [writeMutex] serializes every [sync] call against every other — necessary because
 * [AdaptivePoller.onLocalWrite] enqueues sync work with [androidx.work.ExistingWorkPolicy.REPLACE],
 * which *requests* cancellation of an in-flight [SyncWorker] but doesn't force-stop it (the
 * underlying blocking HTTP client doesn't observe coroutine cancellation mid-call), so two
 * `sync()` calls can genuinely run concurrently when writes happen in quick succession (e.g. a
 * bulk operation). Without this lock, two overlapping `flush()` calls can both read the same
 * still-pending op before either removes it and push it to the server twice, or interleave with
 * a local write's optimistic update and silently corrupt a not-yet-flushed op — callers that
 * also mutate the local store directly (e.g. [pl.blizinski.googletasksstore.GoogleTasksStore]'s
 * write methods) should acquire the same [writeMutex] before doing so, for the same reason.
 */
@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
class SyncEngine<T, TList>(
    private val store: LocalStore<T, TList>,
    private val network: NetworkSource<T, TList>,
    private val pendingOpsProcessor: PendingOpsProcessor<T, TList>,
    private val errorClassifier: SyncErrorClassifier,
    /**
     * Checked at the start of every [sync]/[fullSync] call — lets a caller skip a doomed flush
     * + pull cycle (and its confusing generic push/pull-failed error) when the device has no
     * network, instead of waiting for the underlying HTTP client to time out or throw
     * [java.io.IOException]. Defaults to always-true for callers/tests that don't care.
     */
    private val isOnline: () -> Boolean = { true },
    /**
     * Optional provider-supplied three-way merge. When null (the default), a record with
     * pending local ops wins wholesale over any concurrent remote change (historical
     * behavior). When supplied, [pull] runs before [PendingOpsProcessor.flush] each cycle and
     * folds the server's non-conflicting field changes into such a record before its ops are
     * pushed — see [mergePendingRecord].
     */
    private val merger: ContentMerger<T>? = null,
) {
    val writeMutex: Mutex = Mutex()

    data class SyncResult(
        val hasRemoteChanges: Boolean,
        val errors: List<SyncError>,
        /** Non-null when a sync call failed with a recoverable auth-consent error. */
        val consentIntent: Any? = null,
    )

    private fun offlineResult() = SyncResult(
        hasRemoteChanges = false,
        errors = listOf(
            SyncError(
                occurredAt = Clock.System.now().toEpochMilliseconds(),
                kind = SyncErrorKind.PULL_FAILED,
                entityLocalId = null,
                httpStatus = null,
                message = "No network connection available",
            )
        ),
    )

    suspend fun sync(): SyncResult {
        if (!isOnline()) return offlineResult()
        return writeMutex.withLock { syncLocked() }
    }

    /**
     * Forces a full resync: every list is pulled from scratch (as if it were the first sync)
     * instead of using its stored [SyncedListRecord.lastSyncedAt] for an incremental delta.
     *
     * Use this to reconcile local state that's drifted from the server in a way incremental
     * sync can't catch — e.g. a record whose [SyncedRecord.listLocalId] was optimistically
     * reassigned to a list that turned out not to exist (or belongs to a different account
     * entirely), leaving it invisible under incremental sync because the server never saw a
     * matching update to that record. [pull]'s full-pull path re-fetches every record on the
     * record's real remote list regardless of modification time and matches it back up by
     * [SyncedRecord.remoteId], so [pull]'s existing `listChanged` handling in `syncRecord`
     * repairs the dangling `listLocalId` in place — no separate orphan-scan is needed.
     */
    suspend fun fullSync(): SyncResult {
        if (!isOnline()) return offlineResult()
        return writeMutex.withLock {
            for (list in store.getAllLists()) {
                store.upsertList(list.copy(lastSyncedAt = null))
            }
            syncLocked()
        }
    }

    private suspend fun syncLocked(): SyncResult {
        // Snapshot pending entity IDs up front: everything queued now is protected from being
        // overwritten by this cycle's pull (local wins, or — with a [merger] — a three-way
        // merge that still preserves the local edit).
        val pendingEntityIds = store.getAllPendingOps().map { it.entityLocalId }.toSet()

        // Pull before flush. A local UPDATE pushed to the server overwrites whatever is there;
        // pulling first lets [mergePendingRecord] fold the server's concurrent changes into the
        // local record (and the content its pending ops will push) *before* that push happens,
        // instead of losing them. With no [merger] this ordering is inert — the pull skips
        // pending records either way. Flush still runs when the pull fails (offline-tolerant).
        var pullChanges = false
        var pullConsentIntent: Any? = null
        val pullErrors: List<SyncError> = try {
            val pullResult = pull(pendingEntityIds)
            pullChanges = pullResult.hasRemoteChanges
            pullConsentIntent = pullResult.consentIntent
            pullResult.errors
        } catch (e: Exception) {
            pullConsentIntent = errorClassifier.extractConsentIntent(e)
            listOf(
                SyncError(
                    occurredAt = Clock.System.now().toEpochMilliseconds(),
                    kind = if (pullConsentIntent != null) SyncErrorKind.CONSENT_REQUIRED else (errorClassifier.classifySpecial(e) ?: SyncErrorKind.PULL_FAILED),
                    entityLocalId = null,
                    httpStatus = errorClassifier.httpStatus(e),
                    message = e.message ?: "Unknown error during pull",
                )
            )
        }

        val pushErrors = pendingOpsProcessor.flush()

        return SyncResult(
            hasRemoteChanges = pullChanges,
            errors = pullErrors + pushErrors,
            consentIntent = pullConsentIntent,
        )
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
        val now = Clock.System.now().toEpochMilliseconds()

        val remoteLists = network.getLists()

        // Remote ids from full pulls, accumulated across all lists. Zombie detection is
        // deferred until all lists finish so a record absent from its source list isn't
        // deleted before we see it in the destination list (cross-list move).
        val fullPullRecordIds = mutableMapOf<String, Set<String>>() // listLocalId -> remoteIds

        // lastSyncedAt advances for every list on every cycle regardless of content change (it's
        // the incremental-pull high-water mark, not a content signal) — batched into one write
        // after the loop instead of one per list, so one pull cycle fires the lists table's
        // InvalidationTracker once instead of once per list (each spaced apart by that list's
        // network round-trip).
        val listsToAdvance = mutableListOf<SyncedListRecord<TList>>()

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

            val toInsert = mutableListOf<SyncedRecord<T>>()
            for (remoteRecord in remoteRecords) {
                if (syncRecord(remoteRecord, localList.localId, pendingEntityIds, now, toInsert)) {
                    hasRemoteChanges = true
                }
            }
            // Batched so a first sync (every record is new) writes once instead of once per
            // record — the per-record update/delete path above still applies individually.
            if (toInsert.isNotEmpty()) {
                store.upsertRecords(toInsert)
                hasRemoteChanges = true
            }

            if (updatedMin == null) {
                fullPullRecordIds[localList.localId] = remoteRecords.mapTo(mutableSetOf()) { it.remoteId }
            }
            // Incremental: deletions signalled by remoteRecord.isDeleted, handled in syncRecord.

            // Advance lastSyncedAt so the next poll uses updatedMin — batched below.
            listsToAdvance += localList.copy(lastSyncedAt = now)
        }

        if (listsToAdvance.isNotEmpty()) {
            store.upsertLists(listsToAdvance)
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
                    localId = Uuid.random().toString(),
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
     * - If no local entity exists: append it to [pendingInserts] instead of writing immediately —
     *   the caller batches these into one [LocalStore.upsertRecords] call per list (this is what
     *   makes a first sync, where every record is new, one write instead of one per record).
     * - If the local entity has pending ops: skip (local wins).
     * - Otherwise: apply the remote state.
     */
    private suspend fun syncRecord(
        remoteRecord: RemoteRecord<T>,
        listLocalId: String,
        pendingEntityIds: Set<String>,
        now: Long,
        pendingInserts: MutableList<SyncedRecord<T>>,
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
            pendingInserts += SyncedRecord(
                localId = Uuid.random().toString(),
                remoteId = remoteRecord.remoteId,
                listLocalId = listLocalId,
                content = remoteRecord.content,
                isCompleted = remoteRecord.isCompleted,
                lastSyncedAt = now,
                remoteUpdatedAt = remoteRecord.remoteUpdatedAt,
                lastSyncedContent = remoteRecord.content,
            )
            false  // caller sets hasRemoteChanges once the batch insert runs
        } else if (existing.localId in pendingEntityIds) {
            mergePendingRecord(existing, remoteRecord)
        } else {
            val changed = existing.content != remoteRecord.content || existing.isCompleted != remoteRecord.isCompleted
            val listChanged = existing.listLocalId != listLocalId
            // Also refresh the synced state (not just on a content change) when the stored merge
            // base is missing or stale — e.g. rows written before lastSyncedContent was
            // persisted, or a record whose base drifted. Without this a later offline edit has
            // no base and [mergePendingRecord] can only fall back to last-writer-wins.
            if (changed || existing.lastSyncedContent != remoteRecord.content) {
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

    /**
     * A record with queued local ops came back changed from the server. Without a [merger] the
     * local copy wins wholesale (historical behavior — returns false, writes nothing). With one,
     * three-way merge the server's field changes into the local content and advance the merge
     * base, so the pending ops push the merged result on this same cycle's flush rather than
     * clobbering the server's copy.
     *
     * [SyncedRecord.lastSyncedContent] (the merge base) is null for a record created locally and
     * not yet pulled back. That case is still handed to the [merger] — with a null base it can
     * still fill fields the local copy never set from [remoteRecord], rather than dropping them —
     * and this pull advances the base so later cycles take the fast path below.
     *
     * The generic completion flag ([SyncedRecord.isCompleted]) is deliberately left to "local
     * wins" here — only opaque content [T] is merged.
     */
    private suspend fun mergePendingRecord(
        existing: SyncedRecord<T>,
        remoteRecord: RemoteRecord<T>,
    ): Boolean {
        val merger = merger ?: return false
        val base = existing.lastSyncedContent
        if (base != null && remoteRecord.content == base) return false  // server unchanged since base
        val entityOps = store.getPendingOpsForEntity(existing.localId)
        if (entityOps.any { it.type == OpType.DELETE_RECORD }) return false  // local delete wins

        val localEditedAt = entityOps.maxOfOrNull { it.createdAt } ?: Long.MIN_VALUE
        val preferLocal = localEditedAt >= (remoteRecord.remoteUpdatedAt ?: Long.MIN_VALUE)
        val merged = merger.merge(base, existing.content, remoteRecord.content, preferLocal)

        store.upsertRecord(
            existing.copy(
                content = merged,
                remoteUpdatedAt = remoteRecord.remoteUpdatedAt,
                lastSyncedContent = remoteRecord.content,
            )
        )
        return merged != existing.content
    }
}
