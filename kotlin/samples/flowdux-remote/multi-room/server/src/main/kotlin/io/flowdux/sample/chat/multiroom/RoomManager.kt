package io.flowdux.sample.chat.multiroom

import io.flowdux.Middleware
import io.flowdux.remote.server.RemoteServer
import io.flowdux.remote.server.createRemoteServer
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatState
import io.flowdux.sample.chat.SharedChatAction
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages multiple chat rooms, each with its own independent [RemoteServer].
 *
 * This demonstrates the Room Store pattern where:
 * - Each room has its own Store and state
 * - Rooms are isolated from each other
 * - Rooms can be created/destroyed dynamically
 */
class RoomManager(
    private val applicationScope: CoroutineScope,
) {
    private val rooms = ConcurrentHashMap<String, RemoteServer<ServerChatState, ChatAction>>()

    /** Get all active room IDs (snapshot) */
    fun getRoomIds(): Set<String> = rooms.keys.toSet()

    /** Get room count */
    fun roomCount(): Int = rooms.size

    /** Create a new room or return existing one */
    fun getOrCreateRoom(roomId: String): RemoteServer<ServerChatState, ChatAction> {
        return rooms.computeIfAbsent(roomId) { id ->
            println("[RoomManager] Creating room: $id")
            createRemoteServer(
                initialState = ServerChatState(roomId = id),
                reducer = serverChatReducer,
                processors = chatProcessors(),
                stateMapper = { state ->
                    println("[Room $id] State changed: users=${state.users}, messages=${state.messages.size}")
                    SharedChatAction.SyncState(
                        ChatState(
                            messages = state.messages,
                            users = state.users,
                            lastEvent = state.lastEvent,
                        )
                    )
                },
                scope = applicationScope,
            )
        }
    }

    /** Get room by ID */
    fun getRoom(roomId: String): RemoteServer<ServerChatState, ChatAction>? = rooms[roomId]

    /**
     * Destroy a room only if it's empty (no active sessions).
     *
     * Note: There's a small race window between checking sessionCount and removal,
     * but this is acceptable for cleanup purposes - a newly connected client would
     * simply create a new room via getOrCreateRoom.
     *
     * @return true if room was destroyed, false if room has sessions or doesn't exist
     */
    suspend fun destroyRoomIfEmpty(roomId: String): Boolean {
        val room = rooms[roomId] ?: return false

        // Check if empty - if a client connects after this check,
        // they'll get a new room via getOrCreateRoom (acceptable behavior)
        if (room.sessionCount() > 0) {
            return false
        }

        // Try to remove - only succeeds if room is still the same instance
        val removed = rooms.remove(roomId, room)
        if (removed) {
            println("[RoomManager] Destroying empty room: $roomId")
            room.close()
        }
        return removed
    }

    /** Force destroy a room regardless of session count */
    fun forceDestroyRoom(roomId: String) {
        rooms.remove(roomId)?.let { room ->
            println("[RoomManager] Force destroying room: $roomId")
            room.close()
        }
    }

    /** Clean up empty rooms */
    suspend fun cleanupEmptyRooms() {
        val destroyedRooms = mutableListOf<String>()

        // Take snapshot of keys to avoid concurrent modification
        rooms.keys.toList().forEach { roomId ->
            if (destroyRoomIfEmpty(roomId)) {
                destroyedRooms.add(roomId)
            }
        }

        if (destroyedRooms.isNotEmpty()) {
            println("[RoomManager] Cleaned up ${destroyedRooms.size} empty rooms: $destroyedRooms")
        }
    }

    /** Print status of all rooms (snapshot-based) */
    suspend fun printStatus() {
        // Take snapshot to avoid concurrent modification during iteration
        val snapshot = rooms.toMap()

        println("\n=== Room Status ===")
        if (snapshot.isEmpty()) {
            println("No active rooms")
        } else {
            snapshot.forEach { (roomId, room) ->
                val state = room.currentState
                println("  [$roomId] users=${state.users}, messages=${state.messages.size}")
            }
        }
        println("===================\n")
    }

    /** Shutdown all rooms */
    fun shutdown() {
        println("[RoomManager] Shutting down ${rooms.size} rooms...")
        rooms.values.forEach { it.close() }
        rooms.clear()
    }
}

private fun chatProcessors() =
    Middleware.ActionProcessorBuilder<ServerChatState, ChatAction>().apply {
        on<SharedChatAction.SendMessage> { _, action ->
            emit(ServerChatAction.MessageReceived(user = action.user, text = action.text))
        }
        on<SharedChatAction.JoinRoom> { _, action ->
            emit(ServerChatAction.UserJoined(user = action.user))
        }
        on<SharedChatAction.LeaveRoom> { _, action ->
            emit(ServerChatAction.UserLeft(user = action.user))
        }
    }.build()
