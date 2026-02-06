package io.flowdux.sample.poker

import io.flowdux.Action
import io.flowdux.State
import io.flowdux.remote.ClientSharedAction
import io.flowdux.remote.ServerSharedAction
import kotlinx.serialization.Serializable

interface PokerAction : Action

/** Actions that cross the wire between client and server. */
@Serializable
sealed interface SharedPokerAction : PokerAction {
    // Client → Server
    @Serializable data class JoinTable(val playerId: String) : SharedPokerAction, ServerSharedAction
    @Serializable data class PlaceBet(val amount: Int) : SharedPokerAction, ServerSharedAction
    @Serializable data object Fold : SharedPokerAction, ServerSharedAction
    @Serializable data object Check : SharedPokerAction, ServerSharedAction
    @Serializable data object Call : SharedPokerAction, ServerSharedAction

    // Server → Client (public information - via Room Store)
    @Serializable data class SyncTableState(val state: PublicTableState) : SharedPokerAction, ClientSharedAction

    // Server → Client (private information - via Per-Client Store)
    @Serializable data class SyncHand(val cards: List<Card>) : SharedPokerAction, ClientSharedAction
}

// -- Card Types --
@Serializable
enum class Suit { HEARTS, DIAMONDS, CLUBS, SPADES }

@Serializable
enum class Rank {
    TWO, THREE, FOUR, FIVE, SIX, SEVEN, EIGHT, NINE, TEN,
    JACK, QUEEN, KING, ACE;

    override fun toString(): String = when (this) {
        TWO -> "2"
        THREE -> "3"
        FOUR -> "4"
        FIVE -> "5"
        SIX -> "6"
        SEVEN -> "7"
        EIGHT -> "8"
        NINE -> "9"
        TEN -> "10"
        JACK -> "J"
        QUEEN -> "Q"
        KING -> "K"
        ACE -> "A"
    }
}

@Serializable
data class Card(val suit: Suit, val rank: Rank) {
    override fun toString(): String {
        val suitSymbol = when (suit) {
            Suit.HEARTS -> "♥"
            Suit.DIAMONDS -> "♦"
            Suit.CLUBS -> "♣"
            Suit.SPADES -> "♠"
        }
        return "$rank$suitSymbol"
    }
}

// -- Game State Types --
@Serializable
enum class GamePhase {
    WAITING,      // Waiting for players
    PRE_FLOP,     // Cards dealt, betting before flop
    FLOP,         // 3 community cards revealed
    TURN,         // 4th community card revealed
    RIVER,        // 5th community card revealed
    SHOWDOWN,     // Reveal hands and determine winner
}

@Serializable
data class PlayerInfo(
    val id: String,
    val name: String,
    val chips: Int,
    val currentBet: Int,
    val folded: Boolean,
    val isAllIn: Boolean,
)

/** Public table state visible to all players. */
@Serializable
data class PublicTableState(
    val players: List<PlayerInfo> = emptyList(),
    val communityCards: List<Card> = emptyList(),
    val pot: Int = 0,
    val currentTurnPlayerId: String? = null,
    val phase: GamePhase = GamePhase.WAITING,
    val minimumBet: Int = 0,
    val lastEvent: TableEvent? = null,
) : State

/** Events for UI display. */
@Serializable
sealed interface TableEvent {
    @Serializable data class PlayerJoined(val playerId: String, val name: String) : TableEvent
    @Serializable data class PlayerLeft(val playerId: String) : TableEvent
    @Serializable data class PlayerBet(val playerId: String, val amount: Int) : TableEvent
    @Serializable data class PlayerFolded(val playerId: String) : TableEvent
    @Serializable data class PlayerChecked(val playerId: String) : TableEvent
    @Serializable data class PlayerCalled(val playerId: String, val amount: Int) : TableEvent
    @Serializable data class PhaseChanged(val phase: GamePhase) : TableEvent
    @Serializable data class GameStarted(val message: String) : TableEvent
    @Serializable data class GameEnded(val winnerId: String, val winnerName: String, val pot: Int) : TableEvent
}
