package io.flowdux

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val DEFAULT_CONCURRENCY = 16

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)
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
    private val _isClosed = AtomicBoolean(false)
    private val isLoggingEnabled = logger::class != NoOpStoreLogger::class

    val isClosed: Boolean get() = _isClosed.load()

    private val stateFlow =
        actionFlow
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
        }.run {
            if (isLoggingEnabled) {
                onEach { logger.onMiddlewaresCompleted(it) }
            } else {
                this
            }
        }.catch { error ->
            if (isLoggingEnabled) logger.onErrorOccurred(error)
            emitAll(
                errorProcessor.process(error).run {
                    if (isLoggingEnabled) {
                        onEach { logger.onErrorHandled(it) }
                    } else {
                        this
                    }
                },
            )
        }

    val state: StateFlow<S> = stateFlow

    val currentState: S get() = stateFlow.value

    fun dispatch(action: A) {
        if (_isClosed.load()) {
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
        if (!_isClosed.compareAndSet(expectedValue = false, newValue = true)) return
        actionFlow.close()
        scope.cancel()
    }

    private fun reduceAction(currentState: S, action: A): S {
        if (action is DrainSentinel) {
            action.signal.complete(Unit)
            return currentState
        }
        val newState = reducer.reduce(currentState, action)
        if (isLoggingEnabled) logger.onStateReduced(action, currentState, newState)
        return newState
    }

    /**
     * Gracefully closes the store after all pending actions have been processed.
     *
     * Optionally dispatches cleanup actions (e.g., disconnect, leave room) via [beforeClose],
     * then waits for all queued actions to drain before calling [close].
     *
     * **How it works:** A sentinel action is enqueued after all [beforeClose] dispatches.
     * The sentinel passes through the full middleware pipeline (middlewares pass it through
     * as an unknown action type). When it reaches the reducer, it signals completion without
     * modifying state. This guarantees all previously dispatched actions have been fully
     * processed before [close] is called.
     *
     * **Limitations:**
     * - In-flight [FlowHolderAction]s may still emit actions after the sentinel is processed,
     *   since their flows are collected independently by [FlowHolderMiddleware].
     * - If a middleware filters unknown action types instead of passing them through,
     *   the sentinel will not reach the reducer and the call will fall back to [timeout].
     *
     * @param timeout Maximum time to wait for pending actions to drain. Defaults to 5 seconds.
     * @param beforeClose Optional suspend block invoked before draining.
     *        Use the provided `dispatch` function to enqueue cleanup actions.
     *        Actions are sent directly to the channel (not via [dispatch]) to guarantee
     *        ordering: all cleanup actions will be enqueued before the drain sentinel.
     */
    suspend fun closeGracefully(
        timeout: Duration = 5.seconds,
        beforeClose: (suspend (dispatch: suspend (A) -> Unit) -> Unit)? = null,
    ) {
        if (_isClosed.load()) return
        beforeClose?.invoke {
            if (isLoggingEnabled) logger.onActionDispatched(it)
            actionFlow.send(it)
        }
        val signal = CompletableDeferred<Unit>()
        @Suppress("UNCHECKED_CAST")
        try {
            actionFlow.send(DrainSentinel(signal) as A)
        } catch (_: ClosedSendChannelException) {
            close()
            return
        }
        try {
            withTimeout(timeout) { signal.await() }
        } catch (_: TimeoutCancellationException) {
            // Timeout expired; close anyway
        }
        close()
    }

    private class DrainSentinel(val signal: CompletableDeferred<Unit>) : Action
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
    val allMiddlewares =
        middlewares +
            FlowHolderMiddleware<S, A>(logger, dispatch = { store.dispatch(it) })
    store =
        Store(
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
