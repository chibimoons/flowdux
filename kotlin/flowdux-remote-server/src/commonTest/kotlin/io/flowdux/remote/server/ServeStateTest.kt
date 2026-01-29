package io.flowdux.remote.server

import io.flowdux.createStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test reproducing the sample-app bug and its fix.
 *
 * Bug: the server processes client actions and updates its own state,
 * but without [serveState] the client never receives any state updates
 * because processor-emitted [ClientSharedAction]s bypass the middleware's
 * interception check.
 */
class ServeStateTest {

    /**
     * Reproduces the sample-app bug.
     *
     * Server lifecycle WITHOUT [serveState]:
     * 1. Server store + middleware with processor that emits ClientSharedAction
     * 2. Client sends multiple actions
     * 3. Server state updates correctly
     * 4. Client receives NOTHING — connection.sentActions is empty
     */
    @Test
    fun `without serveState - server updates state but client receives nothing`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()
        val middleware = ProcessorEmittingSRM(connection)
        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer,
            middlewares = listOf(middleware),
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        // Server starts listening
        store.dispatchStartListening()
        delay(100)

        // Client sends actions
        connection.simulateClientAction(ServerAction.ClientAdd(10))
        delay(100)
        connection.simulateClientAction(ServerAction.ClientAdd(20))
        delay(100)

        // Server state IS correctly updated
        assertEquals(30, store.state.value.count)

        // BUG: client received NOTHING — no state sync, no actions
        assertTrue(connection.sentActions.isEmpty())

        store.close()
    }

    /**
     * Verifies the fix: [serveState] observes state changes and dispatches
     * SyncState (a [ClientSharedAction]) which the middleware intercepts and
     * sends to the client.
     */
    @Test
    fun `with serveState - client receives state after each server change`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()
        val middleware = ProcessorEmittingSRM(connection)
        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer,
            middlewares = listOf(middleware),
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        // serveState runs alongside the store (like sample server: store.serveState { ... })
        val serveJob = backgroundScope.launch {
            store.serveState { ServerAction.SyncState(it) }
        }
        delay(100)

        store.dispatchStartListening()
        delay(100)

        // Client sends actions
        connection.simulateClientAction(ServerAction.ClientAdd(10))
        delay(100)
        connection.simulateClientAction(ServerAction.ClientAdd(20))
        delay(100)

        // Server state is correct
        assertEquals(30, store.state.value.count)

        // FIX: client received SyncState with the final state
        val syncStates = connection.sentActions.filterIsInstance<ServerAction.SyncState>()
        assertTrue(syncStates.isNotEmpty())
        assertEquals(30, syncStates.last().state.count)

        serveJob.cancel()
        store.close()
    }

    /**
     * Verifies that [serve] auto-dispatches [InternalStartListening]
     * and syncs state to the client, then closes the store.
     */
    @Test
    fun `serve - auto starts listening and syncs state`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()
        val middleware = ProcessorEmittingSRM(connection)
        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer,
            middlewares = listOf(middleware),
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val serveJob = backgroundScope.launch {
            store.serve { ServerAction.SyncState(it) }
        }
        delay(100)

        // Client sends actions
        connection.simulateClientAction(ServerAction.ClientAdd(10))
        delay(100)
        connection.simulateClientAction(ServerAction.ClientAdd(20))
        delay(100)

        // Server state is correct
        assertEquals(30, store.state.value.count)

        // Client received SyncState with the final state
        val syncStates = connection.sentActions.filterIsInstance<ServerAction.SyncState>()
        assertTrue(syncStates.isNotEmpty())
        assertEquals(30, syncStates.last().state.count)

        serveJob.cancel()
    }

    /**
     * Verifies that [serve] closes the store when cancelled.
     */
    @Test
    fun `serve - closes store after cancellation`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()
        val middleware = ServerRemoteMiddleware<ServerState, ServerAction>(connection)
        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer,
            middlewares = listOf(middleware),
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        val serveJob = backgroundScope.launch {
            store.serve { ServerAction.SyncState(it) }
        }
        delay(100)

        serveJob.cancel()
        delay(100)

        assertTrue(store.isClosed)
    }

    /**
     * Verifies that [use] closes the store after the block completes.
     */
    @Test
    fun `use - closes store after block`() = runTest {
        val connection = MockTypedServerConnection<ServerAction>()
        val middleware = ServerRemoteMiddleware<ServerState, ServerAction>(connection)
        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer,
            middlewares = listOf(middleware),
            errorProcessor = serverErrorProcessor,
            scope = backgroundScope,
        )

        store.use {
            delay(50)
        }

        // Store should be closed after use block
        assertTrue(store.isClosed)
    }
}
