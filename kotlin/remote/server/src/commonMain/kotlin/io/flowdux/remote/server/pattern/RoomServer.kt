package io.flowdux.remote.server.pattern

import io.flowdux.Action
import io.flowdux.ActionProcessorMap
import io.flowdux.DefaultErrorProcessor
import io.flowdux.ErrorProcessor
import io.flowdux.NoOpStoreLogger
import io.flowdux.Reducer
import io.flowdux.State
import io.flowdux.StoreLogger
import io.flowdux.remote.server.ClientHandler
import io.flowdux.remote.server.session.BroadcastConfig
import io.flowdux.remote.server.session.InMemorySessionRegistry
import io.flowdux.remote.server.session.SessionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A server for 1:N:M pattern (1 Server : N Rooms : M Clients per room).
 *
 * Manages multiple rooms, each with its own [ClientHandler].
 * Each room can be a [SharedStateServer] (shared state) or [PerClientServer] (per-client state).
 *
 * ```
 *                  ┌──────────┐     ┌────────┐
 *              ┌───│  Room 1  │─────│Client A│
 * ┌────────┐  │   │  (Store) │─────│Client B│
 * │ Server │──┤   └──────────┘     └────────┘
 * └────────┘  │
 *             │   ┌──────────┐     ┌────────┐
 *             └───│  Room 2  │─────│Client C│
 *                 │  (Store) │
 *                 └──────────┘
 * ```
 *
 * Use cases:
 * - Chat rooms (with [SharedStateServer])
 * - Multiplayer game lobbies (with [SharedStateServer])
 * - Poker tables with private hands (with [PerClientServer])
 * - Collaborative editing per document
 *
 * @param H The type of [ClientHandler] for each room (e.g., [SharedStateServer] or [PerClientServer])
 */
class RoomServer<H : ClientHandler<*>> internal constructor(
    private val roomFactory: (roomId: String) -> H,
    private val scope: CoroutineScope,
) {
    private val rooms = mutableMapOf<String, H>()
    private val mutex = Mutex()

    /**
     * Get or create a room by ID.
     *
     * If the room doesn't exist, it's created using the [roomFactory].
     *
     * @param roomId Unique identifier for the room.
     * @return The [ClientHandler] for the room.
     */
    suspend fun getOrCreateRoom(roomId: String): H = mutex.withLock {
        rooms.getOrPut(roomId) {
            roomFactory(roomId)
        }
    }

    /**
     * Get a room by ID if it exists.
     *
     * @param roomId Unique identifier for the room.
     * @return The [ClientHandler] if the room exists, null otherwise.
     */
    suspend fun getRoom(roomId: String): H? = mutex.withLock {
        rooms[roomId]
    }

    /**
     * Destroy a room, closing its [ClientHandler].
     *
     * @param roomId Unique identifier for the room.
     * @return true if the room existed and was destroyed, false otherwise.
     */
    suspend fun destroyRoom(roomId: String): Boolean = mutex.withLock {
        rooms.remove(roomId)?.let { room ->
            room.close()
            true
        } ?: false
    }

    /**
     * Destroy a room only if it has no active sessions.
     *
     * @param roomId Unique identifier for the room.
     * @return true if the room was empty and destroyed, false otherwise.
     */
    suspend fun destroyRoomIfEmpty(roomId: String): Boolean {
        return mutex.withLock {
            val room = rooms[roomId] ?: return@withLock false
            if (room.sessionCount() > 0) {
                return@withLock false
            }
            rooms.remove(roomId)
            room.close()
            true
        }
    }

    /**
     * Get a snapshot of all active room IDs.
     */
    suspend fun roomIds(): Set<String> = mutex.withLock {
        rooms.keys.toSet()
    }

    /**
     * Get the number of active rooms.
     */
    suspend fun roomCount(): Int = mutex.withLock {
        rooms.size
    }

    /**
     * Clean up all rooms with no active sessions.
     *
     * @return The list of room IDs that were destroyed.
     */
    suspend fun cleanupEmptyRooms(): List<String> {
        val destroyedRooms = mutableListOf<String>()
        mutex.withLock {
            val emptyRoomIds =
                rooms
                    .filter { (_, room) ->
                        room.sessionCount() == 0
                    }.keys
                    .toList()

            emptyRoomIds.forEach { roomId ->
                rooms.remove(roomId)?.let { room ->
                    room.close()
                    destroyedRooms.add(roomId)
                }
            }
        }
        return destroyedRooms
    }

    /**
     * Close all rooms and the server.
     */
    fun close() {
        rooms.values.forEach { it.close() }
        rooms.clear()
    }
}

/**
 * Create a [RoomServer] with a custom room factory.
 *
 * This is the most flexible way to create a RoomServer, supporting any [ClientHandler] type.
 *
 * ```kotlin
 * // With SharedStateServer (shared state per room)
 * val chatRoomServer = createRoomServer { roomId ->
 *     createSharedStateServer(
 *         initialState = ChatState(roomId = roomId),
 *         reducer = chatReducer,
 *         stateMapper = { SyncState(it) },
 *         scope = applicationScope,
 *     )
 * }
 *
 * // With PerClientServer (private state per client within each room)
 * val pokerLobby = createRoomServer { tableId ->
 *     createPerClientServer(
 *         initialStateFactory = { playerId -> PlayerState(playerId) },
 *         reducer = playerReducer,
 *         stateMapper = { SyncHand(it.hand) },
 *         scope = applicationScope,
 *     )
 * }
 *
 * webSocket("/room/{roomId}") {
 *     val roomId = call.parameters["roomId"]!!
 *     val room = roomServer.getOrCreateRoom(roomId)
 *     room.handleClient(sessionId, connection)
 * }
 * ```
 *
 * @param scope Coroutine scope for the server.
 * @param roomFactory Factory function that creates a [ClientHandler] for each room.
 * @return A [RoomServer] managing multiple rooms.
 */
fun <H : ClientHandler<*>> createRoomServer(
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    roomFactory: (roomId: String) -> H,
): RoomServer<H> = RoomServer(roomFactory, scope)

/**
 * Create a [RoomServer] with [SharedStateServer] rooms using simple configuration.
 *
 * All rooms share the same reducer, processors, and configuration.
 * The initial state factory receives the room ID.
 *
 * ```kotlin
 * val roomServer = createSharedStateRoomServer(
 *     initialStateFactory = { roomId -> ChatState(roomId = roomId) },
 *     reducer = chatReducer,
 *     stateMapper = { SyncState(it) },
 *     scope = applicationScope,
 * )
 * ```
 *
 * @param initialStateFactory Factory function that creates initial state for each room.
 * @param reducer Reducer for processing actions.
 * @param stateMapper Maps state to an action that will be broadcast to all clients in the room.
 * @param processors Action processors for server-side handling.
 * @param sessionRegistryFactory Factory for creating session registries per room.
 * @param broadcastConfig Configuration for broadcast behavior.
 * @param errorProcessor Error processor for each room's store.
 * @param logger Logger for each room's store.
 * @param scope Coroutine scope for the server and all rooms.
 * @return A [RoomServer] managing multiple [SharedStateServer] rooms.
 */
fun <S : State, A : Action> createSharedStateRoomServer(
    initialStateFactory: (roomId: String) -> S,
    reducer: Reducer<S, A>,
    stateMapper: (S) -> A,
    processors: ActionProcessorMap<S, A> = emptyMap(),
    sessionRegistryFactory: () -> SessionRegistry<A> = { InMemorySessionRegistry() },
    broadcastConfig: BroadcastConfig = BroadcastConfig.Sequential,
    errorProcessor: ErrorProcessor<A> = DefaultErrorProcessor(),
    logger: StoreLogger<S, A> = NoOpStoreLogger(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): RoomServer<SharedStateServer<S, A>> = createRoomServer(scope) { roomId ->
    createSharedStateServer(
        initialState = initialStateFactory(roomId),
        reducer = reducer,
        stateMapper = stateMapper,
        processors = processors,
        sessionRegistry = sessionRegistryFactory(),
        broadcastConfig = broadcastConfig,
        errorProcessor = errorProcessor,
        logger = logger,
        scope = scope,
    )
}

/**
 * Create a [RoomServer] with [PerClientServer] rooms using simple configuration.
 *
 * Each room has its own [PerClientServer], where each client within a room
 * has private state that is not shared with other clients.
 *
 * ```kotlin
 * val pokerLobby = createPerClientRoomServer(
 *     initialStateFactory = { roomId, playerId -> PlayerState(roomId, playerId) },
 *     reducer = playerReducer,
 *     stateMapper = { SyncHand(it.hand) },
 *     scope = applicationScope,
 * )
 *
 * webSocket("/table/{tableId}/{playerId}") {
 *     val tableId = call.parameters["tableId"]!!
 *     val playerId = call.parameters["playerId"]!!
 *     val table = pokerLobby.getOrCreateRoom(tableId)
 *     table.handleClient(playerId, connection)
 * }
 * ```
 *
 * @param initialStateFactory Factory function that creates initial state for each session.
 *                            Receives both roomId and sessionId.
 * @param reducer Reducer for processing actions.
 * @param stateMapper Maps state to an action that will be sent to the client.
 * @param processors Action processors for server-side handling.
 * @param errorProcessor Error processor for each session's store.
 * @param logger Logger for each session's store.
 * @param scope Coroutine scope for the server and all rooms.
 * @return A [RoomServer] managing multiple [PerClientServer] rooms.
 */
fun <S : State, A : Action> createPerClientRoomServer(
    initialStateFactory: (roomId: String, sessionId: String) -> S,
    reducer: Reducer<S, A>,
    stateMapper: (S) -> A,
    processors: ActionProcessorMap<S, A> = emptyMap(),
    errorProcessor: ErrorProcessor<A> = DefaultErrorProcessor(),
    logger: StoreLogger<S, A> = NoOpStoreLogger(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): RoomServer<PerClientServer<S, A>> = createRoomServer(scope) { roomId ->
    createPerClientServer(
        initialStateFactory = { sessionId -> initialStateFactory(roomId, sessionId) },
        reducer = reducer,
        stateMapper = stateMapper,
        processors = processors,
        errorProcessor = errorProcessor,
        logger = logger,
        scope = scope,
    )
}
