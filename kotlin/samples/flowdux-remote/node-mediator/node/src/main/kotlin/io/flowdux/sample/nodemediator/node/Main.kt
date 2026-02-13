package io.flowdux.sample.nodemediator.node

import io.flowdux.remote.ktor.KtorWebSocketClientConnection
import io.flowdux.remote.ktor.KtorWebSocketServerConnection
import io.flowdux.remote.nodemediator.NodeMediatorEvent
import io.flowdux.remote.nodemediator.NodeRoomServer
import io.flowdux.remote.nodemediator.webSocketNodeTransport
import io.flowdux.remote.serialization.typedJson
import io.flowdux.remote.server.pattern.createSharedStateRoomServer
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Node Mediator Demo — Node Server
 *
 * Each Node server handles local clients and connects to the Central server
 * via [NodeRoomServer]. When a client connects to a room, the Node creates
 * a local Store and registers the room with the Central. Cross-node messages
 * are relayed through the Central.
 *
 * Args: nodeId localPort [centralHost] [centralPort]
 *
 * Endpoints:
 * - WS /ws/{roomId}?user={username} — Client WebSocket connection
 * - GET /rooms — List local rooms
 */
fun main(args: Array<String>) {
    val nodeId = args.getOrElse(0) { "node-1" }
    val localPort = args.getOrElse(1) { "8081" }.toInt()
    val centralHost = args.getOrElse(2) { "localhost" }
    val centralPort = args.getOrElse(3) { "8080" }.toInt()

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Create room server for local room management
    val roomServer = createSharedStateRoomServer(
        initialStateFactory = { roomId ->
            println("[$nodeId] Creating room: $roomId")
            ServerRoomState(roomId = roomId)
        },
        reducer = serverRoomReducer,
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

    // Connect to Central server via NodeRoomServer
    val transport = KtorWebSocketClientConnection.create(
        host = centralHost,
        port = centralPort,
        path = "/node/$nodeId",
    ).webSocketNodeTransport<SharedChatAction>()

    val nodeRoomServer = NodeRoomServer(
        nodeId = nodeId,
        transport = transport,
        roomServer = roomServer,
        scope = applicationScope,
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
    nodeRoomServer.connect()

    // Periodic cleanup of empty rooms
    applicationScope.launch {
        kotlinx.coroutines.delay(30_000)
        while (isActive) {
            val destroyed = nodeRoomServer.cleanupEmptyRooms()
            if (destroyed.isNotEmpty()) {
                println("[$nodeId] Cleaned up empty rooms: $destroyed")
            }
            kotlinx.coroutines.delay(30_000)
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
        ║    WS  /ws/{roomId} — Client connections       ║
        ║    GET /rooms       — Local room list          ║
        ╚════════════════════════════════════════════════╝
        """.trimIndent()
    )
    println()

    val server = embeddedServer(CIO, port = localPort) {
        install(WebSockets) {
            pingPeriod = 15.seconds
        }

        routing {
            get("/rooms") {
                val roomIds = nodeRoomServer.roomIds()
                call.respondText("Rooms on $nodeId (${roomIds.size}): ${roomIds.joinToString(", ").ifEmpty { "(none)" }}")
            }

            webSocket("/ws/{roomId}") {
                val roomId = call.parameters["roomId"] ?: return@webSocket
                val username = call.request.queryParameters["user"] ?: "anonymous"
                val sessionId = UUID.randomUUID().toString()
                println("[$nodeId] Client connected: session=$sessionId room=$roomId user=$username")

                val connection = KtorWebSocketServerConnection(this)
                    .typedJson<SharedChatAction>()

                try {
                    nodeRoomServer.handleClient(roomId, sessionId, connection)
                } finally {
                    withContext(NonCancellable) {
                        nodeRoomServer.dispatchAndForward(roomId, SharedChatAction.LeaveRoom(username))
                        nodeRoomServer.destroyRoomIfEmpty(roomId)
                    }
                    println("[$nodeId] Client disconnected: session=$sessionId room=$roomId")
                }
            }
        }
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        println("[$nodeId] Shutting down...")
        kotlinx.coroutines.runBlocking {
            nodeRoomServer.close()
        }
    })

    server.start(wait = true)
}
