package io.flowdux.remote

import app.cash.turbine.test
import io.flowdux.createStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteFlowMiddlewareTest {

    private val actionCodec = TestActionCodec()
    private val messageCodec = JsonMessageCodec()

    @Test
    fun `SharedAction is intercepted and sent to server`() = runTest {
        val connection = MockRemoteConnection()
        val middleware = RemoteFlowMiddleware<TestState, TestAction>(
            connection = connection,
            actionCodec = actionCodec,
            scope = backgroundScope,
        )

        val store = createStore(
            initialState = TestState(),
            reducer = testReducer,
            middlewares = listOf(middleware),
            errorProcessor = testErrorProcessor,
            scope = backgroundScope,
        )

        middleware.connectTo(store)
        delay(50) // allow connection to establish

        store.state.test {
            assertEquals(TestState(), awaitItem()) // initial state

            store.dispatch(TestAction.ServerAdd(5))
            delay(100) // wait for send

            // No state change because SharedAction is NOT emitted locally
            expectNoEvents()

            // Verify it was sent to the server
            assertEquals(1, connection.sentMessages.size)
            assertTrue(connection.sentMessages[0].contains("ServerAdd"))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `non-SharedAction passes through to local reducer`() = runTest {
        val connection = MockRemoteConnection()
        val middleware = RemoteFlowMiddleware<TestState, TestAction>(
            connection = connection,
            actionCodec = actionCodec,
            scope = backgroundScope,
        )

        val store = createStore(
            initialState = TestState(),
            reducer = testReducer,
            middlewares = listOf(middleware),
            errorProcessor = testErrorProcessor,
            scope = backgroundScope,
        )

        middleware.connectTo(store)
        delay(50)

        store.state.test {
            assertEquals(TestState(), awaitItem())

            store.dispatch(TestAction.LocalIncrement)
            assertEquals(TestState(count = 1), awaitItem())

            store.dispatch(TestAction.Add(10))
            assertEquals(TestState(count = 11), awaitItem())

            // Nothing sent to server
            assertEquals(0, connection.sentMessages.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `server response actions are dispatched to local store`() = runTest {
        val connection = MockRemoteConnection()
        val middleware = RemoteFlowMiddleware<TestState, TestAction>(
            connection = connection,
            actionCodec = actionCodec,
            scope = backgroundScope,
        )

        val store = createStore(
            initialState = TestState(),
            reducer = testReducer,
            middlewares = listOf(middleware),
            errorProcessor = testErrorProcessor,
            scope = backgroundScope,
        )

        middleware.connectTo(store)
        delay(50)

        store.state.test {
            assertEquals(TestState(), awaitItem())

            // Simulate server sending a response with an Add action
            val serverResponse = messageCodec.encodeServerResponse(
                actions = listOf("""{"type":"Add","value":42}"""),
            )
            connection.simulateServerMessage(serverResponse)

            assertEquals(TestState(count = 42), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `server-originated actions are not re-sent to server`() = runTest {
        val connection = MockRemoteConnection()
        val middleware = RemoteFlowMiddleware<TestState, TestAction>(
            connection = connection,
            actionCodec = actionCodec,
            scope = backgroundScope,
        )

        val store = createStore(
            initialState = TestState(),
            reducer = testReducer,
            middlewares = listOf(middleware),
            errorProcessor = testErrorProcessor,
            scope = backgroundScope,
        )

        middleware.connectTo(store)
        delay(50)

        store.state.test {
            assertEquals(TestState(), awaitItem())

            // Simulate server sending a SharedAction type back
            val serverResponse = messageCodec.encodeServerResponse(
                actions = listOf("""{"type":"ServerAdd","value":10}"""),
            )
            connection.simulateServerMessage(serverResponse)

            assertEquals(TestState(count = 10), awaitItem())

            // The server-originated ServerAdd should NOT have been sent back to server
            assertEquals(0, connection.sentMessages.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `multiple server response actions are all dispatched`() = runTest {
        val connection = MockRemoteConnection()
        val middleware = RemoteFlowMiddleware<TestState, TestAction>(
            connection = connection,
            actionCodec = actionCodec,
            scope = backgroundScope,
        )

        val store = createStore(
            initialState = TestState(),
            reducer = testReducer,
            middlewares = listOf(middleware),
            errorProcessor = testErrorProcessor,
            scope = backgroundScope,
        )

        middleware.connectTo(store)
        delay(50)

        store.state.test {
            assertEquals(TestState(), awaitItem())

            val serverResponse = messageCodec.encodeServerResponse(
                actions = listOf(
                    """{"type":"Add","value":10}""",
                    """{"type":"Add","value":20}""",
                    """{"type":"SetMessage","message":"done"}""",
                ),
            )
            connection.simulateServerMessage(serverResponse)

            // All three actions should be dispatched; collect the final state
            var lastState = TestState()
            repeat(3) {
                lastState = awaitItem()
            }
            assertEquals(30, lastState.count)
            assertEquals("done", lastState.message)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `actions are buffered while disconnected`() = runTest {
        val connection = MockRemoteConnection(autoConnect = false)

        val middleware = RemoteFlowMiddleware<TestState, TestAction>(
            connection = connection,
            actionCodec = actionCodec,
            config = RemoteFlowConfig(bufferWhileDisconnected = true),
            scope = backgroundScope,
        )

        val store = createStore(
            initialState = TestState(),
            reducer = testReducer,
            middlewares = listOf(middleware),
            errorProcessor = testErrorProcessor,
            scope = backgroundScope,
        )

        middleware.connectTo(store)
        delay(50)

        store.state.test {
            assertEquals(TestState(), awaitItem())

            // Dispatch while disconnected - should buffer
            store.dispatch(TestAction.ServerAdd(5))
            delay(100)

            // No message sent since disconnected
            assertEquals(0, connection.sentMessages.size)

            // Simulate reconnection
            connection.setConnectionState(ConnectionState.CONNECTED)
            delay(100)

            // Buffer should have been flushed
            assertEquals(1, connection.sentMessages.size)
            assertTrue(connection.sentMessages[0].contains("ServerAdd"))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `buffer respects max size`() = runTest {
        val connection = MockRemoteConnection(autoConnect = false)

        val middleware = RemoteFlowMiddleware<TestState, TestAction>(
            connection = connection,
            actionCodec = actionCodec,
            config = RemoteFlowConfig(bufferWhileDisconnected = true, maxBufferSize = 2),
            scope = backgroundScope,
        )

        val store = createStore(
            initialState = TestState(),
            reducer = testReducer,
            middlewares = listOf(middleware),
            errorProcessor = testErrorProcessor,
            scope = backgroundScope,
        )

        middleware.connectTo(store)
        delay(50)

        store.state.test {
            assertEquals(TestState(), awaitItem())

            // Send 3 actions while disconnected, buffer max is 2
            store.dispatch(TestAction.ServerAdd(1))
            delay(50)
            store.dispatch(TestAction.ServerAdd(2))
            delay(50)
            store.dispatch(TestAction.ServerAdd(3))
            delay(50)

            // Reconnect
            connection.setConnectionState(ConnectionState.CONNECTED)
            delay(100)

            // Only 2 messages should be flushed (oldest dropped)
            assertEquals(2, connection.sentMessages.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

}
