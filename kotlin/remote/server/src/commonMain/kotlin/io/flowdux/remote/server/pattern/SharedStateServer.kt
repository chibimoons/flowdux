package io.flowdux.remote.server.pattern

import io.flowdux.Action
import io.flowdux.ActionProcessorMap
import io.flowdux.DefaultErrorProcessor
import io.flowdux.ErrorProcessor
import io.flowdux.NoOpStoreLogger
import io.flowdux.Reducer
import io.flowdux.State
import io.flowdux.Store
import io.flowdux.StoreLogger
import io.flowdux.remote.server.ClientHandler
import io.flowdux.remote.server.connection.TypedServerConnection
import io.flowdux.remote.server.middleware.InternalAddSession
import io.flowdux.remote.server.serveState
import io.flowdux.remote.server.session.BroadcastConfig
import io.flowdux.remote.server.session.InMemorySessionRegistry
import io.flowdux.remote.server.session.SessionBroadcaster
import io.flowdux.remote.server.session.SessionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * A server for 1:1:N pattern (1 Server : 1 Store : N Clients).
 *
 * Combines a [Store], [SessionRegistry], [SessionBroadcaster], and state broadcasting
 * into a single object. All connected clients share the same state. State changes are
 * automatically broadcast to all clients.
 *
 * ```
 *                           ┌────────┐
 *                      ┌───▶│Client 1│
 * ┌────────┐  ┌───────┐│    └────────┘
 * │ Server │─▶│ Store │┼───▶│Client 2│
 * └────────┘  └───────┘│    └────────┘
 *                      └───▶│Client 3│
 *                           └────────┘
 * ```
 *
 * ## Creating a SharedStateServer
 *
 * Use [createSharedStateServer] for the recommended way:
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
 *
 * Use cases:
 * - Chat rooms (everyone sees the same messages)
 * - Real-time dashboards
 * - Collaborative tools
 *
 * @param S State type
 * @param A Action type
 */
class SharedStateServer<S : State, A : Action> internal constructor(
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
) : ClientHandler<A> {
    /** Current server state as a reactive flow. */
    val state: StateFlow<S> get() = store.state

    /** Current server state snapshot. */
    val currentState: S get() = store.currentState

    /** Snapshot of currently connected session IDs. */
    override suspend fun sessionIds(): Set<String> = sessionRegistry.sessionIds()

    /** Number of currently connected sessions. */
    override suspend fun sessionCount(): Int = sessionRegistry.sessionCount()

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
    override suspend fun handleClient(
        sessionId: String,
        connection: TypedServerConnection<A>,
    ) {
        store.dispatch(InternalAddSession(sessionId, connection) as A)
        sessionRegistry.addSession(sessionId, connection)
        try {
            awaitCancellation()
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
    override fun close() {
        serveJob.cancel()
        ownedScopeJob?.cancel()
        store.close()
    }
}

/**
 * Create a [SharedStateServer] for 1:1:N pattern.
 *
 * @param initialState Initial state for the store.
 * @param reducer Reducer for processing actions.
 * @param stateMapper Maps state to an action that will be broadcast to all clients.
 * @param processors Action processors for server-side handling.
 * @param sessionRegistry Session registry for storing client connections.
 * @param broadcastConfig Configuration for broadcast behavior.
 * @param errorProcessor Error processor for the store.
 * @param logger Logger for the store.
 * @param scope Coroutine scope for the store and state broadcasting.
 */
fun <S : State, A : Action> createSharedStateServer(
    initialState: S,
    reducer: Reducer<S, A>,
    stateMapper: (S) -> A,
    processors: ActionProcessorMap<S, A> = emptyMap(),
    sessionRegistry: SessionRegistry<A> = InMemorySessionRegistry(),
    broadcastConfig: BroadcastConfig = BroadcastConfig.Sequential,
    errorProcessor: ErrorProcessor<A> = DefaultErrorProcessor(),
    logger: StoreLogger<S, A> = NoOpStoreLogger(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): SharedStateServer<S, A> {
    val broadcaster = SessionBroadcaster(sessionRegistry, broadcastConfig)

    val store = createMultiClientStore(
        initialState = initialState,
        reducer = reducer,
        broadcaster = broadcaster,
        processors = processors,
        errorProcessor = errorProcessor,
        logger = logger,
        scope = scope,
    )

    return createSharedStateServer(
        store = store,
        broadcaster = broadcaster,
        stateMapper = stateMapper,
        scope = scope,
    )
}

/**
 * Create a [SharedStateServer] from an existing [Store].
 *
 * Use this when you need full control over store configuration. For most cases,
 * use [createMultiClientStore] to create the store:
 *
 * ```kotlin
 * val registry = InMemorySessionRegistry<MyAction>()
 * val broadcaster = SessionBroadcaster(registry, BroadcastConfig.Default)
 *
 * val store = createMultiClientStore(
 *     initialState = MyState(),
 *     reducer = myReducer,
 *     broadcaster = broadcaster,
 *     processors = myProcessors,
 * )
 *
 * val server = createSharedStateServer(
 *     store = store,
 *     broadcaster = broadcaster,
 *     stateMapper = { state -> SyncState(state) },
 *     scope = scope,
 * )
 * ```
 *
 * @param store The store to use for state management. Should be created with
 *              [createMultiClientStore] or configured with [MultiClientSyncMiddleware].
 * @param broadcaster The broadcaster for sending actions to clients. Must be the same
 *                    instance used when creating the store.
 * @param stateMapper Maps server state to an action that will be broadcast to all clients.
 * @param scope Coroutine scope for state broadcasting. The caller is responsible for
 *              managing the lifecycle of this scope.
 */
fun <S : State, A : Action> createSharedStateServer(
    store: Store<S, A>,
    broadcaster: SessionBroadcaster<A>,
    stateMapper: (S) -> A,
    scope: CoroutineScope,
): SharedStateServer<S, A> {
    val serveJob = scope.launch {
        store.serveState(stateMapper)
    }

    return SharedStateServer(
        sessionRegistry = broadcaster.registry,
        broadcaster = broadcaster,
        store = store,
        serveJob = serveJob,
    )
}

/**
 * Create a [SharedStateServer] from an existing [Store] with a default scope.
 *
 * The created scope is automatically cancelled when [SharedStateServer.close] is called.
 *
 * @param store The store to use for state management.
 * @param broadcaster The broadcaster for sending actions to clients.
 * @param stateMapper Maps server state to an action that will be broadcast to all clients.
 */
fun <S : State, A : Action> createSharedStateServer(
    store: Store<S, A>,
    broadcaster: SessionBroadcaster<A>,
    stateMapper: (S) -> A,
): SharedStateServer<S, A> {
    val ownedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val serveJob = ownedScope.launch {
        store.serveState(stateMapper)
    }

    return SharedStateServer(
        sessionRegistry = broadcaster.registry,
        broadcaster = broadcaster,
        store = store,
        serveJob = serveJob,
        ownedScopeJob = ownedScope.coroutineContext[Job],
    )
}

/**
 * Create a [SharedStateServer] with per-session state mapping.
 *
 * Unlike [createSharedStateServer] which broadcasts the same action to all clients,
 * this variant calls [sessionStateMapper] with the session ID for each connected client,
 * allowing each client to receive a different view of the state.
 *
 * @param initialState Initial state for the store.
 * @param reducer Reducer for processing actions.
 * @param sessionStateMapper Maps state and session ID to an action for that session.
 * @param processors Action processors for server-side handling.
 * @param sessionRegistry Session registry for storing client connections.
 * @param broadcastConfig Configuration for broadcast behavior.
 * @param errorProcessor Error processor for the store.
 * @param logger Logger for the store.
 * @param scope Coroutine scope for the store and state broadcasting.
 */
fun <S : State, A : Action> createSessionAwareSharedStateServer(
    initialState: S,
    reducer: Reducer<S, A>,
    sessionStateMapper: (S, String) -> A?,
    processors: ActionProcessorMap<S, A> = emptyMap(),
    sessionRegistry: SessionRegistry<A> = InMemorySessionRegistry(),
    broadcastConfig: BroadcastConfig = BroadcastConfig.Sequential,
    errorProcessor: ErrorProcessor<A> = DefaultErrorProcessor(),
    logger: StoreLogger<S, A> = NoOpStoreLogger(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): SharedStateServer<S, A> {
    val broadcaster = SessionBroadcaster(sessionRegistry, broadcastConfig)

    val store = createMultiClientStore(
        initialState = initialState,
        reducer = reducer,
        broadcaster = broadcaster,
        processors = processors,
        errorProcessor = errorProcessor,
        logger = logger,
        scope = scope,
    )

    return createSessionAwareSharedStateServer(
        store = store,
        broadcaster = broadcaster,
        sessionStateMapper = sessionStateMapper,
        scope = scope,
    )
}

/**
 * Create a [SharedStateServer] from an existing [Store] with per-session state mapping.
 *
 * Unlike [createSharedStateServer] which broadcasts the same action to all clients,
 * this variant calls [sessionStateMapper] with the session ID for each connected client,
 * allowing each client to receive a different view of the state.
 *
 * @param store The store to use for state management.
 * @param broadcaster The broadcaster for sending actions to clients.
 * @param sessionStateMapper Maps state and session ID to an action for that session.
 * @param scope Coroutine scope for state broadcasting. The caller is responsible for
 *              managing the lifecycle of this scope.
 */
fun <S : State, A : Action> createSessionAwareSharedStateServer(
    store: Store<S, A>,
    broadcaster: SessionBroadcaster<A>,
    sessionStateMapper: (S, String) -> A?,
    scope: CoroutineScope,
): SharedStateServer<S, A> {
    val serveJob = scope.launch {
        store.state.collect { state ->
            broadcaster.sendPerSession { sessionId ->
                sessionStateMapper(state, sessionId)
            }
        }
    }

    return SharedStateServer(
        sessionRegistry = broadcaster.registry,
        broadcaster = broadcaster,
        store = store,
        serveJob = serveJob,
    )
}

/**
 * Create a [SharedStateServer] from an existing [Store] with per-session state mapping and a default scope.
 *
 * The created scope is automatically cancelled when [SharedStateServer.close] is called.
 *
 * @param store The store to use for state management.
 * @param broadcaster The broadcaster for sending actions to clients.
 * @param sessionStateMapper Maps state and session ID to an action for that session.
 */
fun <S : State, A : Action> createSessionAwareSharedStateServer(
    store: Store<S, A>,
    broadcaster: SessionBroadcaster<A>,
    sessionStateMapper: (S, String) -> A?,
): SharedStateServer<S, A> {
    val ownedScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val serveJob = ownedScope.launch {
        store.state.collect { state ->
            broadcaster.sendPerSession { sessionId ->
                sessionStateMapper(state, sessionId)
            }
        }
    }

    return SharedStateServer(
        sessionRegistry = broadcaster.registry,
        broadcaster = broadcaster,
        store = store,
        serveJob = serveJob,
        ownedScopeJob = ownedScope.coroutineContext[Job],
    )
}
