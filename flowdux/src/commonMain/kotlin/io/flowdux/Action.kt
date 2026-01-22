package io.flowdux

import kotlinx.coroutines.flow.Flow

interface Action

/**
 * Action that emits multiple actions via a Flow.
 *
 * When dispatched, the Store automatically subscribes to the Flow
 * and dispatches each emitted action individually.
 *
 * By default, FlowHolderAction is cancelable, meaning that when a new
 * FlowHolderAction of the same type is dispatched, the previous one's
 * flow will be cancelled. Override [cancelable] to return false
 * if you want multiple flows of the same type to run concurrently.
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
 * // Non-cancelable FlowHolderAction (multiple can run concurrently)
 * class ConcurrentStreamAction : FlowHolderAction {
 *     override val cancelable: Boolean get() = false
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
     * Whether this action's flow should be cancelled when a new
     * FlowHolderAction of the same type is dispatched.
     *
     * Default is true. Override to return false for concurrent execution.
     */
    val cancelable: Boolean get() = true
}
