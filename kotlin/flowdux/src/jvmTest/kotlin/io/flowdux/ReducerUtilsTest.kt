package io.flowdux

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReducerUtilsTest {
    data class TestState(
        val value1: Int = 0,
        val value2: String = "",
    ) : State

    sealed interface TestAction : Action {
        data class SetValue1(val value: Int) : TestAction

        data class SetValue2(val value: String) : TestAction

        object Reset : TestAction
    }

    @Test
    fun `buildReducer handles actions`() {
        val reducer = buildReducer<TestState, TestAction> {
            on<TestAction.SetValue1> { state, action ->
                state.copy(value1 = action.value)
            }
            on<TestAction.SetValue2> { state, action ->
                state.copy(value2 = action.value)
            }
            on<TestAction.Reset> { _, _ ->
                TestState()
            }
        }

        var state = TestState()
        state = reducer.reduce(state, TestAction.SetValue1(42))
        assertEquals(42, state.value1)
        assertEquals("", state.value2)

        state = reducer.reduce(state, TestAction.SetValue2("hello"))
        assertEquals(42, state.value1)
        assertEquals("hello", state.value2)

        state = reducer.reduce(state, TestAction.Reset)
        assertEquals(0, state.value1)
        assertEquals("", state.value2)
    }

    @Test
    fun `buildReducer returns unchanged state for unhandled actions`() {
        val reducer = buildReducer<TestState, TestAction> {
            on<TestAction.SetValue1> { state, action ->
                state.copy(value1 = action.value)
            }
        }

        val initialState = TestState(value1 = 10, value2 = "test")

        val result = reducer.reduce(initialState, TestAction.SetValue2("new"))
        assertEquals(initialState, result)
    }

    @Test
    fun `buildReducer last handler wins for same action type`() {
        val reducer = buildReducer<TestState, TestAction> {
            on<TestAction.SetValue1> { state, action ->
                state.copy(value1 = action.value)
            }
            on<TestAction.SetValue1> { state, action ->
                state.copy(value1 = action.value * 2)
            }
        }

        val state = reducer.reduce(TestState(), TestAction.SetValue1(10))
        assertEquals(20, state.value1) // 10 * 2
    }
}
