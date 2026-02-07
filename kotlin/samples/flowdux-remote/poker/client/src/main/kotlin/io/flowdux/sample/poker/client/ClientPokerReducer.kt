package io.flowdux.sample.poker.client

import io.flowdux.Reducer
import io.flowdux.buildReducer
import io.flowdux.sample.poker.PokerAction
import io.flowdux.sample.poker.SharedPokerAction

val clientPokerReducer: Reducer<ClientPokerState, PokerAction> = buildReducer {
    // Sync public table state from Room Store
    on<SharedPokerAction.SyncTableState> { state, action ->
        state.copy(
            players = action.state.players,
            communityCards = action.state.communityCards,
            pot = action.state.pot,
            currentTurnPlayerId = action.state.currentTurnPlayerId,
            phase = action.state.phase,
            minimumBet = action.state.minimumBet,
            lastEvent = action.state.lastEvent,
        )
    }

    // Sync private hand from Per-Client Store
    on<SharedPokerAction.SyncHand> { state, action ->
        state.copy(myHand = action.cards)
    }
}
