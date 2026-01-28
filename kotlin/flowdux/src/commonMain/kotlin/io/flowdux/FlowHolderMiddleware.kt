package io.flowdux

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transform
import kotlin.reflect.KClass

/**
 * Internal middleware that handles [FlowHolderAction] processing.
 *
 * This middleware is automatically added as the last middleware in the Store's
 * middleware chain. It expands FlowHolderActions into their streams of actions
 * and applies the action's [ExecutionStrategy].
 *
 * For nested FlowHolderActions (a FlowHolderAction that emits another FlowHolderAction),
 * processing is done recursively within this middleware, not re-dispatched through
 * the entire middleware chain.
 *
 * @param dispatch function to re-dispatch actions through the full Store pipeline.
 *   Used when a FlowHolderAction has [FlowActionDelivery.Dispatch] delivery mode.
 */
internal class FlowHolderMiddleware<S : State, A : Action>(
    private val logger: StoreLogger<S, A>,
    private val dispatch: (A) -> Unit,
) : Middleware<S, A> {

    override val name: String = "FlowHolderMiddleware"
    override val processors: ActionProcessorMap<S, A> = emptyMap()

    /**
     * Cache of wrapped processors for each FlowHolderAction type.
     * The strategy wrapping is cached per KClass to maintain strategy state
     * (e.g., for TakeLatest, the same strategy instance tracks the current job).
     */
    private val wrappedProcessors = mutableMapOf<KClass<*>, WrappedProcessor<S, A>>()

    override fun process(getState: () -> S, action: A): Flow<A> {
        if (action !is FlowHolderAction) {
            return flowOf(action)
        }

        val wrapped = getOrCreateWrappedProcessor(action)

        return when (action.delivery) {
            FlowActionDelivery.Emit -> flow {
                wrapped.invoke(this, getState(), action)
            }.transform { innerAction ->
                if (innerAction is FlowHolderAction) {
                    // Recursive processing for nested FlowHolderActions
                    emitAll(process(getState, innerAction as A))
                } else {
                    emit(innerAction)
                }
            }

            FlowActionDelivery.Dispatch -> flow<A> {
                wrapped.invoke(this, getState(), action)
            }.onEach { innerAction ->
                // Re-dispatch through full pipeline; nested FlowHolderActions
                // will be processed when they reach this middleware again
                dispatch(innerAction)
            }.transform {
                // Don't emit anything; all inner actions are re-dispatched
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getOrCreateWrappedProcessor(action: FlowHolderAction): WrappedProcessor<S, A> {
        return wrappedProcessors.getOrPut(action::class) {
            val baseProcessor: suspend FlowCollector<A>.(S, A) -> Unit = { _, a ->
                emitAll(
                    (a as FlowHolderAction).toFlowAction()
                        .onEach { logger.onFlowHolderActionEmitted(it as A) }
                        .map { it as A }
                )
            }
            action.strategy.wrap(baseProcessor)
        }
    }
}

private typealias WrappedProcessor<S, A> = suspend FlowCollector<A>.(S, A) -> Unit
