package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.DefaultErrorProcessor
import io.flowdux.ErrorProcessor
import io.flowdux.Middleware
import io.flowdux.NoOpStoreLogger
import io.flowdux.Reducer
import io.flowdux.State
import io.flowdux.Store
import io.flowdux.StoreLogger
import io.flowdux.createStore
import io.flowdux.remote.server.middleware.ClientSharedActionForwarder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Creates a server-side Store with automatic [ClientSharedAction][io.flowdux.remote.ClientSharedAction]
 * re-dispatch support.
 *
 * This function sets up a [ClientSharedActionForwarder] that ensures [ClientSharedAction]s
 * emitted from middleware processors are automatically re-dispatched through the full
 * pipeline, allowing sync middlewares to intercept and send them to the client(s).
 *
 * Example:
 * ```kotlin
 * class MyGameMiddleware(connection: TypedServerConnection<GameAction>) :
 *     SingleClientSyncMiddleware<GameState, GameAction>(connection) {
 *
 *     override val processors = buildProcessors {
 *         on<ScoreChanged> { state, action ->
 *             // With createServerStore, this ClientSharedAction is automatically
 *             // re-dispatched and sent to the client
 *             emit(ScoreUpdate(state.score))
 *             emit(action)  // Update server state
 *         }
 *     }
 * }
 *
 * val store = createServerStore(
 *     initialState = GameState(),
 *     syncMiddleware = MyGameMiddleware(connection),
 *     reducer = gameReducer,
 * )
 * ```
 *
 * @param initialState The initial state of the store.
 * @param syncMiddleware The sync middleware (SingleClientSyncMiddleware or MultiClientSyncMiddleware).
 * @param additionalMiddlewares Additional middlewares to add before the sync middleware.
 * @param reducer The reducer function.
 * @param errorProcessor The error processor for handling exceptions.
 * @param logger The store logger for debugging.
 * @param scope The coroutine scope for the store.
 * @param concurrency The maximum concurrency for action processing.
 * @return A configured [Store] instance.
 */
fun <S : State, A : Action> createServerStore(
    initialState: S,
    syncMiddleware: Middleware<S, A>,
    additionalMiddlewares: List<Middleware<S, A>> = emptyList(),
    reducer: Reducer<S, A>,
    errorProcessor: ErrorProcessor<A> = DefaultErrorProcessor(),
    logger: StoreLogger<S, A> = NoOpStoreLogger(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    concurrency: Int = 16,
): Store<S, A> {
    lateinit var store: Store<S, A>
    val forwarder = ClientSharedActionForwarder<S, A> { store.dispatch(it) }

    // Order: additionalMiddlewares -> syncMiddleware -> forwarder
    val allMiddlewares = additionalMiddlewares + syncMiddleware + forwarder

    store =
        createStore(
            initialState = initialState,
            middlewares = allMiddlewares,
            reducer = reducer,
            errorProcessor = errorProcessor,
            logger = logger,
            scope = scope,
            concurrency = concurrency,
        )
    return store
}
