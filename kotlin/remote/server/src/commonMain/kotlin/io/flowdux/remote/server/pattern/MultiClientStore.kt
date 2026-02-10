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
import io.flowdux.remote.server.createServerStore
import io.flowdux.remote.server.middleware.MultiClientSyncMiddleware
import io.flowdux.remote.server.session.SessionBroadcaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Create a [Store] configured for multi-client communication.
 *
 * The store is pre-configured with [MultiClientSyncMiddleware] that:
 * - Listens for incoming actions from multiple client connections (via [InternalAddSession])
 * - Processes [ServerSharedAction]s through the provided processors
 * - Passes actions to the reducer for state updates
 *
 * This function separates store creation from server creation, allowing for:
 * - Custom middleware composition (add other middlewares alongside MultiClientSyncMiddleware)
 * - Store testing in isolation
 * - Advanced configurations
 *
 * ```kotlin
 * val registry = InMemorySessionRegistry<ChatAction>()
 * val broadcaster = SessionBroadcaster(registry, BroadcastConfig.Default)
 *
 * val store = createMultiClientStore(
 *     initialState = ChatState(),
 *     reducer = chatReducer,
 *     broadcaster = broadcaster,
 *     processors = chatProcessors,
 * )
 *
 * val server = createSharedStateServer(
 *     store = store,
 *     registry = registry,
 *     stateMapper = { SyncState(it) },
 * )
 * ```
 *
 * @param initialState Initial state for the store.
 * @param reducer Reducer for processing actions.
 * @param broadcaster The broadcaster that will be used to send actions to clients.
 *                    Must be the same instance passed to [createSharedStateServer].
 * @param processors Action processors for server-side handling.
 * @param errorProcessor Error processor for the store.
 * @param logger Logger for the store.
 * @param scope Coroutine scope for the store.
 * @return A [Store] configured with [MultiClientSyncMiddleware].
 */
fun <S : State, A : Action> createMultiClientStore(
    initialState: S,
    reducer: Reducer<S, A>,
    broadcaster: SessionBroadcaster<A>,
    processors: ActionProcessorMap<S, A> = emptyMap(),
    errorProcessor: ErrorProcessor<A> = DefaultErrorProcessor(),
    logger: StoreLogger<S, A> = NoOpStoreLogger(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): Store<S, A> {
    val middleware = MultiClientSyncMiddleware<S, A>(processors, broadcaster)

    return createServerStore(
        initialState = initialState,
        syncMiddleware = middleware,
        reducer = reducer,
        errorProcessor = errorProcessor,
        logger = logger,
        scope = scope,
    )
}
