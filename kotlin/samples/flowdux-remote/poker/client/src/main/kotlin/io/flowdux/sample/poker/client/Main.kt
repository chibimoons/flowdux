package io.flowdux.sample.poker.client

import io.flowdux.Store
import io.flowdux.remote.createClientStore
import io.flowdux.remote.ktor.KtorWebSocketClientConnection
import io.flowdux.remote.serialization.typedJsonAs
import io.flowdux.sample.poker.PokerAction
import io.flowdux.sample.poker.SharedPokerAction
import io.flowdux.sample.poker.TableEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Poker Client demonstrating the Per-Client Store pattern.
 *
 * This client receives two types of state updates:
 * 1. Public table state (SyncTableState) - visible to all players
 *    - Community cards, pot, player chips, betting, turn order
 * 2. Private hand (SyncHand) - only for this player
 *    - The player's private cards that others cannot see
 *
 * Usage:
 *   ./gradlew :kotlin:sample-remote-poker:client:run --args="Alice"
 *   ./gradlew :kotlin:sample-remote-poker:client:run --args="Bob"
 */
fun main(args: Array<String>) = runBlocking {
    val playerId = args.firstOrNull() ?: "Player${(1..1000).random()}"

    println("=== Flowdux Poker - Per-Client Store Demo ===")
    println("Player: $playerId")
    println()

    val store = createPokerStore(playerId)

    // Observe state changes
    val collectorJob =
        launch {
            var lastHandSize = 0
            store.state.collect { state ->
                // Display private hand when received/changed
                if (state.myHand.size != lastHandSize && state.myHand.isNotEmpty()) {
                    lastHandSize = state.myHand.size
                    println()
                    println("  YOUR PRIVATE HAND: ${state.myHand.joinToString(" ")}")
                    println("  (Only you can see these cards!)")
                    println()
                }

                // Display public events
                when (val event = state.lastEvent) {
                    is TableEvent.PlayerJoined -> println("  [+] ${event.name} joined the table")
                    is TableEvent.PlayerLeft -> println("  [-] ${event.playerId} left the table")
                    is TableEvent.PlayerBet -> println("  [$] ${event.playerId} bet ${event.amount}")
                    is TableEvent.PlayerFolded -> println("  [X] ${event.playerId} folded")
                    is TableEvent.PlayerChecked -> println("  [.] ${event.playerId} checked")
                    is TableEvent.PlayerCalled -> println("  [=] ${event.playerId} called ${event.amount}")
                    is TableEvent.PhaseChanged -> println("  [>] Phase: ${event.phase}")
                    is TableEvent.GameStarted -> println("  [!] ${event.message}")
                    is TableEvent.GameEnded -> println("  [*] ${event.winnerName} wins ${event.pot} chips!")
                    null -> {}
                }
            }
        }

    // Connect and join
    store.dispatch(ClientPokerAction.Connect)
    delay(500)
    store.dispatch(SharedPokerAction.JoinTable(playerId))
    delay(500)

    println()
    println("Commands: bet <amount> | fold | check | call | status | quit")
    println()

    // Interactive command loop
    while (true) {
        print("> ")
        val input = readlnOrNull()?.trim()?.lowercase() ?: break

        when {
            input == "quit" || input == "exit" -> break
            input == "status" -> printStatus(store)
            input == "fold" -> {
                if (store.currentState.isMyTurn) {
                    store.dispatch(SharedPokerAction.Fold(playerId))
                } else {
                    println("  Not your turn!")
                }
            }
            input == "check" -> {
                if (store.currentState.isMyTurn) {
                    store.dispatch(SharedPokerAction.Check(playerId))
                } else {
                    println("  Not your turn!")
                }
            }
            input == "call" -> {
                if (store.currentState.isMyTurn) {
                    store.dispatch(SharedPokerAction.Call(playerId))
                } else {
                    println("  Not your turn!")
                }
            }
            input.startsWith("bet ") -> {
                val amount = input.removePrefix("bet ").toIntOrNull()
                if (amount != null && store.currentState.isMyTurn) {
                    store.dispatch(SharedPokerAction.PlaceBet(playerId, amount))
                } else if (!store.currentState.isMyTurn) {
                    println("  Not your turn!")
                } else {
                    println("  Invalid amount")
                }
            }
            input.isEmpty() -> { /* ignore empty input */ }
            else -> println("  Unknown command: $input")
        }
        delay(100)
    }

    // Cleanup
    println()
    println("Leaving table...")
    collectorJob.cancel()
    store.dispatch(ClientPokerAction.Disconnect)
    store.close()

    println("=== Goodbye! ===")
}

private fun printStatus(store: Store<ClientPokerState, PokerAction>) {
    val state = store.currentState
    println()
    println("  === Table Status ===")
    println("  Phase: ${state.phase}")
    println("  Pot: ${state.pot}")
    println("  Community Cards: ${state.communityCards.ifEmpty { listOf("(none)") }.joinToString(" ")}")
    println()
    println("  Players:")
    for (player in state.players) {
        val turnMarker = if (player.id == state.currentTurnPlayerId) " <-- YOUR TURN" else ""
        val youMarker = if (player.id == state.playerId) " (you)" else ""
        val status =
            when {
                player.folded -> " [FOLDED]"
                player.isAllIn -> " [ALL-IN]"
                else -> ""
            }
        println("    ${player.name}$youMarker: ${player.chips} chips, bet ${player.currentBet}$status$turnMarker")
    }
    if (state.myHand.isNotEmpty()) {
        println()
        println("  Your Hand: ${state.myHand.joinToString(" ")}")
    }
    println()
}

private fun createPokerStore(playerId: String): Store<ClientPokerState, PokerAction> {
    val connection =
        KtorWebSocketClientConnection
            .create(
                host = "localhost",
                port = 8080,
                path = "/poker/$playerId",
            ).typedJsonAs<SharedPokerAction, PokerAction>()

    return createClientStore(
        initialState = ClientPokerState(playerId = playerId),
        syncMiddleware = PokerRemoteMiddleware(connection),
        reducer = clientPokerReducer,
    )
}
