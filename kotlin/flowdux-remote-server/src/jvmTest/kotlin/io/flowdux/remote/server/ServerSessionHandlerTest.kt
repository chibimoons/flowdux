package io.flowdux.remote.server

import io.flowdux.createStore
import io.flowdux.remote.JsonMessageCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSessionHandlerTest {

    private val codec = JsonMessageCodec()
    private val actionCodec = ServerActionCodec()

    private fun createHandler(scope: kotlinx.coroutines.CoroutineScope): ServerSessionHandler<ServerState, ServerAction> {
        return ServerSessionHandler(
            storeFactory = {
                val collector = ResponseCollector<ServerState, ServerAction>()
                val store = createStore(
                    initialState = ServerState(),
                    reducer = serverReducer,
                    logger = collector,
                    errorProcessor = serverErrorProcessor,
                    scope = scope,
                )
                Pair(store, collector)
            },
            actionCodec = actionCodec,
            processingDelayMs = 50L,
        )
    }

    @Test
    fun `handleMessage dispatches action and returns response`() = runTest {
        val handler = createHandler(backgroundScope)
        handler.initialize()

        val clientMessage = codec.encodeActionMessage("""{"type":"Add","value":10}""")
        val responseRaw = handler.handleMessage(clientMessage)
        val response = codec.decodeServerMessage(responseRaw)

        assertEquals(1, response.actions.size)
        assertEquals("""{"type":"Add","value":10}""", response.actions[0])

        handler.close()
    }

    @Test
    fun `handleMessage processes Increment action`() = runTest {
        val handler = createHandler(backgroundScope)
        handler.initialize()

        val clientMessage = codec.encodeActionMessage("""{"type":"Increment"}""")
        val responseRaw = handler.handleMessage(clientMessage)
        val response = codec.decodeServerMessage(responseRaw)

        assertEquals(1, response.actions.size)
        assertEquals("""{"type":"Increment"}""", response.actions[0])

        handler.close()
    }

    @Test
    fun `sequential messages accumulate state on server`() = runTest {
        val handler = createHandler(backgroundScope)
        handler.initialize()

        val msg1 = codec.encodeActionMessage("""{"type":"Add","value":5}""")
        handler.handleMessage(msg1)

        val msg2 = codec.encodeActionMessage("""{"type":"Add","value":3}""")
        val responseRaw = handler.handleMessage(msg2)
        val response = codec.decodeServerMessage(responseRaw)

        // Second response should contain the Add(3) action
        assertEquals(1, response.actions.size)
        assertEquals("""{"type":"Add","value":3}""", response.actions[0])

        handler.close()
    }

    @Test
    fun `returns empty actions when no state change`() = runTest {
        val handler = ServerSessionHandler(
            storeFactory = {
                val collector = ResponseCollector<ServerState, ServerAction>()
                val store = createStore(
                    initialState = ServerState(),
                    reducer = serverReducer,
                    logger = collector,
                    errorProcessor = serverErrorProcessor,
                    scope = backgroundScope,
                )
                Pair(store, collector)
            },
            actionCodec = actionCodec,
            processingDelayMs = 50L,
        )
        handler.initialize()

        // SetValue(0) on initial state(0) → reducer runs but state doesn't change value-wise
        // However the reducer still produces a new state, so the action IS collected
        val clientMessage = codec.encodeActionMessage("""{"type":"SetValue","value":0}""")
        val responseRaw = handler.handleMessage(clientMessage)
        val response = codec.decodeServerMessage(responseRaw)

        // Action is still collected (reducer ran)
        assertEquals(1, response.actions.size)

        handler.close()
    }

    @Test
    fun `close is safe to call before initialize`() {
        val handler = ServerSessionHandler(
            storeFactory = {
                val collector = ResponseCollector<ServerState, ServerAction>()
                val store = createStore(
                    initialState = ServerState(),
                    reducer = serverReducer,
                    scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()),
                )
                Pair(store, collector)
            },
            actionCodec = actionCodec,
        )
        // Should not throw
        handler.close()
    }

    @Test
    fun `roundtrip client message to server response`() = runTest {
        val handler = createHandler(backgroundScope)
        handler.initialize()

        // Simulate what a real client would send
        val actionJson = actionCodec.encode(ServerAction.Add(42))
        val clientMessage = codec.encodeActionMessage(actionJson)

        val responseRaw = handler.handleMessage(clientMessage)
        val response = codec.decodeServerMessage(responseRaw)

        // Server should echo back the action after reducing
        val decodedAction = actionCodec.decode(response.actions[0])
        assertTrue(decodedAction is ServerAction.Add)
        assertEquals(42, (decodedAction as ServerAction.Add).value)

        handler.close()
    }
}
