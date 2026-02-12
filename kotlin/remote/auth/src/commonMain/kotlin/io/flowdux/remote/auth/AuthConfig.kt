package io.flowdux.remote.auth

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for the authentication handshake.
 *
 * @property handshakeTimeout Maximum time to wait for the auth handshake to complete.
 */
data class AuthConfig(
    val handshakeTimeout: Duration = 10.seconds,
)
