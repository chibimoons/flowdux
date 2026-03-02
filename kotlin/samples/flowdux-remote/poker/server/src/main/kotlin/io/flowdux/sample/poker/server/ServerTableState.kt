package io.flowdux.sample.poker.server

import io.flowdux.State
import io.flowdux.sample.poker.Card
import io.flowdux.sample.poker.GamePhase
import io.flowdux.sample.poker.PlayerInfo
import io.flowdux.sample.poker.PublicTableState
import io.flowdux.sample.poker.TableEvent

/** Server-side table state with private information. */
data class ServerTableState(
    // Public information (synced to all clients)
    val players: Map<String, ServerPlayerInfo> = emptyMap(),
    val communityCards: List<Card> = emptyList(),
    val pot: Int = 0,
    val currentTurnIndex: Int = 0,
    val phase: GamePhase = GamePhase.WAITING,
    val minimumBet: Int = 10,
    val lastEvent: TableEvent? = null,
    // Private information (server-only)
    val deck: List<Card> = emptyList(),
    val hands: Map<String, List<Card>> = emptyMap(), // playerId -> private hand
    val dealerIndex: Int = 0,
) : State {
    val activePlayers: List<ServerPlayerInfo>
        get() = players.values.filter { !it.folded }.toList()

    val activePlayerIds: List<String>
        get() = players.entries.filter { !it.value.folded }.map { it.key }

    val currentTurnPlayerId: String?
        get() = activePlayerIds.getOrNull(currentTurnIndex % activePlayerIds.size.coerceAtLeast(1))

    fun toPublicState(): PublicTableState = PublicTableState(
        players = players.values.map { it.toPlayerInfo() },
        communityCards = communityCards,
        pot = pot,
        currentTurnPlayerId = currentTurnPlayerId,
        phase = phase,
        minimumBet = minimumBet,
        lastEvent = lastEvent,
    )
}

data class ServerPlayerInfo(
    val id: String,
    val name: String,
    val chips: Int = 1000,
    val currentBet: Int = 0,
    val folded: Boolean = false,
    val isAllIn: Boolean = false,
) {
    fun toPlayerInfo(): PlayerInfo = PlayerInfo(
        id = id,
        name = name,
        chips = chips,
        currentBet = currentBet,
        folded = folded,
        isAllIn = isAllIn,
    )
}
