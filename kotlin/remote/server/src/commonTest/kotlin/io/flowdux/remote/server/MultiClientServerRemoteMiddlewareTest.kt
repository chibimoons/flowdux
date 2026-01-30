package io.flowdux.remote.server

import io.flowdux.createStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MultiClientServerRemoteMiddlewareTest {

    @Test
    fun `addSession registers session and sessionCount increases`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()
        val middleware = MultiClientServerRemoteMiddleware<ServerState, ServerAction>()
        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer,
            middlewares = listOf(middleware),
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        store.dispatchAddSession("client-1", connection)
        delay(100)

        assertEquals(1, middleware.sessionCount())
        assertTrue(middleware.sessionIds().contains("client-1"))
        store.close()
    }

    @Test
    fun `removeSession unregisters session`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()
        val middleware = MultiClientServerRemoteMiddleware<ServerState, ServerAction>()
        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer,
            middlewares = listOf(middleware),
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        store.dispatchAddSession("client-1", connection)
        delay(100)
        assertEquals(1, middleware.sessionCount())

        store.dispatchRemoveSession("client-1")
        delay(100)

        assertEquals(0, middleware.sessionCount())
        store.close()
    }

    @Test
    fun `multiple sessions can be registered`() = runTest {
        val middleware = MultiClientServerRemoteMiddleware<ServerState, ServerAction>()
        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer,
            middlewares = listOf(middleware),
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        for (i in 1..3) {
            val conn = MockTypedServerConnection<ServerAction>()
            store.dispatchAddSession("client-$i", conn)
        }
        delay(100)

        assertEquals(3, middleware.sessionCount())
        assertEquals(setOf("client-1", "client-2", "client-3"), middleware.sessionIds())
        store.close()
    }

    @Test
    fun `ClientSharedAction is broadcast to all sessions and not emitted locally`() = runTest {
        val conn1 = MockTypedServerConnection<ServerAction>()
        val conn2 = MockTypedServerConnection<ServerAction>()
        val middleware = MultiClientServerRemoteMiddleware<ServerState, ServerAction>()
        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer,
            middlewares = listOf(middleware),
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        store.dispatchAddSession("client-1", conn1)
        store.dispatchAddSession("client-2", conn2)
        delay(100)

        // Dispatch a ClientSharedAction
        store.dispatch(ServerAction.SyncState(ServerState(42)))
        delay(100)

        // Sent to both clients
        val sync1 = conn1.sentActions.filterIsInstance<ServerAction.SyncState>()
        val sync2 = conn2.sentActions.filterIsInstance<ServerAction.SyncState>()
        assertTrue(sync1.any { it.state.count == 42 })
        assertTrue(sync2.any { it.state.count == 42 })

        // State unchanged — ClientSharedAction was NOT emitted to reducer
        assertEquals(0, store.state.value.count)

        store.close()
    }

    @Test
    fun `broadcast error isolation - one failure does not affect others`() = runTest {
        val goodConn = MockTypedServerConnection<ServerAction>()
        val failingConn = object : TypedServerConnection<ServerAction> {
            override val incoming = goodConn.incoming // unused
            override suspend fun send(action: ServerAction) {
                throw RuntimeException("Connection failed")
            }
        }
        val middleware = MultiClientServerRemoteMiddleware<ServerState, ServerAction>()
        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer,
            middlewares = listOf(middleware),
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        store.dispatchAddSession("failing", failingConn)
        store.dispatchAddSession("good", goodConn)
        delay(100)

        // Broadcast — should not crash
        store.dispatch(ServerAction.SyncState(ServerState(99)))
        delay(100)

        // Good connection still received the action
        val syncs = goodConn.sentActions.filterIsInstance<ServerAction.SyncState>()
        assertTrue(syncs.any { it.state.count == 99 })

        store.close()
    }

    @Test
    fun `InternalAddSession is consumed and does not reach reducer`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()
        val middleware = MultiClientServerRemoteMiddleware<ServerState, ServerAction>()
        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer, // sealed when — would crash if InternalAddSession leaked
            middlewares = listOf(middleware),
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        store.dispatchAddSession("client-1", connection)
        delay(100)

        // State unchanged — action was consumed
        assertEquals(0, store.state.value.count)
        store.close()
    }

    @Test
    fun `InternalRemoveSession is consumed and does not reach reducer`() = runTest {
        val middleware = MultiClientServerRemoteMiddleware<ServerState, ServerAction>()
        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer, // sealed when — would crash if InternalRemoveSession leaked
            middlewares = listOf(middleware),
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        store.dispatchRemoveSession("nonexistent")
        delay(100)

        // State unchanged — action was consumed
        assertEquals(0, store.state.value.count)
        store.close()
    }

    @Test
    fun `non-SharedAction passes through unchanged`() = runTest {
        val middleware = MultiClientServerRemoteMiddleware<ServerState, ServerAction>()

        val action = ServerAction.InternalReset(42)
        val result = middleware.process(
            getState = { ServerState() },
            action = action,
        ).toList()

        assertEquals(listOf(action), result)
    }

    @Test
    fun `processor is invoked for registered action`() = runTest {
        val processors = io.flowdux.Middleware.ActionProcessorBuilder<ServerState, ServerAction>().apply {
            on<ServerAction.ClientAdd> { _, action ->
                emit(ServerAction.Add(action.value))
            }
        }.build()

        val middleware = MultiClientServerRemoteMiddleware<ServerState, ServerAction>(processors)

        val result = middleware.process(
            getState = { ServerState() },
            action = ServerAction.ClientAdd(10),
        ).toList()

        assertEquals(1, result.size)
        assertIs<ServerAction.Add>(result[0])
        assertEquals(10, (result[0] as ServerAction.Add).value)
    }

    @Test
    fun `incoming client message is dispatched through full pipeline`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()
        val middleware = MultiClientServerRemoteMiddleware<ServerState, ServerAction>()
        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer,
            middlewares = listOf(middleware),
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        store.dispatchAddSession("client-1", connection)
        delay(100)

        // Simulate client sending an action
        connection.simulateClientAction(ServerAction.ClientAdd(10))
        delay(100)

        // Action went through full pipeline and reached reducer
        assertEquals(10, store.state.value.count)
        store.close()
    }

    @Test
    fun `sendToClient sends to specific session only`() = runTest {
        val conn1 = MockTypedServerConnection<ServerAction>()
        val conn2 = MockTypedServerConnection<ServerAction>()
        val middleware = MultiClientServerRemoteMiddleware<ServerState, ServerAction>()
        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer,
            middlewares = listOf(middleware),
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        store.dispatchAddSession("client-1", conn1)
        store.dispatchAddSession("client-2", conn2)
        delay(100)

        middleware.sendToClient("client-1", ServerAction.Add(5))

        assertEquals(1, conn1.sentActions.size)
        assertEquals(ServerAction.Add(5), conn1.sentActions[0])
        assertTrue(conn2.sentActions.isEmpty())
        store.close()
    }

    @Test
    fun `sendToClient is no-op for unknown session`() = runTest {
        val middleware = MultiClientServerRemoteMiddleware<ServerState, ServerAction>()

        // Should not throw
        middleware.sendToClient("nonexistent", ServerAction.Add(5))
    }
}
