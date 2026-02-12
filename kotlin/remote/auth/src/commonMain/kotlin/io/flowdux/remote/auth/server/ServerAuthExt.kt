package io.flowdux.remote.auth.server

import io.flowdux.remote.auth.AuthConfig
import io.flowdux.remote.server.connection.ServerConnection

/**
 * Add authentication to a server connection with a verifier.
 *
 * ```kotlin
 * val authed = KtorWebSocketServerConnection(session).withAuth(jwtVerifier)
 *
 * val principal = authed.awaitAuth(scope).getOrElse { reason ->
 *     session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason))
 *     return@webSocket
 * }
 *
 * // use authed as a normal ServerConnection
 * server.handleClient(principal.userId, authed.typedJsonAs<...>())
 * ```
 */
fun <P : AuthPrincipal> ServerConnection.withAuth(
    verifier: AuthVerifier<P>,
    config: AuthConfig = AuthConfig(),
): AuthServerConnection<P> = AuthServerConnection(this, verifier, config)
