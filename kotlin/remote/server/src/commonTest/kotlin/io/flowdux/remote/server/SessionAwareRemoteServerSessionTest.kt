package io.flowdux.remote.server

import io.flowdux.Middleware
import io.flowdux.remote.server.connection.TypedServerConnection
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionAwareRemoteServerSessionTest {

    @Test
    fun `state changes trigger per-session mapping for all connected clients`() = runTest {
        val connAlice = MockTypedServerConnection<PokerAction>()
        val connBob = MockTypedServerConnection<PokerAction>()

        // Use a processor that transforms PlayerAction into DealCards
        val processors = Middleware.ActionProcessorBuilder<PokerState, PokerAction>().apply {
            on<PokerAction.DealCards> { _, action ->
                emit(action) // pass through — this action reaches the reducer
            }
        }.build()

        val server = createSessionAwareRemoteServer(
            initialState = PokerState(),
            reducer = pokerReducer,
            processors = processors,
            sessionStateMapper = { state, sessionId ->
                val hand = state.hands[sessionId] ?: return@createSessionAwareRemoteServer null
                PokerAction.SyncPlayerView(hand = hand, communityCards = state.communityCards)
            },
            errorProcessor = pokerErrorProcessor,
            scope = backgroundScope,
        )

        val jobAlice = backgroundScope.launch { server.handleClient("alice", connAlice) }
        val jobBob = backgroundScope.launch { server.handleClient("bob", connBob) }
        delay(100)

        connAlice.sentActions.clear()
        connBob.sentActions.clear()

        // Trigger state change via a client sending DealCards (which passes through processor)
        connAlice.simulateClientAction(PokerAction.DealCards(
            hands = mapOf("alice" to listOf("A♠", "K♠"), "bob" to listOf("2♣", "3♦")),
            communityCards = listOf("Q♥", "J♦", "10♠"),
        ))
        delay(200)

        // State should be updated
        assertEquals(
            mapOf("alice" to listOf("A♠", "K♠"), "bob" to listOf("2♣", "3♦")),
            server.currentState.hands,
        )

        // Alice receives her hand only
        val aliceViews = connAlice.sentActions.filterIsInstance<PokerAction.SyncPlayerView>()
        assertTrue(aliceViews.isNotEmpty(), "Alice should receive SyncPlayerView")
        assertEquals(listOf("A♠", "K♠"), aliceViews.last().hand)
        assertEquals(listOf("Q♥", "J♦", "10♠"), aliceViews.last().communityCards)

        // Bob receives his hand only
        val bobViews = connBob.sentActions.filterIsInstance<PokerAction.SyncPlayerView>()
        assertTrue(bobViews.isNotEmpty(), "Bob should receive SyncPlayerView")
        assertEquals(listOf("2♣", "3♦"), bobViews.last().hand)
        assertEquals(listOf("Q♥", "J♦", "10♠"), bobViews.last().communityCards)

        jobAlice.cancel()
        jobBob.cancel()
        server.close()
    }

    @Test
    fun `client connect and disconnect works correctly`() = runTest {
        val connAlice = MockTypedServerConnection<PokerAction>()

        val server = createSessionAwareRemoteServer(
            initialState = PokerState(),
            reducer = pokerReducer,
            sessionStateMapper = { state, sessionId ->
                val hand = state.hands[sessionId] ?: return@createSessionAwareRemoteServer null
                PokerAction.SyncPlayerView(hand = hand, communityCards = state.communityCards)
            },
            errorProcessor = pokerErrorProcessor,
            scope = backgroundScope,
        )

        assertEquals(0, server.sessionCount())

        // Connect
        val job = backgroundScope.launch { server.handleClient("alice", connAlice) }
        delay(100)
        assertEquals(1, server.sessionCount())
        assertTrue(server.sessionIds().contains("alice"))

        // Disconnect
        job.cancelAndJoin()
        delay(100)
        assertEquals(0, server.sessionCount())

        server.close()
    }

    @Test
    fun `processors work with session-aware session`() = runTest {
        val connAlice = MockTypedServerConnection<PokerAction>()

        val processors = Middleware.ActionProcessorBuilder<PokerState, PokerAction>().apply {
            on<PokerAction.PlayerAction> { _, action ->
                // Transform PlayerAction into DealCards
                emit(PokerAction.DealCards(
                    hands = mapOf(action.playerId to listOf("A♠", "K♠")),
                    communityCards = listOf("Q♥"),
                ))
            }
        }.build()

        val server = createSessionAwareRemoteServer(
            initialState = PokerState(),
            reducer = pokerReducer,
            processors = processors,
            sessionStateMapper = { state, sessionId ->
                val hand = state.hands[sessionId] ?: return@createSessionAwareRemoteServer null
                PokerAction.SyncPlayerView(hand = hand, communityCards = state.communityCards)
            },
            errorProcessor = pokerErrorProcessor,
            scope = backgroundScope,
        )

        val job = backgroundScope.launch { server.handleClient("alice", connAlice) }
        delay(100)

        connAlice.sentActions.clear()

        // Client sends PlayerAction → processor transforms to DealCards → reducer updates state
        connAlice.simulateClientAction(PokerAction.PlayerAction("alice", "deal"))
        delay(200)

        // State updated via processor
        assertEquals(mapOf("alice" to listOf("A♠", "K♠")), server.currentState.hands)

        // Alice receives her view via session-aware broadcast
        val aliceViews = connAlice.sentActions.filterIsInstance<PokerAction.SyncPlayerView>()
        assertTrue(aliceViews.isNotEmpty(), "Alice should receive SyncPlayerView after processor")
        assertEquals(listOf("A♠", "K♠"), aliceViews.last().hand)

        job.cancel()
        server.close()
    }

    @Test
    fun `session with no matching hand receives nothing`() = runTest {
        val connAlice = MockTypedServerConnection<PokerAction>()
        val connSpectator = MockTypedServerConnection<PokerAction>()

        val processors = Middleware.ActionProcessorBuilder<PokerState, PokerAction>().apply {
            on<PokerAction.DealCards> { _, action ->
                emit(action)
            }
        }.build()

        val server = createSessionAwareRemoteServer(
            initialState = PokerState(),
            reducer = pokerReducer,
            processors = processors,
            sessionStateMapper = { state, sessionId ->
                val hand = state.hands[sessionId] ?: return@createSessionAwareRemoteServer null
                PokerAction.SyncPlayerView(hand = hand, communityCards = state.communityCards)
            },
            errorProcessor = pokerErrorProcessor,
            scope = backgroundScope,
        )

        val jobAlice = backgroundScope.launch { server.handleClient("alice", connAlice) }
        val jobSpectator = backgroundScope.launch { server.handleClient("spectator", connSpectator) }
        delay(100)

        connAlice.sentActions.clear()
        connSpectator.sentActions.clear()

        // Deal only to alice — spectator has no hand
        connAlice.simulateClientAction(PokerAction.DealCards(
            hands = mapOf("alice" to listOf("A♠", "K♠")),
            communityCards = listOf("Q♥"),
        ))
        delay(200)

        // Alice gets her view
        val aliceViews = connAlice.sentActions.filterIsInstance<PokerAction.SyncPlayerView>()
        assertTrue(aliceViews.isNotEmpty(), "Alice should receive her view")

        // Spectator receives nothing (sessionStateMapper returned null)
        val spectatorViews = connSpectator.sentActions.filterIsInstance<PokerAction.SyncPlayerView>()
        assertTrue(spectatorViews.isEmpty(), "Spectator should receive nothing")

        jobAlice.cancel()
        jobSpectator.cancel()
        server.close()
    }

    @Test
    fun `error in one session does not affect others during per-session send`() = runTest {
        val connAlice = MockTypedServerConnection<PokerAction>()
        val failingConn = object : TypedServerConnection<PokerAction> {
            override val incoming = emptyFlow<PokerAction>()
            override suspend fun send(action: PokerAction) {
                throw RuntimeException("Connection failed")
            }
        }

        val processors = Middleware.ActionProcessorBuilder<PokerState, PokerAction>().apply {
            on<PokerAction.DealCards> { _, action -> emit(action) }
        }.build()

        val server = createSessionAwareRemoteServer(
            initialState = PokerState(),
            reducer = pokerReducer,
            processors = processors,
            sessionStateMapper = { state, sessionId ->
                val hand = state.hands[sessionId] ?: return@createSessionAwareRemoteServer null
                PokerAction.SyncPlayerView(hand = hand, communityCards = state.communityCards)
            },
            errorProcessor = pokerErrorProcessor,
            scope = backgroundScope,
        )

        val jobFailing = backgroundScope.launch { server.handleClient("failing", failingConn) }
        val jobAlice = backgroundScope.launch { server.handleClient("alice", connAlice) }
        delay(100)

        connAlice.sentActions.clear()

        connAlice.simulateClientAction(PokerAction.DealCards(
            hands = mapOf("failing" to listOf("X♠"), "alice" to listOf("A♠", "K♠")),
            communityCards = listOf("Q♥"),
        ))
        delay(200)

        // Alice still receives her view despite failing connection
        val aliceViews = connAlice.sentActions.filterIsInstance<PokerAction.SyncPlayerView>()
        assertTrue(aliceViews.isNotEmpty(), "Alice should receive her view")
        assertEquals(listOf("A♠", "K♠"), aliceViews.last().hand)

        jobFailing.cancel()
        jobAlice.cancel()
        server.close()
    }
}
