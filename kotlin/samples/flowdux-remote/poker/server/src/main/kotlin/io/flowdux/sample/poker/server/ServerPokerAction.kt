package io.flowdux.sample.poker.server

import io.flowdux.sample.poker.Card
import io.flowdux.sample.poker.PokerAction

/** Server-only actions (not serialized, not sent over wire). */
sealed interface ServerPokerAction : PokerAction {
    data class PlayerJoined(val playerId: String) : ServerPokerAction
    data class PlayerLeft(val playerId: String) : ServerPokerAction
    data class PlayerBet(val playerId: String, val amount: Int) : ServerPokerAction
    data class PlayerFolded(val playerId: String) : ServerPokerAction
    data class PlayerChecked(val playerId: String) : ServerPokerAction
    data class PlayerCalled(val playerId: String) : ServerPokerAction
    data class StartGame(val deck: List<Card>) : ServerPokerAction
    data object AdvancePhase : ServerPokerAction
    data class DetermineWinner(val winnerId: String) : ServerPokerAction
}
