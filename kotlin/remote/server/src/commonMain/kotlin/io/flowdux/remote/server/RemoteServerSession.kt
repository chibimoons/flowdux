package io.flowdux.remote.server

import io.flowdux.Action
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages client session connections for a remote server.
 *
 * Responsible for:
 * - Registering and unregistering client sessions
 * - Sending actions to specific clients or broadcasting to all
 * - Tracking connected session IDs
 *
 * Does NOT own the Store or middleware — those are wired externally
 * (see [createRemoteServer]).
 *
 * Example:
 * ```kotlin
 * val session = RemoteServerSession<ChatAction>()
 * val middleware = MultiClientServerRemoteMiddleware(processors, session)
 * val store = createStore(middlewares = listOf(middleware), ...)
 * ```
 */
class RemoteServerSession<A : Action> {

    private val sessions = mutableMapOf<String, TypedServerConnection<A>>()
    private val mutex = Mutex()

    /** Snapshot of currently connected session IDs. */
    suspend fun sessionIds(): Set<String> = mutex.withLock { sessions.keys.toSet() }

    /** Number of currently connected sessions. */
    suspend fun sessionCount(): Int = mutex.withLock { sessions.size }

    /**
     * Register a client session.
     */
    suspend fun addSession(sessionId: String, connection: TypedServerConnection<A>) {
        mutex.withLock { sessions[sessionId] = connection }
    }

    /**
     * Unregister a client session.
     */
    suspend fun removeSession(sessionId: String) {
        mutex.withLock { sessions.remove(sessionId) }
    }

    /**
     * Handle a client connection lifecycle.
     *
     * Registers the session, suspends until the calling coroutine is cancelled
     * (e.g., WebSocket disconnect), then automatically removes the session.
     *
     * Note: The caller is responsible for dispatching [InternalAddSession]
     * to the store so that the middleware starts listening for incoming actions.
     *
     * @param sessionId Unique identifier for this client session.
     * @param connection Typed connection for sending/receiving actions.
     */
    suspend fun handleClient(
        sessionId: String,
        connection: TypedServerConnection<A>,
    ) {
        addSession(sessionId, connection)
        try {
            awaitCancellation()
        } finally {
            removeSession(sessionId)
        }
    }

    /**
     * Send an action to a specific client by session ID.
     * No-op if the session does not exist.
     */
    suspend fun sendToClient(sessionId: String, action: A) {
        val connection = mutex.withLock { sessions[sessionId] } ?: return
        try {
            connection.send(action)
        } catch (_: Exception) {
            // Isolate send failures
        }
    }

    /**
     * Send an action to all connected clients.
     * Errors on individual connections are caught and do not affect others.
     */
    suspend fun broadcast(action: A) {
        val snapshot = mutex.withLock { sessions.values.toList() }
        for (connection in snapshot) {
            try {
                connection.send(action)
            } catch (_: Exception) {
                // Isolate per-client send failures
            }
        }
    }

    /**
     * Send a per-session action to each connected client.
     *
     * For each session, calls [mapper] to produce the action for that session.
     * If [mapper] returns `null`, the session is skipped.
     * Errors on individual connections are caught and do not affect others.
     */
    internal suspend fun sendPerSession(mapper: (sessionId: String) -> A?) {
        val snapshot = mutex.withLock { sessions.toMap() }
        for ((sessionId, connection) in snapshot) {
            try {
                val sessionAction = mapper(sessionId) ?: continue
                connection.send(sessionAction)
            } catch (_: Exception) {
                // Isolate per-client send failures
            }
        }
    }
}
