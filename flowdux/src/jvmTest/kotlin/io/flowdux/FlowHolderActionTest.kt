package io.flowdux

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlowHolderActionTest {

    @Test
    fun `FlowHolderAction flattens inner flows and updates state`() =
        runTest {
            val valueChannel = Channel<Int>(Channel.UNLIMITED)

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.StreamConnected(valueChannel.receiveAsFlow()))

                valueChannel.send(5)
                assertEquals(5, awaitItem().count)

                valueChannel.send(3)
                assertEquals(8, awaitItem().count)

                valueChannel.close()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `FlowHolderAction with multiple flows merges all streams`() =
        runTest {
            val channel1 = Channel<Int>(Channel.UNLIMITED)
            val channel2 = Channel<Int>(Channel.UNLIMITED)

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(
                    CounterAction.MultiStreamConnected(
                        channel1.receiveAsFlow(),
                        channel2.receiveAsFlow()
                    )
                )

                channel1.send(10)
                assertEquals(10, awaitItem().count)

                channel2.send(5)
                assertEquals(15, awaitItem().count)

                channel1.send(3)
                assertEquals(18, awaitItem().count)

                channel1.close()
                channel2.close()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `FlowHolderAction completes when channel is closed`() =
        runTest {
            val valueChannel = Channel<Int>(Channel.UNLIMITED)

            val store = createStore(
                initialState = CounterState(),
                reducer = counterReducer,
                errorProcessor = testErrorProcessor,
                scope = backgroundScope,
            )

            store.state.test {
                assertEquals(0, awaitItem().count)

                store.dispatch(CounterAction.StreamConnected(valueChannel.receiveAsFlow()))

                valueChannel.send(5)
                assertEquals(5, awaitItem().count)

                valueChannel.send(10)
                assertEquals(15, awaitItem().count)

                valueChannel.close()

                cancelAndIgnoreRemainingEvents()
            }
        }
}
