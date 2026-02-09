package io.flowdux.sample.multiplexer.server

import io.flowdux.remote.multiplexer.ServerConnectionMultiplexer
import io.flowdux.remote.server.pattern.RoomServer
import io.flowdux.remote.server.pattern.SharedStateServer
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
 * 2. Registers the client with the room's SharedStateServer
 * 3. Starts handling actions for that room
 */
class ClientSession(
    val sessionId: String,
    private val roomServer: RoomServer<SharedStateServer<ServerRoomState, ChatAction>>,
) {
    // Track which rooms this client has joined and their handler jobs
    private val roomJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

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
        if (roomId in roomJobs) {
            println("[ClientSession $sessionId] Already in room: $roomId")
            return
        }

        println("[ClientSession $sessionId] Joining room: $roomId")

        // Get or create the room using RoomServer
        val room = roomServer.getOrCreateRoom(roomId)

        // Create a virtual connection for this room through the multiplexer
        val virtualConnection = multiplexer.getOrCreateRoom(roomId)

        // Register with the room's SharedStateServer (launches handler in background)
        // Track the job so we can cancel it when leaving the room
        // The upcast is needed because SharedChatAction extends ChatAction
        @Suppress("UNCHECKED_CAST")
        val job = scope.launch {
            room.handleClient(
                sessionId,
                virtualConnection as io.flowdux.remote.server.connection.TypedServerConnection<ChatAction>,
            )
        }

        // Store the job for cleanup on leave
        roomJobs[roomId] = job

        // Wait for session to be registered before dispatching
        // Note: This delay is a simple approach for demo purposes. In production,
        // consider using a deterministic signal (e.g., callback or channel).
        kotlinx.coroutines.delay(50)

        // Dispatch the JoinRoom action to the room (it was consumed by the callback)
        room.store.dispatch(joinAction)
    }

    /**
     * Leave a room.
     */
    private suspend fun leaveRoom(roomId: String) {
        val job = roomJobs.remove(roomId) ?: return

        println("[ClientSession $sessionId] Leaving room: $roomId")

        // Cancel the handleClient job to unregister from sessionRegistry
        job.cancel()

        if (::multiplexer.isInitialized) {
            multiplexer.removeRoom(roomId)
        }
    }

    /**
     * Leave all rooms (called on disconnect).
     */
    suspend fun leaveAllRooms() {
        roomJobs.keys.toList().forEach { roomId ->
            leaveRoom(roomId)
        }
        // Signal connection close
        connectionActive.complete(Unit)
    }
}
