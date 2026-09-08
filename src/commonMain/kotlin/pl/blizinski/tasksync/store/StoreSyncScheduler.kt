package pl.blizinski.tasksync.store

/**
 * Abstracts the platform's background-sync scheduling so [DefaultTaskStore] can stay in
 * commonMain. The Android implementation wraps `AdaptivePoller` + `SyncWorkerDependencies`
 * registration; the wasmJs implementation is a no-op (sync happens only on demand via
 * [TaskStore.forceSync]/[TaskStore.fullSync]).
 */
interface StoreSyncScheduler {

    /** Registers dependencies and enqueues the first background poll. Called once at store
     *  construction. */
    fun start()

    /** Called after every local write, to run a sync sooner than the next scheduled poll. */
    fun onLocalWrite()

    /** Cancels background work and unregisters. Called from [TaskStore.close]. */
    fun cancel()
}
