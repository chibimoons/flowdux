package io.flowdux.remote.auth

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for the authentication handshake.
 *
 * @property handshakeTimeout Maximum time to wait for the auth handshake to complete.
 * @property maxAuthAttempts Maximum number of auth attempts allowed on a single connection.
 *   Defaults to 1 (single attempt). Set to 2 to support client-side token refresh.
 *   Used by [io.flowdux.remote.auth.server.AuthServerConnection] only; the client
 *   manages its own retry via `refreshProvider`.
 */
data class AuthConfig(
    val handshakeTimeout: Duration = 10.seconds,
    val maxAuthAttempts: Int = 1,
) {
    init {
        require(maxAuthAttempts >= 1) {
            "maxAuthAttempts must be at least 1, but was $maxAuthAttempts"
        }
    }
}
