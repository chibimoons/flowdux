package io.flowdux

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface ErrorProcessor<A: Action> {
    fun process(throwable: Throwable) : Flow<A>
}

class DefaultErrorProcessor<A: Action> : ErrorProcessor<A> {
    override fun process(throwable: Throwable): Flow<A> {
        return emptyFlow()
    }
}