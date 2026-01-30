package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.ActionProcessorMap
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Manages a single [Store] that serves multiple client connections simultaneously.
 *
 * Encapsulates the Store, middleware, and state broadcasting so that users
 * only interact with this session object. Clients are added via [handleClient]
 * and automatically removed when the coroutine is cancelled (e.g., WebSocket disconnect).
 *
 * Example:
 * ```kotlin
 * val session = createRemoteServerSession(
 *     initialState = ServerChatState(),
 *     reducer = serverChatReducer,
 *     stateMapper = { state -> SyncState(state.toChatState()) },
 *     scope = applicationScope,
 * )
 *
 * // In WebSocket handler:
 * webSocket("/chat") {
 *     val connection = KtorWebSocketServerConnection(this)
 *         .typedJson<SharedChatAction>() as TypedServerConnection<ChatAction>
 *     session.handleClient(sessionId, connection)
 * }
 *
 * // Shutdown:
 * session.close()
 * ```
 */
class RemoteServerSession<S : State, A : Action> internal constructor(
    private val store: Store<S, A>,
    private val middleware: MultiClientServerRemoteMiddleware<S, A>,
    private val serveJob: Job,
) {
    /** Current server state as a reactive flow. */
    val state: StateFlow<S> get() = store.state

    /** Current server state snapshot. */
    val currentState: S get() = store.currentState

    /** Snapshot of currently connected session IDs. */
    suspend fun sessionIds(): Set<String> = middleware.sessionIds()

    /** Number of currently connected sessions. */
    suspend fun sessionCount(): Int = middleware.sessionCount()

    /**
     * Handle a client connection.
     *
     * Registers the client, suspends until the calling coroutine is cancelled
     * (e.g., WebSocket disconnect), then automatically removes the session.
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
        try {
            awaitCancellation()
        } finally {
            store.dispatch(InternalRemoveSession(sessionId) as A)
        }
    }

    /**
     * Send an action to a specific client by session ID.
     * No-op if the session does not exist.
     */
    suspend fun sendToClient(sessionId: String, action: A) {
        middleware.sendToClient(sessionId, action)
    }

    /**
     * Send an action to all connected clients.
     */
    suspend fun broadcast(action: A) {
        middleware.broadcast(action)
    }

    /**
     * Close the session, stopping state broadcasting and closing the store.
     */
    fun close() {
        serveJob.cancel()
        store.close()
    }
}

/**
 * Create a [RemoteServerSession] that manages a single [Store] serving multiple clients.
 *
 * Internally creates the middleware, store, and state broadcasting coroutine.
 *
 * @param initialState Initial state for the store.
 * @param reducer Reducer for processing actions.
 * @param processors Action processors for server-side action handling.
 * @param stateMapper Maps server state to an action (typically a [ClientSharedAction][io.flowdux.remote.ClientSharedAction])
 *   that will be broadcast to all clients on state change.
 * @param errorProcessor Error processor for the store.
 * @param logger Logger for the store.
 * @param scope Coroutine scope for the store and state broadcasting.
 */
fun <S : State, A : Action> createRemoteServerSession(
    initialState: S,
    reducer: Reducer<S, A>,
    processors: ActionProcessorMap<S, A> = emptyMap(),
    stateMapper: (S) -> A,
    errorProcessor: ErrorProcessor<A> = DefaultErrorProcessor(),
    logger: StoreLogger<S, A> = NoOpStoreLogger(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): RemoteServerSession<S, A> {
    val middleware = MultiClientServerRemoteMiddleware<S, A>(processors)

    val store = createStore(
        initialState = initialState,
        middlewares = listOf(middleware),
        reducer = reducer,
        errorProcessor = errorProcessor,
        logger = logger,
        scope = scope,
    )

    val serveJob = scope.launch {
        store.serveState(stateMapper)
    }

    return RemoteServerSession(
        store = store,
        middleware = middleware,
        serveJob = serveJob,
    )
}
