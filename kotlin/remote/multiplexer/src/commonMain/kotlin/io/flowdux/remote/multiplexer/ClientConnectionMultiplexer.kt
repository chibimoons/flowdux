package io.flowdux.remote.multiplexer

import io.flowdux.Action
import io.flowdux.remote.ConnectionState
import io.flowdux.remote.TypedClientConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Client-side connection multiplexer that routes actions to/from multiple rooms
 * over a single physical [TypedClientConnection].
 *
 * Each room gets a virtual [TypedClientConnection] that:
 * - Receives only actions routed to that room
 * - Sends actions tagged with that room's ID
 *
 * Usage:
 * ```kotlin
 * val physical = KtorWebSocketClientConnection.create(host, port, "/ws")
 *     .typedRoutedJson<SharedChatAction>()
 *
 * val mux = ClientConnectionMultiplexer(physical, scope)
 * mux.connect()
 *
 * // room-1, room-2 both share the same WebSocket
 * val room1Conn = mux.getOrCreateRoom("room-1")
 * val room2Conn = mux.getOrCreateRoom("room-2")
 * ```
 *
 * @param A The type of actions being multiplexed
 * @param physicalConnection The underlying connection that carries [RoutedAction] messages
 * @param scope The coroutine scope for the routing job
 */
@OptIn(ExperimentalAtomicApi::class)
class ClientConnectionMultiplexer<A : Action>(
    private val physicalConnection: TypedClientConnection<RoutedAction<A>>,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val rooms = mutableMapOf<String, VirtualClientConnection>()
    private var routingJob: Job? = null
    private var closed = false
    private val connecting = AtomicBoolean(false)

    /**
     * Connection state of the underlying physical connection.
     */
    val connectionState: StateFlow<ConnectionState> = physicalConnection.connectionState

    /**
     * Establishes the physical connection.
     *
     * This launches the connection in a background coroutine and starts routing
     * immediately. The connection runs until [disconnect] or [close] is called.
     *
     * This method is idempotent - calling it multiple times has no effect if
     * already connected.
     */
    fun connect() {
        // Atomic guard: prevents duplicate routing jobs from concurrent calls
        if (!connecting.compareAndSet(expectedValue = false, newValue = true)) {
            return
        }

        // Start routing first so we're ready to receive messages
        startRouting()
        // Launch connection in background (connect() suspends until closed)
        scope.launch {
            physicalConnection.connect()
        }
    }

    /**
     * Disconnects the physical connection and stops routing.
     *
     * Rooms are preserved so that [connect] can be called again to resume.
     * Use [removeRoom] to clean up individual rooms, or [close] to shut down entirely.
     */
    suspend fun disconnect() {
        routingJob?.cancel()
        routingJob?.join()
        routingJob = null
        connecting.store(false)
        physicalConnection.disconnect()
    }

    private fun startRouting() {
        routingJob = scope.launch {
            try {
                physicalConnection.incoming.collect { routedAction ->
                    val roomId = routedAction.roomId
                    val virtualConnection = mutex.withLock { rooms[roomId] }
                    if (virtualConnection != null) {
                        virtualConnection.channel.trySend(routedAction.action)
                    }
                    // Unknown rooms: silent drop (as per design decision)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Physical connection closed or transport error — routing stops.
                // This is expected when the server disconnects or the network drops.
                println("ClientConnectionMultiplexer: routing stopped (${e.message})")
            }
        }
    }

    /**
     * Gets an existing virtual connection for the room, or creates a new one.
     *
     * @param roomId The room identifier
     * @return A [TypedClientConnection] scoped to the specified room
     */
    suspend fun getOrCreateRoom(roomId: String): TypedClientConnection<A> = mutex.withLock {
        check(!closed) { "Multiplexer is closed" }
        rooms.getOrPut(roomId) { VirtualClientConnection(roomId) }
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
     *
     * Cancels the routing job, closes all virtual connection channels,
     * and disconnects the physical connection. After closing, no new rooms
     * can be created.
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
        routingJob?.join()
        routingJob = null
        connecting.store(false)
        physicalConnection.disconnect()
    }

    private inner class VirtualClientConnection(
        private val roomId: String,
    ) : TypedClientConnection<A> {
        val channel = Channel<A>(Channel.BUFFERED)

        override val connectionState: StateFlow<ConnectionState> =
            physicalConnection.connectionState

        override val incoming: Flow<A> = channel.receiveAsFlow()

        override suspend fun send(action: A) {
            val routedAction = RoutedAction(roomId, action)
            physicalConnection.send(routedAction)
        }

        override suspend fun connect() {
            // Virtual connections don't control the physical connection
        }

        override suspend fun disconnect() {
            // Virtual connections don't control the physical connection
            // Use removeRoom() to close a virtual connection
        }
    }
}
