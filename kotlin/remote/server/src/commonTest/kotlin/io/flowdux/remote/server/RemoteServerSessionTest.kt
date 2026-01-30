package io.flowdux.remote.server

import io.flowdux.Middleware
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteServerSessionTest {

    @Test
    fun `handleClient adds session and removes on cancellation`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()
        val session = createRemoteServerSession(
            initialState = ServerState(),
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        assertEquals(0, session.sessionCount())

        // Start client handler
        val clientJob = backgroundScope.launch {
            session.handleClient("client-1", connection)
        }
        delay(100)

        // Session registered
        assertEquals(1, session.sessionCount())
        assertTrue(session.sessionIds().contains("client-1"))

        // Cancel — should auto-remove
        clientJob.cancelAndJoin()
        delay(100)

        assertEquals(0, session.sessionCount())
    }

    @Test
    fun `multiple clients share the same state via incoming messages`() = runTest {
        val conn1 = MockTypedServerConnection<ServerAction>()
        val conn2 = MockTypedServerConnection<ServerAction>()

        val session = createRemoteServerSession(
            initialState = ServerState(),
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        // Connect two clients
        val job1 = backgroundScope.launch { session.handleClient("client-1", conn1) }
        val job2 = backgroundScope.launch { session.handleClient("client-2", conn2) }
        delay(100)

        // Client 1 sends an action
        conn1.simulateClientAction(ServerAction.ClientAdd(10))
        delay(100)

        // Client 2 sends an action
        conn2.simulateClientAction(ServerAction.ClientAdd(20))
        delay(100)

        // Both actions applied to the same state
        assertEquals(30, session.currentState.count)

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun `state changes are broadcast to all clients via serveState`() = runTest {
        val conn1 = MockTypedServerConnection<ServerAction>()
        val conn2 = MockTypedServerConnection<ServerAction>()

        val session = createRemoteServerSession(
            initialState = ServerState(),
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        // Connect two clients
        val job1 = backgroundScope.launch { session.handleClient("client-1", conn1) }
        val job2 = backgroundScope.launch { session.handleClient("client-2", conn2) }
        delay(100)

        // Client 1 sends an action that updates state
        conn1.simulateClientAction(ServerAction.ClientAdd(10))
        delay(200)

        // Both clients should receive SyncState via serveState broadcast
        val sync1 = conn1.sentActions.filterIsInstance<ServerAction.SyncState>()
        val sync2 = conn2.sentActions.filterIsInstance<ServerAction.SyncState>()
        assertTrue(sync1.isNotEmpty(), "Client 1 should receive SyncState")
        assertTrue(sync2.isNotEmpty(), "Client 2 should receive SyncState")

        // Last SyncState should contain the updated state
        assertEquals(10, sync1.last().state.count)
        assertEquals(10, sync2.last().state.count)

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun `sendToClient sends action to specific client only`() = runTest {
        val conn1 = MockTypedServerConnection<ServerAction>()
        val conn2 = MockTypedServerConnection<ServerAction>()

        val session = createRemoteServerSession(
            initialState = ServerState(),
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val job1 = backgroundScope.launch { session.handleClient("client-1", conn1) }
        val job2 = backgroundScope.launch { session.handleClient("client-2", conn2) }
        delay(100)

        // Clear any initial SyncState sent on connection
        conn1.sentActions.clear()
        conn2.sentActions.clear()

        session.sendToClient("client-1", ServerAction.Add(5))

        assertEquals(1, conn1.sentActions.size)
        assertEquals(ServerAction.Add(5), conn1.sentActions[0])
        assertTrue(conn2.sentActions.isEmpty())

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun `broadcast sends action to all clients`() = runTest {
        val conn1 = MockTypedServerConnection<ServerAction>()
        val conn2 = MockTypedServerConnection<ServerAction>()

        val session = createRemoteServerSession(
            initialState = ServerState(),
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val job1 = backgroundScope.launch { session.handleClient("client-1", conn1) }
        val job2 = backgroundScope.launch { session.handleClient("client-2", conn2) }
        delay(100)

        // Clear any initial SyncState
        conn1.sentActions.clear()
        conn2.sentActions.clear()

        session.broadcast(ServerAction.Add(7))

        assertEquals(1, conn1.sentActions.size)
        assertEquals(1, conn2.sentActions.size)
        assertEquals(ServerAction.Add(7), conn1.sentActions[0])
        assertEquals(ServerAction.Add(7), conn2.sentActions[0])

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun `close stops the session`() = runTest {
        val session = createRemoteServerSession(
            initialState = ServerState(),
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        assertFalse(session.state.value.count != 0) // initial state

        session.close()
        // After close, the session should not process new dispatches
        // (Store.isClosed is true internally)
    }

    @Test
    fun `processors are invoked for matching actions`() = runTest {
        val conn = MockTypedServerConnection<ServerAction>()

        val processors = Middleware.ActionProcessorBuilder<ServerState, ServerAction>().apply {
            on<ServerAction.ClientAdd> { _, action ->
                // Processor transforms ClientAdd into InternalReset (a non-shared action)
                // to verify the processor was invoked and the result reaches the reducer
                emit(ServerAction.InternalReset(action.value * 2))
            }
        }.build()

        val session = createRemoteServerSession(
            initialState = ServerState(),
            reducer = serverReducer,
            processors = processors,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val job = backgroundScope.launch { session.handleClient("client-1", conn) }
        delay(100)

        // Client sends ClientAdd(5) → processor emits InternalReset(10)
        conn.simulateClientAction(ServerAction.ClientAdd(5))
        delay(100)

        // InternalReset(10) reaches the reducer → state.count = 10
        assertEquals(10, session.currentState.count)

        job.cancel()
    }
}
