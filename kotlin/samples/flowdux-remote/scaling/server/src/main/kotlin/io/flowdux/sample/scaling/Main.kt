package io.flowdux.sample.scaling

import io.flowdux.Middleware
import io.flowdux.remote.ktor.KtorWebSocketServerConnection
import io.flowdux.remote.serialization.typedJsonAs
import io.flowdux.remote.server.pattern.createSharedStateServer
import io.flowdux.remote.server.session.BroadcastConfig
import io.flowdux.remote.server.session.InMemorySessionRegistry
import io.ktor.http.*
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
import kotlin.time.Duration.Companion.seconds

/**
 * Scaling Demo Server
 *
 * This sample demonstrates the parallel broadcast feature for high-throughput scenarios.
 *
 * Features demonstrated:
 * - BroadcastConfig for parallel message sending
 * - InMemorySessionRegistry for session management
 * - Periodic stats broadcast to all clients
 * - Admin endpoints for testing broadcast performance
 *
 * Run with: ./gradlew :kotlin:sample-remote-scaling:server:run
 *
 * Test endpoints:
 * - GET /stats - Get current server stats
 * - POST /broadcast - Trigger a stats broadcast to all clients
 * - POST /stress/{count} - Simulate {count} counter increments
 */
fun main() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Configure parallel broadcast for high throughput
    // - concurrency = 32 means up to 32 clients receive messages simultaneously
    // - Recommended: 16-64 for 10k-100k clients
    val broadcastConfig = BroadcastConfig(concurrency = 32)

    // Use InMemorySessionRegistry (default, but shown explicitly for demo)
    val sessionRegistry = InMemorySessionRegistry<ScalingAction>()

    println("=== Scaling Demo Server ===")
    println("Broadcast concurrency: ${broadcastConfig.concurrency}")
    println()

    val server = createSharedStateServer(
        initialState = ScalingState(),
        reducer = scalingReducer,
        processors = scalingProcessors(),
        stateMapper = { state ->
            // Broadcast counter updates to all clients on state change
            SharedScalingAction.CounterUpdate(state.counter)
        },
        sessionRegistry = sessionRegistry,
        broadcastConfig = broadcastConfig,
        scope = applicationScope,
    )

    // Periodic stats broadcast (every 5 seconds)
    applicationScope.launch {
        while (isActive) {
            delay(5.seconds)
            val state = server.currentState
            val clientCount = server.sessionCount()

            if (clientCount > 0) {
                server.broadcast(
                    SharedScalingAction.ServerStats(
                        connectedClients = clientCount,
                        counter = state.counter,
                    ),
                )
                println("[Stats] clients=$clientCount, counter=${state.counter}")
            }
        }
    }

    // Monitor connection changes
    applicationScope.launch {
        var lastCount = 0
        server.state.collect { state ->
            if (state.connectedClients != lastCount) {
                lastCount = state.connectedClients
                println("[Server] Connected clients: $lastCount")
            }
        }
    }

    embeddedServer(CIO, port = 8080) {
        install(WebSockets)

        routing {
            // Stats endpoint
            get("/stats") {
                val state = server.currentState
                val clientCount = server.sessionCount()
                call.respond(
                    HttpStatusCode.OK,
                    """
                    {
                        "connectedClients": $clientCount,
                        "counter": ${state.counter},
                        "broadcastConcurrency": ${broadcastConfig.concurrency}
                    }
                    """.trimIndent(),
                )
            }

            // Manual broadcast trigger
            post("/broadcast") {
                val state = server.currentState
                val clientCount = server.sessionCount()
                val startTime = System.currentTimeMillis()

                server.broadcast(
                    SharedScalingAction.ServerStats(
                        connectedClients = clientCount,
                        counter = state.counter,
                    ),
                )

                val elapsed = System.currentTimeMillis() - startTime
                println("[Broadcast] Sent to $clientCount clients in ${elapsed}ms")
                call.respond(HttpStatusCode.OK, "Broadcast to $clientCount clients in ${elapsed}ms")
            }

            // Stress test endpoint
            post("/stress/{count}") {
                val count = call.parameters["count"]?.toIntOrNull() ?: 100
                println("[Stress] Starting $count increments...")

                val startTime = System.currentTimeMillis()
                repeat(count) {
                    server.store.dispatch(SharedScalingAction.Increment(1))
                }
                val elapsed = System.currentTimeMillis() - startTime

                println("[Stress] Completed $count increments in ${elapsed}ms")
                call.respond(HttpStatusCode.OK, "Completed $count increments in ${elapsed}ms")
            }

            // WebSocket endpoint
            webSocket("/ws") {
                val clientId = UUID.randomUUID().toString().take(8)
                println("[Connect] Client $clientId connected")

                // Track connection in state
                server.store.dispatch(ServerScalingAction.ClientConnected(clientId))

                val connection = KtorWebSocketServerConnection(this)
                    .typedJsonAs<SharedScalingAction, ScalingAction>()

                try {
                    // Send initial state
                    val state = server.currentState
                    server.sendToClient(
                        clientId,
                        SharedScalingAction.Pong(state.counter, state.connectedClients),
                    )

                    server.handleClient(clientId, connection)
                } finally {
                    server.store.dispatch(ServerScalingAction.ClientDisconnected(clientId))
                    println("[Disconnect] Client $clientId disconnected")
                }
            }
        }
    }.start(wait = true)

    server.close()
}

private fun scalingProcessors() = Middleware.ActionProcessorBuilder<ScalingState, ScalingAction>().apply {
    on<SharedScalingAction.Ping> { state, _ ->
        // Respond with pong containing current stats
        emit(
            SharedScalingAction.Pong(
                counter = state.counter,
                connectedClients = state.connectedClients,
            ),
        )
    }
    on<SharedScalingAction.Increment> { _, action ->
        // Pass through to reducer
        emit(action)
    }
}.build()
