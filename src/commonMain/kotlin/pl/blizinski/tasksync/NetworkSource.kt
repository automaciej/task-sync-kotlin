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
    /**
     * Moves [remoteId] from [sourceRemoteListId] to [destRemoteListId] in place, preserving the
     * record's identity. Defaults to unsupported — a source without a native cross-list move
     * should simply never enqueue [OpType.MOVE_RECORD] (its caller falls back to
     * create-in-new-list + delete-old-list instead), so this default is never reached in
     * practice; it exists so implementations without a native move need no changes here.
     *
     * [previousRemoteId], if non-null, is the remoteId of the sibling the moved record should
     * land immediately after in [destRemoteListId] — used by [PendingOpsProcessor] to chain a
     * batch of moves into the destination in the same relative order they were moved in,
     * instead of each one landing at the top and reversing the batch's order. Null means "no
     * ordering constraint" (implementations may place it wherever their API defaults to).
     */
    suspend fun moveRecord(
        sourceRemoteListId: String,
        remoteId: String,
        destRemoteListId: String,
        previousRemoteId: String? = null,
    ): Unit = throw UnsupportedOperationException("This source does not support moving records between lists")
}
