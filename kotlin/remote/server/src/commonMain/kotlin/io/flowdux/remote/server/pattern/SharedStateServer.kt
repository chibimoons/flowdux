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
import io.flowdux.createStore
import io.flowdux.remote.server.RemoteServer
import io.flowdux.remote.server.middleware.MultiClientSyncMiddleware
import io.flowdux.remote.server.serveState
import io.flowdux.remote.server.session.BroadcastConfig
import io.flowdux.remote.server.session.InMemorySessionRegistry
import io.flowdux.remote.server.session.SessionBroadcaster
import io.flowdux.remote.server.session.SessionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A server for 1:1:N pattern (1 Server : 1 Store : N Clients).
 *
 * All connected clients share the same state. State changes are
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
 * Use cases:
 * - Chat rooms (everyone sees the same messages)
 * - Real-time dashboards
 * - Collaborative tools
 *
 * @param S State type
 * @param A Action type
 */
typealias SharedStateServer<S, A> = RemoteServer<S, A>

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
    val middleware = MultiClientSyncMiddleware<S, A>(processors, broadcaster)

    val store = createStore(
        initialState = initialState,
        middlewares = listOf(middleware),
        reducer = reducer,
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
 * This is the recommended way to create a SharedStateServer as it gives full control
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
 * val server = createSharedStateServer(
 *     store = store,
 *     broadcaster = broadcaster,
 *     stateMapper = { state -> SyncState(state) },
 *     scope = scope,
 * )
 * ```
 *
 * @param store The store to use for state management.
 * @param broadcaster The broadcaster for sending actions to clients.
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

    return RemoteServer(
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

    return RemoteServer(
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
    val middleware = MultiClientSyncMiddleware<S, A>(processors, broadcaster)

    val store = createStore(
        initialState = initialState,
        middlewares = listOf(middleware),
        reducer = reducer,
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

    return RemoteServer(
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

    return RemoteServer(
        sessionRegistry = broadcaster.registry,
        broadcaster = broadcaster,
        store = store,
        serveJob = serveJob,
        ownedScopeJob = ownedScope.coroutineContext[Job],
    )
}
