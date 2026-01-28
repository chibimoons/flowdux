package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.NoOpStoreLogger
import io.flowdux.State
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

/**
 * A [StoreLogger][io.flowdux.StoreLogger] that collects actions processed by the reducer.
 *
 * Used on the server side to capture all actions that result from processing
 * a client's dispatched action. After the store processes an action,
 * call [drain] to retrieve and clear the collected actions.
 *
 * Thread-safe: uses a [Channel] internally for safe concurrent access
 * from the reducer coroutine and the message-handling coroutine.
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
 * collector.awaitNextReduction()
 * val resultActions = collector.drain()
 * ```
 */
class ResponseCollector<S : State, A : Action> : NoOpStoreLogger<S, A>() {
    private val pending = Channel<A>(Channel.UNLIMITED)
    private var reductionSignal = CompletableDeferred<Unit>()

    override fun onStateReduced(action: A, previousState: S, newState: S) {
        pending.trySend(action)
        reductionSignal.complete(Unit)
    }

    /**
     * Suspends until the next reduction occurs.
     * After returning, call [drain] to retrieve collected actions.
     */
    suspend fun awaitNextReduction() {
        reductionSignal.await()
        reductionSignal = CompletableDeferred()
    }

    /**
     * Returns all collected actions and clears the internal buffer.
     */
    fun drain(): List<A> {
        val result = mutableListOf<A>()
        while (true) {
            val item = pending.tryReceive().getOrNull() ?: break
            result.add(item)
        }
        return result
    }
}
