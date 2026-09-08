package pl.blizinski.tasksync.store

import kotlinx.coroutines.flow.Flow
import pl.blizinski.tasksync.model.StoreCapabilities
import pl.blizinski.tasksync.model.SyncStatus
import pl.blizinski.tasksync.model.Task
import pl.blizinski.tasksync.model.TaskDraft
import pl.blizinski.tasksync.model.TaskList

/**
 * The single public contract every provider library exposes, replacing the four near-identical
 * `*StoreApi` interfaces (`TaskStoreApi`, `TodoistStoreApi`, `GitHubIssuesStoreApi`,
 * `MicrosoftToDoStoreApi`). Always scoped to exactly one connected account; ids are plain
 * source-local strings.
 *
 * Build one with [buildAndroidTaskStore] (Room-backed, with background polling) or
 * [buildWasmTaskStore] (IndexedDB-backed, sync-on-demand).
 *
 * Reads always come from the local cache; writes are optimistic (applied locally, then synced in
 * the background) — no manual refresh needed after a write.
 */
interface TaskStore : AutoCloseable {

    /** What this source's accounts can do — a consumer gates UI on these, never on "which
     *  provider is this". */
    val capabilities: StoreCapabilities

    // --- Read ---

    fun taskLists(): Flow<List<TaskList>>

    /** [listLocalId] is a [TaskList.id] from [taskLists]. */
    fun tasks(listLocalId: String): Flow<List<Task>>

    fun syncStatus(): Flow<SyncStatus>

    // --- Write (optimistic) ---

    /** Creates a task, returns its stable local id. */
    suspend fun createTask(listLocalId: String, draft: TaskDraft): String

    /** Applies [draft] to an existing task. Fields the source cannot represent are ignored. */
    suspend fun updateTask(localId: String, draft: TaskDraft)

    suspend fun completeTask(localId: String)

    suspend fun uncompleteTask(localId: String)

    suspend fun deleteTask(localId: String)

    /**
     * Moves a task to [destListLocalId] in place, returning its (unchanged) local id. Returns
     * null when [StoreCapabilities.supportsNativeMove] is false — the caller must then fall back
     * to create-in-new-list + delete-old, which does change the task's id.
     */
    suspend fun moveTask(localId: String, destListLocalId: String): String?

    /** Throws [IllegalStateException] when [StoreCapabilities.supportsListCreation] is false. */
    suspend fun createList(title: String): String

    suspend fun updateList(localId: String, title: String)

    suspend fun deleteList(localId: String)

    // --- Lifecycle ---

    /** Runs a full sync cycle synchronously (flush pending ops, then incremental pull). */
    suspend fun forceSync()

    /** Like [forceSync] but pulls every list from scratch instead of using stored cursors. */
    suspend fun fullSync()

    /** Cancels background work and releases resources (closes the database). */
    override fun close()
}
