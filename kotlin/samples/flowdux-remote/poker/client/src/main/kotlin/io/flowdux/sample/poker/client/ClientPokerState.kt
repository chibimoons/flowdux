package io.flowdux.sample.poker.client

import io.flowdux.State
import io.flowdux.sample.poker.Card
import io.flowdux.sample.poker.GamePhase
import io.flowdux.sample.poker.PlayerInfo
import io.flowdux.sample.poker.TableEvent

/**
 * Client-side poker state combining public and private information.
 *
 * The client receives two types of state updates:
 * 1. Public table state (SyncTableState) - from Room Store, visible to all
 * 2. Private hand (SyncHand) - from Per-Client Store, only for this player
 */
data class ClientPokerState(
    // Client-local
    val playerId: String = "",

    // Public state (synced from Room Store)
    val players: List<PlayerInfo> = emptyList(),
    val communityCards: List<Card> = emptyList(),
    val pot: Int = 0,
    val currentTurnPlayerId: String? = null,
    val phase: GamePhase = GamePhase.WAITING,
    val minimumBet: Int = 0,
    val lastEvent: TableEvent? = null,

    // Private state (synced from Per-Client Store)
    val myHand: List<Card> = emptyList(),
) : State {
    val isMyTurn: Boolean
        get() = currentTurnPlayerId == playerId

    val myInfo: PlayerInfo?
        get() = players.find { it.id == playerId }

    val myChips: Int
        get() = myInfo?.chips ?: 0

    val myCurrentBet: Int
        get() = myInfo?.currentBet ?: 0

    val amountToCall: Int
        get() = minimumBet - myCurrentBet
}
