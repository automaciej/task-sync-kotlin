package pl.blizinski.tasksync

enum class OpType {
    CREATE_RECORD,
    UPDATE_RECORD,
    COMPLETE_RECORD,
    DELETE_RECORD,
    CREATE_LIST,
    UPDATE_LIST,
    DELETE_LIST,
}

enum class OpStatus { PENDING, FAILED }

/**
 * A queued local mutation awaiting push to the server.
 *
 * [contentJson] carries the *full* new content at the time of the edit (not a per-field diff)
 * for [OpType.CREATE_RECORD]/[OpType.UPDATE_RECORD] — serialized via the source's own
 * `KSerializer<T>`. List ops (CREATE_LIST/UPDATE_LIST) don't need it: their content is read
 * directly from the current local list row by [entityLocalId] when flushed. DELETE_LIST is the
 * one exception — it repurposes [contentJson] to hold the bare remoteId string, preserving it
 * across the list's local removal. COMPLETE_RECORD/DELETE_RECORD need no content at all.
 */
data class PendingOp(
    val id: String,
    val type: OpType,
    val entityLocalId: String,
    val listLocalId: String,
    val contentJson: String? = null,
    val createdAt: Long,
    val attemptCount: Int = 0,
    val status: OpStatus = OpStatus.PENDING,
)

internal fun PendingOp.isListOp() =
    type == OpType.CREATE_LIST || type == OpType.UPDATE_LIST || type == OpType.DELETE_LIST
