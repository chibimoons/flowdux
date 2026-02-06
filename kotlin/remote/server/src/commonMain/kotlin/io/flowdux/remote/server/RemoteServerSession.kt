package io.flowdux.remote.server

import io.flowdux.Action
import kotlinx.coroutines.awaitCancellation

/**
 * Manages client session connections for a remote server.
 *
 * Responsible for:
 * - Registering and unregistering client sessions
 * - Sending actions to specific clients or broadcasting to all
 * - Tracking connected session IDs
 *
 * Implements [SessionRegistry] for session storage, delegating to an internal
 * [InMemorySessionRegistry] by default. Uses [SessionBroadcaster] for sending
 * actions, supporting both sequential and parallel broadcast modes.
 *
 * Does NOT own the Store or middleware — those are wired externally
 * (see [createRemoteServer]).
 *
 * Example:
 * ```kotlin
 * // Default: sequential broadcast
 * val session = RemoteServerSession<ChatAction>()
 *
 * // With parallel broadcast for high throughput
 * val session = RemoteServerSession<ChatAction>(
 *     broadcastConfig = BroadcastConfig(concurrency = 32)
 * )
 *
 * val middleware = MultiClientServerRemoteMiddleware(processors, session)
 * val store = createStore(middlewares = listOf(middleware), ...)
 * ```
 *
 * @param registry The session registry to use for storage. Defaults to [InMemorySessionRegistry].
 * @param broadcastConfig Configuration for broadcast behavior. Defaults to sequential.
 */
class RemoteServerSession<A : Action>(
    private val registry: SessionRegistry<A> = InMemorySessionRegistry(),
    broadcastConfig: BroadcastConfig = BroadcastConfig.Sequential,
) : SessionRegistry<A> {

    private val broadcaster = SessionBroadcaster(registry, broadcastConfig)

    /**
     * Secondary constructor for backward compatibility.
     * Uses [InMemorySessionRegistry] and sequential broadcast.
     */
    constructor() : this(InMemorySessionRegistry(), BroadcastConfig.Sequential)

    // -- SessionRegistry delegation --

    override suspend fun sessionIds(): Set<String> = registry.sessionIds()

    override suspend fun sessionCount(): Int = registry.sessionCount()

    override suspend fun addSession(sessionId: String, connection: TypedServerConnection<A>) {
        registry.addSession(sessionId, connection)
    }

    override suspend fun removeSession(sessionId: String) {
        registry.removeSession(sessionId)
    }

    override suspend fun getSession(sessionId: String): TypedServerConnection<A>? =
        registry.getSession(sessionId)

    override suspend fun getSessions(): Map<String, TypedServerConnection<A>> =
        registry.getSessions()

    // -- Client handling --

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

    // -- Broadcasting (delegated to SessionBroadcaster) --

    /**
     * Send an action to a specific client by session ID.
     * No-op if the session does not exist.
     */
    suspend fun sendToClient(sessionId: String, action: A) {
        broadcaster.sendToClient(sessionId, action)
    }

    /**
     * Send an action to all connected clients.
     *
     * Uses parallel sending if configured with [BroadcastConfig.concurrency] > 1.
     * Errors on individual connections are caught and do not affect others.
     */
    suspend fun broadcast(action: A) {
        broadcaster.broadcast(action)
    }

    /**
     * Send a per-session action to each connected client.
     *
     * For each session, calls [mapper] to produce the action for that session.
     * If [mapper] returns `null`, the session is skipped.
     *
     * Uses parallel sending if configured with [BroadcastConfig.concurrency] > 1.
     * Errors on individual connections are caught and do not affect others.
     */
    internal suspend fun sendPerSession(mapper: (sessionId: String) -> A?) {
        broadcaster.sendPerSession(mapper)
    }
}
