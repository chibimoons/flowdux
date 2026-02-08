package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.ActionProcessorMap
import io.flowdux.DefaultErrorProcessor
import io.flowdux.ErrorProcessor
import io.flowdux.NoOpStoreLogger
import io.flowdux.Reducer
import io.flowdux.State
import io.flowdux.Store
import io.flowdux.StoreLogger
import io.flowdux.createStore
import io.flowdux.remote.server.connection.TypedServerConnection
import io.flowdux.remote.server.middleware.InternalAddSession
import io.flowdux.remote.server.middleware.MultiClientServerRemoteMiddleware
import io.flowdux.remote.server.session.BroadcastConfig
import io.flowdux.remote.server.session.InMemorySessionRegistry
import io.flowdux.remote.server.session.SessionBroadcaster
import io.flowdux.remote.server.session.SessionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Combines a [Store], [SessionRegistry], [SessionBroadcaster], and state broadcasting into a single object.
 *
 * Created by [createRemoteServer] or [createSessionAwareRemoteServer].
 *
 * Example (default - sequential broadcast):
 * ```kotlin
 * val server = createRemoteServer(
 *     initialState = ServerChatState(),
 *     reducer = serverChatReducer,
 *     stateMapper = { state -> SyncState(state.toChatState()) },
 *     scope = applicationScope,
 * )
 * ```
 *
 * Example (parallel broadcast for high throughput):
 * ```kotlin
 * val server = createRemoteServer(
 *     initialState = ServerChatState(),
 *     reducer = serverChatReducer,
 *     stateMapper = { state -> SyncState(state.toChatState()) },
 *     broadcastConfig = BroadcastConfig(concurrency = 32),
 *     scope = applicationScope,
 * )
 * ```
 *
 * Example (custom session registry for distributed deployments):
 * ```kotlin
 * val server = createRemoteServer(
 *     initialState = ServerChatState(),
 *     reducer = serverChatReducer,
 *     stateMapper = { state -> SyncState(state.toChatState()) },
 *     sessionRegistry = RedisSessionRegistry(redis, codec),
 *     broadcastConfig = BroadcastConfig(concurrency = 64),
 *     scope = applicationScope,
 * )
 * ```
 *
 * In WebSocket handler:
 * ```kotlin
 * webSocket("/chat") {
 *     val connection = KtorWebSocketServerConnection(this)
 *         .typedJson<SharedChatAction>() as TypedServerConnection<ChatAction>
 *     server.handleClient(sessionId, connection)
 * }
 * ```
 *
 * Shutdown:
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
    private val serveJob: Job,
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
     */
    fun close() {
        serveJob.cancel()
        store.close()
    }
}

/**
 * Create a [RemoteServer] that manages a single [Store] serving multiple clients.
 *
 * Internally creates the session registry, broadcaster, middleware, store, and state broadcasting coroutine.
 *
 * @param initialState Initial state for the store.
 * @param reducer Reducer for processing actions.
 * @param processors Action processors for server-side action handling.
 * @param stateMapper Maps server state to an action (typically a [ClientSharedAction][io.flowdux.remote.ClientSharedAction])
 *   that will be broadcast to all clients on state change.
 * @param sessionRegistry Session registry for storing client connections. Defaults to [InMemorySessionRegistry].
 * @param broadcastConfig Configuration for broadcast behavior. Defaults to sequential.
 * @param errorProcessor Error processor for the store.
 * @param logger Logger for the store.
 * @param scope Coroutine scope for the store and state broadcasting.
 */
fun <S : State, A : Action> createRemoteServer(
    initialState: S,
    reducer: Reducer<S, A>,
    processors: ActionProcessorMap<S, A> = emptyMap(),
    stateMapper: (S) -> A,
    sessionRegistry: SessionRegistry<A> = InMemorySessionRegistry(),
    broadcastConfig: BroadcastConfig = BroadcastConfig.Sequential,
    errorProcessor: ErrorProcessor<A> = DefaultErrorProcessor(),
    logger: StoreLogger<S, A> = NoOpStoreLogger(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): RemoteServer<S, A> {
    val broadcaster = SessionBroadcaster(sessionRegistry, broadcastConfig)
    val middleware = MultiClientServerRemoteMiddleware<S, A>(processors, broadcaster)

    val store = createStore(
        initialState = initialState,
        middlewares = listOf(middleware),
        reducer = reducer,
        errorProcessor = errorProcessor,
        logger = logger,
        scope = scope,
    )

    val serveJob = scope.launch {
        store.serveState(stateMapper)
    }

    return RemoteServer(
        sessionRegistry = sessionRegistry,
        broadcaster = broadcaster,
        store = store,
        serveJob = serveJob,
    )
}

/**
 * Create a [RemoteServer] with per-session state mapping.
 *
 * Unlike [createRemoteServer] which broadcasts the same action to all clients,
 * this variant calls [sessionStateMapper] with the session ID for each connected client,
 * allowing each client to receive a different view of the state.
 *
 * State changes are observed directly and mapped per-session without going through
 * the dispatch pipeline.
 *
 * @param initialState Initial state for the store.
 * @param reducer Reducer for processing actions.
 * @param processors Action processors for server-side action handling.
 * @param sessionStateMapper Maps server state and session ID to an action for that session,
 *   or `null` to skip sending to that session.
 * @param sessionRegistry Session registry for storing client connections. Defaults to [InMemorySessionRegistry].
 * @param broadcastConfig Configuration for broadcast behavior. Defaults to sequential.
 * @param errorProcessor Error processor for the store.
 * @param logger Logger for the store.
 * @param scope Coroutine scope for the store and state broadcasting.
 */
fun <S : State, A : Action> createSessionAwareRemoteServer(
    initialState: S,
    reducer: Reducer<S, A>,
    processors: ActionProcessorMap<S, A> = emptyMap(),
    sessionStateMapper: (S, String) -> A?,
    sessionRegistry: SessionRegistry<A> = InMemorySessionRegistry(),
    broadcastConfig: BroadcastConfig = BroadcastConfig.Sequential,
    errorProcessor: ErrorProcessor<A> = DefaultErrorProcessor(),
    logger: StoreLogger<S, A> = NoOpStoreLogger(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): RemoteServer<S, A> {
    val broadcaster = SessionBroadcaster(sessionRegistry, broadcastConfig)
    val middleware = MultiClientServerRemoteMiddleware<S, A>(processors, broadcaster)

    val store = createStore(
        initialState = initialState,
        middlewares = listOf(middleware),
        reducer = reducer,
        errorProcessor = errorProcessor,
        logger = logger,
        scope = scope,
    )

    val serveJob = scope.launch {
        store.state.collect { state ->
            broadcaster.sendPerSession { sessionId ->
                sessionStateMapper(state, sessionId)
            }
        }
    }

    return RemoteServer(
        sessionRegistry = sessionRegistry,
        broadcaster = broadcaster,
        store = store,
        serveJob = serveJob,
    )
}
