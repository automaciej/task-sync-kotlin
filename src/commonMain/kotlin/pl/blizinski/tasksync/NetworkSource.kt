package pl.blizinski.tasksync

/**
 * Abstracts a source's remote REST API. This is the *only* place in a source library that
 * knows [T]/[TList]'s shape — [SyncEngine] and [PendingOpsProcessor] never construct or
 * inspect them beyond passing them through.
 *
 * [updatedMin] (epoch ms; null = full pull) is a plain timestamp — implementations convert to
 * whatever date format their own API expects (e.g. RFC 3339 for Google Tasks), so no
 * date-format-specific code needs to live above this interface.
 */
interface NetworkSource<T, TList> {
    suspend fun getLists(): List<RemoteListRecord<TList>>
    suspend fun createList(content: TList): RemoteListRecord<TList>
    suspend fun updateList(remoteListId: String, content: TList)
    suspend fun deleteList(remoteListId: String)

    suspend fun getRecords(remoteListId: String, updatedMin: Long?): List<RemoteRecord<T>>
    suspend fun createRecord(remoteListId: String, content: T): RemoteRecord<T>
    suspend fun updateRecord(remoteListId: String, remoteId: String, content: T)
    suspend fun completeRecord(remoteListId: String, remoteId: String)
    /** Symmetric with [completeRecord] — [isCompleted] is a generic envelope field ([SyncedRecord]),
     * not part of opaque [T], so reverting completion needs its own method rather than an
     * update-with-side-parameter hack. */
    suspend fun uncompleteRecord(remoteListId: String, remoteId: String)
    suspend fun deleteRecord(remoteListId: String, remoteId: String)
}
