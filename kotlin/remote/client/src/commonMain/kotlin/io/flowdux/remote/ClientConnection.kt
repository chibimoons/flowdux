package io.flowdux.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Client-side abstraction for the transport layer between client and server.
 *
 * Implementations handle the actual network communication (e.g., WebSocket, HTTP SSE).
 * The middleware interacts only with this interface, making it transport-agnostic.
 *
 * This is the client-side counterpart to [ServerConnection][io.flowdux.remote.server.ServerConnection].
 */
interface ClientConnection {
    /** Current connection state as a reactive flow. */
    val connectionState: StateFlow<ConnectionState>

    /** Incoming raw messages from the server. */
    val incoming: Flow<String>

    /** Send a raw message to the server. */
    suspend fun send(message: String)

    /** Establish the connection and suspend until disconnected. */
    suspend fun connect()

    /** Close the connection. */
    suspend fun disconnect()
}

/** Represents the connection lifecycle states. */
enum class ConnectionState {
    /** Not connected to the server. */
    DISCONNECTED,

    /** Connection attempt in progress. */
    CONNECTING,

    /** Actively connected and ready to send/receive. */
    CONNECTED,

    /**
     * Attempting to re-establish a lost connection.
     *
     * Emitted by [ReconnectingClientConnection] when the underlying connection drops
     * and a reconnection attempt is in progress.
     */
    RECONNECTING,
}
