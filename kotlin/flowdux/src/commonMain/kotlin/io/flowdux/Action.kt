package io.flowdux

import kotlinx.coroutines.flow.Flow

interface Action

/**
 * Determines how inner actions emitted by a [FlowHolderAction] are delivered.
 *
 * - [Emit]: Inner actions bypass user middlewares and go directly to the reducer.
 *   This is the default and most efficient delivery mode.
 * - [Dispatch]: Inner actions are re-dispatched through the entire middleware pipeline,
 *   allowing user middlewares to observe and process them.
 */
enum class FlowActionDelivery {
    /** Inner actions go directly to the reducer, bypassing user middlewares. */
    Emit,
    /** Inner actions are re-dispatched through the full middleware pipeline. */
    Dispatch,
}

/**
 * Action that emits multiple actions via a Flow.
 *
 * When dispatched, the Store automatically subscribes to the Flow
 * and dispatches each emitted action individually.
 *
 * By default, FlowHolderAction uses [TakeLatest] strategy, meaning that
 * when a new FlowHolderAction of the same type is dispatched, the previous
 * one's flow will be cancelled. Override [strategy] to use a different
 * execution strategy.
 *
 * Example:
 * ```kotlin
 * class BatchAction(private val actions: List<Action>) : FlowHolderAction {
 *     override fun toFlowAction() = actions.asFlow()
 * }
 *
 * class FetchAndProcessAction : FlowHolderAction {
 *     override fun toFlowAction() = flow {
 *         emit(LoadingAction)
 *         val data = fetchData()
 *         emit(DataLoadedAction(data))
 *     }
 * }
 *
 * // Concurrent FlowHolderAction (multiple can run in parallel)
 * class ConcurrentStreamAction : FlowHolderAction {
 *     override val strategy: ExecutionStrategy get() = concurrent()
 *     override fun toFlowAction() = flow { ... }
 * }
 *
 * // Debounced FlowHolderAction
 * class DebouncedSearchAction : FlowHolderAction {
 *     override val strategy: ExecutionStrategy get() = debounce(300.milliseconds)
 *     override fun toFlowAction() = flow { ... }
 * }
 *
 * // Dispatch delivery: inner actions pass through the full middleware pipeline
 * class DispatchedAction : FlowHolderAction {
 *     override val delivery: FlowActionDelivery get() = FlowActionDelivery.Dispatch
 *     override fun toFlowAction() = flow { ... }
 * }
 * ```
 */
interface FlowHolderAction : Action {
    /**
     * Returns a Flow of actions to be dispatched.
     */
    fun toFlowAction(): Flow<Action>

    /**
     * Execution strategy for this FlowHolderAction.
     *
     * Default is [TakeLatest], which cancels previous executions
     * when a new action of the same type is dispatched.
     *
     * Use [concurrent] for parallel execution without cancellation.
     */
    val strategy: ExecutionStrategy get() = TakeLatest()

    /**
     * Delivery mode for inner actions emitted by this FlowHolderAction.
     *
     * Default is [FlowActionDelivery.Emit], which sends inner actions directly
     * to the reducer, bypassing user middlewares.
     *
     * Override with [FlowActionDelivery.Dispatch] to re-dispatch inner actions
     * through the full middleware pipeline.
     */
    val delivery: FlowActionDelivery get() = FlowActionDelivery.Dispatch
}
