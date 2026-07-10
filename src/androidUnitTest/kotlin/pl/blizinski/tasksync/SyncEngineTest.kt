package pl.blizinski.tasksync

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {

    private val T0 = 1_000_000L  // arbitrary base epoch ms

    private fun engine(store: FakeLocalStore, network: FakeNetworkSource): SyncEngine<FakeContent, FakeListContent> {
        val errorClassifier = FakeSyncErrorClassifier()
        val pendingOps = PendingOpsProcessor(store, network, serializer<FakeContent>(), errorClassifier)
        return SyncEngine(store, network, pendingOps, errorClassifier)
    }

    // -----------------------------------------------------------------------
    // Full pull — new records
    // -----------------------------------------------------------------------

    @Test
    fun fullPull_insertsNewRecordFromServer() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()

        store.lists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = null)
        network.listsResponse = listOf(remoteList("RL1"))
        network.recordsResponse["RL1"] = listOf(remoteRecord("RT1", "My Task"))

        engine(store, network).sync()

        val inserted = store.records.values.firstOrNull { it.content.title == "My Task" }
        assertNotNull(inserted, "Record should be inserted from server")
        assertEquals("RT1", inserted.remoteId)
        assertFalse(inserted.isCompleted)
    }

    @Test
    fun fullPull_deletesLocalRecordAbsentFromServer() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()

        store.lists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = null)
        store.records["T1"] = localRecord("T1", "L1", remoteId = "RT1")
        network.listsResponse = listOf(remoteList("RL1"))
        network.recordsResponse["RL1"] = emptyList()

        engine(store, network).sync()

        assertNull(store.records["T1"], "Record absent from server should be hard-deleted locally")
    }

    @Test
    fun fullPull_keepsLocallyCreatedRecordNotOnServer() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()

        store.lists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = null)
        store.records["T_local"] = localRecord("T_local", "L1", remoteId = null)
        network.listsResponse = listOf(remoteList("RL1"))
        network.recordsResponse["RL1"] = emptyList()

        engine(store, network).sync()

        assertNotNull(store.records["T_local"], "Locally-created record (no remoteId) should not be deleted")
    }

    @Test
    fun fullPull_keepsRecordWithPendingOps() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()

        store.lists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = null)
        store.records["T1"] = localRecord("T1", "L1", remoteId = "RT1")
        store.pendingOps["op1"] = pendingOp("op1", "T1", "L1")
        network.listsResponse = listOf(remoteList("RL1"))
        network.recordsResponse["RL1"] = emptyList()

        engine(store, network).sync()

        assertNotNull(store.records["T1"], "Record with pending op should not be deleted during full pull")
    }

    // -----------------------------------------------------------------------
    // Incremental pull — completion status
    // -----------------------------------------------------------------------

    @Test
    fun incrementalPull_appliesRemoteCompletionToLocalRecord() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()

        store.lists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = T0)
        store.records["T1"] = localRecord("T1", "L1", remoteId = "RT1", isCompleted = false)
        network.listsResponse = listOf(remoteList("RL1"))
        network.recordsResponse["RL1"] = listOf(remoteRecord("RT1", isCompleted = true))

        engine(store, network).sync()

        assertTrue(store.records["T1"]!!.isCompleted, "Record completed on server should be marked completed locally")
    }

    @Test
    fun incrementalPull_doesNotOverwriteRecordWithPendingOps() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()

        store.lists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = T0)
        store.records["T1"] = localRecord("T1", "L1", remoteId = "RT1", isCompleted = false)
        store.pendingOps["op1"] = pendingOp("op1", "T1", "L1", OpType.UPDATE_RECORD)
        network.listsResponse = listOf(remoteList("RL1"))
        network.recordsResponse["RL1"] = listOf(remoteRecord("RT1", isCompleted = true))

        engine(store, network).sync()

        assertFalse(store.records["T1"]!!.isCompleted,
            "Record with pending op should not be overwritten by remote state (local wins)")
    }

    @Test
    fun incrementalPull_hardDeletesRecordMarkedDeletedOnServer() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()

        store.lists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = T0)
        store.records["T1"] = localRecord("T1", "L1", remoteId = "RT1")
        network.listsResponse = listOf(remoteList("RL1"))
        network.recordsResponse["RL1"] = listOf(remoteRecord("RT1", isDeleted = true))

        engine(store, network).sync()

        assertNull(store.records["T1"], "Record with isDeleted=true from server should be hard-deleted locally")
    }

    // -----------------------------------------------------------------------
    // updatedMin — 60-second buffer
    // -----------------------------------------------------------------------

    @Test
    fun incrementalPull_updatedMinIs60SecondsBefore_lastSyncedAt() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()
        val lastSynced = 1_800_000L  // 30 minutes in ms

        store.lists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = lastSynced)
        store.records["T1"] = localRecord("T1", "L1", remoteId = "RT1")
        network.listsResponse = listOf(remoteList("RL1"))
        network.recordsResponse["RL1"] = emptyList()

        engine(store, network).sync()

        assertEquals(lastSynced - 60_000L, network.updatedMinCapture["RL1"],
            "updatedMin should be lastSyncedAt minus 60 seconds to close the race window")
    }

    @Test
    fun fullPull_sendsNullUpdatedMin_onFirstSync() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()

        store.lists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = null)
        network.listsResponse = listOf(remoteList("RL1"))
        network.recordsResponse["RL1"] = emptyList()

        engine(store, network).sync()

        assertNull(network.updatedMinCapture["RL1"], "First sync should use full pull (null updatedMin)")
    }

    @Test
    fun newList_firstSyncUsesFullPull() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()

        network.listsResponse = listOf(remoteList("RL1"))
        network.recordsResponse["RL1"] = listOf(remoteRecord("RT1", "My Task"))

        engine(store, network).sync()

        assertNull(network.updatedMinCapture["RL1"], "First sync for a new list should use full pull")
        assertNotNull(store.records.values.firstOrNull { it.content.title == "My Task" },
            "Records from full pull should be inserted")
    }

    @Test
    fun listWithNoLocalRecords_usesFullPullDespiteLastSyncedAt() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()

        store.lists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = T0)
        network.listsResponse = listOf(remoteList("RL1"))
        network.recordsResponse["RL1"] = listOf(remoteRecord("RT1", "Recovered Task"))

        engine(store, network).sync()

        assertNull(network.updatedMinCapture["RL1"],
            "List with lastSyncedAt set but no local records should use full pull")
        assertNotNull(store.records.values.firstOrNull { it.content.title == "Recovered Task" },
            "Records from recovery full pull should be inserted")
    }

    // -----------------------------------------------------------------------
    // Cross-list record moves (server moves record from one list to another)
    // -----------------------------------------------------------------------

    @Test
    fun crossListMove_fullPull_sourceFirst_recordReassignedNotDeleted() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()

        store.lists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = null)
        store.lists["L2"] = localList("L2", remoteId = "RL2", lastSyncedAt = null)
        store.records["T1"] = localRecord("T1", listLocalId = "L1", remoteId = "RT1")

        network.listsResponse = listOf(remoteList("RL1"), remoteList("RL2"))
        network.recordsResponse["RL1"] = emptyList()
        network.recordsResponse["RL2"] = listOf(remoteRecord("RT1"))

        engine(store, network).sync()

        val record = store.records["T1"]
        assertNotNull(record, "Record moved to another list should not be deleted")
        assertEquals("L2", record.listLocalId, "Record should be reassigned to the destination list")
    }

    @Test
    fun crossListMove_incrementalPull_recordAppearsInDestination_reassigned() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()

        store.lists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = T0)
        store.lists["L2"] = localList("L2", remoteId = "RL2", lastSyncedAt = T0)
        store.records["T1"] = localRecord("T1", listLocalId = "L1", remoteId = "RT1")
        store.records["T2"] = localRecord("T2", listLocalId = "L2", remoteId = "RT2")

        network.listsResponse = listOf(remoteList("RL1"), remoteList("RL2"))
        network.recordsResponse["RL1"] = emptyList()
        network.recordsResponse["RL2"] = listOf(remoteRecord("RT2"), remoteRecord("RT1"))

        engine(store, network).sync()

        val record = store.records["T1"]
        assertNotNull(record, "Record should exist after incremental reassignment")
        assertEquals("L2", record.listLocalId, "Record should be reassigned to the destination list")
    }

    @Test
    fun crossListMove_isDeletedFromSourceList_doesNotDeleteReassignedRecord() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()

        store.lists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = T0)
        store.lists["L2"] = localList("L2", remoteId = "RL2", lastSyncedAt = T0)
        store.records["T1"] = localRecord("T1", listLocalId = "L1", remoteId = "RT1")
        store.records["T2"] = localRecord("T2", listLocalId = "L2", remoteId = "RT2")

        network.listsResponse = listOf(remoteList("RL2"), remoteList("RL1"))
        network.recordsResponse["RL2"] = listOf(remoteRecord("RT2"), remoteRecord("RT1"))
        network.recordsResponse["RL1"] = listOf(remoteRecord("RT1", isDeleted = true))

        engine(store, network).sync()

        val record = store.records["T1"]
        assertNotNull(record, "Record should not be deleted by isDeleted from the source list")
        assertEquals("L2", record.listLocalId, "Record should remain in the destination list")
    }

    // -----------------------------------------------------------------------
    // Full pull — completed record preservation
    // -----------------------------------------------------------------------

    @Test
    fun fullPull_keepsLocallyCompletedRecord() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()

        store.lists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = null)
        store.records["T_completed"] = localRecord("T_completed", "L1", remoteId = "RT_completed", isCompleted = true)
        store.records["T_active"] = localRecord("T_active", "L1", remoteId = "RT_active")
        network.listsResponse = listOf(remoteList("RL1"))
        // Full pull returns only the active record — completed one excluded, source-side.
        network.recordsResponse["RL1"] = listOf(remoteRecord("RT_active"))

        engine(store, network).sync()

        assertNotNull(store.records["T_completed"],
            "Completed record must not be deleted by full pull (absent for a reason other than deletion)")
        assertNotNull(store.records["T_active"], "Active record should still be present")
    }

    // -----------------------------------------------------------------------
    // Zombie list detection
    // -----------------------------------------------------------------------

    @Test
    fun zombieList_isDeletedLocallyWhenAbsentFromServer() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()

        store.lists["L_default"] = localList("L_default", remoteId = "RL_default")
        store.lists["L_zombie"] = localList("L_zombie", remoteId = "RL_zombie")

        network.listsResponse = listOf(remoteList("RL_default"))
        network.recordsResponse["RL_default"] = emptyList()

        engine(store, network).sync()

        assertNull(store.lists["L_zombie"], "Local list absent from server response should be hard-deleted")
    }

    @Test
    fun zombieList_locallyCreatedRecordIsReassignedToDefaultList() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()

        store.lists["L_default"] = localList("L_default", remoteId = "RL_default")
        store.lists["L_zombie"] = localList("L_zombie", remoteId = "RL_zombie")
        store.records["T_local"] = localRecord("T_local", "L_zombie", remoteId = null)

        network.listsResponse = listOf(remoteList("RL_default"))
        network.recordsResponse["RL_default"] = emptyList()

        engine(store, network).sync()

        val reassigned = store.records["T_local"]
        assertNotNull(reassigned, "Locally-created record should survive zombie list deletion")
        assertEquals("L_default", reassigned.listLocalId,
            "Locally-created record should be reassigned to the default list")
    }

    @Test
    fun zombieList_syncedRecordIsDeletedWithList() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()

        store.lists["L_default"] = localList("L_default", remoteId = "RL_default")
        store.lists["L_zombie"] = localList("L_zombie", remoteId = "RL_zombie")
        store.records["T_synced"] = localRecord("T_synced", "L_zombie", remoteId = "RT_synced")

        network.listsResponse = listOf(remoteList("RL_default"))
        network.recordsResponse["RL_default"] = emptyList()

        engine(store, network).sync()

        assertNull(store.records["T_synced"], "Synced record in zombie list should be hard-deleted with the list")
    }

    @Test
    fun zombieList_pendingOpsForReassignedRecordPointToNewList() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()

        store.lists["L_default"] = localList("L_default", remoteId = "RL_default")
        store.lists["L_zombie"] = localList("L_zombie", remoteId = "RL_zombie")
        store.records["T_local"] = localRecord("T_local", "L_zombie", remoteId = null)
        store.pendingOps["op1"] = pendingOp("op1", "T_local", "L_zombie", OpType.CREATE_RECORD)

        network.listsResponse = listOf(remoteList("RL_default"))
        network.recordsResponse["RL_default"] = emptyList()
        network.failingListIds.add("RL_zombie")

        engine(store, network).sync()

        val op = store.pendingOps.values.firstOrNull { it.entityLocalId == "T_local" }
        assertNotNull(op, "CREATE op for reassigned record should still exist")
        assertEquals("L_default", op.listLocalId,
            "Pending op's listLocalId should be updated to the new list after reassignment")
    }

    // -----------------------------------------------------------------------
    // Concurrency — writeMutex
    // -----------------------------------------------------------------------

    @Test
    fun sync_serializesConcurrentCalls() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()
        store.lists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = null)
        network.listsResponse = listOf(remoteList("RL1"))
        network.recordsResponse["RL1"] = emptyList()

        var inFlight = 0
        var maxInFlight = 0
        network.onGetLists = {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            delay(50)
            inFlight--
        }

        val syncEngine = engine(store, network)
        val job1 = launch { syncEngine.sync() }
        val job2 = launch { syncEngine.sync() }
        job1.join()
        job2.join()

        assertEquals(1, maxInFlight, "Two concurrent sync() calls should never run their bodies at the same time")
    }
}
