package pl.blizinski.tasksync

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager

/**
 * Manages WorkManager scheduling for periodic sync with adaptive intervals.
 *
 * - [start]: Enqueues the first poll at [SyncConfig.minPollInterval].
 * - [onLocalWrite]: Immediately enqueues a one-shot sync (REPLACE policy) and resets the
 *   interval to min for the subsequent self-scheduled chain.
 * - [cancel]: Cancels all pending sync work.
 *
 * The polling chain is self-sustaining: each [SyncWorker] run schedules the next one at the
 * computed interval, so the chain survives process death.
 */
class AdaptivePoller(
    private val workManager: WorkManager,
    private val config: SyncConfig,
) {

    fun start() {
        workManager.enqueueUniqueWork(
            SyncWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            SyncWorker.buildRequest(config.minPollInterval.inWholeMilliseconds),
        )
    }

    /**
     * Called after any local write. Enqueues an immediate sync with REPLACE policy so it runs
     * before the currently-scheduled delayed poll. The new worker self-schedules the next poll
     * at [SyncConfig.minPollInterval] (because `computeNextIntervalMs` is called with the min
     * interval as the "current" interval for an immediate run).
     */
    fun onLocalWrite() {
        workManager.enqueueUniqueWork(
            SyncWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            SyncWorker.buildImmediateRequest(config.minPollInterval.inWholeMilliseconds),
        )
    }

    fun cancel() {
        workManager.cancelUniqueWork(SyncWorker.WORK_NAME)
    }
}
