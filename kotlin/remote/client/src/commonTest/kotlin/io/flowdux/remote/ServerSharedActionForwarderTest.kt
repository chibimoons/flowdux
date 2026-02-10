package io.flowdux.remote

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSharedActionForwarderTest {

    @Test
    fun serverSharedAction_emitted_from_processor_is_automatically_sent_to_server() = runTest {
        val connection = MockTypedClientConnection<TestAction>()
        val middleware = EmitServerActionTestMiddleware(
            connection = connection,
            scope = backgroundScope,
        )

        val store = createClientStore(
            initialState = TestState(),
            syncMiddleware = middleware,
            reducer = testReducer,
            errorProcessor = testErrorProcessor,
            scope = backgroundScope,
        )

        store.state.test {
            assertEquals(TestState(), awaitItem()) // initial state

            store.dispatch(TestAction.Connect)
            delay(100)

            // TriggerEmitServerAction should:
            // 1. emit(ServerAdd(5)) -> auto re-dispatched -> sent to server
            // 2. emit(Add(1)) -> local state update
            store.dispatch(TestAction.TriggerEmitServerAction(5))
            delay(100)

            // Local state should be updated by Add(1)
            assertEquals(TestState(count = 1), awaitItem())

            // ServerAdd(5) should have been sent to server via auto re-dispatch
            assertEquals(1, connection.sentActions.size)
            assertTrue(connection.sentActions[0] is TestAction.ServerAdd)
            assertEquals(5, (connection.sentActions[0] as TestAction.ServerAdd).value)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun non_serverSharedAction_emitted_from_processor_passes_through_normally() = runTest {
        val connection = MockTypedClientConnection<TestAction>()
        val middleware = EmitServerActionTestMiddleware(
            connection = connection,
            scope = backgroundScope,
        )

        val store = createClientStore(
            initialState = TestState(),
            syncMiddleware = middleware,
            reducer = testReducer,
            errorProcessor = testErrorProcessor,
            scope = backgroundScope,
        )

        store.state.test {
            assertEquals(TestState(), awaitItem()) // initial state

            store.dispatch(TestAction.Connect)
            delay(100)

            // Dispatch a non-ServerSharedAction directly
            store.dispatch(TestAction.Add(10))
            assertEquals(TestState(count = 10), awaitItem())

            // Nothing sent to server
            assertEquals(0, connection.sentActions.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun clientSharedAction_from_server_is_not_re_dispatched_prevents_infinite_loop() = runTest {
        val connection = MockTypedClientConnection<TestAction>()
        val middleware = TestSyncMiddleware(
            connection = connection,
            scope = backgroundScope,
        )

        val store = createClientStore(
            initialState = TestState(),
            syncMiddleware = middleware,
            reducer = testReducer,
            errorProcessor = testErrorProcessor,
            scope = backgroundScope,
        )

        store.state.test {
            assertEquals(TestState(), awaitItem()) // initial state

            store.dispatch(TestAction.Connect)
            delay(100)

            // Simulate server sending an Add action (ClientSharedAction from server's perspective)
            // This should be processed locally, NOT re-dispatched
            connection.simulateServerAction(TestAction.Add(42))

            assertEquals(TestState(count = 42), awaitItem())

            // Nothing sent back to server (no infinite loop)
            assertEquals(0, connection.sentActions.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun multiple_serverSharedActions_emitted_are_all_sent_to_server() = runTest {
        val connection = MockTypedClientConnection<TestAction>()

        // Custom middleware that emits multiple ServerSharedActions
        val middleware = object : SyncMiddleware<TestState, TestAction>(
            connection = connection,
            scope = backgroundScope,
        ) {
            override val processors = buildProcessors {
                on<TestAction.Connect> { _, _ -> startConnection() }
                on<TestAction.TriggerEmitServerAction> { _, action ->
                    emit(TestAction.ServerAdd(action.value))
                    emit(TestAction.ServerSetMessage("test"))
                    emit(TestAction.Add(1)) // local
                }
            }
        }

        val store = createClientStore(
            initialState = TestState(),
            syncMiddleware = middleware,
            reducer = testReducer,
            errorProcessor = testErrorProcessor,
            scope = backgroundScope,
        )

        store.state.test {
            assertEquals(TestState(), awaitItem())

            store.dispatch(TestAction.Connect)
            delay(100)

            store.dispatch(TestAction.TriggerEmitServerAction(5))
            delay(100)

            // Local state updated
            assertEquals(TestState(count = 1), awaitItem())

            // Both ServerSharedActions sent to server
            assertEquals(2, connection.sentActions.size)
            assertTrue(connection.sentActions[0] is TestAction.ServerAdd)
            assertTrue(connection.sentActions[1] is TestAction.ServerSetMessage)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
