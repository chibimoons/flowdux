package io.flowdux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class)
class Store<S : State, A : Action>(
    initialState: S,
    private val reducer: Reducer<S, A>,
    private val middlewares: List<Middleware<S, A>>,
    private val errorProcessor: ErrorProcessor<A>,
    private val scope: CoroutineScope,
) {
    private val actionFlow = Channel<A>()

    private val stateFlow = actionFlow
        .receiveAsFlow()
        .flatMapMerge { processAction(it) }
        .map { reduceAction(state.value, it) }
        .stateIn(scope, SharingStarted.Eagerly, initialState)

    private fun processAction(a: A): Flow<A> = middlewares
        .fold(flowOf(a)) { flow, middleware ->
            flow.flatMapConcat { currentAction ->
                middleware.process(
                    getState = { currentState },
                    action = currentAction,
                )
            }
        }
        .flatMapMerge {
            if (it is FlowHolderAction) {
                it.toFlowAction() as Flow<A>
            } else {
                flowOf(it)
            }
        }
        .catch { emitAll(errorProcessor.process(it)) }


    val state: StateFlow<S> = stateFlow

    val currentState: S get() = stateFlow.value

    private val mutex = Mutex()

    fun dispatch(action: A) {
        scope.launch {
            actionFlow.send(action)
        }
    }

    private suspend fun reduceAction(currentState: S, action: A): S {
        return mutex.withLock {
            reducer.reduce(currentState, action)
        }
    }
}

fun <S : State, A : Action> createStore(
    initialState: S,
    middlewares: List<Middleware<S, A>> = emptyList(),
    reducer: Reducer<S, A>,
    errorProcessor: ErrorProcessor<A> = DefaultErrorProcessor(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): Store<S, A> =
    Store(
        initialState = initialState,
        reducer = reducer,
        middlewares = middlewares,
        errorProcessor = errorProcessor,
        scope = scope,
    )
