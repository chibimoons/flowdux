package io.flowdux.remote.server

import io.flowdux.Middleware
import io.flowdux.Store
import io.flowdux.remote.server.pattern.createSingleClientServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SingleClientServerTest {
    @Test
    fun `createSingleClientServer creates store with SingleClientSyncMiddleware`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()

        val server =
            createSingleClientServer(
                initialState = ServerState(),
                reducer = serverReducer,
                connection = connection,
                errorProcessor = serverErrorProcessor,
                scope = backgroundScope,
            )

        // Start listening
        server.dispatchStartListening()
        delay(100)

        // Client sends an action
        connection.simulateClientAction(ServerAction.ClientAdd(10))
        delay(100)

        // Action is processed
        assertEquals(10, server.state.value.count)

        server.close()
    }

    @Test
    fun `ClientSharedAction is sent to client`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()

        val server =
            createSingleClientServer(
                initialState = ServerState(),
                reducer = serverReducer,
                connection = connection,
                errorProcessor = serverErrorProcessor,
                scope = backgroundScope,
            )

        // Dispatch a ClientSharedAction
        server.dispatch(ServerAction.Add(42))
        delay(100)

        // Action is sent to client (intercepted by middleware)
        assertEquals(1, connection.sentActions.size)
        assertEquals(ServerAction.Add(42), connection.sentActions[0])

        server.close()
    }

    @Test
    fun `serve syncs state to client`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()

        val server =
            createSingleClientServer(
                initialState = ServerState(),
                reducer = serverReducer,
                connection = connection,
                errorProcessor = serverErrorProcessor,
                scope = backgroundScope,
            )

        // Use serve to start listening and syncing state
        val serveJob = backgroundScope.launchServe(server) { ServerAction.SyncState(it) }
        delay(100)

        // Initial state should be synced
        val syncActions = connection.sentActions.filterIsInstance<ServerAction.SyncState>()
        assertTrue(syncActions.isNotEmpty(), "Should receive initial SyncState")
        assertEquals(ServerState(count = 0), syncActions.first().state)

        // Client sends an action
        connection.simulateClientAction(ServerAction.ClientAdd(15))
        delay(100)

        // New state should be synced
        val latestSync = connection.sentActions.filterIsInstance<ServerAction.SyncState>().last()
        assertEquals(ServerState(count = 15), latestSync.state)

        serveJob.cancel()
        server.close()
    }

    @Test
    fun `processors are invoked for matching actions`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()

        val processors =
            Middleware
                .ActionProcessorBuilder<ServerState, ServerAction>()
                .apply {
                    on<ServerAction.ClientAdd> { _, action ->
                        // Transform ClientAdd into InternalReset
                        emit(ServerAction.InternalReset(action.value * 3))
                    }
                }.build()

        val server =
            createSingleClientServer(
                initialState = ServerState(),
                reducer = serverReducer,
                connection = connection,
                processors = processors,
                errorProcessor = serverErrorProcessor,
                scope = backgroundScope,
            )

        // Start listening
        server.dispatchStartListening()
        delay(100)

        // Client sends ClientAdd(5) → processor emits InternalReset(15)
        connection.simulateClientAction(ServerAction.ClientAdd(5))
        delay(100)

        // InternalReset(15) reaches the reducer → state.count = 15
        assertEquals(15, server.state.value.count)

        server.close()
    }

    @Test
    fun `ClientSharedAction emitted from processor is sent to client`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()

        // Processor that emits a ClientSharedAction
        val processors =
            Middleware
                .ActionProcessorBuilder<ServerState, ServerAction>()
                .apply {
                    on<ServerAction.TriggerEmitClientAction> { _, action ->
                        // emit(ClientSharedAction) - should be auto-forwarded to client
                        emit(ServerAction.Add(action.value))
                        // Also emit local action to verify processor runs
                        emit(ServerAction.InternalReset(1))
                    }
                }.build()

        val server =
            createSingleClientServer(
                initialState = ServerState(),
                reducer = serverReducer,
                connection = connection,
                processors = processors,
                errorProcessor = serverErrorProcessor,
                scope = backgroundScope,
            )

        // Start listening
        server.dispatchStartListening()
        delay(100)

        // Trigger the processor
        server.dispatch(ServerAction.TriggerEmitClientAction(42))
        delay(100)

        // Local state should be updated by InternalReset(1)
        assertEquals(1, server.state.value.count)

        // ClientSharedAction (Add(42)) should be sent to client
        val addActions = connection.sentActions.filterIsInstance<ServerAction.Add>()
        assertEquals(1, addActions.size, "emit(ClientSharedAction) should be sent to client")
        assertEquals(42, addActions[0].value)

        server.close()
    }

    @Test
    fun `non-ClientSharedAction passes through to reducer`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()

        val server =
            createSingleClientServer(
                initialState = ServerState(),
                reducer = serverReducer,
                connection = connection,
                errorProcessor = serverErrorProcessor,
                scope = backgroundScope,
            )

        // Dispatch a non-ClientSharedAction
        server.dispatch(ServerAction.InternalReset(100))
        delay(100)

        // Action passes through to reducer
        assertEquals(100, server.state.value.count)

        // Not sent to client (not a ClientSharedAction)
        assertTrue(connection.sentActions.filterIsInstance<ServerAction.InternalReset>().isEmpty())

        server.close()
    }
}

// Helper extension for launching serve in tests
private fun CoroutineScope.launchServe(
    server: Store<ServerState, ServerAction>,
    stateMapper: (ServerState) -> ServerAction,
): Job = launch {
    server.serve(stateMapper)
}
