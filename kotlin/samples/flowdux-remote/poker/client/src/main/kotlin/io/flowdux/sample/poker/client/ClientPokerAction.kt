package io.flowdux.sample.poker.client

import io.flowdux.sample.poker.PokerAction

/** Client-only actions (not sent over wire). */
sealed interface ClientPokerAction : PokerAction {
    data object Connect : ClientPokerAction
    data object Disconnect : ClientPokerAction
}
