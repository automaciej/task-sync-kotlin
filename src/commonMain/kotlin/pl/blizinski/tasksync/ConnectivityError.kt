package pl.blizinski.tasksync

/**
 * True when [error] — or anything in its `cause` chain — is a transport-level connectivity
 * failure: DNS resolution failure, connection refused/reset, socket timeout, no route to host.
 *
 * These are environmental, not app faults, and clear on their own once the network returns, so
 * [SyncEngine] and [PendingOpsProcessor] tag them [SyncErrorKind.OFFLINE] instead of the generic
 * [SyncErrorKind.PULL_FAILED]/[SyncErrorKind.PUSH_FAILED]. A consumer can then keep them out of
 * its crash reporter while still surfacing them on a sync-status screen.
 *
 * Matching is by exception class simple-name so this stays in commonMain. The names below are
 * `java.net`/`javax.net.ssl` and Ktor client types; every Ktor engine this project uses
 * ultimately surfaces one of them for a dead network. [SyncErrorClassifier.classifySpecial]
 * still runs first, so a provider that recognises one of these as something more specific (an
 * auth failure, say) keeps winning.
 */
fun isConnectivityException(error: Throwable): Boolean {
    var current: Throwable? = error
    var hops = 0
    while (current != null && hops < MAX_CAUSE_HOPS) {
        if (current::class.simpleName in CONNECTIVITY_EXCEPTION_NAMES) return true
        current = current.cause
        hops++
    }
    return false
}

private const val MAX_CAUSE_HOPS = 10

private val CONNECTIVITY_EXCEPTION_NAMES = setOf(
    "UnknownHostException",
    "ConnectException",
    "NoRouteToHostException",
    "SocketException",
    "SocketTimeoutException",
    "ConnectTimeoutException",   // io.ktor.client.network.sockets
    "SSLException",
)
