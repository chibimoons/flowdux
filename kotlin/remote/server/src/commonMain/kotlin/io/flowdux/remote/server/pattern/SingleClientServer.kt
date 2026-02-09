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
import io.flowdux.remote.server.connection.TypedServerConnection
import io.flowdux.remote.server.middleware.SingleClientSyncMiddleware
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A server for 1:1:1 pattern (1 Server : 1 Connection : 1 Client).
 *
 * Each client gets their own dedicated Store. State changes are sent only
 * to the connected client.
 *
 * ```
 * ┌────────┐  ┌───────┐  ┌────────┐
 * │ Server │─▶│ Store │─▶│ Client │
 * └────────┘  └───────┘  └────────┘
 * ```
 *
 * Use cases:
 * - Single-player games
 * - Personal dashboards
 * - User-specific sessions
 *
 * This is a type alias for [Store], as the store itself handles client communication
 * via [SingleClientSyncMiddleware]. Use [serve][io.flowdux.remote.server.serve] to
 * start syncing state to the client.
 *
 * @param S State type
 * @param A Action type
 */
typealias SingleClientServer<S, A> = Store<S, A>

/**
 * Create a [Store] configured for single-client communication.
 *
 * The store is pre-configured with [SingleClientSyncMiddleware] that:
 * - Listens for incoming actions from the client connection
 * - Processes [ServerSharedAction]s through the provided processors
 * - Passes actions to the reducer for state updates
 *
 * Use [serve][io.flowdux.remote.server.serve] to start syncing state changes
 * back to the client.
 *
 * ```kotlin
 * val store = createSingleClientStore(
 *     initialState = GameState(),
 *     reducer = gameReducer,
 *     connection = connection,
 *     processors = gameProcessors,
 * )
 * store.serve { SyncState(it) }
 * ```
 *
 * @param initialState Initial state for the store.
 * @param reducer Reducer for processing actions.
 * @param connection Typed connection for sending/receiving actions with the client.
 * @param processors Action processors for server-side handling.
 * @param errorProcessor Error processor for the store.
 * @param logger Logger for the store.
 * @param scope Coroutine scope for the store.
 * @return A [Store] configured with [SingleClientSyncMiddleware].
 */
fun <S : State, A : Action> createSingleClientStore(
    initialState: S,
    reducer: Reducer<S, A>,
    connection: TypedServerConnection<A>,
    processors: ActionProcessorMap<S, A> = emptyMap(),
    errorProcessor: ErrorProcessor<A> = DefaultErrorProcessor(),
    logger: StoreLogger<S, A> = NoOpStoreLogger(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): Store<S, A> {
    val middleware = if (processors.isEmpty()) {
        SingleClientSyncMiddleware(connection)
    } else {
        object : SingleClientSyncMiddleware<S, A>(connection) {
            override val processors: ActionProcessorMap<S, A> = processors
        }
    }

    return createStore(
        initialState = initialState,
        reducer = reducer,
        middlewares = listOf(middleware),
        errorProcessor = errorProcessor,
        logger = logger,
        scope = scope,
    )
}

/**
 * Create a [SingleClientServer] for 1:1:1 pattern.
 *
 * This is an alias for [createSingleClientStore] that emphasizes the server role.
 *
 * ```kotlin
 * webSocket("/game") {
 *     val connection = KtorWebSocketServerConnection(this)
 *         .typedJson<GameAction>() as TypedServerConnection<GameAction>
 *
 *     val server = createSingleClientServer(
 *         initialState = GameState(),
 *         reducer = gameReducer,
 *         connection = connection,
 *     )
 *     server.serve { SyncState(it) }
 * }
 * ```
 *
 * @param initialState Initial state for the store.
 * @param reducer Reducer for processing actions.
 * @param connection Typed connection for sending/receiving actions with the client.
 * @param processors Action processors for server-side handling.
 * @param errorProcessor Error processor for the store.
 * @param logger Logger for the store.
 * @param scope Coroutine scope for the store.
 * @return A [SingleClientServer] (Store) configured with [SingleClientSyncMiddleware].
 */
fun <S : State, A : Action> createSingleClientServer(
    initialState: S,
    reducer: Reducer<S, A>,
    connection: TypedServerConnection<A>,
    processors: ActionProcessorMap<S, A> = emptyMap(),
    errorProcessor: ErrorProcessor<A> = DefaultErrorProcessor(),
    logger: StoreLogger<S, A> = NoOpStoreLogger(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): SingleClientServer<S, A> = createSingleClientStore(
    initialState = initialState,
    reducer = reducer,
    connection = connection,
    processors = processors,
    errorProcessor = errorProcessor,
    logger = logger,
    scope = scope,
)
