package io.flowdux.remote.auth.client

import io.flowdux.remote.ClientConnection
import io.flowdux.remote.ConnectionState
import io.flowdux.remote.auth.AuthConfig
import io.flowdux.remote.auth.AuthProtocol
import io.flowdux.remote.auth.AuthProtocolResponse
import io.flowdux.remote.auth.AuthenticationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decorates a [ClientConnection] with an authentication handshake.
 *
 * During [connect], this connection:
 * 1. Opens the underlying transport connection
 * 2. Sends an auth token (from [tokenProvider])
 * 3. Waits for the server's auth response
 * 4. If auth fails and [refreshProvider] is set, refreshes the token and retries once
 * 5. Forwards non-auth messages to [incoming] (only after auth succeeds)
 *
 * [send] is gated on authentication: calls before auth completes will suspend
 * until the handshake succeeds, or throw [AuthenticationException] if it fails.
 *
 * Usage:
 * ```kotlin
 * val authedConnection = KtorWebSocketClientConnection(url)
 *     .withAuth(
 *         token = { tokenStore.getAccessToken() },
 *         refresh = {
 *             val newTokens = api.refreshTokens(tokenStore.getRefreshToken()!!)
 *             tokenStore.save(newTokens)
 *             newTokens.accessToken
 *         },
 *     )
 * ```
 *
 * @param delegate The underlying transport connection
 * @param tokenProvider Suspend lambda that provides the initial auth token
 * @param config Auth handshake configuration (timeout, etc.)
 * @param refreshProvider Optional lambda to obtain a new token when auth is rejected.
 *   Called at most once per [connect] attempt. Returns the new token, or null to skip retry.
 */
class AuthClientConnection(
    private val delegate: ClientConnection,
    private val tokenProvider: suspend () -> String,
    private val config: AuthConfig = AuthConfig(),
    private val refreshProvider: (suspend () -> String?)? = null,
) : ClientConnection {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val messageChannel = Channel<String>(Channel.UNLIMITED)
    override val incoming: Flow<String> = messageChannel.receiveAsFlow()

    /** Captured auth error reason from the server (if auth failed). Thread-safe via StateFlow. */
    private val _authErrorReason = MutableStateFlow<String?>(null)

    override suspend fun send(message: String) {
        // Gate sends until auth handshake completes
        _connectionState.first { it != ConnectionState.CONNECTING }
        if (_connectionState.value != ConnectionState.CONNECTED) {
            throw AuthenticationException("Cannot send: not authenticated")
        }
        delegate.send(message)
    }

    override suspend fun connect() {
        _connectionState.value = ConnectionState.CONNECTING

        try {
            coroutineScope {
                // 1. Start underlying connection (suspends for lifetime)
                val connectJob = launch { delegate.connect() }

                // 2. Wait for transport to be open
                delegate.connectionState.first { it == ConnectionState.CONNECTED }

                // 3. Send auth token
                val token = tokenProvider()
                delegate.send(AuthProtocol.encodeAuthRequest(token))

                // 4. Start message forwarding (filters auth messages)
                launch {
                    delegate.incoming.collect { raw ->
                        if (AuthProtocol.isAuthMessage(raw)) {
                            try {
                                when (val response = AuthProtocol.decodeAuthResponse(raw)) {
                                    is AuthProtocolResponse.Success ->
                                        _connectionState.value = ConnectionState.CONNECTED

                                    is AuthProtocolResponse.Error -> {
                                        _authErrorReason.value = response.reason
                                        _connectionState.value = ConnectionState.DISCONNECTED
                                    }
                                }
                            } catch (_: Exception) {
                                // Malformed auth message — treat as auth failure
                                _connectionState.value = ConnectionState.DISCONNECTED
                            }
                        } else if (_connectionState.value == ConnectionState.CONNECTED) {
                            // Only forward non-auth messages after successful auth
                            messageChannel.send(raw)
                        }
                    }
                }

                // 5. Wait for auth to complete (with timeout)
                val authed = withTimeoutOrNull(config.handshakeTimeout) {
                    _connectionState.first {
                        it == ConnectionState.CONNECTED || it == ConnectionState.DISCONNECTED
                    }
                }

                if (authed == null) {
                    delegate.disconnect()
                    throw AuthenticationException("Auth handshake timed out")
                }

                // 6. Auth failed — attempt refresh and retry once
                if (_connectionState.value != ConnectionState.CONNECTED) {
                    val refreshed = tryRefresh()
                    if (!refreshed) {
                        val reason = _authErrorReason.value ?: "unknown reason"
                        delegate.disconnect()
                        throw AuthenticationException("Authentication rejected by server: $reason")
                    }
                }

                // 7. Stay alive until connection closes
                connectJob.join()
            }
        } finally {
            _connectionState.value = ConnectionState.DISCONNECTED
            messageChannel.close()
        }
    }

    /**
     * Attempt to refresh the auth token and retry authentication once.
     *
     * @return true if refresh + re-auth succeeded, false otherwise
     */
    private suspend fun tryRefresh(): Boolean {
        val refresh = refreshProvider ?: return false
        val newToken = try {
            withTimeoutOrNull(config.handshakeTimeout) { refresh() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        } ?: return false

        val originalErrorReason = _authErrorReason.value
        _authErrorReason.value = null
        _connectionState.value = ConnectionState.CONNECTING
        delegate.send(AuthProtocol.encodeAuthRequest(newToken))

        val result = withTimeoutOrNull(config.handshakeTimeout) {
            _connectionState.first { it != ConnectionState.CONNECTING }
        }
        if (result == null || _connectionState.value != ConnectionState.CONNECTED) {
            // Restore original error reason if no new error was set (e.g., timeout)
            if (_authErrorReason.value == null) {
                _authErrorReason.value = originalErrorReason
            }
            return false
        }
        return true
    }

    override suspend fun disconnect() {
        delegate.disconnect()
        messageChannel.close()
        _connectionState.value = ConnectionState.DISCONNECTED
    }
}
