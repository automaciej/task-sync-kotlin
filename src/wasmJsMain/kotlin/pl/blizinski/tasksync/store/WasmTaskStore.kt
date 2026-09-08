package pl.blizinski.tasksync.store

import kotlinx.serialization.KSerializer
import pl.blizinski.tasksync.IndexedDbLocalStore
import pl.blizinski.tasksync.NetworkSource
import pl.blizinski.tasksync.PendingOpsProcessor
import pl.blizinski.tasksync.SyncEngine
import pl.blizinski.tasksync.SyncErrorClassifier
import pl.blizinski.tasksync.model.StoreCapabilities
import pl.blizinski.tasksync.model.StoreConfig

/**
 * Builds an IndexedDB-backed [TaskStore] that syncs only on demand (no background poller) — the
 * wasmJs path, mirroring the former `GoogleTasksStoreWasm`/`GitHubIssuesStoreWasm`. Wires the
 * *same* [SyncEngine]/[PendingOpsProcessor] the Android path uses.
 */
fun <T, TList> buildWasmTaskStore(
    config: StoreConfig,
    capabilities: StoreCapabilities,
    network: NetworkSource<T, TList>,
    errorClassifier: SyncErrorClassifier,
    recordSerializer: KSerializer<T>,
    listSerializer: KSerializer<TList>,
    adapter: ContentAdapter<T, TList>,
): TaskStore {
    val localStore = IndexedDbLocalStore(config.dbName, recordSerializer, listSerializer)
    val pendingOpsProcessor = PendingOpsProcessor(localStore, network, recordSerializer, errorClassifier)
    val syncEngine = SyncEngine(localStore, network, pendingOpsProcessor, errorClassifier)

    return DefaultTaskStore(
        store = localStore,
        syncEngine = syncEngine,
        recordSerializer = recordSerializer,
        adapter = adapter,
        capabilities = capabilities,
        config = config,
        closeResources = {},
        schedulerFactory = { NoopStoreSyncScheduler },
    )
}

private object NoopStoreSyncScheduler : StoreSyncScheduler {
    override fun start() {}
    override fun onLocalWrite() {}
    override fun cancel() {}
}
