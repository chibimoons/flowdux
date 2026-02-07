package io.flowdux.remote.server

import io.flowdux.Action

/**
 * Abstraction for storing and managing client session connections.
 *
 * Implementations handle:
 * - Registering and unregistering client sessions
 * - Retrieving individual sessions or all sessions
 * - Thread-safe access to session state
 *
 * The default implementation is [InMemorySessionRegistry] for single-node deployments.
 * For distributed deployments, implement this interface with Redis or other external stores.
 *
 * @param A The action type used for typed connections.
 */
interface SessionRegistry<A : Action> {
    /**
     * Snapshot of currently connected session IDs.
     */
    suspend fun sessionIds(): Set<String>

    /**
     * Number of currently connected sessions.
     */
    suspend fun sessionCount(): Int

    /**
     * Register a client session.
     *
     * @param sessionId Unique identifier for this client session.
     * @param connection Typed connection for sending/receiving actions.
     */
    suspend fun addSession(sessionId: String, connection: TypedServerConnection<A>)

    /**
     * Unregister a client session.
     *
     * @param sessionId The session ID to remove.
     */
    suspend fun removeSession(sessionId: String)

    /**
     * Get a specific session by ID.
     *
     * @param sessionId The session ID to look up.
     * @return The connection if found, null otherwise.
     */
    suspend fun getSession(sessionId: String): TypedServerConnection<A>?

    /**
     * Get a snapshot of all sessions.
     *
     * @return Map of session ID to connection.
     */
    suspend fun getSessions(): Map<String, TypedServerConnection<A>>
}
