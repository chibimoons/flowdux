package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.NoOpStoreLogger
import io.flowdux.State

/**
 * A [StoreLogger][io.flowdux.StoreLogger] that collects actions processed by the reducer.
 *
 * Used on the server side to capture all actions that result from processing
 * a client's dispatched action. After the store processes an action,
 * call [drain] to retrieve and clear the collected actions.
 *
 * Example:
 * ```kotlin
 * val collector = ResponseCollector<ServerState, ServerAction>()
 * val store = createStore(
 *     initialState = ServerState(),
 *     reducer = serverReducer,
 *     logger = collector,
 *     scope = scope,
 * )
 *
 * store.dispatch(someAction)
 * // ... wait for processing ...
 * val resultActions = collector.drain()
 * ```
 */
class ResponseCollector<S : State, A : Action> : NoOpStoreLogger<S, A>() {
    private val pending = mutableListOf<A>()

    override fun onStateReduced(action: A, previousState: S, newState: S) {
        pending.add(action)
    }

    /**
     * Returns all collected actions and clears the internal buffer.
     */
    fun drain(): List<A> {
        val result = pending.toList()
        pending.clear()
        return result
    }
}
