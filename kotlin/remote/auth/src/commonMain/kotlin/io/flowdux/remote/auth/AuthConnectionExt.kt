package io.flowdux.remote.auth

import io.flowdux.remote.ClientConnection
import io.flowdux.remote.server.connection.ServerConnection

// ── Client ──

/**
 * Add authentication to a client connection with a credential provider.
 *
 * ```kotlin
 * val connection = KtorWebSocketClientConnection(url)
 *     .withAuth(CredentialProvider { tokenStore.getAccessToken() })
 *     .typedJson<SharedAction>()
 * ```
 */
fun ClientConnection.withAuth(
    provider: CredentialProvider,
    config: AuthConfig = AuthConfig(),
): ClientConnection = AuthClientConnection(this, provider, config)

/**
 * Add authentication to a client connection with a token provider lambda.
 *
 * ```kotlin
 * val connection = KtorWebSocketClientConnection(url)
 *     .withAuth { tokenStore.getAccessToken() }
 *     .typedJson<SharedAction>()
 * ```
 */
fun ClientConnection.withAuth(
    config: AuthConfig = AuthConfig(),
    provider: suspend () -> String,
): ClientConnection = AuthClientConnection(this, CredentialProvider { provider() }, config)

/**
 * Add authentication to a client connection with a static token.
 *
 * ```kotlin
 * val connection = KtorWebSocketClientConnection(url)
 *     .withAuth(token = "my-api-key")
 *     .typedJson<SharedAction>()
 * ```
 */
fun ClientConnection.withAuth(
    token: String,
    config: AuthConfig = AuthConfig(),
): ClientConnection = AuthClientConnection(this, CredentialProvider { token }, config)

// ── Server ──

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
