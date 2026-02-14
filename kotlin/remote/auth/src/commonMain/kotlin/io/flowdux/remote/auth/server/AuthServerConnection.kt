package io.flowdux.remote.auth.server

import io.flowdux.remote.auth.AuthConfig
import io.flowdux.remote.auth.AuthProtocol
import io.flowdux.remote.server.connection.ServerConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decorates a [ServerConnection] with authentication verification.
 *
 * Call [awaitAuth] to wait for the client's auth message, verify it, and
 * start forwarding non-auth messages to [incoming].
 *
 * Usage:
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
class AuthServerConnection<P : AuthPrincipal>(
    private val delegate: ServerConnection,
    private val verifier: AuthVerifier<P>,
    private val config: AuthConfig = AuthConfig(),
) : ServerConnection {

    private val _principal = CompletableDeferred<P>()
    private val rawChannel = Channel<String>(Channel.UNLIMITED)
    private val messageChannel = Channel<String>(Channel.UNLIMITED)

    override val isActive: Boolean get() = delegate.isActive

    override val incoming: Flow<String> = messageChannel.receiveAsFlow()

    override suspend fun send(message: String) = delegate.send(message)

    /**
     * The verified principal. Available after [awaitAuth] returns [AuthResult.Success].
     * @throws IllegalStateException if called before auth completes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val principal: P get() = _principal.getCompleted()

    /**
     * Await client authentication.
     *
     * Suspends until the client sends an auth message. Verifies the token
     * using the configured [AuthVerifier] and sends the result back.
     * If [AuthConfig.maxAuthAttempts] > 1, the client may retry with a
     * new token after a verification failure (e.g., after refreshing an expired token).
     *
     * After success, [incoming] starts forwarding non-auth messages.
     * After failure, the connection should be closed by the caller.
     *
     * @param scope The coroutine scope used for bridging and forwarding messages.
     *              Typically the caller's scope (e.g., Ktor's WebSocket session scope).
     * @return [AuthResult] with the verified principal or failure reason.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun awaitAuth(scope: CoroutineScope): AuthResult<P> {
        // Bridge delegate.incoming into rawChannel
        val bridgeJob = scope.launch {
            delegate.incoming.collect { rawChannel.send(it) }
            rawChannel.close()
        }

        val result = try {
            withTimeoutOrNull(config.handshakeTimeout) {
                var lastFailure: AuthResult.Failure? = null

                repeat(config.maxAuthAttempts) {
                    val message = rawChannel.receiveCatching().getOrNull()
                        ?: return@withTimeoutOrNull (lastFailure
                            ?: AuthResult.Failure("Connection closed before auth"))

                    if (!AuthProtocol.isAuthMessage(message)) {
                        val reason = "Expected auth message, got: ${message.take(50)}"
                        delegate.send(AuthProtocol.encodeAuthError(reason))
                        return@withTimeoutOrNull AuthResult.Failure(reason)
                    }

                    val token = try {
                        AuthProtocol.decodeAuthRequest(message)
                    } catch (e: Exception) {
                        val reason = "Malformed auth message: ${e.message}"
                        delegate.send(AuthProtocol.encodeAuthError(reason))
                        return@withTimeoutOrNull AuthResult.Failure(reason)
                    }

                    val verifyResult = try {
                        verifier.verify(token)
                    } catch (e: Exception) {
                        val reason = "Verifier error: ${e.message}"
                        delegate.send(AuthProtocol.encodeAuthError(reason))
                        return@withTimeoutOrNull AuthResult.Failure(reason)
                    }

                    when (verifyResult) {
                        is AuthResult.Success -> {
                            _principal.complete(verifyResult.principal)
                            delegate.send(AuthProtocol.encodeAuthSuccess())
                            // Start forwarding remaining messages (filtering auth protocol)
                            scope.launch {
                                for (raw in rawChannel) {
                                    if (!AuthProtocol.isAuthMessage(raw)) {
                                        messageChannel.send(raw)
                                    }
                                }
                                messageChannel.close()
                            }
                            return@withTimeoutOrNull verifyResult
                        }

                        is AuthResult.Failure -> {
                            delegate.send(AuthProtocol.encodeAuthError(verifyResult.reason))
                            lastFailure = verifyResult
                        }
                    }
                }

                lastFailure ?: AuthResult.Failure("Auth failed")
            } ?: AuthResult.Failure("Auth handshake timed out")
        } finally {
            // On failure/timeout, clean up channels so consumers don't hang
            if (!_principal.isCompleted) {
                bridgeJob.cancel()
                rawChannel.close()
                messageChannel.close()
            }
        }

        return result
    }
}
