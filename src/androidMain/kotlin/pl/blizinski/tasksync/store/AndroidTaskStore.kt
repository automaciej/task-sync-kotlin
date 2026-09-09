package pl.blizinski.tasksync.store

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.work.WorkManager
import java.util.UUID
import kotlinx.serialization.KSerializer
import pl.blizinski.tasksync.AdaptivePoller
import pl.blizinski.tasksync.NetworkSource
import pl.blizinski.tasksync.PendingOpsProcessor
import pl.blizinski.tasksync.RoomLocalStore
import pl.blizinski.tasksync.SyncConfig
import pl.blizinski.tasksync.SyncEngine
import pl.blizinski.tasksync.SyncErrorClassifier
import pl.blizinski.tasksync.SyncWorkerDependencies
import pl.blizinski.tasksync.db.TaskSyncDatabase
import pl.blizinski.tasksync.isNetworkAvailable
import pl.blizinski.tasksync.model.StoreCapabilities
import pl.blizinski.tasksync.model.StoreConfig

/**
 * Builds a Room-backed [TaskStore] with adaptive background polling — the Android path every
 * provider library uses. Replaces each library's hand-written ~300-line store class: a provider
 * now supplies only its [network], [errorClassifier], content [KSerializer]s, [adapter],
 * [capabilities], and (Google Tasks only) [migrations].
 *
 * The Room schema, DAOs, `SyncEngine`, `PendingOpsProcessor`, `AdaptivePoller`, and
 * `SyncWorkerDependencies` registration are all shared, unchanged.
 */
fun <T, TList> buildAndroidTaskStore(
    context: Context,
    config: StoreConfig,
    capabilities: StoreCapabilities,
    network: NetworkSource<T, TList>,
    errorClassifier: SyncErrorClassifier,
    recordSerializer: KSerializer<T>,
    listSerializer: KSerializer<TList>,
    adapter: ContentAdapter<T, TList>,
    migrations: List<Migration> = emptyList(),
    merger: ContentMerger<T>? = null,
): TaskStore {
    val appContext = context.applicationContext
    val db = Room.databaseBuilder(appContext, TaskSyncDatabase::class.java, config.dbName)
        .apply { if (migrations.isNotEmpty()) addMigrations(*migrations.toTypedArray()) }
        .build()
    val localStore = RoomLocalStore(db.recordsDao(), db.listsDao(), db.pendingOpsDao(), recordSerializer, listSerializer)
    val pendingOpsProcessor = PendingOpsProcessor(
        localStore, network, recordSerializer, errorClassifier,
        pushLatestEntityContent = merger != null,
    )
    val syncEngine = SyncEngine(
        localStore, network, pendingOpsProcessor, errorClassifier,
        isOnline = { isNetworkAvailable(appContext) },
        merger = merger,
    )
    val syncConfig = SyncConfig(config.minPollInterval, config.maxPollInterval)
    val workManager = WorkManager.getInstance(appContext)

    return DefaultTaskStore(
        store = localStore,
        syncEngine = syncEngine,
        recordSerializer = recordSerializer,
        adapter = adapter,
        capabilities = capabilities,
        config = config,
        closeResources = { db.close() },
        now = { System.currentTimeMillis() },
        newId = { UUID.randomUUID().toString() },
        logError = { msg, e -> Log.e("TaskStore", msg, e) },
        schedulerFactory = { onSyncResult ->
            AndroidStoreSyncScheduler(syncEngine, syncConfig, config.dbName, onSyncResult, workManager)
        },
    )
}

internal class AndroidStoreSyncScheduler(
    private val syncEngine: SyncEngine<*, *>,
    private val syncConfig: SyncConfig,
    private val dbName: String,
    private val onSyncResult: (SyncEngine.SyncResult) -> Unit,
    workManager: WorkManager,
) : StoreSyncScheduler {

    private val poller = AdaptivePoller(workManager, syncConfig, instanceKey = dbName)

    override fun start() {
        SyncWorkerDependencies.put(
            dbName,
            SyncWorkerDependencies.Deps(syncEngine, syncConfig, onSyncResult = onSyncResult),
        )
        poller.start()
    }

    override fun onLocalWrite() = poller.onLocalWrite()

    override fun cancel() {
        poller.cancel()
        SyncWorkerDependencies.remove(dbName)
    }
}
