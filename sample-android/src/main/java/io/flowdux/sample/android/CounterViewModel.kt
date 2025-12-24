package io.flowdux.sample.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.flowdux.createStore

class CounterViewModel : ViewModel() {
    private val store = createStore(
        initialState = CounterState(),
        reducer = counterReducer,
        scope = viewModelScope
    )

    val state = store.state

    fun increment() = store.dispatch(CounterAction.Increment)
    fun decrement() = store.dispatch(CounterAction.Decrement)
    fun reset() = store.dispatch(CounterAction.Reset)

    override fun onCleared() {
        super.onCleared()
        store.cancel()
    }
}
