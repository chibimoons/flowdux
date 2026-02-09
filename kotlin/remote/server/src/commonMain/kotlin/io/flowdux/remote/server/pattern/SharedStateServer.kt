package io.flowdux.remote.server.pattern

import io.flowdux.Action
import io.flowdux.ActionProcessorMap
import io.flowdux.DefaultErrorProcessor
import io.flowdux.ErrorProcessor
import io.flowdux.NoOpStoreLogger
import io.flowdux.Reducer
import io.flowdux.State
import io.flowdux.StoreLogger
import io.flowdux.remote.server.RemoteServer
import io.flowdux.remote.server.createRemoteServer
import io.flowdux.remote.server.createSessionAwareRemoteServer
import io.flowdux.remote.server.session.BroadcastConfig
import io.flowdux.remote.server.session.InMemorySessionRegistry
import io.flowdux.remote.server.session.SessionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
): SharedStateServer<S, A> = createRemoteServer(
    initialState = initialState,
    reducer = reducer,
    processors = processors,
    stateMapper = stateMapper,
    sessionRegistry = sessionRegistry,
    broadcastConfig = broadcastConfig,
    errorProcessor = errorProcessor,
    logger = logger,
    scope = scope,
)

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
): SharedStateServer<S, A> = createSessionAwareRemoteServer(
    initialState = initialState,
    reducer = reducer,
    processors = processors,
    sessionStateMapper = sessionStateMapper,
    sessionRegistry = sessionRegistry,
    broadcastConfig = broadcastConfig,
    errorProcessor = errorProcessor,
    logger = logger,
    scope = scope,
)
