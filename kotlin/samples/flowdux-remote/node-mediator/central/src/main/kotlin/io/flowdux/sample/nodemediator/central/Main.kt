package io.flowdux.sample.nodemediator.central

import io.flowdux.remote.ktor.KtorWebSocketServerConnection
import io.flowdux.remote.nodemediator.CentralNodeManager
import io.flowdux.remote.nodemediator.NodeMediatorEvent
import io.flowdux.remote.nodemediator.registry.InMemoryRoomRegistry
import io.flowdux.remote.nodemediator.typedNodeActionJson
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
import kotlin.time.Duration.Companion.seconds

/**
 * Node Mediator Demo — Central Server
 *
 * The Central server coordinates multiple Node servers. Each Node connects
 * via WebSocket and registers rooms. When a Node sends an upstream action
 * (e.g., a chat message), the Central relays it to the appropriate Node
 * that owns the target room.
 *
 * Endpoints:
 * - WS /node/{nodeId} — Node WebSocket connection
 * - GET /stats — Connection statistics
 */
fun main() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val roomRegistry = InMemoryRoomRegistry()

    lateinit var manager: CentralNodeManager<SharedChatAction>
    manager = CentralNodeManager(
        roomRegistry = roomRegistry,
        onUpstreamAction = { nodeId, roomId, action ->
            // Relay asynchronously to avoid CancellationException propagation
            // back through handleNode's collect, which would kill the sender's connection.
            println("[Central] Upstream from node=$nodeId room=$roomId: $action")
            applicationScope.launch {
                val allNodes = manager.connectedNodeIds()
                for (targetNodeId in allNodes) {
                    if (targetNodeId != nodeId) {
                        try {
                            manager.sendToNode(targetNodeId, roomId, action)
                        } catch (_: Exception) {
                            println("[Central] Failed to relay to $targetNodeId")
                        }
                    }
                }
            }
        },
        onEvent = { event ->
            when (event) {
                is NodeMediatorEvent.NodeConnected ->
                    println("[Central] Node connected: ${event.nodeId}")
                is NodeMediatorEvent.NodeDisconnected ->
                    println("[Central] Node disconnected: ${event.nodeId} (cause=${event.cause?.message})")
                is NodeMediatorEvent.CallbackFailed ->
                    println("[Central] Callback failed for room=${event.roomId}: ${event.cause.message}")
                else -> {}
            }
        },
    )

    // Periodic status logging
    applicationScope.launch {
        while (isActive) {
            delay(15_000)
            val nodes = manager.connectedNodeIds()
            val rooms = roomRegistry.getAllAssignments()
            println("\n[Stats] Nodes: ${nodes.size} ($nodes), Rooms: ${rooms.size} ($rooms)")
        }
    }

    println(
        """
        ╔════════════════════════════════════════════════╗
        ║     FlowDux Node Mediator — Central Server     ║
        ╠════════════════════════════════════════════════╣
        ║  Endpoints:                                    ║
        ║    WS  /node/{nodeId} — Node connections       ║
        ║    GET /stats         — Statistics              ║
        ╠════════════════════════════════════════════════╣
        ║  Default port: 8080                            ║
        ╚════════════════════════════════════════════════╝
        """.trimIndent(),
    )
    println()

    val server = embeddedServer(CIO, port = 8080) {
        install(WebSockets) {
            pingPeriod = 15.seconds
        }

        routing {
            get("/stats") {
                val nodes = manager.connectedNodeIds()
                val rooms = roomRegistry.getAllAssignments()
                call.respondText("Nodes: ${nodes.size} ($nodes)\nRooms: ${rooms.size} ($rooms)")
            }

            webSocket("/node/{nodeId}") {
                val nodeId = call.parameters["nodeId"] ?: return@webSocket
                println("[Central] Node connecting: $nodeId")

                val connection = KtorWebSocketServerConnection(this)
                    .typedNodeActionJson<SharedChatAction>()

                manager.handleNode(nodeId, connection)
            }
        }
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            println("[Central] Shutting down...")
            kotlinx.coroutines.runBlocking { manager.close() }
        },
    )

    server.start(wait = true)
}
