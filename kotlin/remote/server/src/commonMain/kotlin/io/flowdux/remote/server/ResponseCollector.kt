package io.flowdux.remote.server

import io.flowdux.Action
import io.flowdux.NoOpStoreLogger
import io.flowdux.State
import kotlinx.coroutines.channels.Channel

/**
 * A [io.flowdux.StoreLogger] that collects reduced actions and signals when reductions occur.
 *
 * This utility is useful for server-side request-response patterns where you need to
 * await the result of a dispatched action before proceeding (e.g., dispatch an action,
 * wait for the reduction, then read the updated state).
 *
 * **Thread Safety:**
 * Uses [Channel.CONFLATED] for `reductionSignal` to eliminate race conditions that would
 * occur with CompletableDeferred swapping. Multiple concurrent reductions between
 * [awaitNextReduction] calls are coalesced into a single signal.
 *
 * Example:
 * ```kotlin
 * val collector = ResponseCollector<MyState, MyAction>()
 * val store = createStore(logger = collector, ...)
 *
 * store.dispatch(MyAction.DoSomething)
 * collector.awaitNextReduction()
 * val pendingActions = collector.drainPending()
 * ```
 */
class ResponseCollector<S : State, A : Action> : NoOpStoreLogger<S, A>() {

    /**
     * Channel for pending actions that have been reduced.
     * Use [drainPending] to retrieve accumulated actions.
     */
    private val pending = Channel<A>(Channel.UNLIMITED)

    /**
     * Conflated channel used as a reduction signal.
     * - [trySend] on reduction ensures a signal is always available after reduction
     * - [receive] in [awaitNextReduction] consumes the signal
     * - Conflated semantics: multiple rapid reductions coalesce into one signal
     */
    private val reductionSignal = Channel<Unit>(Channel.CONFLATED)

    /**
     * Called by the store after each action reduces the state.
     * Records the action and signals that a reduction occurred.
     */
    override fun onStateReduced(action: A, previousState: S, newState: S) {
        pending.trySend(action)
        reductionSignal.trySend(Unit)
    }

    /**
     * Suspends until at least one reduction has occurred since the last call.
     *
     * If a reduction already occurred before this call, returns immediately.
     * If multiple reductions occur while suspended, only one signal is consumed
     * (subsequent reductions will trigger the next [awaitNextReduction] call).
     */
    suspend fun awaitNextReduction() {
        reductionSignal.receive()
    }

    /**
     * Drains and returns all pending actions that have been reduced.
     *
     * Returns an empty list if no actions are pending.
     * This is a non-suspending operation.
     */
    fun drainPending(): List<A> {
        val result = mutableListOf<A>()
        while (true) {
            val action = pending.tryReceive().getOrNull() ?: break
            result.add(action)
        }
        return result
    }
}
