package io.flowdux.remote.server

import io.flowdux.createStore
import io.flowdux.remote.ActionCodec
import io.flowdux.remote.MessageCodec
import io.flowdux.remote.serialization.JsonMessageCodec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerSessionHandlerTest {

    private val actionCodec = ServerActionCodec()
    private val messageCodec = JsonMessageCodec()

    /**
     * A raw [ServerConnection] mock for use with [ServerSessionHandler], which still
     * receives a raw connection and delegates to typed connection internally via storeFactory.
     */
    private class MockRawServerConnection : ServerConnection {
        private val incomingChannel = Channel<String>(Channel.BUFFERED)
        override val incoming: Flow<String> = incomingChannel.receiveAsFlow()

        val sentMessages = mutableListOf<String>()

        override suspend fun send(message: String) {
            sentMessages.add(message)
        }

        suspend fun simulateClientMessage(message: String) {
            incomingChannel.send(message)
        }
    }

    private fun createHandler(
        scope: kotlinx.coroutines.CoroutineScope,
        connection: MockRawServerConnection,
    ): ServerSessionHandler<ServerState, ServerAction> {
        return ServerSessionHandler(
            storeFactory = { conn ->
                val typedConn = conn.typed(actionCodec, messageCodec)
                val srm = TestServerRemoteMiddleware(typedConn)
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
        val connection = MockRawServerConnection()
        val handler = createHandler(backgroundScope, connection)
        handler.initialize()
        handler.dispatch(ServerAction.StartListening)
        delay(100)

        // Simulate client sending Add (ClientSharedAction)
        val clientMessage = messageCodec.encodeActionMessage(actionCodec.encode(ServerAction.Add(10)))
        connection.simulateClientMessage(clientMessage)

        // Add is ClientSharedAction — intercepted by SRM, sent back to client
        waitUntil { connection.sentMessages.size == 1 }

        val response = messageCodec.decodeServerMessage(connection.sentMessages[0])
        assertEquals(actionCodec.encode(ServerAction.Add(10)), response.actions[0])

        handler.close()
    }

    @Test
    fun `incoming Increment is sent to client`() = runTest {
        val connection = MockRawServerConnection()
        val handler = createHandler(backgroundScope, connection)
        handler.initialize()
        handler.dispatch(ServerAction.StartListening)
        delay(100)

        connection.simulateClientMessage(messageCodec.encodeActionMessage(actionCodec.encode(ServerAction.Increment)))

        waitUntil { connection.sentMessages.size == 1 }

        val response = messageCodec.decodeServerMessage(connection.sentMessages[0])
        assertEquals(actionCodec.encode(ServerAction.Increment), response.actions[0])

        handler.close()
    }

    @Test
    fun `sequential incoming messages send separate responses`() = runTest {
        val connection = MockRawServerConnection()
        val handler = createHandler(backgroundScope, connection)
        handler.initialize()
        handler.dispatch(ServerAction.StartListening)
        delay(100)

        connection.simulateClientMessage(messageCodec.encodeActionMessage(actionCodec.encode(ServerAction.Add(5))))
        waitUntil { connection.sentMessages.size == 1 }

        connection.simulateClientMessage(messageCodec.encodeActionMessage(actionCodec.encode(ServerAction.Add(3))))
        waitUntil { connection.sentMessages.size == 2 }

        val resp1 = messageCodec.decodeServerMessage(connection.sentMessages[0])
        assertEquals(actionCodec.encode(ServerAction.Add(5)), resp1.actions[0])

        val resp2 = messageCodec.decodeServerMessage(connection.sentMessages[1])
        assertEquals(actionCodec.encode(ServerAction.Add(3)), resp2.actions[0])

        handler.close()
    }

    @Test
    fun `dispatch sends server-initiated ClientSharedAction to client`() = runTest {
        val connection = MockRawServerConnection()
        val handler = createHandler(backgroundScope, connection)
        handler.initialize()

        handler.dispatch(ServerAction.Add(99))

        waitUntil { connection.sentMessages.size == 1 }

        val response = messageCodec.decodeServerMessage(connection.sentMessages[0])
        assertEquals(actionCodec.encode(ServerAction.Add(99)), response.actions[0])

        handler.close()
    }

    @Test
    fun `non-ClientSharedAction reaches reducer and updates state`() = runTest {
        val connection = MockRawServerConnection()
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
        val connection = MockRawServerConnection()
        val handler = ServerSessionHandler<ServerState, ServerAction>(
            storeFactory = { conn ->
                val typedConn = conn.typed(actionCodec, messageCodec)
                val srm = TestServerRemoteMiddleware(typedConn)
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
        val connection = MockRawServerConnection()
        val handler = createHandler(backgroundScope, connection)
        handler.initialize()
        handler.dispatch(ServerAction.StartListening)
        delay(100)

        val actionJson = actionCodec.encode(ServerAction.Add(42))
        val clientMessage = messageCodec.encodeActionMessage(actionJson)
        connection.simulateClientMessage(clientMessage)

        waitUntil { connection.sentMessages.size == 1 }

        val response = messageCodec.decodeServerMessage(connection.sentMessages[0])
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

/**
 * A simple [ActionCodec] for [ServerAction] used in tests.
 * Kept here because it's only needed by ServerSessionHandlerTest for the roundtrip through
 * raw ServerConnection → typed → middleware.
 */
private class ServerActionCodec : io.flowdux.remote.ActionCodec<ServerAction> {
    override fun encode(action: ServerAction): String = when (action) {
        is ServerAction.StartListening -> """{"type":"StartListening"}"""
        is ServerAction.ClientAdd -> """{"type":"ClientAdd","value":${action.value}}"""
        is ServerAction.Add -> """{"type":"Add","value":${action.value}}"""
        is ServerAction.SetValue -> """{"type":"SetValue","value":${action.value}}"""
        is ServerAction.Increment -> """{"type":"Increment"}"""
        is ServerAction.InternalReset -> """{"type":"InternalReset","value":${action.value}}"""
    }

    override fun decode(json: String): ServerAction = when {
        json.contains("\"type\":\"StartListening\"") -> ServerAction.StartListening
        json.contains("\"type\":\"ClientAdd\"") -> {
            val value = Regex(""""value":(\d+)""").find(json)!!.groupValues[1].toInt()
            ServerAction.ClientAdd(value)
        }
        json.contains("\"type\":\"Add\"") -> {
            val value = Regex(""""value":(\d+)""").find(json)!!.groupValues[1].toInt()
            ServerAction.Add(value)
        }
        json.contains("\"type\":\"SetValue\"") -> {
            val value = Regex(""""value":(\d+)""").find(json)!!.groupValues[1].toInt()
            ServerAction.SetValue(value)
        }
        json.contains("\"type\":\"Increment\"") -> ServerAction.Increment
        json.contains("\"type\":\"InternalReset\"") -> {
            val value = Regex(""""value":(\d+)""").find(json)!!.groupValues[1].toInt()
            ServerAction.InternalReset(value)
        }
        else -> throw IllegalArgumentException("Unknown action JSON: $json")
    }
}
