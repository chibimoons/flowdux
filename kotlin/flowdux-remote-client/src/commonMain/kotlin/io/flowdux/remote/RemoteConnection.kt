package io.flowdux.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstraction for the transport layer between client and server.
 *
 * Implementations handle the actual network communication (e.g., WebSocket, HTTP SSE).
 * The middleware interacts only with this interface, making it transport-agnostic.
 */
interface RemoteConnection {
    /** Current connection state as a reactive flow. */
    val connectionState: StateFlow<ConnectionState>

    /** Incoming raw messages from the server. */
    val incoming: Flow<String>

    /** Send a raw message to the server. */
    suspend fun send(message: String)

    /** Establish the connection. */
    suspend fun connect()

    /** Close the connection. */
    suspend fun disconnect()
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}
