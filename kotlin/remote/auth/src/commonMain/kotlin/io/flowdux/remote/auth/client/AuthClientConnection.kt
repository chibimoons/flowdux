package io.flowdux.remote.auth.client

import io.flowdux.remote.ClientConnection
import io.flowdux.remote.ConnectionState
import io.flowdux.remote.auth.AuthConfig
import io.flowdux.remote.auth.AuthProtocol
import io.flowdux.remote.auth.AuthProtocolResponse
import io.flowdux.remote.auth.AuthenticationException
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
 * 2. Sends an auth token (from [credentialProvider])
 * 3. Waits for the server's auth response
 * 4. Forwards non-auth messages to [incoming] (only after auth succeeds)
 *
 * [send] is gated on authentication: calls before auth completes will suspend
 * until the handshake succeeds, or throw [AuthenticationException] if it fails.
 *
 * Usage:
 * ```kotlin
 * val authedConnection = KtorWebSocketClientConnection(url)
 *     .withAuth { tokenStore.getAccessToken() }
 * ```
 */
class AuthClientConnection(
    private val delegate: ClientConnection,
    private val credentialProvider: CredentialProvider,
    private val config: AuthConfig = AuthConfig(),
) : ClientConnection {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val messageChannel = Channel<String>(Channel.UNLIMITED)
    override val incoming: Flow<String> = messageChannel.receiveAsFlow()

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
                val token = credentialProvider.provide()
                delegate.send(AuthProtocol.encodeAuthRequest(token))

                // 4. Start message forwarding (filters auth messages)
                launch {
                    delegate.incoming.collect { raw ->
                        if (AuthProtocol.isAuthMessage(raw)) {
                            try {
                                when (AuthProtocol.decodeAuthResponse(raw)) {
                                    is AuthProtocolResponse.Success ->
                                        _connectionState.value = ConnectionState.CONNECTED

                                    is AuthProtocolResponse.Error -> {
                                        _connectionState.value = ConnectionState.DISCONNECTED
                                        delegate.disconnect()
                                    }
                                }
                            } catch (_: Exception) {
                                // Malformed auth message — treat as auth failure
                                _connectionState.value = ConnectionState.DISCONNECTED
                                delegate.disconnect()
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

                if (_connectionState.value != ConnectionState.CONNECTED) {
                    throw AuthenticationException("Authentication rejected by server")
                }

                // 6. Stay alive until connection closes
                connectJob.join()
            }
        } finally {
            _connectionState.value = ConnectionState.DISCONNECTED
            messageChannel.close()
        }
    }

    override suspend fun disconnect() {
        delegate.disconnect()
        messageChannel.close()
        _connectionState.value = ConnectionState.DISCONNECTED
    }
}
