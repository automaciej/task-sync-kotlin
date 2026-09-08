package pl.blizinski.tasksync.model

/**
 * A link from a task back to the resource it was created from (e.g. a Gmail message or a Google
 * Doc). Only Google Tasks populates this today; other sources leave [Task.links] empty. Kept in
 * the shared model so a superset [Task] can carry it without a per-provider escape hatch.
 */
data class TaskLink(
    val type: String?,
    val description: String?,
    val link: String,
)
