package io.flowdux.remote.auth.client

import io.flowdux.remote.ClientConnection
import io.flowdux.remote.auth.AuthConfig

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
): ClientConnection = AuthClientConnection(this, { token }, config)

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
    token: suspend () -> String,
): ClientConnection = AuthClientConnection(this, token, config)

/**
 * Add authentication with token refresh support.
 *
 * When the server rejects the initial token, [refresh] is called once to obtain a new token.
 * If the refreshed token is also rejected (or [refresh] returns null / throws), authentication fails.
 *
 * ```kotlin
 * val connection = KtorWebSocketClientConnection(url)
 *     .withAuth(
 *         token = { tokenStore.getAccessToken() },
 *         refresh = {
 *             val newTokens = api.refreshTokens(tokenStore.getRefreshToken()!!)
 *             tokenStore.save(newTokens)
 *             newTokens.accessToken
 *         },
 *     )
 *     .typedJson<SharedAction>()
 * ```
 */
fun ClientConnection.withAuth(
    config: AuthConfig = AuthConfig(),
    token: suspend () -> String,
    refresh: suspend () -> String?,
): ClientConnection = AuthClientConnection(this, token, config, refresh)
