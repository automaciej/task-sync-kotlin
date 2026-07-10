package pl.blizinski.tasksync

enum class SyncErrorKind { PUSH_FAILED, PULL_FAILED, AUTH_FAILED, CONSENT_REQUIRED, ADVANCED_PROTECTION }

data class SyncError(
    val occurredAt: Long,
    val kind: SyncErrorKind,
    val entityLocalId: String? = null,
    val httpStatus: Int? = null,
    val message: String,
)

/**
 * Folds one sync cycle's [new] errors into [previous]'s accumulated history: a clean cycle
 * (empty [new]) clears the history (the problem it was tracking is presumably resolved),
 * otherwise [new] errors are prepended and the result capped at [max] entries. Shared by every
 * caller that turns a [SyncEngine.SyncResult] into a status update — both the foreground
 * (`forceSync()`) and background ([SyncWorker]) sync paths in a consuming source library.
 *
 * Generic over the error element type rather than fixed to [SyncError]: a consuming source
 * library (e.g. `google-tasks-kotlin`) accumulates its own already-mapped public `SyncError`
 * type here, not this module's internal one — this function only needs list semantics, never
 * inspects the element itself.
 */
fun <T> accumulateRecentErrors(previous: List<T>, new: List<T>, max: Int): List<T> =
    if (new.isEmpty()) emptyList() else (new + previous).take(max)

/**
 * Classifies exceptions thrown by a [NetworkSource] implementation. This is the one place
 * source-specific exception introspection (e.g. matching on Google's own exception types)
 * lives — [SyncEngine] only ever calls this interface, never inspects an exception itself.
 *
 * [classifySpecial] returns `null` for an exception that isn't one of the specifically-known
 * kinds (auth failure, consent required, etc.) — the caller (push-side [PendingOpsProcessor] or
 * pull-side [SyncEngine]) supplies its own generic fallback ([SyncErrorKind.PUSH_FAILED] or
 * [SyncErrorKind.PULL_FAILED] respectively), since only the *caller* knows which side of the
 * sync cycle it's on; the classifier can't tell push and pull exceptions apart by inspecting
 * the exception alone.
 *
 * [extractConsentIntent] returns `Any?` rather than a platform type (e.g. Android's `Intent`)
 * to keep this commonMain interface platform-neutral — same reasoning as
 * `SyncStatus.consentIntent` in the source library that consumes this one; the caller casts.
 */
interface SyncErrorClassifier {
    fun classifySpecial(e: Exception): SyncErrorKind?
    fun httpStatus(e: Exception): Int?
    fun extractConsentIntent(e: Exception): Any?
}
