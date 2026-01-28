package io.flowdux.remote.server

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction for the transport layer between server and client.
 *
 * Server-side counterpart to [ClientConnection][io.flowdux.remote.ClientConnection].
 * Implementations handle the actual network communication (e.g., WebSocket session).
 */
interface ServerConnection {
    /** Incoming raw messages from the client. */
    val incoming: Flow<String>

    /** Send a raw message to the client. */
    suspend fun send(message: String)
}
