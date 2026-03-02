package io.flowdux.sample.poker.server

import io.flowdux.sample.poker.Card
import io.flowdux.sample.poker.PokerAction

/** Server-only actions (not serialized, not sent over wire). */
sealed interface ServerPokerAction : PokerAction {
    data class PlayerLeft(val playerId: String) : ServerPokerAction

    data class StartGame(val deck: List<Card>) : ServerPokerAction

    data object AdvancePhase : ServerPokerAction

    data class DetermineWinner(val winnerId: String) : ServerPokerAction
}
