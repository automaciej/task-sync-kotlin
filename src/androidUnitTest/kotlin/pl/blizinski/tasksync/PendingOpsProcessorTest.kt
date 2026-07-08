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
