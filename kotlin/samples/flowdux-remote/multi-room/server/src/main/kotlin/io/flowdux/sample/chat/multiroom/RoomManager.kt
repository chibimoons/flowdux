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

    /** Get all active room IDs */
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

    /** Destroy a room */
    fun destroyRoom(roomId: String) {
        rooms.remove(roomId)?.let { room ->
            println("[RoomManager] Destroying room: $roomId")
            room.close()
        }
    }

    /** Clean up empty rooms */
    suspend fun cleanupEmptyRooms() {
        val emptyRooms = rooms.entries.filter { (_, room) ->
            room.sessionCount() == 0
        }.map { it.key }

        emptyRooms.forEach { roomId ->
            destroyRoom(roomId)
        }

        if (emptyRooms.isNotEmpty()) {
            println("[RoomManager] Cleaned up ${emptyRooms.size} empty rooms: $emptyRooms")
        }
    }

    /** Print status of all rooms */
    suspend fun printStatus() {
        println("\n=== Room Status ===")
        if (rooms.isEmpty()) {
            println("No active rooms")
        } else {
            rooms.forEach { (roomId, room) ->
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
