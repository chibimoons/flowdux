package io.flowdux.remote.server

import io.flowdux.createStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerRemoteMiddlewareTest {

    @Test
    fun `ClientSharedAction is intercepted and sent to client`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()
        val middleware = ServerRemoteMiddleware<ServerState, ServerAction>(
            connection = connection,
        )

        val result = middleware.process(
            getState = { ServerState() },
            action = ServerAction.Add(10),
        ).toList()

        // ClientSharedAction is consumed — not emitted downstream
        assertTrue(result.isEmpty())

        // Verify it was sent to client as a typed action
        assertEquals(1, connection.sentActions.size)
        assertTrue(connection.sentActions[0] is ServerAction.Add)
        assertEquals(10, (connection.sentActions[0] as ServerAction.Add).value)
    }

    @Test
    fun `non-ClientSharedAction passes through unchanged`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()
        val middleware = ServerRemoteMiddleware<ServerState, ServerAction>(
            connection = connection,
        )

        val action = ServerAction.InternalReset(42)
        val result = middleware.process(
            getState = { ServerState() },
            action = action,
        ).toList()

        // Non-ClientSharedAction passes through
        assertEquals(listOf(action), result)

        // Nothing sent to client
        assertTrue(connection.sentActions.isEmpty())
    }

    @Test
    fun `multiple ClientSharedActions are each sent separately`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()
        val middleware = ServerRemoteMiddleware<ServerState, ServerAction>(
            connection = connection,
        )

        middleware.process(
            getState = { ServerState() },
            action = ServerAction.Increment,
        ).toList()

        middleware.process(
            getState = { ServerState(1) },
            action = ServerAction.Add(5),
        ).toList()

        assertEquals(2, connection.sentActions.size)

        assertTrue(connection.sentActions[0] is ServerAction.Increment)
        assertTrue(connection.sentActions[1] is ServerAction.Add)
        assertEquals(5, (connection.sentActions[1] as ServerAction.Add).value)
    }

    @Test
    fun `typed action roundtrip preserves action data`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()
        val middleware = ServerRemoteMiddleware<ServerState, ServerAction>(
            connection = connection,
        )

        middleware.process(
            getState = { ServerState() },
            action = ServerAction.Add(42),
        ).toList()

        val sentAction = connection.sentActions[0]
        assertTrue(sentAction is ServerAction.Add)
        assertEquals(42, (sentAction as ServerAction.Add).value)
    }

    @Test
    fun `incoming client messages are dispatched through pipeline via FlowHolderAction`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()
        val middleware = TestServerRemoteMiddleware(
            connection = connection,
        )

        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer,
            middlewares = listOf(middleware),
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        // Start listening
        store.dispatch(ServerAction.StartListening)
        delay(100)

        // Simulate client sending a ClientAdd action (typed)
        connection.simulateClientAction(ServerAction.ClientAdd(10))
        delay(100)

        // ClientAdd is ServerSharedAction — passes through SRM, reaches reducer
        assertEquals(10, store.state.value.count)

        store.close()
    }
}
