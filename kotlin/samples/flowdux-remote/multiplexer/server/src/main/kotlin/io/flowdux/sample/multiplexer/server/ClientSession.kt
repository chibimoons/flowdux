package io.flowdux.sample.multiplexer.server

import io.flowdux.remote.multiplexer.ServerConnectionMultiplexer
import io.flowdux.sample.multiplexer.ChatAction
import io.flowdux.sample.multiplexer.SharedChatAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Manages a single client's participation in multiple rooms.
 *
 * When a client sends a JoinRoom action for a new room, this session:
 * 1. Creates a virtual connection for that room via the multiplexer
 * 2. Registers the client with the room's RemoteServer
 * 3. Starts handling actions for that room
 */
class ClientSession(
    val sessionId: String,
    private val roomManager: RoomManager,
) {
    // Track which rooms this client has joined
    private val joinedRooms = mutableSetOf<String>()

    // Multiplexer is set after construction (due to circular dependency with callback)
    private lateinit var multiplexer: ServerConnectionMultiplexer<SharedChatAction>

    // Used to keep the connection alive
    private val connectionActive = CompletableDeferred<Unit>()

    fun setMultiplexer(mux: ServerConnectionMultiplexer<SharedChatAction>) {
        multiplexer = mux
    }

    /**
     * Handle an action that arrived for a room (called from multiplexer callback).
     */
    suspend fun handleAction(roomId: String, action: SharedChatAction, scope: CoroutineScope) {
        when (action) {
            is SharedChatAction.JoinRoom -> {
                // Join the room and forward the action
                joinRoom(roomId, action, scope)
            }
            is SharedChatAction.LeaveRoom -> {
                // Leave the room
                leaveRoom(roomId)
            }
            else -> {
                // For other actions, the room should already exist
                // This shouldn't happen if client follows protocol
                println("[ClientSession $sessionId] Received action for unknown room: $roomId")
            }
        }
    }

    /**
     * Handle the client's connection - keeps the session alive.
     */
    suspend fun handleConnection() = coroutineScope {
        // Wait until the connection is closed (signaled externally)
        connectionActive.await()
    }

    /**
     * Join a room and start handling its actions.
     */
    private suspend fun joinRoom(roomId: String, joinAction: SharedChatAction.JoinRoom, scope: CoroutineScope) {
        if (roomId in joinedRooms) {
            println("[ClientSession $sessionId] Already in room: $roomId")
            return
        }

        println("[ClientSession $sessionId] Joining room: $roomId")

        // Get or create the room
        val room = roomManager.getOrCreateRoom(roomId)

        // Create a virtual connection for this room through the multiplexer
        val virtualConnection = multiplexer.getOrCreateRoom(roomId)

        // Register with the room's RemoteServer (launches handler in background)
        // The upcast is needed because SharedChatAction extends ChatAction
        @Suppress("UNCHECKED_CAST")
        scope.launch {
            room.handleClient(
                sessionId,
                virtualConnection as io.flowdux.remote.server.TypedServerConnection<ChatAction>,
            )
        }

        // Wait for session to be registered before dispatching
        kotlinx.coroutines.delay(50)

        joinedRooms.add(roomId)

        // Dispatch the JoinRoom action to the room (it was consumed by the callback)
        room.store.dispatch(joinAction)
    }

    /**
     * Leave a room.
     */
    private suspend fun leaveRoom(roomId: String) {
        if (roomId !in joinedRooms) {
            return
        }

        println("[ClientSession $sessionId] Leaving room: $roomId")
        if (::multiplexer.isInitialized) {
            multiplexer.removeRoom(roomId)
        }
        joinedRooms.remove(roomId)
    }

    /**
     * Leave all rooms (called on disconnect).
     */
    suspend fun leaveAllRooms() {
        joinedRooms.toList().forEach { roomId ->
            leaveRoom(roomId)
        }
        // Signal connection close
        connectionActive.complete(Unit)
    }
}
