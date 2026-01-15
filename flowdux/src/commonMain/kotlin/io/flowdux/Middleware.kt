package io.flowdux

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlin.reflect.KClass

typealias ActionProcessor<S, A> = suspend FlowCollector<A>.(S, A) -> Unit
typealias ActionProcessorMap<S, A> = Map<KClass<*>, ActionProcessor<S, A>>

interface Middleware<S : State, A : Action> {
    val name: String get() = this::class.simpleName ?: "Unknown"
    val processors: ActionProcessorMap<S, A>

    fun process(
        getState: () -> S,
        action: A,
    ): Flow<A> = flow {
        val processor = processors[action::class]
        if (processor != null) {
            processor.invoke(this, getState(), action)
        } else {
            emit(action)
        }
    }

    class ActionProcessorBuilder<S, A> {
        val processors = mutableMapOf<KClass<*>, ActionProcessor<S, A>>()

        inline fun <reified T : A> on(
            noinline processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit
        ) {
            @Suppress("UNCHECKED_CAST")
            processors[T::class] = processor as ActionProcessor<S, A>
        }

        inline fun <reified T : A> on(
            noinline processor: suspend FlowCollector<A>.() -> Unit
        ) {
            processors[T::class] = { _, _ -> processor() }
        }

        /**
         * Registers an action processor with an execution strategy.
         *
         * @param strategy The execution strategy to apply (e.g., takeLatest, takeLeading, debounce, throttle)
         * @param processor The action processor function
         */
        inline fun <reified T : A> on(
            strategy: ExecutionStrategy,
            noinline processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit
        ) {
            val wrappedProcessor = strategy.wrap(processor)
            @Suppress("UNCHECKED_CAST")
            processors[T::class] = wrappedProcessor as ActionProcessor<S, A>
        }

        /**
         * Registers an action processor with an execution strategy (no state/action parameters).
         *
         * @param strategy The execution strategy to apply (e.g., takeLatest, takeLeading, debounce, throttle)
         * @param processor The action processor function
         */
        inline fun <reified T : A> on(
            strategy: ExecutionStrategy,
            noinline processor: suspend FlowCollector<A>.() -> Unit
        ) {
            val wrappedProcessor: suspend FlowCollector<A>.(S, T) -> Unit = { _, _ -> processor() }
            val wrapped = strategy.wrap(wrappedProcessor)
            @Suppress("UNCHECKED_CAST")
            processors[T::class] = wrapped as ActionProcessor<S, A>
        }

        fun build() = processors.toMap()
    }

    fun buildProcessors(
        block: ActionProcessorBuilder<S, A>.() -> Unit
    ): ActionProcessorMap<S, A> {
        return ActionProcessorBuilder<S, A>().apply(block).build()
    }
}
