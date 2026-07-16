package pl.blizinski.tasksync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.ConcurrentHashMap

/**
 * WorkManager worker that runs one full sync cycle (flush + pull) for one [instanceKey].
 *
 * The worker self-schedules its next run using the adaptive interval: the current interval is
 * passed as [KEY_INTERVAL_MS] input data, and the next interval is computed from
 * [computeNextIntervalMs] based on whether the pull found remote changes.
 *
 * [instanceKey] (typically the consuming store's database file name — unique per connected
 * account) keys both the WorkManager unique work name ([workName]) and the
 * [SyncWorkerDependencies] lookup, so multiple concurrently-connected accounts (e.g. a Google
 * account and a Microsoft account, or two accounts of the same source type) each get their own
 * independent polling chain instead of colliding on a single shared slot/work name.
 */
internal class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val instanceKey = inputData.getString(KEY_INSTANCE_KEY) ?: return Result.failure()
        val currentIntervalMs = inputData.getLong(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS)

        val deps = SyncWorkerDependencies[instanceKey]
        if (deps == null) {
            // Process was started by WorkManager without the store being initialized
            // (e.g. app not open). Keep the chain alive so the next run can sync.
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                workName(instanceKey),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                buildRequest(currentIntervalMs, instanceKey),
            )
            return Result.success()
        }

        val syncResult = deps.syncEngine.sync()
        deps.onSyncResult(syncResult)
        val nextIntervalMs = computeNextIntervalMs(
            currentMs = currentIntervalMs,
            hasChanges = syncResult.hasRemoteChanges,
            minMs = deps.config.minPollInterval.inWholeMilliseconds,
            maxMs = deps.config.maxPollInterval.inWholeMilliseconds,
        )

        // Self-schedule the next poll. APPEND_OR_REPLACE ensures the next run starts after the
        // current one finishes, even if the current run is still RUNNING.
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            workName(instanceKey),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            buildRequest(nextIntervalMs, instanceKey),
        )

        return Result.success()
    }

    companion object {
        internal const val KEY_INTERVAL_MS = "intervalMs"
        internal const val KEY_INSTANCE_KEY = "instanceKey"
        private const val DEFAULT_INTERVAL_MS = 60_000L // 1 minute fallback when deps unavailable

        /** Unique WorkManager work name for [instanceKey] — one independent polling chain per
         *  connected account instead of every account sharing a single chain. */
        internal fun workName(instanceKey: String) = "task_sync_poll_$instanceKey"

        // Requiring connectivity means an offline device's chain simply waits instead of running
        // (and failing) every poll interval — WorkManager starts the work the moment a network
        // becomes available, then the worker's own self-scheduling resumes as normal.
        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        internal fun buildRequest(initialDelayMs: Long, instanceKey: String) =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(initialDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_INTERVAL_MS to initialDelayMs, KEY_INSTANCE_KEY to instanceKey))
                .setConstraints(networkConstraints)
                .build()

        internal fun buildImmediateRequest(nextIntervalMs: Long, instanceKey: String) =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_INTERVAL_MS to nextIntervalMs, KEY_INSTANCE_KEY to instanceKey))
                .setConstraints(networkConstraints)
                .build()
    }
}

/**
 * Holds the live dependencies for [SyncWorker], keyed by instance so multiple concurrently
 * connected accounts don't overwrite each other's [Deps]. Set/cleared by each consuming store
 * (keyed by its own database file name) before/after its work requests are enqueued.
 */
object SyncWorkerDependencies {
    private val byInstanceKey = ConcurrentHashMap<String, Deps>()

    fun put(instanceKey: String, deps: Deps) { byInstanceKey[instanceKey] = deps }
    fun remove(instanceKey: String) { byInstanceKey.remove(instanceKey) }
    operator fun get(instanceKey: String): Deps? = byInstanceKey[instanceKey]

    class Deps(
        val syncEngine: SyncEngine<*, *>,
        val config: SyncConfig,
        /**
         * Called with every [SyncEngine.sync] result this worker produces, in addition to the
         * scheduling use `hasRemoteChanges` already gets in [SyncWorker.doWork] — lets the
         * registering store apply the same status-update logic to background syncs that it
         * already applies to its own foreground `forceSync()` calls. No-op by default so
         * existing callers that don't need this aren't required to supply one.
         */
        val onSyncResult: (SyncEngine.SyncResult) -> Unit = {},
    )
}

/**
 * Pure function that computes the next poll interval.
 * - If the last sync found remote changes: reset to [minMs].
 * - Otherwise: double the current interval, capped at [maxMs].
 */
internal fun computeNextIntervalMs(
    currentMs: Long,
    hasChanges: Boolean,
    minMs: Long,
    maxMs: Long,
): Long = if (hasChanges) minMs else minOf(currentMs * 2, maxMs)
