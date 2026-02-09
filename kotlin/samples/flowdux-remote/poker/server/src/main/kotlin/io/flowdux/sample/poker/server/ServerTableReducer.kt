package io.flowdux.sample.poker.server

import io.flowdux.Reducer
import io.flowdux.buildReducer
import io.flowdux.sample.poker.Card
import io.flowdux.sample.poker.GamePhase
import io.flowdux.sample.poker.PokerAction
import io.flowdux.sample.poker.SharedPokerAction
import io.flowdux.sample.poker.TableEvent

val serverTableReducer: Reducer<ServerTableState, PokerAction> = buildReducer {
    on<SharedPokerAction.JoinTable> { state, action ->
        val newPlayer = ServerPlayerInfo(
            id = action.playerId,
            name = action.playerId,
            chips = 1000,
        )
        state.copy(
            players = state.players + (action.playerId to newPlayer),
            lastEvent = TableEvent.PlayerJoined(action.playerId, action.playerId),
        )
    }

    on<ServerPokerAction.PlayerLeft> { state, action ->
        state.copy(
            players = state.players - action.playerId,
            hands = state.hands - action.playerId,
            lastEvent = TableEvent.PlayerLeft(action.playerId),
        )
    }

    on<ServerPokerAction.StartGame> { state, action ->
        if (state.players.size < 2) return@on state

        val playerIds = state.players.keys.toList()
        var remainingDeck = action.deck.toMutableList()
        val newHands = mutableMapOf<String, List<Card>>()

        // Deal 2 cards to each player
        for (playerId in playerIds) {
            val hand = listOf(remainingDeck.removeFirst(), remainingDeck.removeFirst())
            newHands[playerId] = hand
        }

        // Reset player states for new round
        val resetPlayers = state.players.mapValues { (_, player) ->
            player.copy(currentBet = 0, folded = false, isAllIn = false)
        }

        state.copy(
            players = resetPlayers,
            deck = remainingDeck,
            hands = newHands,
            communityCards = emptyList(),
            pot = 0,
            phase = GamePhase.PRE_FLOP,
            currentTurnIndex = 0,
            lastEvent = TableEvent.GameStarted("Cards dealt! Place your bets."),
        )
    }

    on<SharedPokerAction.PlaceBet> { state, action ->
        val player = state.players[action.playerId] ?: return@on state
        val betAmount = action.amount.coerceAtMost(player.chips)
        val isAllIn = betAmount >= player.chips

        val updatedPlayer = player.copy(
            chips = player.chips - betAmount,
            currentBet = player.currentBet + betAmount,
            isAllIn = isAllIn,
        )

        val newMinimumBet = maxOf(state.minimumBet, updatedPlayer.currentBet)

        state.copy(
            players = state.players + (action.playerId to updatedPlayer),
            pot = state.pot + betAmount,
            minimumBet = newMinimumBet,
            currentTurnIndex = (state.currentTurnIndex + 1) % state.activePlayerIds.size.coerceAtLeast(1),
            lastEvent = TableEvent.PlayerBet(action.playerId, betAmount),
        )
    }

    on<SharedPokerAction.Fold> { state, action ->
        val player = state.players[action.playerId] ?: return@on state
        val updatedPlayer = player.copy(folded = true)

        state.copy(
            players = state.players + (action.playerId to updatedPlayer),
            currentTurnIndex = state.currentTurnIndex % state.activePlayerIds.size.coerceAtLeast(1),
            lastEvent = TableEvent.PlayerFolded(action.playerId),
        )
    }

    on<SharedPokerAction.Check> { state, action ->
        state.copy(
            currentTurnIndex = (state.currentTurnIndex + 1) % state.activePlayerIds.size.coerceAtLeast(1),
            lastEvent = TableEvent.PlayerChecked(action.playerId),
        )
    }

    on<SharedPokerAction.Call> { state, action ->
        val player = state.players[action.playerId] ?: return@on state
        val callAmount = (state.minimumBet - player.currentBet).coerceAtMost(player.chips)
        val isAllIn = callAmount >= player.chips

        val updatedPlayer = player.copy(
            chips = player.chips - callAmount,
            currentBet = player.currentBet + callAmount,
            isAllIn = isAllIn,
        )

        state.copy(
            players = state.players + (action.playerId to updatedPlayer),
            pot = state.pot + callAmount,
            currentTurnIndex = (state.currentTurnIndex + 1) % state.activePlayerIds.size.coerceAtLeast(1),
            lastEvent = TableEvent.PlayerCalled(action.playerId, callAmount),
        )
    }

    on<ServerPokerAction.AdvancePhase> { state, _ ->
        val (newPhase, newCommunityCards) = when (state.phase) {
            GamePhase.PRE_FLOP -> {
                // Reveal 3 community cards (flop)
                val flop = state.deck.take(3)
                GamePhase.FLOP to flop
            }
            GamePhase.FLOP -> {
                // Reveal 4th card (turn)
                val turn = state.communityCards + state.deck.drop(3).take(1)
                GamePhase.TURN to turn
            }
            GamePhase.TURN -> {
                // Reveal 5th card (river)
                val river = state.communityCards + state.deck.drop(4).take(1)
                GamePhase.RIVER to river
            }
            GamePhase.RIVER -> {
                GamePhase.SHOWDOWN to state.communityCards
            }
            else -> state.phase to state.communityCards
        }

        // Reset current bets for new betting round
        val resetPlayers = state.players.mapValues { (_, player) ->
            player.copy(currentBet = 0)
        }

        state.copy(
            phase = newPhase,
            communityCards = newCommunityCards,
            players = resetPlayers,
            minimumBet = 10,
            currentTurnIndex = 0,
            lastEvent = TableEvent.PhaseChanged(newPhase),
        )
    }

    on<ServerPokerAction.DetermineWinner> { state, action ->
        val winner = state.players[action.winnerId] ?: return@on state
        val updatedWinner = winner.copy(chips = winner.chips + state.pot)

        state.copy(
            players = state.players + (action.winnerId to updatedWinner),
            pot = 0,
            phase = GamePhase.WAITING,
            hands = emptyMap(),
            communityCards = emptyList(),
            deck = emptyList(),
            lastEvent = TableEvent.GameEnded(action.winnerId, winner.name, state.pot),
        )
    }
}
