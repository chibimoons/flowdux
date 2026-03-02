package io.flowdux.sample.multiplexer.client

import io.flowdux.Store
import io.flowdux.remote.TypedClientConnection
import io.flowdux.remote.createClientStore
import io.flowdux.remote.multiplexer.ClientConnectionMultiplexer
import io.flowdux.sample.multiplexer.ChatAction
import io.flowdux.sample.multiplexer.SharedChatAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages multiple room connections for a single client.
 *
 * Each room has its own Store with its own state, but they all share
 * the same physical WebSocket connection through the multiplexer.
 */
class ClientRoomManager(
    private val username: String,
    private val multiplexer: ClientConnectionMultiplexer<SharedChatAction>,
    private val scope: CoroutineScope,
) {
    private val rooms = mutableMapOf<String, RoomConnection>()
    var activeRoom: String = ""
        private set

    /** Get list of joined rooms */
    fun joinedRooms(): List<String> = rooms.keys.toList()

    /** Join a room */
    suspend fun joinRoom(roomId: String) {
        if (roomId in rooms) {
            println("[Client] Already in room: $roomId")
            switchRoom(roomId)
            return
        }

        println("[Client] Joining room: $roomId")

        // Get virtual connection for this room
        val virtualConnection = multiplexer.getOrCreateRoom(roomId)

        // Create store for this room
        @Suppress("UNCHECKED_CAST")
        val store = createRoomStore(roomId, virtualConnection as TypedClientConnection<ChatAction>)

        val roomConnection = RoomConnection(roomId, store)
        rooms[roomId] = roomConnection

        // Start observing room state
        roomConnection.startObserving(scope, username)

        // Connect and join
        store.dispatch(ClientRoomAction.Connect)
        delay(100)
        store.dispatch(SharedChatAction.JoinRoom(username))

        // Switch to this room
        activeRoom = roomId
        println("[Client] Joined room: $roomId (now active)")
    }

    /** Leave a room */
    suspend fun leaveRoom(roomId: String) {
        val roomConnection =
            rooms.remove(roomId) ?: run {
                println("[Client] Not in room: $roomId")
                return
            }

        println("[Client] Leaving room: $roomId")

        // Send leave action
        roomConnection.store.dispatch(SharedChatAction.LeaveRoom(username))
        delay(100)

        // Disconnect and close
        roomConnection.store.dispatch(ClientRoomAction.Disconnect)
        roomConnection.close()

        // Remove from multiplexer
        multiplexer.removeRoom(roomId)

        // Switch active room if needed
        if (activeRoom == roomId) {
            activeRoom = rooms.keys.firstOrNull() ?: ""
            if (activeRoom.isNotEmpty()) {
                println("[Client] Switched to room: $activeRoom")
            }
        }
    }

    /** Leave all rooms */
    suspend fun leaveAllRooms() {
        rooms.keys.toList().forEach { roomId ->
            leaveRoom(roomId)
        }
    }

    /** Switch active room */
    fun switchRoom(roomId: String) {
        if (roomId !in rooms) {
            println("[Client] Not in room: $roomId. Use /join $roomId first.")
            return
        }

        activeRoom = roomId
        println("[Client] Switched to room: $roomId")
    }

    /** Send message to active room */
    fun sendMessage(text: String) {
        val roomConnection = rooms[activeRoom]
        if (roomConnection == null) {
            println("[Client] No active room. Use /join <room> first.")
            return
        }

        roomConnection.store.dispatch(SharedChatAction.SendMessage(username, text))
    }

    private fun createRoomStore(
        roomId: String,
        connection: TypedClientConnection<ChatAction>,
    ): Store<ClientRoomState, ChatAction> = createClientStore(
        initialState = ClientRoomState(roomId = roomId),
        syncMiddleware = RoomRemoteMiddleware(connection),
        reducer = clientRoomReducer,
    )
}

/**
 * Represents a connection to a single room.
 */
class RoomConnection(val roomId: String, val store: Store<ClientRoomState, ChatAction>) {
    private var observerJob: kotlinx.coroutines.Job? = null

    fun startObserving(scope: CoroutineScope, currentUser: String) {
        observerJob =
            scope.launch {
                var lastEventId = 0
                store.state.collect { state ->
                    // Only show events we haven't seen
                    val event = state.lastEvent
                    if (event != null && state.hashCode() != lastEventId) {
                        lastEventId = state.hashCode()

                        when (event) {
                            is io.flowdux.sample.multiplexer.ChatEvent.UserJoined -> {
                                if (event.user != currentUser) {
                                    println("\n[$roomId] ${event.user} joined the room")
                                }
                            }
                            is io.flowdux.sample.multiplexer.ChatEvent.UserLeft -> {
                                println("\n[$roomId] ${event.user} left the room")
                            }
                            is io.flowdux.sample.multiplexer.ChatEvent.MessageReceived -> {
                                if (event.user != currentUser) {
                                    println("\n[$roomId] ${event.user}: ${event.text}")
                                }
                            }
                        }
                    }
                }
            }
    }

    fun close() {
        observerJob?.cancel()
        store.close()
    }
}
