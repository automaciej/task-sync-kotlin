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
 * Classifies exceptions thrown by a [NetworkSource] implementation. This is the one place
 * source-specific exception introspection (e.g. matching on Google's own exception types)
 * lives — [SyncEngine] only ever calls this interface, never inspects an exception itself.
 *
 * [extractConsentIntent] returns `Any?` rather than a platform type (e.g. Android's `Intent`)
 * to keep this commonMain interface platform-neutral — same reasoning as
 * `SyncStatus.consentIntent` in the source library that consumes this one; the caller casts.
 */
interface SyncErrorClassifier {
    fun classify(e: Exception): SyncErrorKind
    fun httpStatus(e: Exception): Int?
    fun extractConsentIntent(e: Exception): Any?
}
