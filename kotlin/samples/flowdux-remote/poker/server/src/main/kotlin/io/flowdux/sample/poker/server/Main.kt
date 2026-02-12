package io.flowdux.sample.poker.server

import io.flowdux.remote.ktor.KtorWebSocketServerConnection
import io.flowdux.remote.serialization.typedJsonAs
import io.flowdux.sample.poker.PokerAction
import io.flowdux.sample.poker.SharedPokerAction
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Poker Server demonstrating the Per-Client Store pattern.
 *
 * Architecture:
 * ```
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    Poker Table (Room Store)                  │
 * │  State: game phase, betting, community cards, turn order    │
 * └──────────────────────────┬──────────────────────────────────┘
 *                            │ dispatch (private cards)
 *          ┌─────────────────┼─────────────────┐
 *          ▼                 ▼                 ▼
 *     ┌─────────┐       ┌─────────┐       ┌─────────┐
 *     │Player 1 │       │Player 2 │       │Player 3 │
 *     │ Store   │       │ Store   │       │ Store   │
 *     │(private)│       │(private)│       │(private)│
 *     └────┬────┘       └────┬────┘       └────┬────┘
 *          │                 │                 │
 *          ▼                 ▼                 ▼
 *         C1                C2                C3
 *                     (WebSocket Clients)
 * ```
 *
 * Each client receives:
 * - Public state (SyncTableState) from Room Store - visible to all
 * - Private hand (SyncHand) from their Per-Client Store - only for them
 */
fun main() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val pokerTable = PokerTable(applicationScope)

    // Monitor table state
    applicationScope.launch {
        pokerTable.roomStore.state.collect { state ->
            println("[Server] Phase: ${state.phase}, Players: ${state.players.size}, " +
                "Pot: ${state.pot}, Turn: ${state.currentTurnPlayerId ?: "none"}")
        }
    }

    embeddedServer(CIO, port = 8080) {
        install(WebSockets)

        routing {
            // Admin endpoints for demo control
            post("/start") {
                pokerTable.startGame()
                call.respond(HttpStatusCode.OK, "Game started")
            }

            post("/advance") {
                pokerTable.advancePhase()
                call.respond(HttpStatusCode.OK, "Phase advanced")
            }

            post("/winner") {
                pokerTable.determineWinner()
                call.respond(HttpStatusCode.OK, "Winner determined")
            }

            webSocket("/poker/{playerId}") {
                val playerId = call.parameters["playerId"] ?: return@webSocket

                println("[Server] Player connecting: $playerId")

                // Create typed connection for this player
                val connection = KtorWebSocketServerConnection(this)
                    .typedJsonAs<SharedPokerAction, PokerAction>()

                // Create Per-Client Store for private state
                val playerSession = PlayerSession(playerId, connection)

                // Add to Room Store
                pokerTable.addPlayer(playerId, playerSession)

                try {
                    coroutineScope {
                        // Handle public state (Room Store → all clients)
                        launch {
                            pokerTable.roomStore.handleClient(playerId, connection)
                        }

                        // Handle private state (Per-Client Store → this client only)
                        launch {
                            playerSession.serve()
                        }
                    }
                } finally {
                    pokerTable.removePlayer(playerId)
                    println("[Server] Player disconnected: $playerId")
                }
            }
        }
    }.start(wait = true)

    pokerTable.close()
}
