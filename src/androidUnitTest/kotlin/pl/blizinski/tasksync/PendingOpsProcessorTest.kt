package pl.blizinski.tasksync

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the "pending ops carry the full new content, not a per-field diff" design decision —
 * not directly tested in google-tasks-kotlin's original suite (only op *merging* was), but
 * central enough to this rewrite to deserve its own direct coverage.
 */
class PendingOpsProcessorTest {

    private val json = Json
    private fun contentJson(title: String) = json.encodeToString(serializer<FakeContent>(), FakeContent(title))

    private fun processor(store: FakeLocalStore, network: FakeNetworkSource) =
        PendingOpsProcessor(store, network, serializer<FakeContent>(), FakeSyncErrorClassifier())

    @Test
    fun createRecordPushesFullContentAndWritesBackRemoteId() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()
        store.lists["L1"] = localList("L1", remoteId = "RL1")
        store.records["T1"] = localRecord("T1", "L1", remoteId = null, title = "New Task")
        store.pendingOps["op1"] = PendingOp(
            id = "op1", type = OpType.CREATE_RECORD, entityLocalId = "T1", listLocalId = "L1",
            contentJson = contentJson("New Task"), createdAt = 0L,
        )

        val errors = processor(store, network).flush()

        assertTrue(errors.isEmpty())
        assertEquals("created-remote-New Task", store.records["T1"]!!.remoteId)
        assertTrue(store.pendingOps.isEmpty(), "Pending op should be removed after a successful push")
    }

    @Test
    fun updateRecordPushesFullContentToNetwork() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()
        store.lists["L1"] = localList("L1", remoteId = "RL1")
        store.records["T1"] = localRecord("T1", "L1", remoteId = "RT1", title = "Old Title")
        store.pendingOps["op1"] = PendingOp(
            id = "op1", type = OpType.UPDATE_RECORD, entityLocalId = "T1", listLocalId = "L1",
            contentJson = contentJson("New Title"), createdAt = 0L,
        )

        val errors = processor(store, network).flush()

        assertTrue(errors.isEmpty())
        assertTrue(store.pendingOps.isEmpty())
    }

    @Test
    fun moveRecordCallsNetworkWithSourceAndDestinationRemoteListIds() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()
        store.lists["L1"] = localList("L1", remoteId = "RL1")
        store.lists["L2"] = localList("L2", remoteId = "RL2")
        store.records["T1"] = localRecord("T1", "L2", remoteId = "RT1", title = "Task")
        store.pendingOps["op1"] = PendingOp(
            id = "op1", type = OpType.MOVE_RECORD, entityLocalId = "T1", listLocalId = "L2",
            contentJson = "L1", createdAt = 0L,
        )

        val errors = processor(store, network).flush()

        assertTrue(errors.isEmpty())
        assertEquals(listOf(FakeNetworkSource.MoveCall("RL1", "RT1", "RL2", previous = null)), network.moveCalls)
        assertTrue(store.pendingOps.isEmpty())
    }

    @Test
    fun batchOfMovesToSameDestinationChainsPreviousToPreserveOrder() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()
        store.lists["L1"] = localList("L1", remoteId = "RL1")
        store.lists["L2"] = localList("L2", remoteId = "RL2")
        store.records["T1"] = localRecord("T1", "L2", remoteId = "RT1", title = "Task 1")
        store.records["T2"] = localRecord("T2", "L2", remoteId = "RT2", title = "Task 2")
        store.records["T3"] = localRecord("T3", "L2", remoteId = "RT3", title = "Task 3")
        // createdAt order matches selection order: T1, T2, T3.
        store.pendingOps["op1"] = PendingOp(
            id = "op1", type = OpType.MOVE_RECORD, entityLocalId = "T1", listLocalId = "L2", contentJson = "L1", createdAt = 0L,
        )
        store.pendingOps["op2"] = PendingOp(
            id = "op2", type = OpType.MOVE_RECORD, entityLocalId = "T2", listLocalId = "L2", contentJson = "L1", createdAt = 1L,
        )
        store.pendingOps["op3"] = PendingOp(
            id = "op3", type = OpType.MOVE_RECORD, entityLocalId = "T3", listLocalId = "L2", contentJson = "L1", createdAt = 2L,
        )

        val errors = processor(store, network).flush()

        assertTrue(errors.isEmpty())
        assertEquals(
            listOf(
                FakeNetworkSource.MoveCall("RL1", "RT1", "RL2", previous = null),
                FakeNetworkSource.MoveCall("RL1", "RT2", "RL2", previous = "RT1"),
                FakeNetworkSource.MoveCall("RL1", "RT3", "RL2", previous = "RT2"),
            ),
            network.moveCalls,
            "Each move after the first should chain off the previous one moved into L2, preserving selection order",
        )
    }

    @Test
    fun moveRecordNotYetCreatedRemotelyIsSkipped() = runTest {
        // Simulates a CREATE+MOVE that merge() already folded into a single CREATE targeting
        // the destination — if a stray MOVE_RECORD op somehow still reaches flush() for a
        // record with no remoteId, it should be a no-op rather than fail.
        val store = FakeLocalStore()
        val network = FakeNetworkSource()
        store.lists["L1"] = localList("L1", remoteId = "RL1")
        store.lists["L2"] = localList("L2", remoteId = "RL2")
        store.records["T1"] = localRecord("T1", "L2", remoteId = null, title = "Task")
        store.pendingOps["op1"] = PendingOp(
            id = "op1", type = OpType.MOVE_RECORD, entityLocalId = "T1", listLocalId = "L2",
            contentJson = "L1", createdAt = 0L,
        )

        val errors = processor(store, network).flush()

        assertTrue(errors.isEmpty())
        assertTrue(network.moveCalls.isEmpty())
    }

    @Test
    fun failedPushLeavesOpPendingAndRecordsAttempt() = runTest {
        val store = FakeLocalStore()
        val network = FakeNetworkSource()
        network.failingListIds.add("RL1")
        store.lists["L1"] = localList("L1", remoteId = "RL1")
        store.records["T1"] = localRecord("T1", "L1", remoteId = null, title = "New Task")
        store.pendingOps["op1"] = PendingOp(
            id = "op1", type = OpType.CREATE_RECORD, entityLocalId = "T1", listLocalId = "L1",
            contentJson = contentJson("New Task"), createdAt = 0L,
        )

        val errors = processor(store, network).flush()

        assertEquals(1, errors.size)
        assertEquals(1, store.pendingOps["op1"]?.attemptCount)
        assertEquals(OpStatus.FAILED, store.pendingOps["op1"]?.status)
    }
}
