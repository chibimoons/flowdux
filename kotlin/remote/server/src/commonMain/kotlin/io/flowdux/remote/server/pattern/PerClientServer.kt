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
import io.flowdux.remote.server.serve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A server for 1:N:N pattern (1 Server : N Sessions : N Private Stores).
 *
 * Each client gets their own dedicated [Store] with private state.
 * Unlike [SharedStateServer], clients don't share state with each other.
 *
 * ```
 *             ┌──────────┐     ┌────────┐
 *         ┌───│ Session1 │─────│Client 1│
 * ┌──────┐│   │ (Store)  │     └────────┘
 * │Server│┤   └──────────┘
 * └──────┘│
 *         │   ┌──────────┐     ┌────────┐
 *         └───│ Session2 │─────│Client 2│
 *             │ (Store)  │     └────────┘
 *             └──────────┘
 * ```
 *
 * Use cases:
 * - Poker (private hand per player)
 * - Turn-based games (private information per player)
 * - User-specific persistent sessions
 *
 * @param S State type
 * @param A Action type
 */
class PerClientServer<S : State, A : Action> internal constructor(
    private val sessionFactory: (sessionId: String, connection: TypedServerConnection<A>) -> Store<S, A>,
    private val stateMapper: (S) -> A,
    private val scope: CoroutineScope,
) : ClientHandler<A> {
    private val sessions = mutableMapOf<String, Store<S, A>>()
    private val mutex = Mutex()

    /**
     * Handle a client connection.
     *
     * Creates a new [Store] for the client, starts serving state to them,
     * and cleans up when the connection ends.
     *
     * @param sessionId Unique identifier for this client session.
     * @param connection Typed connection for sending/receiving actions.
     */
    override suspend fun handleClient(sessionId: String, connection: TypedServerConnection<A>) {
        val store =
            mutex.withLock {
                sessionFactory(sessionId, connection).also { store ->
                    sessions[sessionId] = store
                }
            }

        try {
            store.serve(stateMapper)
        } finally {
            mutex.withLock {
                sessions.remove(sessionId)
            }
            store.close()
        }
    }

    /**
     * Get a session by ID if it exists.
     *
     * @param sessionId Unique identifier for the session.
     * @return The [Store] if the session exists, null otherwise.
     */
    suspend fun getSession(sessionId: String): Store<S, A>? = mutex.withLock {
        sessions[sessionId]
    }

    /**
     * Get a snapshot of all active session IDs.
     */
    override suspend fun sessionIds(): Set<String> = mutex.withLock {
        sessions.keys.toSet()
    }

    /**
     * Get the number of active sessions.
     */
    override suspend fun sessionCount(): Int = mutex.withLock {
        sessions.size
    }

    /**
     * Close all sessions and the server.
     */
    override fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }
}

/**
 * Create a [PerClientServer] with a custom session factory.
 *
 * Use this when you need full control over the [Store] creation for each client.
 *
 * ```kotlin
 * val playerServer = createPerClientServer(
 *     stateMapper = { state -> SyncHand(state.hand) },
 *     scope = applicationScope,
 * ) { sessionId, connection ->
 *     createSingleClientServer(
 *         initialState = PlayerState(playerId = sessionId),
 *         reducer = playerReducer,
 *         connection = connection,
 *         processors = playerProcessors,
 *     )
 * }
 *
 * webSocket("/game/{playerId}") {
 *     val playerId = call.parameters["playerId"]!!
 *     playerServer.handleClient(playerId, connection)
 * }
 * ```
 *
 * @param stateMapper Maps state to an action that will be sent to the client.
 * @param scope Coroutine scope for the server.
 * @param sessionFactory Factory function that creates a [Store] for each client.
 * @return A [PerClientServer] managing per-client stores.
 */
fun <S : State, A : Action> createPerClientServer(
    stateMapper: (S) -> A,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    sessionFactory: (sessionId: String, connection: TypedServerConnection<A>) -> Store<S, A>,
): PerClientServer<S, A> = PerClientServer(sessionFactory, stateMapper, scope)

/**
 * Create a [PerClientServer] with simple configuration.
 *
 * All sessions share the same reducer, processors, and configuration.
 * The initial state factory receives the session ID.
 *
 * ```kotlin
 * val playerServer = createPerClientServer(
 *     initialStateFactory = { sessionId -> PlayerState(playerId = sessionId) },
 *     reducer = playerReducer,
 *     stateMapper = { state -> SyncHand(state.hand) },
 *     scope = applicationScope,
 * )
 *
 * webSocket("/game/{playerId}") {
 *     val playerId = call.parameters["playerId"]!!
 *     playerServer.handleClient(playerId, connection)
 * }
 * ```
 *
 * @param initialStateFactory Factory function that creates initial state for each session.
 * @param reducer Reducer for processing actions.
 * @param stateMapper Maps state to an action that will be sent to the client.
 * @param processors Action processors for server-side handling.
 * @param errorProcessor Error processor for each session's store.
 * @param logger Logger for each session's store.
 * @param scope Coroutine scope for the server.
 * @return A [PerClientServer] managing per-client stores.
 */
fun <S : State, A : Action> createPerClientServer(
    initialStateFactory: (sessionId: String) -> S,
    reducer: Reducer<S, A>,
    stateMapper: (S) -> A,
    processors: ActionProcessorMap<S, A> = emptyMap(),
    errorProcessor: ErrorProcessor<A> = DefaultErrorProcessor(),
    logger: StoreLogger<S, A> = NoOpStoreLogger(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): PerClientServer<S, A> = createPerClientServer(
    stateMapper = stateMapper,
    scope = scope,
) { sessionId, connection ->
    createSingleClientServer(
        initialState = initialStateFactory(sessionId),
        reducer = reducer,
        connection = connection,
        processors = processors,
        errorProcessor = errorProcessor,
        logger = logger,
        scope = scope,
    )
}
