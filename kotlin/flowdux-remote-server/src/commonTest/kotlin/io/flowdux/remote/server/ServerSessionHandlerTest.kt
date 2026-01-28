package io.flowdux.remote.server

import io.flowdux.createStore
import io.flowdux.remote.JsonMessageCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerSessionHandlerTest {

    private val codec = JsonMessageCodec()
    private val actionCodec = ServerActionCodec()

    private fun createHandler(
        scope: kotlinx.coroutines.CoroutineScope,
        connection: MockServerConnection,
    ): ServerSessionHandler<ServerState, ServerAction> {
        return ServerSessionHandler(
            storeFactory = { conn ->
                val srm = TestServerRemoteMiddleware(conn, actionCodec)
                createStore(
                    initialState = ServerState(),
                    reducer = serverReducer,
                    middlewares = listOf(srm),
                    errorProcessor = serverErrorProcessor,
                    scope = scope,
                )
            },
            connection = connection,
        )
    }

    @Test
    fun `incoming client message is dispatched via FlowHolderAction`() = runTest {
        val connection = MockServerConnection()
        val handler = createHandler(backgroundScope, connection)
        handler.initialize()
        handler.dispatch(ServerAction.StartListening)
        delay(100)

        // Simulate client sending Add (ClientSharedAction)
        val clientMessage = codec.encodeActionMessage("""{"type":"Add","value":10}""")
        connection.simulateClientMessage(clientMessage)

        // Add is ClientSharedAction — intercepted by SRM, sent back to client
        waitUntil { connection.sentMessages.size == 1 }

        val response = codec.decodeServerMessage(connection.sentMessages[0])
        assertEquals("""{"type":"Add","value":10}""", response.actions[0])

        handler.close()
    }

    @Test
    fun `incoming Increment is sent to client`() = runTest {
        val connection = MockServerConnection()
        val handler = createHandler(backgroundScope, connection)
        handler.initialize()
        handler.dispatch(ServerAction.StartListening)
        delay(100)

        connection.simulateClientMessage(codec.encodeActionMessage("""{"type":"Increment"}"""))

        waitUntil { connection.sentMessages.size == 1 }

        val response = codec.decodeServerMessage(connection.sentMessages[0])
        assertEquals("""{"type":"Increment"}""", response.actions[0])

        handler.close()
    }

    @Test
    fun `sequential incoming messages send separate responses`() = runTest {
        val connection = MockServerConnection()
        val handler = createHandler(backgroundScope, connection)
        handler.initialize()
        handler.dispatch(ServerAction.StartListening)
        delay(100)

        connection.simulateClientMessage(codec.encodeActionMessage("""{"type":"Add","value":5}"""))
        waitUntil { connection.sentMessages.size == 1 }

        connection.simulateClientMessage(codec.encodeActionMessage("""{"type":"Add","value":3}"""))
        waitUntil { connection.sentMessages.size == 2 }

        val resp1 = codec.decodeServerMessage(connection.sentMessages[0])
        assertEquals("""{"type":"Add","value":5}""", resp1.actions[0])

        val resp2 = codec.decodeServerMessage(connection.sentMessages[1])
        assertEquals("""{"type":"Add","value":3}""", resp2.actions[0])

        handler.close()
    }

    @Test
    fun `dispatch sends server-initiated ClientSharedAction to client`() = runTest {
        val connection = MockServerConnection()
        val handler = createHandler(backgroundScope, connection)
        handler.initialize()

        handler.dispatch(ServerAction.Add(99))

        waitUntil { connection.sentMessages.size == 1 }

        val response = codec.decodeServerMessage(connection.sentMessages[0])
        assertEquals("""{"type":"Add","value":99}""", response.actions[0])

        handler.close()
    }

    @Test
    fun `non-ClientSharedAction reaches reducer and updates state`() = runTest {
        val connection = MockServerConnection()
        val handler = createHandler(backgroundScope, connection)
        handler.initialize()

        handler.dispatch(ServerAction.InternalReset(42))

        handler.state.first { it.count == 42 }
        assertEquals(42, handler.state.value.count)

        assertTrue(connection.sentMessages.isEmpty())

        handler.close()
    }

    @Test
    fun `close is safe to call before initialize`() {
        val connection = MockServerConnection()
        val handler = ServerSessionHandler<ServerState, ServerAction>(
            storeFactory = { conn ->
                val srm = TestServerRemoteMiddleware(conn, actionCodec)
                createStore(
                    initialState = ServerState(),
                    reducer = serverReducer,
                    middlewares = listOf(srm),
                    scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
                )
            },
            connection = connection,
        )
        handler.close()
    }

    @Test
    fun `roundtrip client message to server response`() = runTest {
        val connection = MockServerConnection()
        val handler = createHandler(backgroundScope, connection)
        handler.initialize()
        handler.dispatch(ServerAction.StartListening)
        delay(100)

        val actionJson = actionCodec.encode(ServerAction.Add(42))
        val clientMessage = codec.encodeActionMessage(actionJson)
        connection.simulateClientMessage(clientMessage)

        waitUntil { connection.sentMessages.size == 1 }

        val response = codec.decodeServerMessage(connection.sentMessages[0])
        val decodedAction = actionCodec.decode(response.actions[0])
        assertTrue(decodedAction is ServerAction.Add)
        assertEquals(42, (decodedAction as ServerAction.Add).value)

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
