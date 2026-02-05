package io.flowdux.remote.server

import io.flowdux.createStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResponseCollectorTest {

    @Test
    fun `awaitNextReduction returns after single reduction`() = runTest {
        val collector = ResponseCollector<ServerState, ServerAction>()
        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer,
            errorProcessor = serverErrorProcessor,
            logger = collector,
            scope = backgroundScope,
        )

        store.dispatch(ServerAction.Increment)

        withTimeout(1000) {
            collector.awaitNextReduction()
        }

        val pending = collector.drainPending()
        assertEquals(1, pending.size)
        assertEquals(ServerAction.Increment, pending.first())
    }

    @Test
    fun `drainPending returns empty list when no actions pending`() = runTest {
        val collector = ResponseCollector<ServerState, ServerAction>()

        val pending = collector.drainPending()
        assertTrue(pending.isEmpty())
    }

    @Test
    fun `drainPending accumulates multiple actions`() = runTest {
        val collector = ResponseCollector<ServerState, ServerAction>()
        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer,
            errorProcessor = serverErrorProcessor,
            logger = collector,
            scope = backgroundScope,
        )

        store.dispatch(ServerAction.Increment)
        store.dispatch(ServerAction.Add(5))
        store.dispatch(ServerAction.SetValue(100))

        // Wait for all reductions
        withTimeout(1000) {
            collector.awaitNextReduction()
        }
        // Small delay to allow remaining dispatches to process
        delay(50)

        val pending = collector.drainPending()
        assertEquals(3, pending.size)
        assertEquals(ServerAction.Increment, pending[0])
        assertEquals(ServerAction.Add(5), pending[1])
        assertEquals(ServerAction.SetValue(100), pending[2])
    }

    @Test
    fun `sequential await and drain pattern works correctly`() = runTest {
        val collector = ResponseCollector<ServerState, ServerAction>()
        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer,
            errorProcessor = serverErrorProcessor,
            logger = collector,
            scope = backgroundScope,
        )

        // First dispatch-await-drain cycle
        store.dispatch(ServerAction.Increment)
        withTimeout(1000) { collector.awaitNextReduction() }
        val first = collector.drainPending()
        assertEquals(1, first.size)

        // Second dispatch-await-drain cycle
        store.dispatch(ServerAction.Add(10))
        withTimeout(1000) { collector.awaitNextReduction() }
        val second = collector.drainPending()
        assertEquals(1, second.size)
        assertEquals(ServerAction.Add(10), second.first())
    }

    /**
     * Concurrent test that would fail with CompletableDeferred-based implementation.
     *
     * This test simulates rapid concurrent dispatches from multiple coroutines,
     * exercising the race condition scenario described in issue #77:
     * - With CompletableDeferred: signal could be lost if `onStateReduced()` completes
     *   an already-completed deferred while `awaitNextReduction()` is creating a new one
     * - With Channel(CONFLATED): signals are never lost, though they may be coalesced
     */
    @Test
    fun `concurrent dispatches do not cause signal loss`() = runTest {
        val collector = ResponseCollector<ServerState, ServerAction>()
        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer,
            errorProcessor = serverErrorProcessor,
            logger = collector,
            scope = backgroundScope,
        )

        val dispatchCount = 100
        val awaitCount = 50

        // Launch concurrent dispatchers
        val dispatchJobs = (1..dispatchCount).map { i ->
            launch(Dispatchers.Default) {
                store.dispatch(ServerAction.Add(i))
            }
        }

        // Launch concurrent awaiters - each should complete without deadlock
        val awaitJobs = (1..awaitCount).map {
            async(Dispatchers.Default) {
                withTimeout(5000) {
                    collector.awaitNextReduction()
                }
                true
            }
        }

        // All dispatches complete
        dispatchJobs.forEach { it.join() }

        // All awaits should complete (no deadlock from lost signals)
        val results = awaitJobs.awaitAll()
        assertTrue(results.all { it })

        // Drain should capture many (possibly not all due to coalescing) actions
        delay(100) // Allow final reductions to settle
        val pending = collector.drainPending()
        assertTrue(pending.isNotEmpty(), "Should have collected some actions")
    }

    /**
     * Stress test: rapid alternating dispatch-await cycles.
     *
     * With the old CompletableDeferred implementation, this pattern could deadlock
     * if the deferred swap happens between `await()` returning and the new deferred creation.
     */
    @Test
    fun `rapid dispatch-await cycles do not deadlock`() = runTest {
        val collector = ResponseCollector<ServerState, ServerAction>()
        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer,
            errorProcessor = serverErrorProcessor,
            logger = collector,
            scope = backgroundScope,
        )

        repeat(50) { i ->
            store.dispatch(ServerAction.Add(i))
            withTimeout(1000) {
                collector.awaitNextReduction()
            }
            collector.drainPending()
        }

        // If we reach here without timeout, the test passes
        assertEquals(1225, store.currentState.count) // sum of 0..49 is 1225
    }

    /**
     * Test that verifies awaitNextReduction is truly suspending when no reduction has occurred.
     */
    @Test
    fun `awaitNextReduction suspends until reduction occurs`() = runTest {
        val collector = ResponseCollector<ServerState, ServerAction>()
        val store = createStore(
            initialState = ServerState(),
            reducer = serverReducer,
            errorProcessor = serverErrorProcessor,
            logger = collector,
            scope = backgroundScope,
        )

        var awaited = false

        val awaitJob = launch {
            collector.awaitNextReduction()
            awaited = true
        }

        // Verify still waiting
        delay(50)
        assertEquals(false, awaited)

        // Now dispatch
        store.dispatch(ServerAction.Increment)

        // Await should complete
        withTimeout(1000) {
            awaitJob.join()
        }
        assertEquals(true, awaited)
    }
}
