package io.flowdux.remote.auth

import io.flowdux.remote.ClientConnection
import io.flowdux.remote.ConnectionState
import io.flowdux.remote.auth.server.AuthPrincipal
import io.flowdux.remote.auth.server.AuthResult
import io.flowdux.remote.auth.server.AuthVerifier
import io.flowdux.remote.server.connection.ServerConnection
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

// ── Test Principal ──

data class TestPrincipal(
    val userId: String,
    val name: String = "Test User",
) : AuthPrincipal

// ── Mock ClientConnection (raw string level) ──

class MockClientConnection(
    private val autoConnect: Boolean = true,
) : ClientConnection {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val incomingChannel = Channel<String>(Channel.UNLIMITED)
    override val incoming: Flow<String> = incomingChannel.receiveAsFlow()

    val sentMessages = mutableListOf<String>()
    var connectCalled = false
    var disconnectCalled = false

    override suspend fun send(message: String) {
        sentMessages.add(message)
    }

    override suspend fun connect() {
        connectCalled = true
        if (autoConnect) {
            _connectionState.value = ConnectionState.CONNECTED
        }
        // Keep alive until cancelled
        awaitCancellation()
    }

    override suspend fun disconnect() {
        disconnectCalled = true
        _connectionState.value = ConnectionState.DISCONNECTED
        incomingChannel.close()
    }

    /** Simulate receiving a raw message from the server. */
    suspend fun simulateIncoming(message: String) {
        incomingChannel.send(message)
    }

    /** Manually set the connection state. */
    fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }
}

// ── Mock ServerConnection (raw string level) ──

class MockServerConnection : ServerConnection {

    private val incomingChannel = Channel<String>(Channel.UNLIMITED)
    override val incoming: Flow<String> = incomingChannel.receiveAsFlow()

    val sentMessages = mutableListOf<String>()

    override suspend fun send(message: String) {
        sentMessages.add(message)
    }

    /** Simulate receiving a raw message from the client. */
    suspend fun simulateIncoming(message: String) {
        incomingChannel.send(message)
    }

    /** Close the incoming channel to simulate connection close. */
    fun closeIncoming() {
        incomingChannel.close()
    }
}

// ── Test Verifiers ──

/** Always-accept verifier that returns a [TestPrincipal]. */
val acceptAllVerifier = AuthVerifier<TestPrincipal> { token ->
    AuthResult.Success(TestPrincipal(userId = token, name = "User $token"))
}

/** Always-reject verifier. */
val rejectAllVerifier = AuthVerifier<TestPrincipal> { _ ->
    AuthResult.Failure("Access denied")
}

/** Verifier that accepts only a specific token. */
fun tokenVerifier(validToken: String) = AuthVerifier<TestPrincipal> { token ->
    if (token == validToken) {
        AuthResult.Success(TestPrincipal(userId = "user-1", name = "Valid User"))
    } else {
        AuthResult.Failure("Invalid token")
    }
}

/** Verifier that throws an exception (simulates JWT library crash). */
val throwingVerifier = AuthVerifier<TestPrincipal> { _ ->
    throw RuntimeException("JWT decode failed")
}
