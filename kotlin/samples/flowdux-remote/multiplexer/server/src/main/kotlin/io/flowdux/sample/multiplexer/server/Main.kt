package io.flowdux.sample.multiplexer.server

import io.flowdux.Middleware
import io.flowdux.remote.ktor.KtorWebSocketServerConnection
import io.flowdux.remote.multiplexer.ServerConnectionMultiplexer
import io.flowdux.remote.multiplexer.typedRoutedJson
import io.flowdux.remote.server.pattern.SharedStateServer
import io.flowdux.remote.server.pattern.createSharedStateRoomServer
import io.flowdux.sample.multiplexer.ChatAction
import io.flowdux.sample.multiplexer.RoomState
import io.flowdux.sample.multiplexer.SharedChatAction
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Connection Multiplexer Demo Server
 *
 * This sample demonstrates how a single WebSocket connection can serve
 * multiple chat rooms using [ServerConnectionMultiplexer].
 *
 * Key features:
 * - Single WebSocket endpoint for all rooms
 * - Client sends room requests, server creates virtual connections per room
 * - Each room has its own independent Store (via [createSharedStateRoomServer])
 * - Messages are routed to the correct room automatically
 *
 * Endpoints:
 * - GET /rooms — List active rooms
 * - GET /stats — Show connection statistics
 * - WS /ws — Single multiplexed WebSocket endpoint
 *
 * Protocol:
 * - Client sends JoinRoom action to join a room
 * - All subsequent actions include roomId for routing
 */
fun main() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clientSessions = ConcurrentHashMap<String, ClientSession>()

    // Create room server using the new pattern-based API
    val roomServer = createSharedStateRoomServer(
        initialStateFactory = { roomId ->
            println("[RoomServer] Creating room: $roomId")
            ServerRoomState(roomId = roomId)
        },
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

    // Periodic cleanup of empty rooms
    applicationScope.launch {
        while (isActive) {
            delay(30_000)
            val destroyed = roomServer.cleanupEmptyRooms()
            if (destroyed.isNotEmpty()) {
                println("[RoomServer] Cleaned up ${destroyed.size} empty rooms: $destroyed")
            }
        }
    }

    // Periodic status logging
    applicationScope.launch {
        while (isActive) {
            delay(10_000)
            val clients = clientSessions.size
            val rooms = roomServer.roomCount()
            println("\n[Stats] Clients: $clients, Rooms: $rooms")
            printStatus(roomServer)
        }
    }

    println(
        """
        ╔══════════════════════════════════════════════════════════╗
        ║     FlowDux Connection Multiplexer Demo Server           ║
        ╠══════════════════════════════════════════════════════════╣
        ║  A single WebSocket connection serves multiple rooms!    ║
        ╠══════════════════════════════════════════════════════════╣
        ║  Endpoints:                                              ║
        ║    GET  /rooms  - List active rooms                      ║
        ║    GET  /stats  - Show connection statistics             ║
        ║    WS   /ws     - Single multiplexed WebSocket endpoint  ║
        ╠══════════════════════════════════════════════════════════╣
        ║  Example rooms: general, kotlin, java, random            ║
        ╚══════════════════════════════════════════════════════════╝
        """.trimIndent()
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

            // Show statistics
            get("/stats") {
                val clients = clientSessions.size
                val rooms = roomServer.roomCount()
                call.respondText("Clients: $clients, Rooms: $rooms")
            }

            // Single multiplexed WebSocket endpoint
            webSocket("/ws") {
                val sessionId = UUID.randomUUID().toString()
                println("[Server] Client $sessionId connected")

                // Create typed connection with routed actions
                val physicalConnection = KtorWebSocketServerConnection(this)
                    .typedRoutedJson<SharedChatAction>()

                // Track this client session (created before multiplexer for callback)
                val clientSession = ClientSession(sessionId, roomServer)
                clientSessions[sessionId] = clientSession

                // Create multiplexer for this client with callback for unknown rooms
                val multiplexer = ServerConnectionMultiplexer(physicalConnection, this) { roomId, action ->
                    // Handle actions for unknown rooms (e.g., JoinRoom)
                    clientSession.handleAction(roomId, action, this@webSocket)
                }
                clientSession.setMultiplexer(multiplexer)

                try {
                    // Handle incoming routed actions - keep connection alive
                    clientSession.handleConnection()
                } finally {
                    println("[Server] Client $sessionId disconnected")

                    // Cleanup: leave all rooms and close multiplexer
                    clientSession.leaveAllRooms()
                    multiplexer.close()
                    clientSessions.remove(sessionId)

                    // Cleanup empty rooms
                    roomServer.cleanupEmptyRooms()
                }
            }
        }
    }.start(wait = true)

    roomServer.close()
}

private suspend fun printStatus(roomServer: io.flowdux.remote.server.pattern.RoomServer<SharedStateServer<ServerRoomState, ChatAction>>) {
    val roomIds = roomServer.roomIds()
    if (roomIds.isEmpty()) {
        println("  No active rooms")
    } else {
        roomIds.forEach { roomId ->
            val room = roomServer.getRoom(roomId)
            room?.let {
                val state = it.currentState
                println("  [$roomId] users=${state.users.size}, messages=${state.messages.size}")
            }
        }
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
