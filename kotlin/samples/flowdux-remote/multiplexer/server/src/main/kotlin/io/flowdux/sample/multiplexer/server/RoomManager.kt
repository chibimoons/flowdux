package io.flowdux.sample.multiplexer.server

import io.flowdux.Middleware
import io.flowdux.remote.server.pattern.SharedStateServer
import io.flowdux.remote.server.pattern.createSharedStateServer
import io.flowdux.sample.multiplexer.ChatAction
import io.flowdux.sample.multiplexer.RoomState
import io.flowdux.sample.multiplexer.SharedChatAction
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages multiple chat rooms, each with its own independent [SharedStateServer].
 *
 * This is similar to the multi-room sample's RoomManager, but designed to work
 * with the ConnectionMultiplexer where a single client can participate in
 * multiple rooms over one connection.
 */
class RoomManager(
    private val applicationScope: CoroutineScope,
) {
    private val rooms = ConcurrentHashMap<String, SharedStateServer<ServerRoomState, ChatAction>>()

    /** Get all active room IDs */
    fun getRoomIds(): Set<String> = rooms.keys.toSet()

    /** Get room count */
    fun roomCount(): Int = rooms.size

    /** Get or create a room */
    fun getOrCreateRoom(roomId: String): SharedStateServer<ServerRoomState, ChatAction> {
        return rooms.computeIfAbsent(roomId) { id ->
            println("[RoomManager] Creating room: $id")
            createSharedStateServer(
                initialState = ServerRoomState(roomId = id),
                reducer = serverRoomReducer,
                processors = roomProcessors(),
                stateMapper = { state ->
                    SharedChatAction.SyncState(
                        RoomState(
                            roomId = state.roomId,
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

    /** Destroy a room if it's empty */
    suspend fun destroyRoomIfEmpty(roomId: String): Boolean {
        val room = rooms[roomId] ?: return false

        if (room.sessionCount() > 0) {
            return false
        }

        val removed = rooms.remove(roomId, room)
        if (removed) {
            println("[RoomManager] Destroying empty room: $roomId")
            room.close()
        }
        return removed
    }

    /** Clean up all empty rooms */
    suspend fun cleanupEmptyRooms() {
        val destroyedRooms = mutableListOf<String>()

        rooms.keys.toList().forEach { roomId ->
            if (destroyRoomIfEmpty(roomId)) {
                destroyedRooms.add(roomId)
            }
        }

        if (destroyedRooms.isNotEmpty()) {
            println("[RoomManager] Cleaned up ${destroyedRooms.size} empty rooms: $destroyedRooms")
        }
    }

    /** Print status of all rooms */
    suspend fun printStatus() {
        val snapshot = rooms.toMap()

        if (snapshot.isEmpty()) {
            println("  No active rooms")
        } else {
            snapshot.forEach { (roomId, room) ->
                val state = room.currentState
                println("  [$roomId] users=${state.users.size}, messages=${state.messages.size}")
            }
        }
    }

    /** Shutdown all rooms */
    fun shutdown() {
        println("[RoomManager] Shutting down ${rooms.size} rooms...")
        rooms.values.forEach { it.close() }
        rooms.clear()
    }
}

private fun roomProcessors() =
    Middleware.ActionProcessorBuilder<ServerRoomState, ChatAction>().apply {
        on<SharedChatAction.SendMessage> { _, action ->
            emit(ServerRoomAction.MessageReceived(user = action.user, text = action.text))
        }
        on<SharedChatAction.JoinRoom> { _, action ->
            emit(ServerRoomAction.UserJoined(user = action.user))
        }
        on<SharedChatAction.LeaveRoom> { _, action ->
            emit(ServerRoomAction.UserLeft(user = action.user))
        }
    }.build()
