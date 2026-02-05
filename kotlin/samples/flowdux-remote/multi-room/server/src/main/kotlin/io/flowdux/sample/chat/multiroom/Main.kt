package io.flowdux.sample.chat.multiroom

import io.flowdux.remote.ktor.KtorWebSocketServerConnection
import io.flowdux.remote.serialization.typedJson
import io.flowdux.remote.server.TypedServerConnection
import io.flowdux.sample.chat.ChatAction
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
 * This sample demonstrates the Room Store pattern with multiple independent rooms:
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
    val roomManager = RoomManager(applicationScope)

    // Periodic cleanup of empty rooms
    applicationScope.launch {
        while (isActive) {
            delay(30_000) // Every 30 seconds
            roomManager.cleanupEmptyRooms()
        }
    }

    // Periodic status logging
    applicationScope.launch {
        while (isActive) {
            delay(10_000) // Every 10 seconds
            roomManager.printStatus()
        }
    }

    println("""
        ╔══════════════════════════════════════════════════╗
        ║     FlowDux Multi-Room Chat Server               ║
        ╠══════════════════════════════════════════════════╣
        ║  Endpoints:                                      ║
        ║    GET  /rooms         - List active rooms       ║
        ║    WS   /room/{roomId} - Connect to a room       ║
        ╠══════════════════════════════════════════════════╣
        ║  Example rooms: general, random, kotlin, java    ║
        ╚══════════════════════════════════════════════════╝
    """.trimIndent())
    println()

    embeddedServer(CIO, port = 8080) {
        install(WebSockets)

        routing {
            // List active rooms
            get("/rooms") {
                val roomIds = roomManager.getRoomIds()
                call.respondText("Active rooms (${roomIds.size}): ${roomIds.joinToString(", ").ifEmpty { "(none)" }}")
            }

            // Room-specific WebSocket endpoint
            webSocket("/room/{roomId}") {
                val roomId = call.parameters["roomId"]
                if (roomId.isNullOrBlank()) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Room ID required"))
                    return@webSocket
                }

                val room = roomManager.getOrCreateRoom(roomId)
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
                    // This avoids race condition with new clients connecting
                    roomManager.destroyRoomIfEmpty(roomId)
                }
            }
        }
    }.start(wait = true)

    roomManager.shutdown()
}
