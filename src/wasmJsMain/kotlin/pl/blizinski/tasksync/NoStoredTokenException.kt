package pl.blizinski.tasksync

/**
 * Thrown by a wasmJs source's token provider when no access token is available for an
 * otherwise-connected account — e.g. after a page reload, when the account's *connection*
 * record persisted (see TaskCompass's Docs/designs/2026-08-02-web-indexeddb-persistence.md) but
 * the token itself didn't. This is expected, not a bug in that persistence work: browser OAuth
 * session tokens (Google Identity Services' token-model flow) are short-lived and were never
 * meant to survive a reload; a GitHub PAT could in principle be persisted but currently isn't
 * either (same in-memory-only scope cut as the original PoC).
 *
 * A [SyncErrorClassifier] recognizing this type should classify it as
 * [SyncErrorKind.AUTH_FAILED] — the same error kind an expired/revoked token produces via a real
 * 401 — so the app's existing "this account needs reconnecting" handling covers this case too,
 * rather than it surfacing as an opaque generic sync failure.
 */
class NoStoredTokenException(message: String) : Exception(message)
