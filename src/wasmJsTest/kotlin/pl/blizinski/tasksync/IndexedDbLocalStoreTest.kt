package pl.blizinski.tasksync

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
private data class TestContent(val title: String)

/**
 * Runs [IndexedDbLocalStore] against a real headless-Chrome IndexedDB (see
 * `wasmJsBrowserTest`), covering exactly the behavior [RoomLocalStore]'s DAO queries already
 * guarantee via `WHERE isDeleted = 0` (see `SyncedRecordsDao.observeByList`/`getAllForList`).
 * This class exists because [IndexedDbLocalStore] filters in Kotlin instead of SQL, so nothing
 * enforces the two implementations agree — they diverged silently once before (soft-deleted
 * records stayed visible on web, e.g. a "deleted" task reappearing in Task Compass's Focus
 * comparison view) with no test catching it, since [IndexedDbLocalStore] previously had no test
 * coverage on any platform. New [LocalStore] methods should get a case here whenever their
 * Room counterpart applies a WHERE-clause filter that this store must replicate in Kotlin.
 */
@OptIn(ExperimentalUuidApi::class)
class IndexedDbLocalStoreTest {

    private fun newStore() = IndexedDbLocalStore(
        dbName = "test-${Uuid.random()}",
        recordSerializer = TestContent.serializer(),
        listSerializer = String.serializer(),
    )

    private fun record(localId: String, listLocalId: String, isDeleted: Boolean = false) = SyncedRecord(
        localId = localId,
        remoteId = null,
        listLocalId = listLocalId,
        content = TestContent(title = localId),
        isDeleted = isDeleted,
    )

    @Test
    fun recordsExcludesSoftDeletedRecords() = runTest {
        val store = newStore()
        store.upsertRecord(record("a", "list-1"))
        store.upsertRecord(record("b", "list-1"))
        store.softDeleteRecord("b")

        val visible = store.records("list-1").first()

        assertEquals(listOf("a"), visible.map { it.localId })
    }

    @Test
    fun getAllRecordsForListExcludesSoftDeletedRecords() = runTest {
        val store = newStore()
        store.upsertRecord(record("a", "list-1"))
        store.upsertRecord(record("b", "list-1"))
        store.softDeleteRecord("b")

        val visible = store.getAllRecordsForList("list-1")

        assertEquals(listOf("a"), visible.map { it.localId })
    }

    @Test
    fun recordsOnlyIncludesTheRequestedList() = runTest {
        val store = newStore()
        store.upsertRecord(record("a", "list-1"))
        store.upsertRecord(record("b", "list-2"))

        val visible = store.records("list-1").first()

        assertEquals(listOf("a"), visible.map { it.localId })
    }

    @Test
    fun softDeletedRecordIsStillReachableByLocalId() = runTest {
        // getRecordByLocalId is used by every write path (e.g. updateTask) to look up a record
        // before mutating it — it must NOT filter isDeleted, unlike records()/getAllRecordsForList.
        val store = newStore()
        store.upsertRecord(record("a", "list-1"))
        store.softDeleteRecord("a")

        val fetched = store.getRecordByLocalId("a")

        assertTrue(fetched != null && fetched.isDeleted)
    }
}
