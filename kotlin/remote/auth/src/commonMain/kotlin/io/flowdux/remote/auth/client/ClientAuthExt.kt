package io.flowdux.remote.auth.client

import io.flowdux.remote.ClientConnection
import io.flowdux.remote.auth.AuthConfig

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
