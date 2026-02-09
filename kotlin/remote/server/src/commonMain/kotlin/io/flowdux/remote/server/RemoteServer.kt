package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.State
import io.flowdux.Store
import io.flowdux.remote.server.connection.TypedServerConnection
import io.flowdux.remote.server.middleware.InternalAddSession
import io.flowdux.remote.server.session.BroadcastConfig
import io.flowdux.remote.server.session.SessionBroadcaster
import io.flowdux.remote.server.session.SessionRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/**
 * Combines a [Store], [SessionRegistry], [SessionBroadcaster], and state broadcasting into a single object.
 *
 * ## Creating a RemoteServer
 *
 * Use [createSharedStateServer][io.flowdux.remote.server.pattern.createSharedStateServer] for the recommended way:
 *
 * ```kotlin
 * val server = createSharedStateServer(
 *     initialState = ServerState(),
 *     reducer = serverReducer,
 *     processors = myProcessors,
 *     stateMapper = { state -> SyncState(state) },
 *     scope = applicationScope,
 * )
 * ```
 *
 * For advanced use cases with custom store configuration:
 *
 * ```kotlin
 * val registry = InMemorySessionRegistry<ChatAction>()
 * val broadcaster = SessionBroadcaster(registry, BroadcastConfig.Default)
 * val middleware = MultiClientSyncMiddleware<ServerState, ChatAction>(processors, broadcaster)
 *
 * val store = createStore(
 *     initialState = ServerState(),
 *     reducer = serverReducer,
 *     middlewares = listOf(middleware, loggingMiddleware),
 *     scope = applicationScope,
 * )
 *
 * val server = createSharedStateServer(
 *     store = store,
 *     broadcaster = broadcaster,
 *     stateMapper = { state -> SyncState(state) },
 * )
 * ```
 *
 * ## Handling clients
 * ```kotlin
 * webSocket("/chat") {
 *     val connection = KtorWebSocketServerConnection(this)
 *         .typedJson<SharedChatAction>() as TypedServerConnection<ChatAction>
 *     server.handleClient(sessionId, connection)
 * }
 * ```
 *
 * ## Shutdown
 * ```kotlin
 * server.close()
 * ```
 */
class RemoteServer<S : State, A : Action> internal constructor(
    /**
     * The session registry managing client connections.
     */
    val sessionRegistry: SessionRegistry<A>,
    /**
     * The broadcaster for sending actions to clients.
     */
    val broadcaster: SessionBroadcaster<A>,
    /**
     * The underlying store.
     */
    val store: Store<S, A>,
    internal val serveJob: Job,
    /**
     * Optional scope job to cancel when [close] is called.
     * Used when the factory function creates a default scope.
     */
    private val ownedScopeJob: Job? = null,
) {
    /** Current server state as a reactive flow. */
    val state: StateFlow<S> get() = store.state

    /** Current server state snapshot. */
    val currentState: S get() = store.currentState

    /** Snapshot of currently connected session IDs. */
    suspend fun sessionIds(): Set<String> = sessionRegistry.sessionIds()

    /** Number of currently connected sessions. */
    suspend fun sessionCount(): Int = sessionRegistry.sessionCount()

    /**
     * Handle a client connection.
     *
     * Dispatches [InternalAddSession] to start listening for incoming messages,
     * registers the session in [SessionRegistry], suspends until cancelled,
     * then cleans up the session.
     *
     * @param sessionId Unique identifier for this client session.
     * @param connection Typed connection for sending/receiving actions.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun handleClient(
        sessionId: String,
        connection: TypedServerConnection<A>,
    ) {
        store.dispatch(InternalAddSession(sessionId, connection) as A)
        sessionRegistry.addSession(sessionId, connection)
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            sessionRegistry.removeSession(sessionId)
        }
    }

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
     */
    suspend fun broadcast(action: A) {
        broadcaster.broadcast(action)
    }

    /**
     * Close the server, stopping state broadcasting and closing the store.
     *
     * If the server was created with a default scope (no explicit scope parameter),
     * that scope is also cancelled.
     */
    fun close() {
        serveJob.cancel()
        ownedScopeJob?.cancel()
        store.close()
    }
}
