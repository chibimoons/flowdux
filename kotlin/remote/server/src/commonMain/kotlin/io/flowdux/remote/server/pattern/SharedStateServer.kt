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
import io.flowdux.remote.server.middleware.InternalPerSessionStateServing
import io.flowdux.remote.server.middleware.InternalSendToClient
import io.flowdux.remote.server.middleware.InternalSessionListener
import io.flowdux.remote.server.middleware.InternalStateServing
import io.flowdux.remote.server.session.BroadcastConfig
import io.flowdux.remote.server.session.InMemorySessionRegistry
import io.flowdux.remote.server.session.SessionBroadcaster
import io.flowdux.remote.server.session.SessionRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

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
     * The session registry managing client connections (read-only access).
     */
    private val sessionRegistry: SessionRegistry<A>,
    /**
     * The underlying store.
     */
    val store: Store<S, A>,
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
     * Registers the session in the registry and dispatches [InternalSessionListener]
     * to start listening for incoming messages. Suspends until the connection's incoming
     * flow terminates (error or completion) or until cancelled, then removes
     * the session from the registry directly to ensure cleanup even if the store is closed.
     *
     * @param sessionId Unique identifier for this client session.
     * @param connection Typed connection for sending/receiving actions.
     */
    @Suppress("UNCHECKED_CAST")
    override suspend fun handleClient(sessionId: String, connection: TypedServerConnection<A>) {
        sessionRegistry.addSession(sessionId, connection)
        val listenerDone = CompletableDeferred<Unit>()
        if (store.isClosed) {
            listenerDone.complete(Unit)
        } else {
            store.dispatch(
                InternalSessionListener(connection) { listenerDone.complete(Unit) } as A,
            )
            // Handle race: store may have closed between isClosed check and dispatch,
            // causing dispatch to silently drop. Complete to avoid hanging.
            if (store.isClosed) listenerDone.complete(Unit)
        }
        try {
            listenerDone.await()
        } finally {
            // Direct removal ensures cleanup even if store is already closed
            sessionRegistry.removeSession(sessionId)
        }
    }

    /**
     * Send an action to a specific client by session ID.
     * No-op if the session does not exist.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun sendToClient(sessionId: String, action: A) {
        store.dispatch(InternalSendToClient(sessionId, action) as A)
    }

    /**
     * Send an action to all connected clients.
     *
     * The action should be a [ClientSharedAction] to be broadcast properly.
     */
    suspend fun broadcast(action: A) {
        store.dispatch(action)
    }

    /**
     * Close the server, stopping state broadcasting and closing the store.
     */
    override fun close() {
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
 * @param scope Coroutine scope for the store.
 */
@Suppress("UNCHECKED_CAST")
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

    val store =
        createMultiClientStore(
            initialState = initialState,
            reducer = reducer,
            broadcaster = broadcaster,
            processors = processors,
            errorProcessor = errorProcessor,
            logger = logger,
            scope = scope,
        )

    // Start state broadcasting via FlowHolderAction directly
    store.dispatch(InternalStateServing(store.state, stateMapper as (Any) -> Action) as A)

    return SharedStateServer(
        sessionRegistry = sessionRegistry,
        store = store,
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
 *     registry = registry,
 *     stateMapper = { state -> SyncState(state) },
 * )
 * ```
 *
 * @param store The store to use for state management. Should be created with
 *              [createMultiClientStore] or configured with [MultiClientSyncMiddleware].
 * @param registry The session registry for managing client connections.
 * @param stateMapper Maps server state to an action that will be broadcast to all clients.
 */
@Suppress("UNCHECKED_CAST")
fun <S : State, A : Action> createSharedStateServer(
    store: Store<S, A>,
    registry: SessionRegistry<A>,
    stateMapper: (S) -> A,
): SharedStateServer<S, A> {
    // Start state broadcasting via FlowHolderAction directly
    store.dispatch(InternalStateServing(store.state, stateMapper as (Any) -> Action) as A)

    return SharedStateServer(
        sessionRegistry = registry,
        store = store,
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
 * @param scope Coroutine scope for the store.
 */
@Suppress("UNCHECKED_CAST")
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

    val store =
        createMultiClientStore(
            initialState = initialState,
            reducer = reducer,
            broadcaster = broadcaster,
            processors = processors,
            errorProcessor = errorProcessor,
            logger = logger,
            scope = scope,
        )

    // Start per-session state broadcasting via FlowHolderAction directly
    store.dispatch(
        InternalPerSessionStateServing(
            store.state,
            sessionStateMapper as (Any, String) -> Action?,
            sessionRegistry,
        ) as A,
    )

    return SharedStateServer(
        sessionRegistry = sessionRegistry,
        store = store,
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
 * @param registry The session registry for managing client connections.
 * @param sessionStateMapper Maps state and session ID to an action for that session.
 */
@Suppress("UNCHECKED_CAST")
fun <S : State, A : Action> createSessionAwareSharedStateServer(
    store: Store<S, A>,
    registry: SessionRegistry<A>,
    sessionStateMapper: (S, String) -> A?,
): SharedStateServer<S, A> {
    // Start per-session state broadcasting via FlowHolderAction directly
    store.dispatch(
        InternalPerSessionStateServing(
            store.state,
            sessionStateMapper as (Any, String) -> Action?,
            registry,
        ) as A,
    )

    return SharedStateServer(
        sessionRegistry = registry,
        store = store,
    )
}
