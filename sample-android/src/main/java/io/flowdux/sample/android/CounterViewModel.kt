package io.flowdux.sample.android

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.flowdux.StoreLogger
import io.flowdux.createStore

class CounterViewModel : ViewModel() {
    companion object {
        private const val TAG = "CounterStore"
    }

    private val store = createStore(
        initialState = CounterState(),
        reducer = counterReducer,
        scope = viewModelScope,
        logger = object : StoreLogger<CounterState, CounterAction> {
            override fun onActionDispatched(action: CounterAction) {
                Log.d(TAG, "[DISPATCH] $action")
            }

            override fun onMiddlewareProcessing(
                middlewareName: String,
                action: CounterAction
            ) {
                Log.d(TAG, "[MIDDLEWARE] $middlewareName processing $action")
            }

            override fun onMiddlewaresCompleted(action: CounterAction) {
                Log.d(TAG, "[MIDDLEWARE_CHAIN] completed with $action")
            }

            override fun onFlowHolderActionEmitted(action: CounterAction) {
                Log.d(TAG, "[FLOW_ACTION] emitted $action")
            }

            override fun onErrorOccurred(throwable: Throwable) {
                Log.e(TAG, "[ERROR] ${throwable.message}", throwable)
            }

            override fun onErrorHandled(action: CounterAction) {
                Log.d(TAG, "[ERROR_HANDLED] $action")
            }

            override fun onStateReduced(
                action: CounterAction,
                previousState: CounterState,
                newState: CounterState
            ) {
                Log.d(TAG, "[REDUCE] $action: $previousState -> $newState")
            }
        }
    )

    val state = store.state

    fun increment() = store.dispatch(CounterAction.Increment)
    fun decrement() = store.dispatch(CounterAction.Decrement)
    fun reset() = store.dispatch(CounterAction.Reset)
}
