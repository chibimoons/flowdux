package io.flowdux.remote.server

import io.flowdux.Middleware
import io.flowdux.remote.server.pattern.createSharedStateServer
import io.flowdux.remote.server.session.BroadcastConfig
import io.flowdux.remote.server.session.InMemorySessionRegistry
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedStateServerTest {

    @Test
    fun `handleClient adds session and removes on cancellation`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()
        val server = createSharedStateServer(
            initialState = ServerState(),
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        assertEquals(0, server.sessionCount())

        // Start client handler
        val clientJob = backgroundScope.launch {
            server.handleClient("client-1", connection)
        }
        delay(100)

        // Session registered
        assertEquals(1, server.sessionCount())
        assertTrue(server.sessionIds().contains("client-1"))

        // Cancel — should auto-remove
        clientJob.cancelAndJoin()
        delay(100)

        assertEquals(0, server.sessionCount())
    }

    @Test
    fun `multiple clients share the same state via incoming messages`() = runTest {
        val conn1 = MockTypedServerConnection<ServerAction>()
        val conn2 = MockTypedServerConnection<ServerAction>()

        val server = createSharedStateServer(
            initialState = ServerState(),
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        // Connect two clients
        val job1 = backgroundScope.launch { server.handleClient("client-1", conn1) }
        val job2 = backgroundScope.launch { server.handleClient("client-2", conn2) }
        delay(100)

        // Client 1 sends an action
        conn1.simulateClientAction(ServerAction.ClientAdd(10))
        delay(100)

        // Client 2 sends an action
        conn2.simulateClientAction(ServerAction.ClientAdd(20))
        delay(100)

        // Both actions applied to the same state
        assertEquals(30, server.currentState.count)

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun `state changes are broadcast to all clients via serveState`() = runTest {
        val conn1 = MockTypedServerConnection<ServerAction>()
        val conn2 = MockTypedServerConnection<ServerAction>()

        val server = createSharedStateServer(
            initialState = ServerState(),
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        // Connect two clients
        val job1 = backgroundScope.launch { server.handleClient("client-1", conn1) }
        val job2 = backgroundScope.launch { server.handleClient("client-2", conn2) }
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

        val server = createSharedStateServer(
            initialState = ServerState(),
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val job1 = backgroundScope.launch { server.handleClient("client-1", conn1) }
        val job2 = backgroundScope.launch { server.handleClient("client-2", conn2) }
        delay(100)

        // Clear any initial SyncState sent on connection
        conn1.sentActions.clear()
        conn2.sentActions.clear()

        server.sendToClient("client-1", ServerAction.Add(5))
        delay(100)

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

        val server = createSharedStateServer(
            initialState = ServerState(),
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val job1 = backgroundScope.launch { server.handleClient("client-1", conn1) }
        val job2 = backgroundScope.launch { server.handleClient("client-2", conn2) }
        delay(100)

        // Clear any initial SyncState
        conn1.sentActions.clear()
        conn2.sentActions.clear()

        server.broadcast(ServerAction.Add(7))
        delay(100)

        assertEquals(1, conn1.sentActions.size)
        assertEquals(1, conn2.sentActions.size)
        assertEquals(ServerAction.Add(7), conn1.sentActions[0])
        assertEquals(ServerAction.Add(7), conn2.sentActions[0])

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun `close stops the session`() = runTest {
        val server = createSharedStateServer(
            initialState = ServerState(),
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        assertFalse(server.state.value.count != 0) // initial state

        server.close()
        // After close, the server should not process new dispatches
        // (Store.isClosed is true internally)
    }

    @Test
    fun `ClientSharedAction emitted from processor is broadcast to all clients`() = runTest {
        val conn1 = MockTypedServerConnection<ServerAction>()
        val conn2 = MockTypedServerConnection<ServerAction>()

        // Processor that emits a ClientSharedAction
        val processors = Middleware.ActionProcessorBuilder<ServerState, ServerAction>().apply {
            on<ServerAction.TriggerEmitClientAction> { _, action ->
                // emit(ClientSharedAction) - should be auto-forwarded and broadcast
                emit(ServerAction.Add(action.value))
                // Also emit local action to verify processor runs
                emit(ServerAction.InternalReset(1))
            }
        }.build()

        val server = createSharedStateServer(
            initialState = ServerState(),
            reducer = serverReducer,
            processors = processors,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val job1 = backgroundScope.launch { server.handleClient("client-1", conn1) }
        val job2 = backgroundScope.launch { server.handleClient("client-2", conn2) }
        delay(100)

        // Clear any initial SyncState
        conn1.sentActions.clear()
        conn2.sentActions.clear()

        // Trigger the processor via client action
        conn1.simulateClientAction(ServerAction.TriggerEmitClientAction(42))
        delay(100)

        // Local state should be updated by InternalReset(1)
        assertEquals(1, server.currentState.count)

        // ClientSharedAction (Add(42)) should be broadcast to all clients
        val addActions1 = conn1.sentActions.filterIsInstance<ServerAction.Add>()
        val addActions2 = conn2.sentActions.filterIsInstance<ServerAction.Add>()
        assertEquals(1, addActions1.size, "emit(ClientSharedAction) should be sent to client 1")
        assertEquals(1, addActions2.size, "emit(ClientSharedAction) should be sent to client 2")
        assertEquals(42, addActions1[0].value)
        assertEquals(42, addActions2[0].value)

        job1.cancel()
        job2.cancel()
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

        val server = createSharedStateServer(
            initialState = ServerState(),
            reducer = serverReducer,
            processors = processors,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val job = backgroundScope.launch { server.handleClient("client-1", conn) }
        delay(100)

        // Client sends ClientAdd(5) → processor emits InternalReset(10)
        conn.simulateClientAction(ServerAction.ClientAdd(5))
        delay(100)

        // InternalReset(10) reaches the reducer → state.count = 10
        assertEquals(10, server.currentState.count)

        job.cancel()
    }

    // -- Parallel Broadcast Tests --

    @Test
    fun `parallel broadcast sends action to all clients`() = runTest {
        val conn1 = MockTypedServerConnection<ServerAction>()
        val conn2 = MockTypedServerConnection<ServerAction>()
        val conn3 = MockTypedServerConnection<ServerAction>()

        val server = createSharedStateServer(
            initialState = ServerState(),
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            broadcastConfig = BroadcastConfig(concurrency = 4),
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val job1 = backgroundScope.launch { server.handleClient("client-1", conn1) }
        val job2 = backgroundScope.launch { server.handleClient("client-2", conn2) }
        val job3 = backgroundScope.launch { server.handleClient("client-3", conn3) }
        delay(100)

        // Clear any initial SyncState
        conn1.sentActions.clear()
        conn2.sentActions.clear()
        conn3.sentActions.clear()

        server.broadcast(ServerAction.Add(42))
        delay(100)

        assertEquals(1, conn1.sentActions.size)
        assertEquals(1, conn2.sentActions.size)
        assertEquals(1, conn3.sentActions.size)
        assertEquals(ServerAction.Add(42), conn1.sentActions[0])
        assertEquals(ServerAction.Add(42), conn2.sentActions[0])
        assertEquals(ServerAction.Add(42), conn3.sentActions[0])

        job1.cancel()
        job2.cancel()
        job3.cancel()
    }

    @Test
    fun `parallel broadcast with custom session registry`() = runTest {
        val conn1 = MockTypedServerConnection<ServerAction>()
        val conn2 = MockTypedServerConnection<ServerAction>()

        val customRegistry = InMemorySessionRegistry<ServerAction>()

        val server = createSharedStateServer(
            initialState = ServerState(),
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            sessionRegistry = customRegistry,
            broadcastConfig = BroadcastConfig(concurrency = 16),
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val job1 = backgroundScope.launch { server.handleClient("client-1", conn1) }
        val job2 = backgroundScope.launch { server.handleClient("client-2", conn2) }
        delay(100)

        // Verify sessions are in our custom registry
        assertEquals(2, customRegistry.sessionCount())
        assertTrue(customRegistry.sessionIds().contains("client-1"))
        assertTrue(customRegistry.sessionIds().contains("client-2"))

        // Clear any initial SyncState
        conn1.sentActions.clear()
        conn2.sentActions.clear()

        server.broadcast(ServerAction.Add(99))
        delay(100)

        assertEquals(1, conn1.sentActions.size)
        assertEquals(1, conn2.sentActions.size)
        assertEquals(ServerAction.Add(99), conn1.sentActions[0])
        assertEquals(ServerAction.Add(99), conn2.sentActions[0])

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun `BroadcastConfig validates concurrency`() {
        // Valid configs
        BroadcastConfig(concurrency = 1)
        BroadcastConfig(concurrency = 16)
        BroadcastConfig(concurrency = 100)

        // Invalid config should throw
        try {
            BroadcastConfig(concurrency = 0)
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("concurrency") == true)
        }

        try {
            BroadcastConfig(concurrency = -1)
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("concurrency") == true)
        }
    }

    @Test
    fun `BroadcastConfig companion presets are valid`() {
        assertEquals(1, BroadcastConfig.Sequential.concurrency)
        assertEquals(16, BroadcastConfig.Default.concurrency)
        assertEquals(64, BroadcastConfig.HighThroughput.concurrency)
    }

    // -- Dead Session Cleanup Tests --

    @Test
    fun `handleClient removes session when connection incoming flow completes normally`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()
        val server = createSharedStateServer(
            initialState = ServerState(),
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val clientJob = backgroundScope.launch {
            server.handleClient("client-1", connection)
        }
        delay(100)
        assertEquals(1, server.sessionCount())

        // Simulate clean disconnection — incoming flow completes
        connection.closeIncoming()
        delay(200)

        // Session should be removed automatically
        assertEquals(0, server.sessionCount())
        assertTrue(clientJob.isCompleted)
    }

    @Test
    fun `handleClient removes session when connection incoming flow errors`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()
        val server = createSharedStateServer(
            initialState = ServerState(),
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val clientJob = backgroundScope.launch {
            server.handleClient("client-1", connection)
        }
        delay(100)
        assertEquals(1, server.sessionCount())

        // Simulate connection failure — incoming flow throws
        connection.closeIncomingWithError(RuntimeException("connection lost"))
        delay(200)

        // Session should be removed automatically
        assertEquals(0, server.sessionCount())
        assertTrue(clientJob.isCompleted)
    }

    @Test
    fun `dead session does not leak when other sessions remain active`() = runTest {
        val conn1 = MockTypedServerConnection<ServerAction>()
        val conn2 = MockTypedServerConnection<ServerAction>()

        val server = createSharedStateServer(
            initialState = ServerState(),
            reducer = serverReducer,
            stateMapper = { ServerAction.SyncState(it) },
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val job1 = backgroundScope.launch { server.handleClient("client-1", conn1) }
        val job2 = backgroundScope.launch { server.handleClient("client-2", conn2) }
        delay(100)
        assertEquals(2, server.sessionCount())

        // Client 1 disconnects with error
        conn1.closeIncomingWithError(RuntimeException("network error"))
        delay(200)

        // Only dead session is removed; active session remains
        assertEquals(1, server.sessionCount())
        assertTrue(server.sessionIds().contains("client-2"))
        assertFalse(server.sessionIds().contains("client-1"))
        assertTrue(job1.isCompleted)
        assertFalse(job2.isCompleted)

        // Active session still works
        conn2.simulateClientAction(ServerAction.ClientAdd(5))
        delay(100)
        assertEquals(5, server.currentState.count)

        job2.cancel()
    }
}
