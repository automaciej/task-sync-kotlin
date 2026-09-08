package pl.blizinski.tasksync.store

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import pl.blizinski.tasksync.InMemoryLocalStore
import pl.blizinski.tasksync.LocalStore
import pl.blizinski.tasksync.NetworkSource
import pl.blizinski.tasksync.PendingOpsProcessor
import pl.blizinski.tasksync.RemoteListRecord
import pl.blizinski.tasksync.RemoteRecord
import pl.blizinski.tasksync.SyncEngine
import pl.blizinski.tasksync.SyncErrorClassifier
import pl.blizinski.tasksync.SyncErrorKind
import pl.blizinski.tasksync.SyncedListRecord
import pl.blizinski.tasksync.SyncedRecord
import pl.blizinski.tasksync.model.RecurrenceRule
import pl.blizinski.tasksync.model.RecurrenceStyle
import pl.blizinski.tasksync.model.StoreCapabilities
import pl.blizinski.tasksync.model.StoreConfig
import pl.blizinski.tasksync.model.TaskDraft
import pl.blizinski.tasksync.model.TaskList
import pl.blizinski.tasksync.model.Task
import pl.blizinski.tasksync.model.TaskRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers the wiring [DefaultTaskStore] extracted from the four hand-written provider stores. */
class DefaultTaskStoreTest {

    @Serializable
    private data class TC(
        val title: String,
        val notes: String? = null,
        val dueDate: Long? = null,
        val completedDate: Long? = null,
        val recur: String? = null,
    )

    @Serializable
    private data class LC(val title: String)

    private class TestAdapter : ContentAdapter<TC, LC> {
        override fun toTask(record: SyncedRecord<TC>) = Task(
            id = TaskRef(record.localId, record.remoteId),
            listId = record.listLocalId,
            title = record.content.title,
            notes = record.content.notes,
            isCompleted = record.isCompleted,
            dueDate = record.content.dueDate,
            completedDate = record.content.completedDate,
            recurrenceRule = record.content.recur?.let { RecurrenceRule.TextRule(it) },
        )

        override fun toTaskList(list: SyncedListRecord<LC>) = TaskList(list.localId, list.content.title)

        override fun newContent(draft: TaskDraft, now: Long) = TC(
            title = draft.title, notes = draft.notes, dueDate = draft.dueDate,
            recur = (draft.recurrenceRule as? RecurrenceRule.TextRule)?.text,
        )

        override fun applyDraft(existing: TC, draft: TaskDraft) = existing.copy(
            title = draft.title, notes = draft.notes, dueDate = draft.dueDate,
            recur = (draft.recurrenceRule as? RecurrenceRule.TextRule)?.text,
        )

        override fun applyCompletion(existing: TC, completed: Boolean, at: Long?) = existing.copy(completedDate = at)

        override fun newListContent(title: String) = LC(title)

        override fun applyListTitle(existing: LC, title: String) = LC(title)
    }

    private class RecordingScheduler : StoreSyncScheduler {
        var started = 0
        var writes = 0
        var cancelled = 0
        override fun start() { started++ }
        override fun onLocalWrite() { writes++ }
        override fun cancel() { cancelled++ }
    }

    private class NoopNet(var failGetLists: Exception? = null) : NetworkSource<TC, LC> {
        override suspend fun getLists(): List<RemoteListRecord<LC>> {
            failGetLists?.let { throw it }
            return emptyList()
        }
        override suspend fun createList(content: LC) = RemoteListRecord("r", content)
        override suspend fun updateList(remoteListId: String, content: LC) = Unit
        override suspend fun deleteList(remoteListId: String) = Unit
        override suspend fun getRecords(remoteListId: String, updatedMin: Long?) = emptyList<RemoteRecord<TC>>()
        override suspend fun createRecord(remoteListId: String, content: TC) = RemoteRecord("r", content)
        override suspend fun updateRecord(remoteListId: String, remoteId: String, content: TC) = Unit
        override suspend fun completeRecord(remoteListId: String, remoteId: String) = Unit
        override suspend fun uncompleteRecord(remoteListId: String, remoteId: String) = Unit
        override suspend fun deleteRecord(remoteListId: String, remoteId: String) = Unit
    }

    private class NoopClassifier : SyncErrorClassifier {
        override fun classifySpecial(e: Exception): SyncErrorKind? = null
        override fun httpStatus(e: Exception): Int? = null
        override fun extractConsentIntent(e: Exception): Any? = null
    }

    private fun caps(listCreation: Boolean = true, nativeMove: Boolean = true) = StoreCapabilities(
        supportsDueTime = true, supportsPriority = true, supportsLabels = true,
        supportsManualOrdering = false, supportsSubtasks = false, supportsMultipleLists = true,
        supportsListCreation = listCreation, supportsManualDelete = true, supportsNativeMove = nativeMove,
        recurrenceStyle = RecurrenceStyle.FUZZY,
    )

    private class Fixture(
        val store: DefaultTaskStore<TC, LC>,
        val scheduler: RecordingScheduler,
        val local: LocalStore<TC, LC>,
    )

    private fun fixture(
        capabilities: StoreCapabilities = caps(),
        local: LocalStore<TC, LC> = InMemoryLocalStore(),
        net: NoopNet = NoopNet(),
    ): Fixture {
        val scheduler = RecordingScheduler()
        val classifier = NoopClassifier()
        val processor = PendingOpsProcessor(local, net, serializer<TC>(), classifier)
        val engine = SyncEngine(local, net, processor, classifier)
        var counter = 0
        val store = DefaultTaskStore(
            store = local, syncEngine = engine, recordSerializer = serializer<TC>(),
            adapter = TestAdapter(), capabilities = capabilities, config = StoreConfig(dbName = "test"),
            closeResources = {}, now = { 1_000L }, newId = { "id-${counter++}" },
            schedulerFactory = { scheduler },
        )
        return Fixture(store, scheduler, local)
    }

    @Test
    fun start_isCalledOnConstruction() {
        assertEquals(1, fixture().scheduler.started)
    }

    @Test
    fun createTask_emitsMappedTask_enqueuesOp_pokesScheduler() = runTest {
        val f = fixture()
        val id = f.store.createTask("list-1", TaskDraft(title = "Buy milk", dueDate = 42L))

        val tasks = f.store.tasks("list-1").first()
        assertEquals(1, tasks.size)
        assertEquals("Buy milk", tasks[0].title)
        assertEquals(42L, tasks[0].dueDate)
        assertEquals(id, tasks[0].id.localId)
        assertEquals(1, f.store.syncStatus().first().pendingOpCount)
        assertEquals(1, f.scheduler.writes)
    }

    @Test
    fun updateTask_appliesDraft() = runTest {
        val f = fixture()
        val id = f.store.createTask("list-1", TaskDraft(title = "old"))
        f.store.updateTask(id, TaskDraft(title = "new", notes = "n", recurrenceRule = RecurrenceRule.TextRule("every day")))

        val task = f.store.tasks("list-1").first().single()
        assertEquals("new", task.title)
        assertEquals("n", task.notes)
        assertEquals(RecurrenceRule.TextRule("every day"), task.recurrenceRule)
    }

    @Test
    fun completeTask_setsEnvelopeFlagAndContentDate() = runTest {
        val f = fixture()
        val id = f.store.createTask("list-1", TaskDraft(title = "t"))
        f.store.completeTask(id)

        val task = f.store.tasks("list-1").first().single()
        assertTrue(task.isCompleted)
        assertEquals(1_000L, task.completedDate)

        f.store.uncompleteTask(id)
        val after = f.store.tasks("list-1").first().single()
        assertTrue(!after.isCompleted)
        assertNull(after.completedDate)
    }

    @Test
    fun moveTask_returnsNull_whenNotNativeMove() = runTest {
        val f = fixture(capabilities = caps(nativeMove = false))
        val id = f.store.createTask("list-1", TaskDraft(title = "t"))
        assertNull(f.store.moveTask(id, "list-2"))
    }

    @Test
    fun moveTask_returnsSameLocalId_andReassigns_whenNativeMove() = runTest {
        val f = fixture()
        val id = f.store.createTask("list-1", TaskDraft(title = "t"))
        val returned = f.store.moveTask(id, "list-2")
        assertEquals(id, returned)
        assertEquals(1, f.store.tasks("list-2").first().size)
        assertEquals(0, f.store.tasks("list-1").first().size)
    }

    @Test
    fun createList_throws_whenUnsupported() = runTest {
        val f = fixture(capabilities = caps(listCreation = false))
        assertFailsWith<IllegalStateException> { f.store.createList("x") }
    }

    @Test
    fun createList_succeeds_whenSupported() = runTest {
        val f = fixture()
        val listId = f.store.createList("Groceries")
        val lists = f.store.taskLists().first()
        assertEquals(1, lists.size)
        assertEquals("Groceries", lists[0].title)
        assertEquals(listId, lists[0].id)
    }

    @Test
    fun forceSync_setsLastSyncedAt() = runTest {
        val f = fixture()
        f.store.forceSync()
        assertEquals(1_000L, f.store.syncStatus().first().lastSyncedAt)
    }

    @Test
    fun forceSync_networkError_isCapturedInRecentErrors() = runTest {
        val f = fixture(net = NoopNet(failGetLists = IllegalStateException("boom")))
        f.store.forceSync()
        val errors = f.store.syncStatus().first().recentErrors
        assertEquals(1, errors.size)
        assertEquals(SyncErrorKind.PULL_FAILED, errors[0].kind)
        assertNull(errors[0].taskLocalId)
    }

    @Test
    fun readFlowFailure_reportsFatalStorageError_andEmitsDefault() = runTest {
        val throwing = object : LocalStore<TC, LC> by InMemoryLocalStore() {
            override fun records(listLocalId: String): Flow<List<SyncedRecord<TC>>> =
                flow { throw RuntimeException("disk gone") }
        }
        val f = fixture(local = throwing)
        assertEquals(emptyList(), f.store.tasks("list-1").first())
        assertNotNull(f.store.syncStatus().first().fatalStorageError)
    }

    @Test
    fun close_cancelsScheduler() {
        val f = fixture()
        f.store.close()
        assertEquals(1, f.scheduler.cancelled)
    }
}
