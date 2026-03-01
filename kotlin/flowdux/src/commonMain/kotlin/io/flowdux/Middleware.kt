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

    fun process(getState: () -> S, action: A): Flow<A> = flow {
        val processor = processors[action::class]
        if (processor != null) {
            processor.invoke(this, getState(), action)
        } else {
            emit(action)
        }
    }

    /**
     * Exception thrown when attempting to register a duplicate action processor.
     */
    class DuplicateProcessorException(actionClass: KClass<*>) :
        IllegalArgumentException(
            "Processor for action type '${actionClass.simpleName}' is already registered. " +
                "Each action type can only have one processor per middleware.",
        )

    class ActionProcessorBuilder<S, A> {
        @PublishedApi
        internal val processors = mutableMapOf<KClass<*>, ActionProcessor<S, A>>()

        @PublishedApi
        internal fun checkDuplicate(actionClass: KClass<*>) {
            if (actionClass in processors) {
                throw DuplicateProcessorException(actionClass)
            }
        }

        inline fun <reified T : A> on(noinline processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit) {
            checkDuplicate(T::class)
            @Suppress("UNCHECKED_CAST")
            processors[T::class] = processor as ActionProcessor<S, A>
        }

        inline fun <reified T : A> on(noinline processor: suspend FlowCollector<A>.() -> Unit) {
            checkDuplicate(T::class)
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
            noinline processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit,
        ) {
            checkDuplicate(T::class)
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
            noinline processor: suspend FlowCollector<A>.() -> Unit,
        ) {
            checkDuplicate(T::class)
            val baseProcessor: suspend FlowCollector<A>.(S, T) -> Unit = { _, _ -> processor() }
            val wrappedProcessor = strategy.wrap(baseProcessor)
            @Suppress("UNCHECKED_CAST")
            processors[T::class] = wrappedProcessor as ActionProcessor<S, A>
        }

        /**
         * Groups multiple action processors under a shared execution strategy.
         * Actions within the same group share the strategy's state (e.g., cancellation, throttling).
         *
         * Example:
         * ```
         * group(takeLatest()) {
         *     on<SearchAction> { state, action -> ... }
         *     on<RefreshAction> { state, action -> ... }
         * }
         * ```
         *
         * @param strategy The shared execution strategy for all processors in this group
         * @param block The builder block to register processors
         */
        fun group(strategy: ExecutionStrategy, block: StrategyGroupBuilder<S, A>.() -> Unit) {
            StrategyGroupBuilder(strategy, processors).apply(block)
        }

        fun build() = processors.toMap()
    }

    /**
     * Builder for registering action processors within a strategy group.
     * All processors registered here share the same strategy instance.
     */
    class StrategyGroupBuilder<S, A>(
        @PublishedApi internal val strategy: ExecutionStrategy,
        @PublishedApi internal val processors: MutableMap<KClass<*>, ActionProcessor<S, A>>,
    ) {
        @PublishedApi
        internal fun checkDuplicate(actionClass: KClass<*>) {
            if (actionClass in processors) {
                throw DuplicateProcessorException(actionClass)
            }
        }

        /**
         * Registers an action processor that shares this group's execution strategy.
         */
        inline fun <reified T : A> on(noinline processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit) {
            checkDuplicate(T::class)
            val wrappedProcessor = strategy.wrap(processor)
            @Suppress("UNCHECKED_CAST")
            processors[T::class] = wrappedProcessor as ActionProcessor<S, A>
        }

        /**
         * Registers an action processor (no parameters) that shares this group's execution strategy.
         */
        inline fun <reified T : A> on(noinline processor: suspend FlowCollector<A>.() -> Unit) {
            checkDuplicate(T::class)
            val baseProcessor: suspend FlowCollector<A>.(S, T) -> Unit = { _, _ -> processor() }
            val wrappedProcessor = strategy.wrap(baseProcessor)
            @Suppress("UNCHECKED_CAST")
            processors[T::class] = wrappedProcessor as ActionProcessor<S, A>
        }
    }

    fun buildProcessors(block: ActionProcessorBuilder<S, A>.() -> Unit): ActionProcessorMap<S, A> =
        ActionProcessorBuilder<S, A>().apply(block).build()
}
