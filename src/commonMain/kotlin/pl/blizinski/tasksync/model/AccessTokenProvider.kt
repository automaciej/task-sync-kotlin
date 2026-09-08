package pl.blizinski.tasksync.model

/**
 * Supplies an OAuth access token for a source's API. Implemented by the host app, wrapping
 * wherever the token is obtained (platform account APIs on Android, a browser identity flow on
 * wasmJs), so a provider library never hardcodes a platform-specific auth mechanism.
 *
 * Replaces the per-provider `*AccessTokenProvider` interfaces (which were this same one-method
 * shape, bar Microsoft's method name).
 */
interface AccessTokenProvider {
    suspend fun getToken(): String
}
