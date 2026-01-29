package io.flowdux.remote.server

import io.flowdux.createStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerSessionHandlerTest {

    private fun createHandler(
        scope: kotlinx.coroutines.CoroutineScope,
        connection: MockTypedServerConnection<ServerAction>,
    ): ServerSessionHandler<ServerState, ServerAction> {
        return ServerSessionHandler(
            storeFactory = { _ ->
                val srm = TestServerRemoteMiddleware(connection)
                createStore(
                    initialState = ServerState(),
                    reducer = serverReducer,
                    middlewares = listOf(srm),
                    errorProcessor = serverErrorProcessor,
                    scope = scope,
                )
            },
            connection = MockRawServerConnection(),
        )
    }

    @Test
    fun `incoming client action is dispatched via FlowHolderAction`() = runTest {
        val typedConnection = MockTypedServerConnection<ServerAction>()
        val handler = createHandler(backgroundScope, typedConnection)
        handler.initialize()
        handler.dispatch(ServerAction.StartListening)
        delay(100)

        // Simulate client sending Add (ClientSharedAction)
        typedConnection.simulateClientAction(ServerAction.Add(10))

        // Add is ClientSharedAction — intercepted by SRM, sent back to client
        waitUntil { typedConnection.sentActions.size == 1 }
        assertEquals(ServerAction.Add(10), typedConnection.sentActions[0])

        handler.close()
    }

    @Test
    fun `incoming Increment is sent to client`() = runTest {
        val typedConnection = MockTypedServerConnection<ServerAction>()
        val handler = createHandler(backgroundScope, typedConnection)
        handler.initialize()
        handler.dispatch(ServerAction.StartListening)
        delay(100)

        typedConnection.simulateClientAction(ServerAction.Increment)

        waitUntil { typedConnection.sentActions.size == 1 }
        assertEquals(ServerAction.Increment, typedConnection.sentActions[0])

        handler.close()
    }

    @Test
    fun `sequential incoming actions send separate responses`() = runTest {
        val typedConnection = MockTypedServerConnection<ServerAction>()
        val handler = createHandler(backgroundScope, typedConnection)
        handler.initialize()
        handler.dispatch(ServerAction.StartListening)
        delay(100)

        typedConnection.simulateClientAction(ServerAction.Add(5))
        waitUntil { typedConnection.sentActions.size == 1 }

        typedConnection.simulateClientAction(ServerAction.Add(3))
        waitUntil { typedConnection.sentActions.size == 2 }

        assertEquals(ServerAction.Add(5), typedConnection.sentActions[0])
        assertEquals(ServerAction.Add(3), typedConnection.sentActions[1])

        handler.close()
    }

    @Test
    fun `dispatch sends server-initiated ClientSharedAction to client`() = runTest {
        val typedConnection = MockTypedServerConnection<ServerAction>()
        val handler = createHandler(backgroundScope, typedConnection)
        handler.initialize()

        handler.dispatch(ServerAction.Add(99))

        waitUntil { typedConnection.sentActions.size == 1 }
        assertEquals(ServerAction.Add(99), typedConnection.sentActions[0])

        handler.close()
    }

    @Test
    fun `non-ClientSharedAction reaches reducer and updates state`() = runTest {
        val typedConnection = MockTypedServerConnection<ServerAction>()
        val handler = createHandler(backgroundScope, typedConnection)
        handler.initialize()

        handler.dispatch(ServerAction.InternalReset(42))

        handler.state.first { it.count == 42 }
        assertEquals(42, handler.state.value.count)

        assertTrue(typedConnection.sentActions.isEmpty())

        handler.close()
    }

    @Test
    fun `close is safe to call before initialize`() {
        val handler = ServerSessionHandler<ServerState, ServerAction>(
            storeFactory = { _ ->
                val srm = TestServerRemoteMiddleware(MockTypedServerConnection())
                createStore(
                    initialState = ServerState(),
                    reducer = serverReducer,
                    middlewares = listOf(srm),
                    scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
                )
            },
            connection = MockRawServerConnection(),
        )
        handler.close()
    }

    private suspend fun waitUntil(
        maxAttempts: Int = 500,
        intervalMs: Long = 10,
        condition: () -> Boolean,
    ) {
        repeat(maxAttempts) {
            if (condition()) return
            kotlinx.coroutines.delay(intervalMs)
        }
        throw AssertionError("Timed out waiting for condition")
    }
}

/**
 * A raw [ServerConnection] stub for [ServerSessionHandler] constructor.
 * Not used for actual data flow — the typed connection is injected directly into the middleware.
 */
private class MockRawServerConnection : ServerConnection {
    override val incoming = kotlinx.coroutines.flow.emptyFlow<String>()
    override suspend fun send(message: String) {}
}
