package io.flowdux.remote.server

import io.flowdux.Middleware
import io.flowdux.remote.server.pattern.createPerClientServer
import io.flowdux.remote.server.pattern.createSingleClientServer
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerClientServerTest {

    @Test
    fun `handleClient creates session and serves state`() = runTest {
        val perClientServer = createPerClientServer(
            initialStateFactory = { sessionId -> ServerState(count = sessionId.length) },
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val conn = MockTypedServerConnection<ServerAction>()

        assertEquals(0, perClientServer.sessionCount())

        // Connect client
        val clientJob = backgroundScope.launch {
            perClientServer.handleClient("player-1", conn)
        }
        delay(100)

        // Session created
        assertEquals(1, perClientServer.sessionCount())
        assertTrue(perClientServer.sessionIds().contains("player-1"))

        // Initial state synced to client
        val syncActions = conn.sentActions.filterIsInstance<ServerAction.SyncState>()
        assertTrue(syncActions.isNotEmpty(), "Should receive initial SyncState")
        assertEquals(8, syncActions.first().state.count) // "player-1".length = 8

        clientJob.cancelAndJoin()
        perClientServer.close()
    }

    @Test
    fun `handleClient removes session on disconnect`() = runTest {
        val perClientServer = createPerClientServer(
            initialStateFactory = { ServerState() },
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val conn = MockTypedServerConnection<ServerAction>()

        val clientJob = backgroundScope.launch {
            perClientServer.handleClient("player-1", conn)
        }
        delay(100)

        assertEquals(1, perClientServer.sessionCount())

        // Disconnect
        clientJob.cancelAndJoin()
        delay(100)

        // Session removed
        assertEquals(0, perClientServer.sessionCount())

        perClientServer.close()
    }

    @Test
    fun `each client has independent state`() = runTest {
        val perClientServer = createPerClientServer(
            initialStateFactory = { ServerState() },
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val conn1 = MockTypedServerConnection<ServerAction>()
        val conn2 = MockTypedServerConnection<ServerAction>()

        // Connect two clients
        val job1 = backgroundScope.launch {
            perClientServer.handleClient("player-1", conn1)
        }
        val job2 = backgroundScope.launch {
            perClientServer.handleClient("player-2", conn2)
        }
        delay(100)

        assertEquals(2, perClientServer.sessionCount())

        // Clear initial syncs
        conn1.sentActions.clear()
        conn2.sentActions.clear()

        // Client 1 sends action
        conn1.simulateClientAction(ServerAction.ClientAdd(10))
        delay(100)

        // Client 2 sends action
        conn2.simulateClientAction(ServerAction.ClientAdd(20))
        delay(100)

        // Each client receives their own state (not shared)
        val sync1 = conn1.sentActions.filterIsInstance<ServerAction.SyncState>().last()
        val sync2 = conn2.sentActions.filterIsInstance<ServerAction.SyncState>().last()

        assertEquals(10, sync1.state.count) // player-1 state
        assertEquals(20, sync2.state.count) // player-2 state

        // Cross-check: client 1 should NOT see client 2's state
        val allSync1 = conn1.sentActions.filterIsInstance<ServerAction.SyncState>()
        assertTrue(allSync1.none { it.state.count == 20 })

        job1.cancel()
        job2.cancel()
        perClientServer.close()
    }

    @Test
    fun `getSession returns active session`() = runTest {
        val perClientServer = createPerClientServer(
            initialStateFactory = { ServerState() },
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val conn = MockTypedServerConnection<ServerAction>()

        // No session yet
        assertNull(perClientServer.getSession("player-1"))

        val clientJob = backgroundScope.launch {
            perClientServer.handleClient("player-1", conn)
        }
        delay(100)

        // Session exists
        val session = perClientServer.getSession("player-1")
        assertNotNull(session)

        // Can dispatch to session
        session.dispatch(ServerAction.InternalReset(99))
        delay(100)

        val syncActions = conn.sentActions.filterIsInstance<ServerAction.SyncState>()
        assertEquals(99, syncActions.last().state.count)

        clientJob.cancel()
        perClientServer.close()
    }

    @Test
    fun `custom session factory is used`() = runTest {
        var factoryCalledWith: String? = null

        val perClientServer = createPerClientServer(
            stateMapper = { ServerAction.SyncState(it) },
            scope = backgroundScope,
        ) { sessionId, connection ->
            factoryCalledWith = sessionId
            createSingleClientServer(
                initialState = ServerState(count = 42),
                reducer = serverReducer,
                connection = connection,
                errorProcessor = serverErrorProcessor,
                scope = backgroundScope,
            )
        }

        val conn = MockTypedServerConnection<ServerAction>()

        val clientJob = backgroundScope.launch {
            perClientServer.handleClient("custom-player", conn)
        }
        delay(100)

        // Factory was called with correct sessionId
        assertEquals("custom-player", factoryCalledWith)

        // Initial state from factory
        val syncActions = conn.sentActions.filterIsInstance<ServerAction.SyncState>()
        assertEquals(42, syncActions.first().state.count)

        clientJob.cancel()
        perClientServer.close()
    }

    @Test
    fun `processors work with PerClientServer`() = runTest {
        val processors = Middleware.ActionProcessorBuilder<ServerState, ServerAction>().apply {
            on<ServerAction.ClientAdd> { _, action ->
                emit(ServerAction.InternalReset(action.value * 5))
            }
        }.build()

        val perClientServer = createPerClientServer(
            initialStateFactory = { ServerState() },
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            processors = processors,
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val conn = MockTypedServerConnection<ServerAction>()

        val clientJob = backgroundScope.launch {
            perClientServer.handleClient("player-1", conn)
        }
        delay(100)

        conn.sentActions.clear()

        // Client sends ClientAdd(5) → processor emits InternalReset(25)
        conn.simulateClientAction(ServerAction.ClientAdd(5))
        delay(100)

        val syncActions = conn.sentActions.filterIsInstance<ServerAction.SyncState>()
        assertEquals(25, syncActions.last().state.count)

        clientJob.cancel()
        perClientServer.close()
    }

    @Test
    fun `close shuts down all sessions`() = runTest {
        val perClientServer = createPerClientServer(
            initialStateFactory = { ServerState() },
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val conn1 = MockTypedServerConnection<ServerAction>()
        val conn2 = MockTypedServerConnection<ServerAction>()

        val job1 = backgroundScope.launch {
            perClientServer.handleClient("player-1", conn1)
        }
        val job2 = backgroundScope.launch {
            perClientServer.handleClient("player-2", conn2)
        }
        delay(100)

        assertEquals(2, perClientServer.sessionCount())

        perClientServer.close()
        assertEquals(0, perClientServer.sessionCount())

        job1.cancel()
        job2.cancel()
    }
}
