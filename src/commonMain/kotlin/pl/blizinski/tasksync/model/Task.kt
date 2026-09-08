package pl.blizinski.tasksync.model

/**
 * The public task model surfaced by a [pl.blizinski.tasksync.store.TaskStore] — the **superset**
 * of every provider's task shape. A common core plus fields only some providers populate; every
 * provider-specific field is nullable or defaulted.
 *
 * A null / empty value here **never** asserts "this source does not support the feature" — that
 * is [StoreCapabilities]' job. A source simply leaves what it does not carry at its default.
 * This replaces the four separate `models.Task` classes each provider library used to declare
 * with a "kept for shape parity with the other source libraries" comment.
 */
data class Task(
    val id: TaskRef,
    val listId: String,
    val title: String,
    val notes: String? = null,
    val isCompleted: Boolean = false,
    /** Epoch milliseconds. */
    val createdDate: Long? = null,
    /** Due date as epoch milliseconds. Date-only sources (Google Tasks) use midnight UTC; see
     *  [dueHasTime]. Null means no due date. */
    val dueDate: Long? = null,
    /** True when [dueDate] carries a meaningful time-of-day, not just a calendar date. Always
     *  false for date-only sources. */
    val dueHasTime: Boolean = false,
    /** Completion date as epoch milliseconds. Null if not completed. */
    val completedDate: Long? = null,
    /** Source-native priority; meaning is source-specific and not normalised (Todoist 1–4,
     *  Microsoft 0–2). Null when the source has no priority concept. */
    val priority: Int? = null,
    /** Source-native labels / categories. Empty when unsupported. */
    val labels: List<String> = emptyList(),
    /** True when this task has a parent task (Google Tasks / Todoist). */
    val isSubtask: Boolean = false,
    /** Lexicographic sibling position (Google Tasks). Null when the source has no manual order. */
    val position: String? = null,
    /** True when the task is hidden (Google Tasks: completed when the list was last cleared). */
    val isHidden: Boolean = false,
    /** Absolute link to the task in the source's web UI (Google Tasks). Null otherwise. */
    val webViewLink: String? = null,
    /** Links back to resources this task was created from (Google Tasks: Gmail message, Doc). */
    val links: List<TaskLink> = emptyList(),
    /** Recurrence rule in this source's native style — see [RecurrenceRule]. Null if not
     *  recurring or the source has no recurrence concept. */
    val recurrenceRule: RecurrenceRule? = null,
)
