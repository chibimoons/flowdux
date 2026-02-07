package io.flowdux.remote.multiplexer

import io.flowdux.Action
import io.flowdux.remote.server.TypedServerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Server-side connection multiplexer that routes actions to/from multiple rooms
 * over a single physical [TypedServerConnection].
 *
 * Each room gets a virtual [TypedServerConnection] that:
 * - Receives only actions routed to that room
 * - Sends actions tagged with that room's ID
 *
 * Usage:
 * ```kotlin
 * webSocket("/ws") {
 *     val physical = KtorWebSocketServerConnection(this)
 *         .typedRoutedJson<SharedChatAction>()
 *
 *     val mux = ServerConnectionMultiplexer(physical, this) { roomId, action ->
 *         // Handle action for unknown room (e.g., JoinRoom)
 *         handleNewRoom(roomId, action)
 *     }
 *
 *     // When client requests to join a room
 *     val virtualConn = mux.getOrCreateRoom("room-1")
 *     roomManager.getRoom("room-1").handleClient(sessionId, virtualConn)
 * }
 * ```
 *
 * @param A The type of actions being multiplexed
 * @param physicalConnection The underlying connection that carries [RoutedAction] messages
 * @param scope The coroutine scope for the routing job
 * @param onUnknownRoom Optional callback invoked when an action arrives for an unknown room.
 *        This allows dynamic room creation (e.g., for JoinRoom actions).
 */
class ServerConnectionMultiplexer<A : Action>(
    private val physicalConnection: TypedServerConnection<RoutedAction<A>>,
    private val scope: CoroutineScope,
    private val onUnknownRoom: (suspend (roomId: String, action: A) -> Unit)? = null,
) {
    private val mutex = Mutex()
    private val rooms = mutableMapOf<String, VirtualServerConnection>()
    private var routingJob: Job? = null
    private var closed = false

    init {
        startRouting()
    }

    private fun startRouting() {
        routingJob = scope.launch {
            try {
                physicalConnection.incoming.collect { routedAction ->
                    val roomId = routedAction.roomId
                    val virtualConnection = mutex.withLock { rooms[roomId] }
                    if (virtualConnection != null) {
                        virtualConnection.channel.send(routedAction.action)
                    } else if (onUnknownRoom != null) {
                        // Invoke callback for unknown room (e.g., to handle JoinRoom)
                        onUnknownRoom.invoke(roomId, routedAction.action)
                    }
                    // If no callback and unknown room: silent drop
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Connection closed or error
            }
        }
    }

    /**
     * Gets an existing virtual connection for the room, or creates a new one.
     *
     * @param roomId The room identifier
     * @return A [TypedServerConnection] scoped to the specified room
     */
    suspend fun getOrCreateRoom(roomId: String): TypedServerConnection<A> = mutex.withLock {
        check(!closed) { "Multiplexer is closed" }
        rooms.getOrPut(roomId) { VirtualServerConnection(roomId) }
    }

    /**
     * Removes a room from the multiplexer and closes its virtual connection.
     *
     * @param roomId The room identifier to remove
     */
    suspend fun removeRoom(roomId: String) {
        val virtualConnection = mutex.withLock {
            rooms.remove(roomId)
        }
        virtualConnection?.channel?.close()
    }

    /**
     * Returns the set of currently active room IDs.
     */
    suspend fun roomIds(): Set<String> = mutex.withLock {
        rooms.keys.toSet()
    }

    /**
     * Checks if a room exists in the multiplexer.
     *
     * @param roomId The room identifier to check
     * @return true if the room exists
     */
    suspend fun hasRoom(roomId: String): Boolean = mutex.withLock {
        rooms.containsKey(roomId)
    }

    /**
     * Closes the multiplexer and all virtual connections.
     */
    suspend fun close() {
        val virtualConnections = mutex.withLock {
            closed = true
            val connections = rooms.values.toList()
            rooms.clear()
            connections
        }
        virtualConnections.forEach { it.channel.close() }
        routingJob?.cancel()
    }

    private inner class VirtualServerConnection(
        private val roomId: String,
    ) : TypedServerConnection<A> {
        val channel = Channel<A>(Channel.BUFFERED)

        override val incoming: Flow<A> = channel.receiveAsFlow()

        override suspend fun send(action: A) {
            val routedAction = RoutedAction(roomId, action)
            physicalConnection.send(routedAction)
        }
    }
}
