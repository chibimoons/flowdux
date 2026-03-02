package io.flowdux.sample.chat.multiroom

import io.flowdux.Middleware
import io.flowdux.remote.ktor.KtorWebSocketServerConnection
import io.flowdux.remote.serialization.typedJson
import io.flowdux.remote.server.connection.TypedServerConnection
import io.flowdux.remote.server.pattern.createSharedStateRoomServer
import io.flowdux.sample.chat.ChatAction
import io.flowdux.sample.chat.ChatState
import io.flowdux.sample.chat.SharedChatAction
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Multi-Room Chat Server Demo
 *
 * This sample demonstrates the Room Store pattern with multiple independent rooms
 * using [createSharedStateRoomServer]:
 * - Dynamic room creation/destruction
 * - Room isolation (messages stay within their room)
 * - Automatic empty room cleanup
 *
 * Endpoints:
 * - GET /rooms — List active rooms
 * - WS /room/{roomId} — Connect to a specific room
 */
fun main() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Create room server using the new pattern-based API
    val roomServer = createSharedStateRoomServer(
        initialStateFactory = { roomId ->
            println("[RoomServer] Creating room: $roomId")
            ServerChatState(roomId = roomId)
        },
        reducer = serverChatReducer,
        processors = chatProcessors(),
        stateMapper = { state ->
            println("[Room ${state.roomId}] State changed: users=${state.users}, messages=${state.messages.size}")
            SharedChatAction.SyncState(
                ChatState(
                    messages = state.messages,
                    users = state.users,
                    lastEvent = state.lastEvent,
                ),
            )
        },
        scope = applicationScope,
    )

    // Periodic cleanup of empty rooms
    applicationScope.launch {
        while (isActive) {
            delay(30_000) // Every 30 seconds
            val destroyed = roomServer.cleanupEmptyRooms()
            if (destroyed.isNotEmpty()) {
                println("[RoomServer] Cleaned up ${destroyed.size} empty rooms: $destroyed")
            }
        }
    }

    // Periodic status logging
    applicationScope.launch {
        while (isActive) {
            delay(10_000) // Every 10 seconds
            printStatus(roomServer)
        }
    }

    println(
        """
        ╔══════════════════════════════════════════════════╗
        ║     FlowDux Multi-Room Chat Server               ║
        ╠══════════════════════════════════════════════════╣
        ║  Endpoints:                                      ║
        ║    GET  /rooms         - List active rooms       ║
        ║    WS   /room/{roomId} - Connect to a room       ║
        ╠══════════════════════════════════════════════════╣
        ║  Example rooms: general, random, kotlin, java    ║
        ╚══════════════════════════════════════════════════╝
        """.trimIndent(),
    )
    println()

    embeddedServer(CIO, port = 8080) {
        install(WebSockets)

        routing {
            // List active rooms
            get("/rooms") {
                val roomIds = roomServer.roomIds()
                call.respondText("Active rooms (${roomIds.size}): ${roomIds.joinToString(", ").ifEmpty { "(none)" }}")
            }

            // Room-specific WebSocket endpoint
            webSocket("/room/{roomId}") {
                val roomId = call.parameters["roomId"]
                if (roomId.isNullOrBlank()) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Room ID required"))
                    return@webSocket
                }

                val room = roomServer.getOrCreateRoom(roomId)
                val sessionId = UUID.randomUUID().toString()

                @Suppress("UNCHECKED_CAST")
                val connection = KtorWebSocketServerConnection(this)
                    .typedJson<SharedChatAction>() as TypedServerConnection<ChatAction>

                println("[Server] [$roomId] Client $sessionId connected")

                try {
                    room.handleClient(sessionId, connection)
                } finally {
                    println("[Server] [$roomId] Client $sessionId disconnected")

                    // Auto-cleanup: destroy room atomically if it's empty
                    roomServer.destroyRoomIfEmpty(roomId)
                }
            }
        }
    }.start(wait = true)

    roomServer.close()
}

private suspend fun printStatus(roomServer: io.flowdux.remote.server.pattern.RoomServer<*>) {
    val roomIds = roomServer.roomIds()
    println("\n=== Room Status ===")
    if (roomIds.isEmpty()) {
        println("No active rooms")
    } else {
        roomIds.forEach { roomId ->
            @Suppress("UNCHECKED_CAST")
            val room = roomServer.getRoom(
                roomId,
            ) as? io.flowdux.remote.server.pattern.SharedStateServer<ServerChatState, ChatAction>
            room?.let {
                val state = it.currentState
                println("  [$roomId] users=${state.users}, messages=${state.messages.size}")
            }
        }
    }
    println("===================\n")
}

private fun chatProcessors() = Middleware.ActionProcessorBuilder<ServerChatState, ChatAction>().apply {
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
