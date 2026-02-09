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
import io.flowdux.remote.server.middleware.MultiClientSyncMiddleware
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
 * ## Creating a RemoteServer
 *
 * ### Option 1: Full control with store (recommended)
 * ```kotlin
 * // 1. Create session infrastructure
 * val registry = InMemorySessionRegistry<ChatAction>()
 * val broadcaster = SessionBroadcaster(registry, BroadcastConfig.Default)
 *
 * // 2. Create middleware (custom or default)
 * val middleware = MultiClientSyncMiddleware<ServerState, ChatAction>(
 *     processors = myProcessors,
 *     broadcaster = broadcaster,
 * )
 *
 * // 3. Create store with full flexibility
 * val store = createStore(
 *     initialState = ServerState(),
 *     reducer = serverReducer,
 *     middlewares = listOf(middleware, loggingMiddleware), // add any middlewares
 *     scope = applicationScope,
 * )
 *
 * // 4. Create server
 * val server = createRemoteServer(
 *     store = store,
 *     broadcaster = broadcaster,
 *     stateMapper = { state -> SyncState(state) },
 * )
 * ```
 *
 * ### Option 2: Quick setup (convenience)
 * ```kotlin
 * val server = createRemoteServer(
 *     initialState = ServerState(),
 *     reducer = serverReducer,
 *     processors = myProcessors,
 *     stateMapper = { state -> SyncState(state) },
 *     scope = applicationScope,
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
 * Create a [RemoteServer] from an existing [Store].
 *
 * This is the recommended way to create a RemoteServer as it gives full control
 * over store configuration, middleware, and other settings.
 *
 * ```kotlin
 * val registry = InMemorySessionRegistry<MyAction>()
 * val broadcaster = SessionBroadcaster(registry, BroadcastConfig.Default)
 * val middleware = MultiClientSyncMiddleware<MyState, MyAction>(processors, broadcaster)
 *
 * val store = createStore(
 *     initialState = MyState(),
 *     reducer = myReducer,
 *     middlewares = listOf(middleware, otherMiddleware),
 *     scope = scope,
 * )
 *
 * val server = createRemoteServer(
 *     store = store,
 *     broadcaster = broadcaster,
 *     stateMapper = { state -> SyncState(state) },
 * )
 * ```
 *
 * @param store The store to use for state management.
 * @param broadcaster The broadcaster for sending actions to clients.
 * @param stateMapper Maps server state to an action (typically a [ClientSharedAction][io.flowdux.remote.ClientSharedAction])
 *   that will be broadcast to all clients on state change.
 * @param scope Coroutine scope for state broadcasting. Defaults to the store's scope if not provided.
 */
fun <S : State, A : Action> createRemoteServer(
    store: Store<S, A>,
    broadcaster: SessionBroadcaster<A>,
    stateMapper: (S) -> A,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): RemoteServer<S, A> {
    val serveJob = scope.launch {
        store.serveState(stateMapper)
    }

    return RemoteServer(
        sessionRegistry = broadcaster.registry,
        broadcaster = broadcaster,
        store = store,
        serveJob = serveJob,
    )
}

/**
 * Create a [RemoteServer] from an existing [Store] with per-session state mapping.
 *
 * Unlike [createRemoteServer] which broadcasts the same action to all clients,
 * this variant calls [sessionStateMapper] with the session ID for each connected client,
 * allowing each client to receive a different view of the state.
 *
 * ```kotlin
 * val server = createSessionAwareRemoteServer(
 *     store = store,
 *     broadcaster = broadcaster,
 *     sessionStateMapper = { state, sessionId ->
 *         // Return personalized state for each session
 *         SyncPlayerView(hand = state.hands[sessionId] ?: emptyList())
 *     },
 * )
 * ```
 *
 * @param store The store to use for state management.
 * @param broadcaster The broadcaster for sending actions to clients.
 * @param sessionStateMapper Maps server state and session ID to an action for that session,
 *   or `null` to skip sending to that session.
 * @param scope Coroutine scope for state broadcasting.
 */
fun <S : State, A : Action> createSessionAwareRemoteServer(
    store: Store<S, A>,
    broadcaster: SessionBroadcaster<A>,
    sessionStateMapper: (S, String) -> A?,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): RemoteServer<S, A> {
    val serveJob = scope.launch {
        store.state.collect { state ->
            broadcaster.sendPerSession { sessionId ->
                sessionStateMapper(state, sessionId)
            }
        }
    }

    return RemoteServer(
        sessionRegistry = broadcaster.registry,
        broadcaster = broadcaster,
        store = store,
        serveJob = serveJob,
    )
}

// ============================================================================
// Convenience functions (creates store internally)
// ============================================================================

/**
 * Create a [RemoteServer] with a new store (convenience function).
 *
 * This function creates the store internally. For more control over middleware
 * and store configuration, use the overload that accepts a [Store] directly.
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
    val middleware = MultiClientSyncMiddleware<S, A>(processors, broadcaster)

    val store = createStore(
        initialState = initialState,
        middlewares = listOf(middleware),
        reducer = reducer,
        errorProcessor = errorProcessor,
        logger = logger,
        scope = scope,
    )

    return createRemoteServer(
        store = store,
        broadcaster = broadcaster,
        stateMapper = stateMapper,
        scope = scope,
    )
}

/**
 * Create a [RemoteServer] with per-session state mapping (convenience function).
 *
 * This function creates the store internally. For more control over middleware
 * and store configuration, use the overload that accepts a [Store] directly.
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
    val middleware = MultiClientSyncMiddleware<S, A>(processors, broadcaster)

    val store = createStore(
        initialState = initialState,
        middlewares = listOf(middleware),
        reducer = reducer,
        errorProcessor = errorProcessor,
        logger = logger,
        scope = scope,
    )

    return createSessionAwareRemoteServer(
        store = store,
        broadcaster = broadcaster,
        sessionStateMapper = sessionStateMapper,
        scope = scope,
    )
}
