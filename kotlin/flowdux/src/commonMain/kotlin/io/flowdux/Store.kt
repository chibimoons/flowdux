package io.flowdux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val DEFAULT_CONCURRENCY = 16

@OptIn(ExperimentalCoroutinesApi::class)
class Store<S : State, A : Action> internal constructor(
    initialState: S,
    private val reducer: Reducer<S, A>,
    private val middlewares: List<Middleware<S, A>>,
    private val errorProcessor: ErrorProcessor<A>,
    private val logger: StoreLogger<S, A>,
    private val scope: CoroutineScope,
    private val concurrency: Int,
) {
    private val actionFlow = Channel<A>()
    private var _isClosed = false
    private val isLoggingEnabled = logger !is NoOpStoreLogger

    val isClosed: Boolean get() = _isClosed

    private val stateFlow = actionFlow
        .receiveAsFlow()
        .flatMapMerge(concurrency) { processAction(it) }
        .map { reduceAction(state.value, it) }
        .stateIn(scope, SharingStarted.Eagerly, initialState)

    private fun processAction(a: A): Flow<A> = middlewares
        .fold(flowOf(a)) { flow, middleware ->
            flow.flatMapMerge(concurrency) { currentAction ->
                if (isLoggingEnabled) logger.onMiddlewareProcessing(middleware.name, currentAction)
                middleware.process(
                    getState = { currentState },
                    action = currentAction,
                )
            }
        }
        .run {
            if (isLoggingEnabled) onEach { logger.onMiddlewaresCompleted(it) }
            else this
        }
        .catch { error ->
            if (isLoggingEnabled) logger.onErrorOccurred(error)
            emitAll(
                errorProcessor.process(error).run {
                    if (isLoggingEnabled) onEach { logger.onErrorHandled(it) }
                    else this
                }
            )
        }

    val state: StateFlow<S> = stateFlow

    val currentState: S get() = stateFlow.value

    fun dispatch(action: A) {
        if (_isClosed) {
            if (isLoggingEnabled) logger.onDispatchAfterClose(action)
            return
        }
        scope.launch {
            try {
                if (isLoggingEnabled) logger.onActionDispatched(action)
                actionFlow.send(action)
            } catch (_: ClosedSendChannelException) {
                // Race condition: close() called between isClosed check and send
                if (isLoggingEnabled) logger.onDispatchAfterClose(action)
            }
        }
    }

    fun close() {
        if (_isClosed) return
        _isClosed = true
        actionFlow.close()
        scope.cancel()
    }

    private fun reduceAction(currentState: S, action: A): S {
        val newState = reducer.reduce(currentState, action)
        if (isLoggingEnabled) logger.onStateReduced(action, currentState, newState)
        return newState
    }
}

fun <S : State, A : Action> createStore(
    initialState: S,
    middlewares: List<Middleware<S, A>> = emptyList(),
    reducer: Reducer<S, A>,
    errorProcessor: ErrorProcessor<A> = DefaultErrorProcessor(),
    logger: StoreLogger<S, A> = NoOpStoreLogger(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    concurrency: Int = DEFAULT_CONCURRENCY,
): Store<S, A> {
    lateinit var store: Store<S, A>
    val allMiddlewares = middlewares +
        FlowHolderMiddleware<S, A>(logger, dispatch = { store.dispatch(it) })
    store = Store(
        initialState = initialState,
        reducer = reducer,
        middlewares = allMiddlewares,
        errorProcessor = errorProcessor,
        logger = logger,
        scope = scope,
        concurrency = concurrency,
    )
    return store
}
