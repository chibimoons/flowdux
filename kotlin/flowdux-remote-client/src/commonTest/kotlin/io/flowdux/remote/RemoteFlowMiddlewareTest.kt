package io.flowdux.remote

import app.cash.turbine.test
import io.flowdux.createStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteFlowMiddlewareTest {

    private val actionCodec = TestActionCodec()
    private val messageCodec = JsonMessageCodec()

    @Test
    fun `SharedAction is intercepted and sent to server`() = runTest {
        val connection = MockRemoteConnection()
        val middleware = TestRemoteFlowMiddleware(
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

        store.state.test {
            assertEquals(TestState(), awaitItem()) // initial state

            store.dispatch(TestAction.Connect)
            delay(100) // allow connection to establish and listener to start

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
        val middleware = TestRemoteFlowMiddleware(
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

        store.state.test {
            assertEquals(TestState(), awaitItem())

            store.dispatch(TestAction.Connect)
            delay(100)

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
        val middleware = TestRemoteFlowMiddleware(
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

        store.state.test {
            assertEquals(TestState(), awaitItem())

            store.dispatch(TestAction.Connect)
            delay(100)

            // Simulate server sending a response with an Add action (non-SharedAction)
            val serverResponse = messageCodec.encodeServerResponse(
                actions = listOf("""{"type":"Add","value":42}"""),
            )
            connection.simulateServerMessage(serverResponse)

            assertEquals(TestState(count = 42), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `non-SharedAction server responses pass through without being sent to server`() = runTest {
        val connection = MockRemoteConnection()
        val middleware = TestRemoteFlowMiddleware(
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

        store.state.test {
            assertEquals(TestState(), awaitItem())

            store.dispatch(TestAction.Connect)
            delay(100)

            // Simulate server sending a non-SharedAction response
            val serverResponse = messageCodec.encodeServerResponse(
                actions = listOf("""{"type":"Add","value":10}"""),
            )
            connection.simulateServerMessage(serverResponse)

            assertEquals(TestState(count = 10), awaitItem())

            // Non-SharedAction server responses should NOT be sent to server
            assertEquals(0, connection.sentMessages.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `multiple server response actions are all dispatched`() = runTest {
        val connection = MockRemoteConnection()
        val middleware = TestRemoteFlowMiddleware(
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

        store.state.test {
            assertEquals(TestState(), awaitItem())

            store.dispatch(TestAction.Connect)
            delay(100)

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

}
