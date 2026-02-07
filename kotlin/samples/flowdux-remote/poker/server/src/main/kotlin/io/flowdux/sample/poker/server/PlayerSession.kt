package io.flowdux.sample.poker.server

import io.flowdux.State
import io.flowdux.Store
import io.flowdux.createStore
import io.flowdux.buildReducer
import io.flowdux.remote.server.ServerRemoteMiddleware
import io.flowdux.remote.server.TypedServerConnection
import io.flowdux.remote.server.serve
import io.flowdux.sample.poker.Card
import io.flowdux.sample.poker.PokerAction
import io.flowdux.sample.poker.SharedPokerAction

/**
 * Per-Client Store for managing private player state.
 *
 * Each player has their own PlayerSession that:
 * 1. Holds their private hand (cards only they can see)
 * 2. Syncs private state to their specific client via WebSocket
 * 3. Receives updates from the PokerTable (Room Store)
 *
 * This demonstrates the Per-Client Store pattern where:
 * - Room Store (PokerTable) manages shared game state
 * - Per-Client Store (PlayerSession) manages player-specific private state
 */
class PlayerSession(
    val playerId: String,
    private val connection: TypedServerConnection<PokerAction>,
) {
    private val middleware = PlayerRemoteMiddleware(connection)

    val store: Store<PlayerState, PokerAction> = createStore(
        initialState = PlayerState(playerId = playerId),
        reducer = playerReducer,
        middlewares = listOf(middleware),
    )

    /**
     * Called by PokerTable to update this player's private hand.
     * The hand is then synced to the client via the middleware.
     */
    fun updateHand(cards: List<Card>) {
        store.dispatch(PlayerAction.SetHand(cards))
    }

    /**
     * Starts serving the player's private state to their client.
     * State changes are automatically synced as SyncHand actions.
     */
    suspend fun serve() {
        store.serve { playerState ->
            SharedPokerAction.SyncHand(playerState.hand)
        }
    }

    fun close() {
        store.close()
    }
}

/** Player-local state (private hand). */
data class PlayerState(
    val playerId: String,
    val hand: List<Card> = emptyList(),
) : State

/** Player-local actions (not sent over wire). */
sealed interface PlayerAction : PokerAction {
    data class SetHand(val cards: List<Card>) : PlayerAction
}

/** Reducer for player-local state. */
private val playerReducer = buildReducer<PlayerState, PokerAction> {
    on<PlayerAction.SetHand> { state, action ->
        state.copy(hand = action.cards)
    }
}

/** Middleware for player session - handles private state sync. */
private class PlayerRemoteMiddleware(
    connection: TypedServerConnection<PokerAction>,
) : ServerRemoteMiddleware<PlayerState, PokerAction>(
    connection = connection,
) {
    override val name: String = "PlayerRemoteMiddleware"

    // No processors needed - this store only sends state, doesn't receive actions
    override val processors = buildProcessors { }
}
