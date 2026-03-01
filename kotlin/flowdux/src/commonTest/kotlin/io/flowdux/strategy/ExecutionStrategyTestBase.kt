package io.flowdux.strategy

import io.flowdux.Action
import io.flowdux.ErrorProcessor
import io.flowdux.Reducer
import io.flowdux.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Shared test utilities for ExecutionStrategy tests.
 */
object ExecutionStrategyTestBase {
    // Test-specific state and actions
    data class TestState(val values: List<String> = emptyList()) : State

    sealed interface TestAction : Action {
        data class Fetch(val id: String) : TestAction

        data class FetchSuccess(val id: String, val result: String) : TestAction

        data class Search(val query: String) : TestAction

        data class SearchResult(val query: String, val results: List<String>) : TestAction

        data class Click(val buttonId: String) : TestAction

        data class ClickProcessed(val buttonId: String) : TestAction
    }

    val testReducer =
        Reducer<TestState, TestAction> { state, action ->
            when (action) {
                is TestAction.FetchSuccess -> state.copy(values = state.values + action.result)
                is TestAction.SearchResult -> state.copy(values = action.results)
                is TestAction.ClickProcessed -> state.copy(values = state.values + action.buttonId)
                else -> state
            }
        }

    val testErrorProcessor =
        object : ErrorProcessor<TestAction> {
            override fun process(throwable: Throwable): Flow<TestAction> = emptyFlow()
        }
}
