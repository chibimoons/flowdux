package io.flowdux.sample.poker.server

import io.flowdux.Middleware
import io.flowdux.sequential
import io.flowdux.remote.server.RemoteServer
import io.flowdux.remote.server.TypedServerConnection
import io.flowdux.remote.server.createRemoteServer
import io.flowdux.sample.poker.Card
import io.flowdux.sample.poker.GamePhase
import io.flowdux.sample.poker.PokerAction
import io.flowdux.sample.poker.Rank
import io.flowdux.sample.poker.SharedPokerAction
import io.flowdux.sample.poker.Suit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Room Store for managing the poker table (shared game state).
 *
 * The PokerTable manages:
 * 1. Public game state (visible to all players)
 * 2. Private hands (distributed to Per-Client Stores)
 *
 * Architecture:
 * ```
 * PokerTable (Room Store) - public state
 *       │
 *       ├── PlayerSession (Per-Client Store) - Player 1's private hand
 *       ├── PlayerSession (Per-Client Store) - Player 2's private hand
 *       └── PlayerSession (Per-Client Store) - Player 3's private hand
 * ```
 *
 * Data Flow:
 * 1. Client sends action (e.g., PlaceBet) → Room Store processes
 * 2. Room Store updates public state → broadcast to all clients
 * 3. Room Store distributes private hands → each PlayerSession
 * 4. PlayerSession syncs hand → only to that specific client
 */
class PokerTable(
    private val applicationScope: CoroutineScope,
) {
    private val players = ConcurrentHashMap<String, PlayerSession>()

    val roomStore: RemoteServer<ServerTableState, PokerAction> = createRemoteServer(
        initialState = ServerTableState(),
        reducer = serverTableReducer,
        processors = tableProcessors(),
        stateMapper = { state ->
            SharedPokerAction.SyncTableState(state.toPublicState())
        },
        scope = applicationScope,
    )

    init {
        // Watch for hand changes and propagate to Per-Client Stores
        applicationScope.launch {
            roomStore.state.collect { tableState ->
                propagateHands(tableState)
                checkGameState(tableState)
            }
        }
    }

    /**
     * Adds a player to the table.
     * Creates a PlayerSession (Per-Client Store) for their private state.
     */
    fun addPlayer(playerId: String, session: PlayerSession) {
        players[playerId] = session
        println("[PokerTable] Player added: $playerId (total: ${players.size})")
    }

    /**
     * Removes a player from the table.
     */
    fun removePlayer(playerId: String) {
        players.remove(playerId)?.close()
        roomStore.store.dispatch(ServerPokerAction.PlayerLeft(playerId))
        println("[PokerTable] Player removed: $playerId (remaining: ${players.size})")
    }

    /**
     * Propagates private hands from Room Store to Per-Client Stores.
     * Each player only receives their own hand.
     */
    private fun propagateHands(tableState: ServerTableState) {
        for ((playerId, hand) in tableState.hands) {
            players[playerId]?.updateHand(hand)
        }
    }

    /**
     * Checks game state and triggers automatic transitions.
     */
    private fun checkGameState(tableState: ServerTableState) {
        // Check if only one player remains (others folded)
        if (tableState.phase != GamePhase.WAITING && tableState.activePlayers.size == 1) {
            val winnerId = tableState.activePlayerIds.first()
            applicationScope.launch {
                delay(500) // Brief delay for dramatic effect
                roomStore.store.dispatch(ServerPokerAction.DetermineWinner(winnerId))
            }
        }
    }

    /**
     * Starts a new game if enough players are present.
     */
    fun startGame() {
        val state = roomStore.state.value
        if (state.players.size >= 2 && state.phase == GamePhase.WAITING) {
            val deck = createShuffledDeck()
            roomStore.store.dispatch(ServerPokerAction.StartGame(deck))
        }
    }

    /**
     * Advances to the next phase (for demo purposes).
     */
    fun advancePhase() {
        roomStore.store.dispatch(ServerPokerAction.AdvancePhase)
    }

    /**
     * Determines a winner (simplified - picks first active player).
     * In a real game, this would evaluate hand rankings.
     */
    fun determineWinner() {
        val state = roomStore.state.value
        val winnerId = state.activePlayerIds.firstOrNull()
        if (winnerId != null) {
            roomStore.store.dispatch(ServerPokerAction.DetermineWinner(winnerId))
        }
    }

    fun close() {
        players.values.forEach { it.close() }
        players.clear()
        roomStore.close()
    }

    private fun tableProcessors() =
        Middleware.ActionProcessorBuilder<ServerTableState, PokerAction>().apply {
            on<SharedPokerAction.JoinTable> { _, action ->
                emit(ServerPokerAction.PlayerJoined(action.playerId))
            }
            // Use sequential strategy to ensure poker actions are processed in order
            // and prevent race conditions from rapid action submissions
            group(sequential()) {
                on<SharedPokerAction.PlaceBet> { state, action ->
                    val currentPlayerId = state.currentTurnPlayerId
                    if (currentPlayerId != null) {
                        emit(ServerPokerAction.PlayerBet(currentPlayerId, action.amount))
                    }
                }
                on<SharedPokerAction.Fold> { state, _ ->
                    val currentPlayerId = state.currentTurnPlayerId
                    if (currentPlayerId != null) {
                        emit(ServerPokerAction.PlayerFolded(currentPlayerId))
                    }
                }
                on<SharedPokerAction.Check> { state, _ ->
                    val currentPlayerId = state.currentTurnPlayerId
                    if (currentPlayerId != null) {
                        emit(ServerPokerAction.PlayerChecked(currentPlayerId))
                    }
                }
                on<SharedPokerAction.Call> { state, _ ->
                    val currentPlayerId = state.currentTurnPlayerId
                    if (currentPlayerId != null) {
                        emit(ServerPokerAction.PlayerCalled(currentPlayerId))
                    }
                }
            }
        }.build()

    companion object {
        fun createShuffledDeck(): List<Card> {
            val deck = mutableListOf<Card>()
            for (suit in Suit.entries) {
                for (rank in Rank.entries) {
                    deck.add(Card(suit, rank))
                }
            }
            deck.shuffle()
            return deck
        }
    }
}
