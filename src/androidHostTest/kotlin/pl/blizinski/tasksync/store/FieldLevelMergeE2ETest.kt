package pl.blizinski.tasksync.store

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import pl.blizinski.tasksync.InMemoryLocalStore
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
import pl.blizinski.tasksync.model.Task
import pl.blizinski.tasksync.model.TaskDraft
import pl.blizinski.tasksync.model.TaskList
import pl.blizinski.tasksync.model.TaskRef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end reproduction of the hand-test that motivated the field-level merge: on one device,
 * offline, edit a task's title; meanwhile the server copy's notes change; reconnect and sync.
 * Both edits must survive. Runs a real [DefaultTaskStore] + [SyncEngine] + [PendingOpsProcessor]
 * with a provider-style [contentMerger] against an in-memory fake server.
 */
class FieldLevelMergeE2ETest {

    @Serializable
    private data class TC(
        val title: String,
        val notes: String? = null,
        val dueDate: Long? = null,
        val completedDate: Long? = null,
    )

    @Serializable
    private data class LC(val title: String)

    private val merger = contentMerger(emptyBase = TC(title = "")) {
        remote.copy(
            title = pick { it.title },
            notes = pick { it.notes },
            dueDate = pick { it.dueDate },
            completedDate = pick { it.completedDate },
        )
    }

    private class TestAdapter : ContentAdapter<TC, LC> {
        override fun toTask(record: SyncedRecord<TC>) = Task(
            id = TaskRef(record.localId, record.remoteId),
            listId = record.listLocalId,
            title = record.content.title,
            notes = record.content.notes,
            isCompleted = record.isCompleted,
            dueDate = record.content.dueDate,
            completedDate = record.content.completedDate,
        )
        override fun toTaskList(list: SyncedListRecord<LC>) = TaskList(list.localId, list.content.title)
        override fun newContent(draft: TaskDraft, now: Long) =
            TC(title = draft.title, notes = draft.notes, dueDate = draft.dueDate)
        override fun applyDraft(existing: TC, draft: TaskDraft) =
            existing.copy(title = draft.title, notes = draft.notes, dueDate = draft.dueDate)
        override fun applyCompletion(existing: TC, completed: Boolean, at: Long?) = existing.copy(completedDate = at)
        override fun newListContent(title: String) = LC(title)
        override fun applyListTitle(existing: LC, title: String) = LC(title)
    }

    private class NoopClassifier : SyncErrorClassifier {
        override fun classifySpecial(e: Exception): SyncErrorKind? = null
        override fun httpStatus(e: Exception): Int? = null
        override fun extractConsentIntent(e: Exception): Any? = null
    }

    private class NoopScheduler : StoreSyncScheduler {
        override fun start() {}
        override fun onLocalWrite() {}
        override fun cancel() {}
    }

    /** In-memory fake server: one list, a mutable record map, records every updateRecord call. */
    private class FakeServer : NetworkSource<TC, LC> {
        val listId = "RL1"
        val records = mutableMapOf<String, RemoteRecord<TC>>()
        val updateCalls = mutableListOf<TC>()
        private var counter = 0

        override suspend fun getLists() = listOf(RemoteListRecord(listId, LC("List")))
        override suspend fun createList(content: LC) = RemoteListRecord("new-list", content)
        override suspend fun updateList(remoteListId: String, content: LC) = Unit
        override suspend fun deleteList(remoteListId: String) = Unit

        override suspend fun getRecords(remoteListId: String, updatedMin: Long?) = records.values.toList()

        override suspend fun createRecord(remoteListId: String, content: TC): RemoteRecord<TC> {
            val id = "R${counter++}"
            val rec = RemoteRecord(remoteId = id, content = content, remoteUpdatedAt = 1L)
            records[id] = rec
            return rec
        }

        override suspend fun updateRecord(remoteListId: String, remoteId: String, content: TC) {
            updateCalls += content
            records[remoteId] = records.getValue(remoteId).copy(content = content)
        }

        override suspend fun completeRecord(remoteListId: String, remoteId: String) = Unit
        override suspend fun uncompleteRecord(remoteListId: String, remoteId: String) = Unit
        override suspend fun deleteRecord(remoteListId: String, remoteId: String) { records.remove(remoteId) }
    }

    private fun buildStore(
        server: FakeServer,
        local: InMemoryLocalStore<TC, LC> = InMemoryLocalStore(),
    ): DefaultTaskStore<TC, LC> {
        val classifier = NoopClassifier()
        val processor = PendingOpsProcessor(local, server, serializer<TC>(), classifier, pushLatestEntityContent = true)
        val engine = SyncEngine(local, server, processor, classifier, merger = merger)
        return DefaultTaskStore(
            store = local, syncEngine = engine, recordSerializer = serializer<TC>(),
            adapter = TestAdapter(),
            capabilities = StoreCapabilities(
                supportsDueTime = true, supportsPriority = false, supportsLabels = false,
                supportsManualOrdering = false, supportsSubtasks = false, supportsMultipleLists = true,
                supportsListCreation = false, supportsManualDelete = true, supportsNativeMove = false,
                recurrenceStyle = RecurrenceStyle.NONE,
            ),
            config = StoreConfig(dbName = "test"),
            closeResources = {}, now = { 1_000L }, newId = { "local-id" },
            schedulerFactory = { NoopScheduler() },
        )
    }

    private suspend fun DefaultTaskStore<TC, LC>.listLocalId(): String =
        taskLists().first().single().id

    @Test
    fun offlineTitleEdit_plus_concurrentRemoteNotesEdit_bothSurvive() = runTest {
        val server = FakeServer()
        server.records["R0"] = RemoteRecord("R0", TC(title = "Original", notes = "Original notes"), remoteUpdatedAt = 10L)
        val store = buildStore(server)

        // Cycle 1: first sync brings the task down and records the merge base.
        store.forceSync()
        val listId = store.listLocalId()
        val local1 = store.tasks(listId).first().single()
        assertEquals("Original", local1.title)
        assertEquals("Original notes", local1.notes)

        // Offline: edit the title on this device. The editor re-submits the current notes.
        store.updateTask(local1.id.localId, TaskDraft(title = "Edited on phone", notes = "Original notes"))

        // Meanwhile the server copy's notes change (edited on the web).
        server.records["R0"] = server.records.getValue("R0").copy(
            content = TC(title = "Original", notes = "Edited on web"),
            remoteUpdatedAt = 20L,
        )

        // Cycle 2: reconnect and sync.
        store.forceSync()

        val merged = store.tasks(listId).first().single()
        assertEquals("Edited on phone", merged.title, "local title edit must survive")
        assertEquals("Edited on web", merged.notes, "concurrent remote notes edit must survive")

        assertEquals(
            TC(title = "Edited on phone", notes = "Edited on web"),
            server.updateCalls.last(),
            "the merged record, not the stale local snapshot, is pushed to the server",
        )
        assertEquals(
            TC(title = "Edited on phone", notes = "Edited on web"),
            server.records.getValue("R0").content,
            "server ends up with both edits",
        )
    }

    @Test
    fun nullMergeBase_offlineTitleEdit_plus_concurrentRemoteNotesEdit() = runTest {
        // Simulates a task whose lastSyncedContent was never populated (created by an app build
        // from before the field was written, or a locally-created record not yet pulled back).
        val server = FakeServer()
        server.records["R0"] = RemoteRecord("R0", TC(title = "Original", notes = "Edited on web"), remoteUpdatedAt = 20L)

        val local = InMemoryLocalStore<TC, LC>()
        local.upsertList(SyncedListRecord(localId = "L0", remoteId = server.listId, content = LC("List")))
        local.upsertRecord(
            SyncedRecord(
                localId = "T0", remoteId = "R0", listLocalId = "L0",
                content = TC(title = "Original", notes = "Original notes"), // clean synced state...
                lastSyncedAt = 1L,
                lastSyncedContent = null,                                   // ...but no merge base recorded
            ),
        )
        val store = buildStore(server, local)
        // Offline: edit the title. updateTask should capture a base from the pre-edit content.
        store.updateTask("T0", TaskDraft(title = "Edited on phone", notes = "Original notes"))

        store.forceSync()

        val merged = store.tasks("L0").first().single()
        assertEquals("Edited on phone", merged.title, "local title edit must survive")
        assertEquals("Edited on web", merged.notes, "concurrent remote notes edit must survive (base captured at edit time)")
    }

    @Test
    fun cleanSync_backfillsMissingMergeBase_forOlderRows() = runTest {
        val server = FakeServer()
        server.records["R0"] = RemoteRecord("R0", TC(title = "Original", notes = "Original notes"), remoteUpdatedAt = 5L)

        val local = InMemoryLocalStore<TC, LC>()
        local.upsertList(SyncedListRecord(localId = "L0", remoteId = server.listId, content = LC("List")))
        local.upsertRecord(
            SyncedRecord(
                localId = "T0", remoteId = "R0", listLocalId = "L0",
                content = TC(title = "Original", notes = "Original notes"),
                lastSyncedAt = 1L, lastSyncedContent = null, // older row, no base
            ),
        )
        val store = buildStore(server, local)

        // A plain sync with nothing changed on either side must still record the base.
        store.forceSync()
        assertEquals(
            TC(title = "Original", notes = "Original notes"),
            local.getRecordByLocalId("T0")!!.lastSyncedContent,
            "a clean pull backfills lastSyncedContent for a row that lacked it",
        )
    }
}
