package io.flowdux.remote.server

import io.flowdux.createStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionAwareActionTest {

    @Test
    fun `SessionAwareAction sends different actions per session`() = runTest {
        val connAlice = MockTypedServerConnection<PokerAction>()
        val connBob = MockTypedServerConnection<PokerAction>()
        val session = RemoteServerSession<PokerAction>()
        val middleware = MultiClientServerRemoteMiddleware<PokerState, PokerAction>(session = session)
        val store = createStore(
            initialState = PokerState(),
            reducer = pokerReducer,
            middlewares = listOf(middleware),
            errorProcessor = pokerErrorProcessor,
            scope = backgroundScope,
        )

        session.addSession("alice", connAlice)
        store.dispatchAddSession("alice", connAlice)
        session.addSession("bob", connBob)
        store.dispatchAddSession("bob", connBob)
        delay(100)

        // Dispatch a SessionAwareAction
        val gameState = PokerState(
            hands = mapOf("alice" to listOf("A♠", "K♠"), "bob" to listOf("2♣", "3♦")),
            communityCards = listOf("Q♥", "J♦", "10♠"),
        )
        @Suppress("UNCHECKED_CAST")
        store.dispatch(PokerAction.SyncGameState(gameState) as PokerAction)
        delay(100)

        // Alice gets her hand
        val aliceViews = connAlice.sentActions.filterIsInstance<PokerAction.SyncPlayerView>()
        assertEquals(1, aliceViews.size)
        assertEquals(listOf("A♠", "K♠"), aliceViews[0].hand)
        assertEquals(listOf("Q♥", "J♦", "10♠"), aliceViews[0].communityCards)

        // Bob gets his hand
        val bobViews = connBob.sentActions.filterIsInstance<PokerAction.SyncPlayerView>()
        assertEquals(1, bobViews.size)
        assertEquals(listOf("2♣", "3♦"), bobViews[0].hand)
        assertEquals(listOf("Q♥", "J♦", "10♠"), bobViews[0].communityCards)

        store.close()
    }

    @Test
    fun `forSession returning null skips that session`() = runTest {
        val connAlice = MockTypedServerConnection<PokerAction>()
        val connCharlie = MockTypedServerConnection<PokerAction>()
        val session = RemoteServerSession<PokerAction>()
        val middleware = MultiClientServerRemoteMiddleware<PokerState, PokerAction>(session = session)
        val store = createStore(
            initialState = PokerState(),
            reducer = pokerReducer,
            middlewares = listOf(middleware),
            errorProcessor = pokerErrorProcessor,
            scope = backgroundScope,
        )

        session.addSession("alice", connAlice)
        store.dispatchAddSession("alice", connAlice)
        session.addSession("charlie", connCharlie) // charlie has no hand
        store.dispatchAddSession("charlie", connCharlie)
        delay(100)

        val gameState = PokerState(
            hands = mapOf("alice" to listOf("A♠", "K♠")), // no entry for charlie
            communityCards = listOf("Q♥"),
        )
        @Suppress("UNCHECKED_CAST")
        store.dispatch(PokerAction.SyncGameState(gameState) as PokerAction)
        delay(100)

        // Alice receives her view
        assertEquals(1, connAlice.sentActions.filterIsInstance<PokerAction.SyncPlayerView>().size)

        // Charlie receives nothing (forSession returned null)
        assertTrue(connCharlie.sentActions.isEmpty())

        store.close()
    }

    @Test
    fun `error in one session does not affect others during sendPerSession`() = runTest {
        val connAlice = MockTypedServerConnection<PokerAction>()
        val failingConn = object : TypedServerConnection<PokerAction> {
            override val incoming = connAlice.incoming // unused
            override suspend fun send(action: PokerAction) {
                throw RuntimeException("Connection failed")
            }
        }
        val session = RemoteServerSession<PokerAction>()
        val middleware = MultiClientServerRemoteMiddleware<PokerState, PokerAction>(session = session)
        val store = createStore(
            initialState = PokerState(),
            reducer = pokerReducer,
            middlewares = listOf(middleware),
            errorProcessor = pokerErrorProcessor,
            scope = backgroundScope,
        )

        session.addSession("failing", failingConn)
        store.dispatchAddSession("failing", failingConn)
        session.addSession("alice", connAlice)
        store.dispatchAddSession("alice", connAlice)
        delay(100)

        val gameState = PokerState(
            hands = mapOf(
                "failing" to listOf("X♠"),
                "alice" to listOf("A♠", "K♠"),
            ),
            communityCards = listOf("Q♥"),
        )
        @Suppress("UNCHECKED_CAST")
        store.dispatch(PokerAction.SyncGameState(gameState) as PokerAction)
        delay(100)

        // Alice still receives her view despite failing connection
        val aliceViews = connAlice.sentActions.filterIsInstance<PokerAction.SyncPlayerView>()
        assertEquals(1, aliceViews.size)
        assertEquals(listOf("A♠", "K♠"), aliceViews[0].hand)

        store.close()
    }

    @Test
    fun `regular ClientSharedAction still broadcasts uniformly`() = runTest {
        val conn1 = MockTypedServerConnection<ServerAction>()
        val conn2 = MockTypedServerConnection<ServerAction>()
        val session = RemoteServerSession<ServerAction>()
        val middleware = MultiClientServerRemoteMiddleware<ServerState, ServerAction>(session = session)
        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer,
            middlewares = listOf(middleware),
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        session.addSession("client-1", conn1)
        store.dispatchAddSession("client-1", conn1)
        session.addSession("client-2", conn2)
        store.dispatchAddSession("client-2", conn2)
        delay(100)

        // Dispatch a regular ClientSharedAction (NOT SessionAwareAction)
        store.dispatch(ServerAction.SyncState(ServerState(42)))
        delay(100)

        // Both clients receive the same action
        val sync1 = conn1.sentActions.filterIsInstance<ServerAction.SyncState>()
        val sync2 = conn2.sentActions.filterIsInstance<ServerAction.SyncState>()
        assertTrue(sync1.any { it.state.count == 42 })
        assertTrue(sync2.any { it.state.count == 42 })

        store.close()
    }

    @Test
    fun `SessionAwareAction does not reach reducer`() = runTest {
        val connAlice = MockTypedServerConnection<PokerAction>()
        val session = RemoteServerSession<PokerAction>()
        val middleware = MultiClientServerRemoteMiddleware<PokerState, PokerAction>(session = session)
        val store = createStore(
            initialState = PokerState(),
            reducer = pokerReducer,
            middlewares = listOf(middleware),
            errorProcessor = pokerErrorProcessor,
            scope = backgroundScope,
        )

        session.addSession("alice", connAlice)
        store.dispatchAddSession("alice", connAlice)
        delay(100)

        val gameState = PokerState(
            hands = mapOf("alice" to listOf("A♠")),
            communityCards = emptyList(),
        )
        @Suppress("UNCHECKED_CAST")
        store.dispatch(PokerAction.SyncGameState(gameState) as PokerAction)
        delay(100)

        // State unchanged — SessionAwareAction was intercepted, not emitted to reducer
        assertEquals(PokerState(), store.state.value)

        store.close()
    }
}
