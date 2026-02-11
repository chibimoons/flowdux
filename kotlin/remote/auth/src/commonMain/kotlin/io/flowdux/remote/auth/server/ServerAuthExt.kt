package io.flowdux.remote.auth.server

import io.flowdux.remote.auth.AuthConfig
import io.flowdux.remote.server.connection.ServerConnection

/**
 * Add authentication to a server connection with a verifier.
 *
 * ```kotlin
 * val authed = KtorWebSocketServerConnection(session).withAuth(jwtVerifier)
 * when (val result = authed.awaitAuth()) {
 *     is AuthResult.Success -> { /* use authed as ServerConnection */ }
 *     is AuthResult.Failure -> session.close(...)
 * }
 * ```
 */
fun <P : AuthPrincipal> ServerConnection.withAuth(
    verifier: AuthVerifier<P>,
    config: AuthConfig = AuthConfig(),
): AuthServerConnection<P> = AuthServerConnection(this, verifier, config)
