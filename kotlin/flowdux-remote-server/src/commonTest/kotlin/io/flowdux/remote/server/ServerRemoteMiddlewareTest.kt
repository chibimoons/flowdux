package io.flowdux.remote.server

import io.flowdux.createStore
import io.flowdux.remote.JsonMessageCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerRemoteMiddlewareTest {

    private val actionCodec = ServerActionCodec()
    private val messageCodec = JsonMessageCodec()

    @Test
    fun `ClientSharedAction is intercepted and sent to client`() = runTest {
        val connection = MockServerConnection()
        val middleware = ServerRemoteMiddleware<ServerState, ServerAction>(
            connection = connection,
            actionCodec = actionCodec,
            messageCodec = messageCodec,
        )

        val result = middleware.process(
            getState = { ServerState() },
            action = ServerAction.Add(10),
        ).toList()

        // ClientSharedAction is consumed — not emitted downstream
        assertTrue(result.isEmpty())

        // Verify it was sent to client
        assertEquals(1, connection.sentMessages.size)
        val response = messageCodec.decodeServerMessage(connection.sentMessages[0])
        assertEquals(1, response.actions.size)
        assertEquals("""{"type":"Add","value":10}""", response.actions[0])
    }

    @Test
    fun `non-ClientSharedAction passes through unchanged`() = runTest {
        val connection = MockServerConnection()
        val middleware = ServerRemoteMiddleware<ServerState, ServerAction>(
            connection = connection,
            actionCodec = actionCodec,
            messageCodec = messageCodec,
        )

        val action = ServerAction.InternalReset(42)
        val result = middleware.process(
            getState = { ServerState() },
            action = action,
        ).toList()

        // Non-ClientSharedAction passes through
        assertEquals(listOf(action), result)

        // Nothing sent to client
        assertTrue(connection.sentMessages.isEmpty())
    }

    @Test
    fun `multiple ClientSharedActions are each sent separately`() = runTest {
        val connection = MockServerConnection()
        val middleware = ServerRemoteMiddleware<ServerState, ServerAction>(
            connection = connection,
            actionCodec = actionCodec,
            messageCodec = messageCodec,
        )

        middleware.process(
            getState = { ServerState() },
            action = ServerAction.Increment,
        ).toList()

        middleware.process(
            getState = { ServerState(1) },
            action = ServerAction.Add(5),
        ).toList()

        assertEquals(2, connection.sentMessages.size)

        val resp1 = messageCodec.decodeServerMessage(connection.sentMessages[0])
        assertEquals("""{"type":"Increment"}""", resp1.actions[0])

        val resp2 = messageCodec.decodeServerMessage(connection.sentMessages[1])
        assertEquals("""{"type":"Add","value":5}""", resp2.actions[0])
    }

    @Test
    fun `encoding roundtrip preserves action data`() = runTest {
        val connection = MockServerConnection()
        val middleware = ServerRemoteMiddleware<ServerState, ServerAction>(
            connection = connection,
            actionCodec = actionCodec,
            messageCodec = messageCodec,
        )

        middleware.process(
            getState = { ServerState() },
            action = ServerAction.Add(42),
        ).toList()

        val response = messageCodec.decodeServerMessage(connection.sentMessages[0])
        val decoded = actionCodec.decode(response.actions[0])
        assertTrue(decoded is ServerAction.Add)
        assertEquals(42, (decoded as ServerAction.Add).value)
    }

    @Test
    fun `incoming client messages are dispatched through pipeline via FlowHolderAction`() = runTest {
        val connection = MockServerConnection()
        val middleware = TestServerRemoteMiddleware(
            connection = connection,
            actionCodec = actionCodec,
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

        // Simulate client sending a ClientAdd action
        val clientMessage = messageCodec.encodeActionMessage("""{"type":"ClientAdd","value":10}""")
        connection.simulateClientMessage(clientMessage)
        delay(100)

        // ClientAdd is ServerSharedAction — passes through SRM, reaches reducer
        assertEquals(10, store.state.value.count)

        store.close()
    }
}
