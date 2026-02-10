package io.flowdux.remote.server

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerDeliveryMiddlewareTest {

    @Test
    fun `ClientSharedAction emitted from processor is automatically sent to client`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()
        val middleware = EmitClientActionTestMiddleware(connection)

        val store = createServerStore(
            initialState = ServerState(),
            syncMiddleware = middleware,
            reducer = serverReducer,
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        // Start listening
        store.dispatchStartListening()
        delay(100)

        // TriggerEmitClientAction should:
        // 1. emit(Add(5)) -> auto re-dispatched -> sent to client
        // 2. emit(InternalReset(1)) -> local state update
        store.dispatch(ServerAction.TriggerEmitClientAction(5))
        delay(100)

        // Local state should be updated by InternalReset(1)
        assertEquals(1, store.state.value.count)

        // Add(5) should have been sent to client via auto re-dispatch
        assertEquals(1, connection.sentActions.size)
        assertTrue(connection.sentActions[0] is ServerAction.Add)
        assertEquals(5, (connection.sentActions[0] as ServerAction.Add).value)

        store.close()
    }

    @Test
    fun `non-ClientSharedAction emitted from processor passes through normally`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()
        val middleware = EmitClientActionTestMiddleware(connection)

        val store = createServerStore(
            initialState = ServerState(),
            syncMiddleware = middleware,
            reducer = serverReducer,
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        // Start listening
        store.dispatchStartListening()
        delay(100)

        // Dispatch a non-ClientSharedAction directly
        store.dispatch(ServerAction.InternalReset(10))
        delay(100)

        // State updated normally
        assertEquals(10, store.state.value.count)

        // Nothing sent to client
        assertEquals(0, connection.sentActions.size)

        store.close()
    }

    @Test
    fun `ServerSharedAction from client is NOT re-dispatched (prevents infinite loop)`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()
        val middleware = EmitClientActionTestMiddleware(connection)

        val store = createServerStore(
            initialState = ServerState(),
            syncMiddleware = middleware,
            reducer = serverReducer,
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        // Start listening
        store.dispatchStartListening()
        delay(100)

        // Simulate client sending a ClientAdd action (ServerSharedAction from client's perspective)
        // This should be processed locally, NOT re-dispatched
        connection.simulateClientAction(ServerAction.ClientAdd(10))
        delay(100)

        // State updated from client action
        assertEquals(10, store.state.value.count)

        // Nothing sent back to client (no infinite loop)
        assertEquals(0, connection.sentActions.size)

        store.close()
    }

    @Test
    fun `multiple ClientSharedActions emitted are all sent to client`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()

        // Custom middleware that emits multiple ClientSharedActions
        val middleware = object : io.flowdux.remote.server.middleware.SingleClientSyncMiddleware<ServerState, ServerAction>(
            connection = connection,
        ) {
            override val processors = buildProcessors {
                on<ServerAction.TriggerEmitClientAction> { _, action ->
                    emit(ServerAction.Add(action.value))
                    emit(ServerAction.SetValue(100))
                    emit(ServerAction.InternalReset(1)) // local
                }
            }
        }

        val store = createServerStore(
            initialState = ServerState(),
            syncMiddleware = middleware,
            reducer = serverReducer,
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        // Start listening
        store.dispatchStartListening()
        delay(100)

        store.dispatch(ServerAction.TriggerEmitClientAction(5))
        delay(100)

        // Local state updated by InternalReset(1)
        assertEquals(1, store.state.value.count)

        // Both ClientSharedActions sent to client
        assertEquals(2, connection.sentActions.size)
        assertTrue(connection.sentActions[0] is ServerAction.Add)
        assertTrue(connection.sentActions[1] is ServerAction.SetValue)

        store.close()
    }
}
