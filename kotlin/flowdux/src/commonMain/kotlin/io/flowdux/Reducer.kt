package io.flowdux

import kotlin.reflect.KClass

fun interface Reducer<S : State, A : Action> {
    fun reduce(
        state: S,
        action: A,
    ): S
}

class ReducerBuilder<S : State, A : Action> {
    @PublishedApi
    internal val handlers = mutableMapOf<KClass<out A>, (S, A) -> S>()

    inline fun <reified T : A> on(noinline handler: (S, T) -> S) {
        handlers[T::class] = { state, action ->
            handler(state, action as T)
        }
    }

    fun build(): Reducer<S, A> =
        Reducer { state, action ->
            handlers[action::class]?.invoke(state, action) ?: state
        }
}

inline fun <S : State, A : Action> buildReducer(
    block: ReducerBuilder<S, A>.() -> Unit
): Reducer<S, A> = ReducerBuilder<S, A>().apply(block).build()
