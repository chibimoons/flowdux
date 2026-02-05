package io.flowdux.remote

import app.cash.turbine.test
import io.flowdux.createStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `internal scope is cancelled on disconnect when no scope provided`() = runTest {
        val cancellationSignal = CompletableDeferred<Boolean>()
        val connection = CancellationTrackingMockConnection<TestAction>(cancellationSignal)

        // Do NOT provide a scope - internal scope should be created and cancelled
        val middleware = TestClientRemoteMiddleware(
            connection = connection,
            scope = null,
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

            // Connect - starts the internal scope's coroutine
            store.dispatch(TestAction.Connect)
            delay(100)

            // Verify connection is active (coroutine is running)
            assertFalse(cancellationSignal.isCompleted)

            // Disconnect - should cancel the internal scope
            store.dispatch(TestAction.Disconnect)

            // Wait for cancellation signal
            val wasCancelled = withTimeoutOrNull(1000) {
                cancellationSignal.await()
            }

            assertNotNull(wasCancelled, "Internal scope should be cancelled on disconnect")
            assertTrue(wasCancelled, "Internal scope coroutine should have been cancelled")

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `external scope is NOT cancelled on disconnect when scope provided`() = runTest {
        val cancellationSignal = CompletableDeferred<Boolean>()
        val connection = CancellationTrackingMockConnection<TestAction>(cancellationSignal)

        // Provide an external scope - should NOT be cancelled on disconnect
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

            // Connect - starts a coroutine in the provided scope
            store.dispatch(TestAction.Connect)
            delay(100)

            // Verify connection is active
            assertFalse(cancellationSignal.isCompleted)

            // Disconnect - should NOT cancel the external scope
            store.dispatch(TestAction.Disconnect)

            // Give it time to potentially cancel (but it shouldn't)
            val wasCancelled = withTimeoutOrNull(500) {
                cancellationSignal.await()
            }

            // The external scope should NOT be cancelled by disconnect
            assertNull(wasCancelled, "External scope should NOT be cancelled on disconnect")

            cancelAndIgnoreRemainingEvents()
        }
    }

}
