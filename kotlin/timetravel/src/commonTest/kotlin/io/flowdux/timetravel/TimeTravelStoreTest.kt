package io.flowdux.timetravel

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TimeTravelStoreTest {
    @Test
    fun `initial state is recorded in history`() = runTest {
        val store =
            createTimeTravelStore(
                initialState = CounterState(count = 5),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

        assertEquals(1, store.history.size)
        assertEquals(0, store.currentIndex)
        assertEquals(5, store.history[0].currentState.count)
        assertNull(store.history[0].action)
        assertNull(store.history[0].previousState)
    }

    @Test
    fun `dispatch records state changes in history`() = runTest {
        val store =
            createTimeTravelStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

        store.state.test {
            assertEquals(0, awaitItem().count)

            store.dispatch(CounterAction.Increment)
            assertEquals(1, awaitItem().count)

            store.dispatch(CounterAction.Add(10))
            assertEquals(11, awaitItem().count)

            assertEquals(3, store.history.size)
            assertEquals(2, store.currentIndex)

            assertEquals(0, store.history[0].currentState.count)
            assertEquals(1, store.history[1].currentState.count)
            assertEquals(11, store.history[2].currentState.count)

            assertTrue(store.history[1].action is CounterAction.Increment)
            assertTrue(store.history[2].action is CounterAction.Add)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `undo moves to previous state`() = runTest {
        val store =
            createTimeTravelStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

        store.state.test {
            assertEquals(0, awaitItem().count)

            store.dispatch(CounterAction.Increment)
            assertEquals(1, awaitItem().count)

            store.dispatch(CounterAction.Increment)
            assertEquals(2, awaitItem().count)

            assertTrue(store.canUndo)
            assertTrue(store.undo())
            assertEquals(1, awaitItem().count)
            assertEquals(1, store.currentIndex)

            assertTrue(store.undo())
            assertEquals(0, awaitItem().count)
            assertEquals(0, store.currentIndex)

            assertFalse(store.canUndo)
            assertFalse(store.undo())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `redo moves to next state`() = runTest {
        val store =
            createTimeTravelStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

        store.state.test {
            assertEquals(0, awaitItem().count)

            store.dispatch(CounterAction.Increment)
            assertEquals(1, awaitItem().count)

            store.dispatch(CounterAction.Increment)
            assertEquals(2, awaitItem().count)

            store.undo()
            assertEquals(1, awaitItem().count)

            store.undo()
            assertEquals(0, awaitItem().count)

            assertTrue(store.canRedo)
            assertTrue(store.redo())
            assertEquals(1, awaitItem().count)
            assertEquals(1, store.currentIndex)

            assertTrue(store.redo())
            assertEquals(2, awaitItem().count)
            assertEquals(2, store.currentIndex)

            assertFalse(store.canRedo)
            assertFalse(store.redo())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `jumpTo moves to specific state`() = runTest {
        val store =
            createTimeTravelStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

        store.state.test {
            assertEquals(0, awaitItem().count)

            store.dispatch(CounterAction.Add(10))
            assertEquals(10, awaitItem().count)

            store.dispatch(CounterAction.Add(20))
            assertEquals(30, awaitItem().count)

            store.dispatch(CounterAction.Add(30))
            assertEquals(60, awaitItem().count)

            assertTrue(store.jumpTo(1))
            assertEquals(10, awaitItem().count)
            assertEquals(1, store.currentIndex)

            assertTrue(store.jumpTo(3))
            assertEquals(60, awaitItem().count)
            assertEquals(3, store.currentIndex)

            assertTrue(store.jumpTo(0))
            assertEquals(0, awaitItem().count)
            assertEquals(0, store.currentIndex)

            assertFalse(store.jumpTo(-1))
            assertFalse(store.jumpTo(100))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dispatch from past state truncates future history`() = runTest {
        val store =
            createTimeTravelStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

        store.state.test {
            assertEquals(0, awaitItem().count)

            store.dispatch(CounterAction.Add(10))
            assertEquals(10, awaitItem().count)

            store.dispatch(CounterAction.Add(20))
            assertEquals(30, awaitItem().count)

            store.dispatch(CounterAction.Add(30))
            assertEquals(60, awaitItem().count)

            assertEquals(4, store.history.size)

            store.jumpTo(1)
            assertEquals(10, awaitItem().count)

            store.dispatch(CounterAction.Add(5))
            assertEquals(15, awaitItem().count)

            assertEquals(3, store.history.size)
            assertEquals(2, store.currentIndex)
            assertEquals(0, store.history[0].currentState.count)
            assertEquals(10, store.history[1].currentState.count)
            assertEquals(15, store.history[2].currentState.count)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reset moves to initial state`() = runTest {
        val store =
            createTimeTravelStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

        store.state.test {
            assertEquals(0, awaitItem().count)

            store.dispatch(CounterAction.Add(10))
            assertEquals(10, awaitItem().count)

            store.dispatch(CounterAction.Add(20))
            assertEquals(30, awaitItem().count)

            assertTrue(store.reset())
            assertEquals(0, awaitItem().count)
            assertEquals(0, store.currentIndex)
            assertEquals(3, store.history.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clear resets history with current state`() = runTest {
        val store =
            createTimeTravelStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

        store.state.test {
            assertEquals(0, awaitItem().count)

            store.dispatch(CounterAction.Add(10))
            assertEquals(10, awaitItem().count)

            store.dispatch(CounterAction.Add(20))
            assertEquals(30, awaitItem().count)

            store.clear()

            assertEquals(1, store.history.size)
            assertEquals(0, store.currentIndex)
            assertEquals(30, store.history[0].currentState.count)
            assertEquals(30, store.currentState.count)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `maxHistorySize limits history`() = runTest {
        val store =
            createTimeTravelStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                maxHistorySize = 3,
                scope = backgroundScope,
            )

        store.state.test {
            assertEquals(0, awaitItem().count)

            store.dispatch(CounterAction.Add(1))
            assertEquals(1, awaitItem().count)

            store.dispatch(CounterAction.Add(2))
            assertEquals(3, awaitItem().count)

            assertEquals(3, store.history.size)

            store.dispatch(CounterAction.Add(3))
            assertEquals(6, awaitItem().count)

            assertEquals(3, store.history.size)
            assertEquals(2, store.currentIndex)
            assertEquals(1, store.history[0].currentState.count)
            assertEquals(3, store.history[1].currentState.count)
            assertEquals(6, store.history[2].currentState.count)

            store.dispatch(CounterAction.Add(4))
            assertEquals(10, awaitItem().count)

            assertEquals(3, store.history.size)
            assertEquals(2, store.currentIndex)
            assertEquals(3, store.history[0].currentState.count)
            assertEquals(6, store.history[1].currentState.count)
            assertEquals(10, store.history[2].currentState.count)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `close prevents further dispatch`() = runTest {
        val store =
            createTimeTravelStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

        assertFalse(store.isClosed)

        store.close()

        assertTrue(store.isClosed)
    }

    @Test
    fun `timestamps are recorded`() = runTest {
        val store =
            createTimeTravelStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

        store.state.test {
            assertEquals(0, awaitItem().count)

            val beforeDispatch =
                kotlinx.datetime.Clock.System
                    .now()
                    .toEpochMilliseconds()
            store.dispatch(CounterAction.Increment)
            assertEquals(1, awaitItem().count)
            val afterDispatch =
                kotlinx.datetime.Clock.System
                    .now()
                    .toEpochMilliseconds()

            val snapshot = store.history[1]
            assertTrue(snapshot.timestamp >= beforeDispatch)
            assertTrue(snapshot.timestamp <= afterDispatch)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `history indices are correct after truncation`() = runTest {
        val store =
            createTimeTravelStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                maxHistorySize = 5,
                scope = backgroundScope,
            )

        store.state.test {
            awaitItem()

            repeat(10) { i ->
                store.dispatch(CounterAction.Add(i + 1))
                awaitItem()
            }

            store.history.forEachIndexed { idx, snapshot ->
                assertEquals(idx, snapshot.index)
            }

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `initialHistory restores history and state`() = runTest {
        val savedHistory: List<StateSnapshot<CounterState, CounterAction>> =
            listOf(
                StateSnapshot(
                    index = 0,
                    action = null,
                    previousState = null,
                    currentState = CounterState(0),
                    timestamp = 1000L,
                ),
                StateSnapshot(
                    index = 1,
                    action = CounterAction.Add(10),
                    previousState = CounterState(0),
                    currentState = CounterState(10),
                    timestamp = 2000L,
                ),
                StateSnapshot(
                    index = 2,
                    action = CounterAction.Add(20),
                    previousState = CounterState(10),
                    currentState = CounterState(30),
                    timestamp = 3000L,
                ),
            )

        val store =
            createTimeTravelStore(
                initialHistory = savedHistory,
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

        assertEquals(3, store.history.size)
        assertEquals(2, store.currentIndex)
        assertEquals(30, store.currentState.count)

        assertTrue(store.canUndo)
        assertFalse(store.canRedo)

        store.undo()
        assertEquals(10, store.currentState.count)

        store.undo()
        assertEquals(0, store.currentState.count)
    }

    @Test
    fun `initialHistory allows dispatch to continue from restored state`() = runTest {
        val savedHistory: List<StateSnapshot<CounterState, CounterAction>> =
            listOf(
                StateSnapshot(
                    index = 0,
                    action = null,
                    previousState = null,
                    currentState = CounterState(100),
                    timestamp = 1000L,
                ),
            )

        val store =
            createTimeTravelStore(
                initialHistory = savedHistory,
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

        store.state.test {
            assertEquals(100, awaitItem().count)

            store.dispatch(CounterAction.Add(50))
            assertEquals(150, awaitItem().count)

            assertEquals(2, store.history.size)
            assertEquals(100, store.history[0].currentState.count)
            assertEquals(150, store.history[1].currentState.count)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `empty initialHistory throws exception`() = runTest {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                createTimeTravelStore(
                    initialHistory = emptyList<StateSnapshot<CounterState, CounterAction>>(),
                    reducer = counterReducer,
                    errorProcessor = testErrorProcessor,
                    scope = backgroundScope,
                )
            }
        assertEquals("initialHistory must not be empty", exception.message)
    }
}
