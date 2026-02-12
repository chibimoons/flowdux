package io.flowdux.remote.auth.client

/**
 * Client-side provider for authentication credentials (token string).
 * Called during connection handshake.
 *
 * Example:
 * ```kotlin
 * val provider = CredentialProvider { tokenStore.getAccessToken() }
 * ```
 */
fun interface CredentialProvider {
    suspend fun provide(): String
}
