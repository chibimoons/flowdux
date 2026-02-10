package io.flowdux.remote

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Creates a client-side Store with automatic [ServerSharedAction] re-dispatch support.
 *
 * This function sets up a [ClientDeliveryMiddleware] that ensures [ServerSharedAction]s
 * emitted from middleware processors are automatically re-dispatched through the full
 * pipeline, allowing [SyncMiddleware] to intercept and send them to the server.
 *
 * Example:
 * ```kotlin
 * class MySyncMiddleware(connection: TypedClientConnection<MyAction>) :
 *     SyncMiddleware<MyState, MyAction>(connection) {
 *
 *     override val processors = buildProcessors {
 *         on<Connect> { _, _ -> startConnection() }
 *         on<SendMessage> { _, action ->
 *             // With createClientStore, this ServerSharedAction is automatically
 *             // re-dispatched and sent to the server
 *             emit(ChatMessage(action.text))
 *         }
 *     }
 * }
 *
 * val store = createClientStore(
 *     initialState = MyState(),
 *     syncMiddleware = MySyncMiddleware(connection),
 *     reducer = myReducer,
 * )
 * ```
 *
 * @param initialState The initial state of the store.
 * @param syncMiddleware The [SyncMiddleware] for server communication.
 * @param additionalMiddlewares Additional middlewares to add before the sync middleware.
 * @param reducer The reducer function.
 * @param errorProcessor The error processor for handling exceptions.
 * @param logger The store logger for debugging.
 * @param scope The coroutine scope for the store.
 * @param concurrency The maximum concurrency for action processing.
 * @return A configured [Store] instance.
 */
fun <S : State, A : Action> createClientStore(
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
    val deliveryMiddleware = ClientDeliveryMiddleware<S, A> { store.dispatch(it) }

    // Order: additionalMiddlewares -> syncMiddleware -> deliveryMiddleware
    val allMiddlewares = additionalMiddlewares + syncMiddleware + deliveryMiddleware

    store = createStore(
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
