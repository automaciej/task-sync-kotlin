package pl.blizinski.tasksync

/**
 * A [SyncErrorClassifier] for sources whose auth failures surface as a plain HTTP status on a
 * provider-specific API exception, with no interactive consent flow to hand back — i.e. every
 * source except Google Tasks.
 *
 * [statusOf] extracts the HTTP status from an exception this source's [NetworkSource] can throw
 * (`(e as? XxxApiException)?.httpStatus`); a `401` classifies as [SyncErrorKind.AUTH_FAILED].
 * [extraSpecial] runs first and lets a source add its own cases (e.g. Microsoft's
 * `MicrosoftReauthRequiredException`) without subclassing. [extractConsentIntent] is always
 * null — recovery for these sources is "reconnect the account".
 *
 * Replaces the byte-identical `TodoistSyncErrorClassifier` / `GitHubSyncErrorClassifier` and the
 * near-identical `MicrosoftSyncErrorClassifier`.
 */
class HttpStatusSyncErrorClassifier(
    private val statusOf: (Exception) -> Int?,
    private val extraSpecial: (Exception) -> SyncErrorKind? = { null },
) : SyncErrorClassifier {

    override fun classifySpecial(e: Exception): SyncErrorKind? {
        extraSpecial(e)?.let { return it }
        return if (statusOf(e) == 401) SyncErrorKind.AUTH_FAILED else null
    }

    override fun httpStatus(e: Exception): Int? = statusOf(e)

    override fun extractConsentIntent(e: Exception): Any? = null
}
