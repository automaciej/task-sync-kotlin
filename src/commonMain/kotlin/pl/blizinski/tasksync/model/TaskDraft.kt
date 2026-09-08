package pl.blizinski.tasksync.model

/**
 * The write payload for [pl.blizinski.tasksync.store.TaskStore.createTask] /
 * [pl.blizinski.tasksync.store.TaskStore.updateTask] — the superset of every provider's
 * create/update parameters, replacing the four divergent parameter lists the `*StoreApi`
 * interfaces each grew.
 *
 * A [pl.blizinski.tasksync.store.ContentAdapter] decides what to keep: a date-only source
 * truncates [dueDate] and ignores [dueHasTime]; a source with no recurrence concept ignores
 * [recurrenceRule]; and so on — "accept and ignore what this source cannot represent".
 */
data class TaskDraft(
    val title: String,
    val notes: String? = null,
    val dueDate: Long? = null,
    val dueHasTime: Boolean = false,
    val priority: Int? = null,
    val labels: List<String> = emptyList(),
    val recurrenceRule: RecurrenceRule? = null,
)
