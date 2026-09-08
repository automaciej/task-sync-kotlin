package pl.blizinski.tasksync.store

import pl.blizinski.tasksync.SyncedListRecord
import pl.blizinski.tasksync.SyncedRecord
import pl.blizinski.tasksync.model.Task
import pl.blizinski.tasksync.model.TaskDraft
import pl.blizinski.tasksync.model.TaskList

/**
 * The whole per-provider surface [DefaultTaskStore] needs: how to map this source's opaque
 * content type [T]/[TList] to and from the public [Task]/[TaskList], and how to fold a
 * [TaskDraft] into [T]. A provider library supplies one of these plus its [NetworkSource],
 * its error classifier, and its [StoreCapabilities] — everything else is shared.
 *
 * "Accept and ignore what this source cannot represent" lives here: e.g. a date-only source's
 * [newContent]/[applyDraft] truncates a time-of-day [TaskDraft.dueDate] and drops
 * [TaskDraft.recurrenceRule]; GitHub Issues drops [TaskDraft.dueDate] entirely. Keying that off
 * the provider's own wire knowledge is clearer than a capability `when` inside the store.
 */
interface ContentAdapter<T, TList> {

    fun toTask(record: SyncedRecord<T>): Task

    fun toTaskList(list: SyncedListRecord<TList>): TaskList

    /** Builds fresh content for a new task. [now] is epoch ms, for a `createdDate`-style field. */
    fun newContent(draft: TaskDraft, now: Long): T

    /** Returns [existing] with [draft]'s representable fields applied. */
    fun applyDraft(existing: T, draft: TaskDraft): T

    /**
     * Returns [existing] with completion state reflected in content (e.g. a `completedDate`
     * field). [at] is the completion time (epoch ms) when [completed] is true, null otherwise.
     * The generic `isCompleted` envelope flag is handled by the store; this is only for content
     * types that also mirror it.
     */
    fun applyCompletion(existing: T, completed: Boolean, at: Long?): T

    fun newListContent(title: String): TList

    fun applyListTitle(existing: TList, title: String): TList
}
