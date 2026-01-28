package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.State
import io.flowdux.Store
import kotlinx.coroutines.flow.StateFlow

/**
 * Handles a single client session on the server side.
 *
 * The [storeFactory] receives a [ServerConnection] so the caller can create
 * any [ServerRemoteMiddleware] subclass and include it in the middleware pipeline.
 *
 * Example:
 * ```kotlin
 * val handler = ServerSessionHandler(
 *     storeFactory = { connection ->
 *         val srm = MyChatSRM(connection)
 *         createStore(
 *             initialState = ServerState(),
 *             reducer = serverReducer,
 *             middlewares = listOf(MyServerMiddleware(), srm),
 *             scope = scope,
 *         )
 *     },
 *     connection = serverConnection,
 * )
 * handler.initialize()
 * handler.dispatch(StartListeningAction)
 * handler.close()
 * ```
 *
 * @param storeFactory Factory that creates a Store, receiving the [ServerConnection]
 *   to build a [ServerRemoteMiddleware] (or subclass) and include it in the middleware pipeline.
 * @param connection The transport layer for communicating with the client.
 */
class ServerSessionHandler<S : State, A : Action>(
    private val storeFactory: (ServerConnection) -> Store<S, A>,
    private val connection: ServerConnection,
) {
    private lateinit var store: Store<S, A>

    /**
     * Current state of the internal store.
     */
    val state: StateFlow<S>
        get() = store.state

    /**
     * Initialize the internal store.
     * Must be called before [dispatch].
     */
    fun initialize() {
        store = storeFactory(connection)
    }

    /**
     * Dispatch an action to the store.
     *
     * @param action The action to dispatch.
     */
    fun dispatch(action: A) {
        store.dispatch(action)
    }

    /**
     * Close the internal store and release resources.
     */
    fun close() {
        if (::store.isInitialized) {
            store.close()
        }
    }
}
