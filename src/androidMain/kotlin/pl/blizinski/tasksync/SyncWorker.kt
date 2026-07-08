package pl.blizinski.tasksync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * WorkManager worker that runs one full sync cycle (flush + pull).
 *
 * The worker self-schedules its next run using the adaptive interval: the current interval is
 * passed as [KEY_INTERVAL_MS] input data, and the next interval is computed from
 * [computeNextIntervalMs] based on whether the pull found remote changes.
 *
 * Dependencies ([SyncWorkerDependencies]) are set by the consuming store before the first work
 * request is enqueued. Only one store instance per process is supported — same constraint as
 * the original google-tasks-kotlin implementation this was extracted from; the star-projected
 * [SyncEngine] reference means this is not a generics limitation, just an unaddressed one
 * (multi-account polling would need per-account unique work names, not a single static slot).
 */
internal class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val currentIntervalMs = inputData.getLong(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS)

        val deps = SyncWorkerDependencies.current
        if (deps == null) {
            // Process was started by WorkManager without the store being initialized
            // (e.g. app not open). Keep the chain alive so the next run can sync.
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                buildRequest(currentIntervalMs),
            )
            return Result.success()
        }

        val syncResult = deps.syncEngine.sync()
        val nextIntervalMs = computeNextIntervalMs(
            currentMs = currentIntervalMs,
            hasChanges = syncResult.hasRemoteChanges,
            minMs = deps.config.minPollInterval.inWholeMilliseconds,
            maxMs = deps.config.maxPollInterval.inWholeMilliseconds,
        )

        // Self-schedule the next poll. APPEND_OR_REPLACE ensures the next run starts after the
        // current one finishes, even if the current run is still RUNNING.
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            buildRequest(nextIntervalMs),
        )

        return Result.success()
    }

    companion object {
        internal const val WORK_NAME = "task_sync_poll"
        internal const val KEY_INTERVAL_MS = "intervalMs"
        private const val DEFAULT_INTERVAL_MS = 60_000L // 1 minute fallback when deps unavailable

        internal fun buildRequest(initialDelayMs: Long) =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(initialDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_INTERVAL_MS to initialDelayMs))
                .build()

        internal fun buildImmediateRequest(nextIntervalMs: Long) =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_INTERVAL_MS to nextIntervalMs))
                .build()
    }
}

/** Holds the live dependencies for [SyncWorker]. Set once by the consuming store before any work requests are enqueued. */
object SyncWorkerDependencies {
    @Volatile
    var current: Deps? = null

    class Deps(
        val syncEngine: SyncEngine<*, *>,
        val config: SyncConfig,
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
