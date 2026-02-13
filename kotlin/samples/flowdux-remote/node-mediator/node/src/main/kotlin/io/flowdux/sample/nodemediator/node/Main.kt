package io.flowdux.sample.nodemediator.node

import io.flowdux.Middleware
import io.flowdux.remote.ktor.KtorWebSocketClientConnection
import io.flowdux.remote.ktor.KtorWebSocketServerConnection
import io.flowdux.remote.nodemediator.InMemoryRoomRegistry
import io.flowdux.remote.nodemediator.NodeMediator
import io.flowdux.remote.nodemediator.NodeMediatorEvent
import io.flowdux.remote.nodemediator.typedNodeActionJson
import io.flowdux.remote.serialization.typedJsonAs
import io.flowdux.remote.server.pattern.createSharedStateRoomServer
import io.flowdux.sample.nodemediator.shared.ChatAction
import io.flowdux.sample.nodemediator.shared.RoomState
import io.flowdux.sample.nodemediator.shared.SharedChatAction
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

/**
 * Node Mediator Demo — Node Server
 *
 * Each Node server handles local clients and connects to the Central server
 * via [NodeMediator]. When a client joins a room, the Node creates a local
 * Store and registers the room with the Central. Cross-node messages are
 * relayed through the Central.
 *
 * Args: nodeId localPort [centralHost] [centralPort]
 *
 * Endpoints:
 * - WS /ws — Client WebSocket connection
 * - GET /rooms — List local rooms
 */
fun main(args: Array<String>) {
    val nodeId = args.getOrElse(0) { "node-1" }
    val localPort = args.getOrElse(1) { "8081" }.toInt()
    val centralHost = args.getOrElse(2) { "localhost" }
    val centralPort = args.getOrElse(3) { "8080" }.toInt()

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val localRegistry = InMemoryRoomRegistry()

    // Create room server for local room management
    val roomServer = createSharedStateRoomServer(
        initialStateFactory = { roomId ->
            println("[$nodeId] Creating room: $roomId")
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

    // Connect to Central server
    val centralConnection = KtorWebSocketClientConnection.create(
        host = centralHost,
        port = centralPort,
        path = "/node/$nodeId",
    ).typedNodeActionJson<SharedChatAction>()

    val mediator = NodeMediator(
        nodeId = nodeId,
        centralConnection = centralConnection,
        scope = applicationScope,
        onUnknownRoom = { roomId, action ->
            // Dynamic room creation when Central sends to an unknown room
            println("[$nodeId] Unknown room from Central: $roomId, creating...")
            val room = roomServer.getOrCreateRoom(roomId)
            room.store.dispatch(action)
        },
        onEvent = { event ->
            when (event) {
                is NodeMediatorEvent.RoutingStopped ->
                    println("[$nodeId] Routing stopped: ${event.cause.message}")
                is NodeMediatorEvent.ConnectionFailed ->
                    println("[$nodeId] Connection to Central failed: ${event.cause.message}")
                is NodeMediatorEvent.CallbackFailed ->
                    println("[$nodeId] Callback failed for room=${event.roomId}: ${event.cause.message}")
                else -> {}
            }
        },
    )

    // Connect to Central
    mediator.connect()

    // Periodic cleanup
    applicationScope.launch {
        while (isActive) {
            delay(30_000)
            val destroyed = roomServer.cleanupEmptyRooms()
            if (destroyed.isNotEmpty()) {
                println("[$nodeId] Cleaned up empty rooms: $destroyed")
                destroyed.forEach { roomId ->
                    mediator.unregisterRoom(roomId)
                    localRegistry.unassignRoom(roomId)
                }
            }
        }
    }

    println(
        """
        ╔════════════════════════════════════════════════╗
        ║     FlowDux Node Mediator — Node Server        ║
        ╠════════════════════════════════════════════════╣
        ║  Node ID: $nodeId
        ║  Local port: $localPort
        ║  Central: $centralHost:$centralPort
        ╠════════════════════════════════════════════════╣
        ║  Endpoints:                                    ║
        ║    WS  /ws    — Client connections              ║
        ║    GET /rooms — Local room list                 ║
        ╚════════════════════════════════════════════════╝
        """.trimIndent()
    )
    println()

    embeddedServer(CIO, port = localPort) {
        install(WebSockets)

        routing {
            get("/rooms") {
                val roomIds = roomServer.roomIds()
                call.respondText("Rooms on $nodeId (${roomIds.size}): ${roomIds.joinToString(", ").ifEmpty { "(none)" }}")
            }

            webSocket("/ws") {
                val sessionId = UUID.randomUUID().toString()
                println("[$nodeId] Client connected: $sessionId")

                val connection = KtorWebSocketServerConnection(this)
                    .typedJsonAs<SharedChatAction, ChatAction>()

                try {
                    // Listen for client actions
                    connection.incoming.collect { action ->
                        when (action) {
                            is SharedChatAction.JoinRoom -> {
                                val roomId = "room-${action.user}"
                                val room = roomServer.getOrCreateRoom(roomId)

                                // Register room with Central if not already
                                if (!mediator.hasRoom(roomId)) {
                                    mediator.registerRoom(roomId) { centralAction ->
                                        // Central → Node: dispatch to local room store
                                        room.store.dispatch(centralAction)
                                    }
                                    localRegistry.assignRoom(roomId, nodeId)
                                }

                                // Handle this client in the room
                                applicationScope.launch {
                                    room.handleClient(sessionId, connection)
                                }
                                delay(50)
                                room.store.dispatch(action)

                                // Forward to Central for cross-node visibility
                                mediator.forwardToCentral(roomId, action)
                            }

                            is SharedChatAction.SendMessage -> {
                                // Find the room this client is in and dispatch + forward
                                val roomIds = roomServer.roomIds()
                                for (roomId in roomIds) {
                                    val room = roomServer.getRoom(roomId) ?: continue
                                    room.store.dispatch(action)
                                    mediator.forwardToCentral(roomId, action)
                                    break
                                }
                            }

                            is SharedChatAction.LeaveRoom -> {
                                val roomIds = roomServer.roomIds()
                                for (roomId in roomIds) {
                                    val room = roomServer.getRoom(roomId) ?: continue
                                    room.store.dispatch(action)
                                    mediator.forwardToCentral(roomId, action)
                                    break
                                }
                            }

                            else -> {}
                        }
                    }
                } finally {
                    println("[$nodeId] Client disconnected: $sessionId")
                }
            }
        }
    }.start(wait = true)

    applicationScope.launch {
        mediator.close()
        roomServer.close()
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
