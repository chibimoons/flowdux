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
class ClientRemoteMiddlewareTest {

    @Test
    fun `ServerSharedAction is intercepted and sent to server`() = runTest {
        val connection = MockTypedClientConnection<TestAction>()
        val middleware = TestClientRemoteMiddleware(
            connection = connection,
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

            // No state change because ServerSharedAction is NOT emitted locally
            expectNoEvents()

            // Verify it was sent to the server as a typed action
            assertEquals(1, connection.sentActions.size)
            assertTrue(connection.sentActions[0] is TestAction.ServerAdd)
            assertEquals(5, (connection.sentActions[0] as TestAction.ServerAdd).value)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `non-ServerSharedAction passes through to local reducer`() = runTest {
        val connection = MockTypedClientConnection<TestAction>()
        val middleware = TestClientRemoteMiddleware(
            connection = connection,
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
            assertEquals(0, connection.sentActions.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `server response actions are dispatched to local store`() = runTest {
        val connection = MockTypedClientConnection<TestAction>()
        val middleware = TestClientRemoteMiddleware(
            connection = connection,
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

            // Simulate server sending a typed Add action
            connection.simulateServerAction(TestAction.Add(42))

            assertEquals(TestState(count = 42), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `non-ServerSharedAction server responses pass through without being sent to server`() = runTest {
        val connection = MockTypedClientConnection<TestAction>()
        val middleware = TestClientRemoteMiddleware(
            connection = connection,
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

            // Simulate server sending a non-ServerSharedAction response
            connection.simulateServerAction(TestAction.Add(10))

            assertEquals(TestState(count = 10), awaitItem())

            // Non-ServerSharedAction server responses should NOT be sent to server
            assertEquals(0, connection.sentActions.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `multiple server response actions are all dispatched`() = runTest {
        val connection = MockTypedClientConnection<TestAction>()
        val middleware = TestClientRemoteMiddleware(
            connection = connection,
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

            // Simulate server sending multiple actions sequentially
            connection.simulateServerAction(TestAction.Add(10))
            connection.simulateServerAction(TestAction.Add(20))
            connection.simulateServerAction(TestAction.SetMessage("done"))

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
    fun `onConnectionError callback dispatches error action when connection fails`() = runTest {
        val connection = MockTypedClientConnection<TestAction>(
            connectException = RuntimeException("Connection failed"),
        )
        val middleware = TestClientRemoteMiddleware(
            connection = connection,
            scope = backgroundScope,
            onConnectionError = { e -> TestAction.ConnectionError(e.message ?: "Unknown error") },
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

            // The error action should be dispatched through the store
            val errorState = awaitItem()
            assertEquals("Connection failed", errorState.message)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
