package pl.blizinski.tasksync.model

/**
 * A task list / project / repository, as surfaced by a [pl.blizinski.tasksync.store.TaskStore].
 * [id] is the list's local id (matches [pl.blizinski.tasksync.SyncedListRecord.localId]); what a
 * "list" means is source-specific (a Google Tasks list, a Todoist project, a GitHub repo).
 */
data class TaskList(
    val id: String,
    val title: String,
)
