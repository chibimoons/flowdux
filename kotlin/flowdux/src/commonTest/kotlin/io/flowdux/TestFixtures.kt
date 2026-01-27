package io.flowdux

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Common test fixtures shared across all test files.
 */

data class CounterState(val count: Int = 0) : State

sealed interface CounterAction : Action {
    object Increment : CounterAction
    object Decrement : CounterAction
    data class Add(val value: Int) : CounterAction
    data class SetValue(val value: Int) : CounterAction
    data class FetchData(val id: String) : CounterAction
    data class FetchDataSuccess(val id: String, val value: Int) : CounterAction
    data class FetchDataError(val id: String, val error: String) : CounterAction
    object Reset : CounterAction

    data class StreamConnected(
        private val valueFlow: Flow<Int>,
    ) : CounterAction, FlowHolderAction {
        override fun toFlowAction(): Flow<Action> = valueFlow.map { Add(it) }
    }

    data class MultiStreamConnected(
        private val flow1: Flow<Int>,
        private val flow2: Flow<Int>,
    ) : CounterAction, FlowHolderAction {
        override fun toFlowAction() = listOf(
            flow1.map { Add(it) },
            flow2.map { Add(it) }
        ).merge()
    }

    /**
     * Cancelable infinite stream action (default cancelable = true).
     * When a new instance is dispatched, the previous stream is cancelled.
     */
    data class InfiniteStreamAction(
        val id: String,
        val emitInterval: Long = 100L,
    ) : CounterAction, FlowHolderAction {
        override fun toFlowAction(): Flow<Action> = flow {
            while (true) {
                delay(emitInterval)
                emit(Add(1))
            }
        }
    }

    /**
     * Concurrent FlowHolderAction using Concurrent strategy.
     * Multiple streams can run concurrently.
     */
    data class NonCancelableStreamAction(
        val id: String,
        val values: List<Int>,
        val delayBetween: Long = 50L,
    ) : CounterAction, FlowHolderAction {
        override val strategy: ExecutionStrategy get() = concurrent()

        override fun toFlowAction(): Flow<Action> = flow {
            for (value in values) {
                delay(delayBetween)
                emit(Add(value))
            }
        }
    }

    /**
     * Another cancelable infinite stream action (default TakeLatest strategy).
     * This is a different type from InfiniteStreamAction, used to test that
     * different cancelable types don't cancel each other.
     */
    data class SecondaryStreamAction(
        val id: String,
        val emitInterval: Long = 100L,
    ) : CounterAction, FlowHolderAction {
        override fun toFlowAction(): Flow<Action> = flow {
            while (true) {
                delay(emitInterval)
                emit(Add(10))
            }
        }
    }

    /**
     * FlowHolderAction using TakeLeading strategy.
     * First execution runs, subsequent ones are ignored until completion.
     */
    data class TakeLeadingStreamAction(
        val id: String,
        val values: List<Int>,
        val delayBetween: Long = 50L,
    ) : CounterAction, FlowHolderAction {
        override val strategy: ExecutionStrategy get() = takeLeading()

        override fun toFlowAction(): Flow<Action> = flow {
            for (value in values) {
                delay(delayBetween)
                emit(Add(value))
            }
        }
    }

    /**
     * FlowHolderAction using Debounce strategy.
     * Waits for the specified duration after the last action before executing.
     */
    data class DebouncedStreamAction(
        val id: String,
        val value: Int,
        val debounceMs: Long = 100L,
    ) : CounterAction, FlowHolderAction {
        override val strategy: ExecutionStrategy get() = debounce(debounceMs)

        override fun toFlowAction(): Flow<Action> = flow {
            emit(Add(value))
        }
    }

    /**
     * FlowHolderAction using Throttle strategy.
     * Executes immediately, then ignores subsequent actions for the window duration.
     */
    data class ThrottledStreamAction(
        val id: String,
        val value: Int,
        val throttleMs: Long = 100L,
    ) : CounterAction, FlowHolderAction {
        override val strategy: ExecutionStrategy get() = throttle(throttleMs)

        override fun toFlowAction(): Flow<Action> = flow {
            emit(Add(value))
        }
    }

    /**
     * FlowHolderAction that emits another FlowHolderAction (nested).
     * Uses Concurrent strategy to avoid cancellation issues with recursive calls.
     * Used to test recursive processing of nested FlowHolderActions.
     */
    data class NestedFlowHolderAction(
        val innerAction: FlowHolderAction,
    ) : CounterAction, FlowHolderAction {
        override val strategy: ExecutionStrategy get() = concurrent()

        override fun toFlowAction(): Flow<Action> = flow {
            emit(Add(100)) // Emit a regular action first
            emit(innerAction) // Then emit another FlowHolderAction
        }
    }

    /**
     * Action that triggers the middleware to emit multiple FlowHolderActions.
     * Used to test concurrent emission of FlowHolderActions from middleware.
     */
    object StartMultipleObservers : CounterAction

    /**
     * Marker action emitted after all FlowHolderActions are emitted.
     * Used to verify that middleware code after emit() is executed.
     */
    data class SetupComplete(val timestamp: Long) : CounterAction
}

val counterReducer =
    Reducer<CounterState, CounterAction> { state, action ->
        when (action) {
            is CounterAction.Increment -> state.copy(count = state.count + 1)
            is CounterAction.Decrement -> state.copy(count = state.count - 1)
            is CounterAction.Add -> state.copy(count = state.count + action.value)
            is CounterAction.SetValue -> state.copy(count = action.value)
            is CounterAction.FetchData -> state
            is CounterAction.FetchDataSuccess -> state.copy(count = action.value)
            is CounterAction.FetchDataError -> state
            is CounterAction.Reset -> state.copy(count = 0)
            is CounterAction.StreamConnected -> state
            is CounterAction.MultiStreamConnected -> state
            is CounterAction.InfiniteStreamAction -> state
            is CounterAction.NonCancelableStreamAction -> state
            is CounterAction.SecondaryStreamAction -> state
            is CounterAction.TakeLeadingStreamAction -> state
            is CounterAction.DebouncedStreamAction -> state
            is CounterAction.ThrottledStreamAction -> state
            is CounterAction.NestedFlowHolderAction -> state
            is CounterAction.StartMultipleObservers -> state
            is CounterAction.SetupComplete -> state
        }
    }

val testErrorProcessor = object : ErrorProcessor<CounterAction> {
    override fun process(throwable: Throwable): Flow<CounterAction> {
        return emptyFlow()
    }
}
