package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.remote.server.connection.TypedServerConnection

/**
 * Common interface for server components that handle client connections.
 *
 * This interface is implemented by both [SharedStateServer][io.flowdux.remote.server.pattern.SharedStateServer]
 * and [PerClientServer][io.flowdux.remote.server.pattern.PerClientServer], allowing
 * [RoomServer][io.flowdux.remote.server.pattern.RoomServer] to manage rooms of either type.
 *
 * @param A Action type
 */
interface ClientHandler<A : Action> {
    /**
     * Handle a client connection.
     *
     * Suspends until the connection is closed or cancelled.
     *
     * @param sessionId Unique identifier for this client session.
     * @param connection Typed connection for sending/receiving actions.
     */
    suspend fun handleClient(sessionId: String, connection: TypedServerConnection<A>)

    /**
     * Get a snapshot of all active session IDs.
     */
    suspend fun sessionIds(): Set<String>

    /**
     * Get the number of active sessions.
     */
    suspend fun sessionCount(): Int

    /**
     * Close this handler and release resources.
     */
    fun close()
}
