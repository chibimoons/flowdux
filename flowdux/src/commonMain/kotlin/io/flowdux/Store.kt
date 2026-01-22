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
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/** Cancellation flag for FlowHolderAction streams. */
private class CancelFlag {
    var cancelled = false
}

@OptIn(ExperimentalCoroutinesApi::class)
class Store<S : State, A : Action>(
    initialState: S,
    private val reducer: Reducer<S, A>,
    private val middlewares: List<Middleware<S, A>>,
    private val errorProcessor: ErrorProcessor<A>,
    private val logger: StoreLogger<S, A>,
    private val scope: CoroutineScope,
) {
    private val actionFlow = Channel<A>()
    private var _isClosed = false

    /** Active cancel flags for cancelable FlowHolderActions, keyed by KClass. */
    private val activeFlags = mutableMapOf<KClass<*>, CancelFlag>()

    val isClosed: Boolean get() = _isClosed

    private val stateFlow = actionFlow
        .receiveAsFlow()
        .flatMapMerge { processAction(it) }
        .map { reduceAction(state.value, it) }
        .stateIn(scope, SharingStarted.Eagerly, initialState)

    private fun processAction(a: A): Flow<A> = middlewares
        .fold(flowOf(a)) { flow, middleware ->
            flow.flatMapConcat { currentAction ->
                logger.onMiddlewareProcessing(middleware.name, currentAction)
                middleware.process(
                    getState = { currentState },
                    action = currentAction,
                )
            }
        }
        .onEach { logger.onMiddlewaresCompleted(it) }
        .flatMapMerge { processFlowHolderAction(it) }
        .catch { error ->
            logger.onErrorOccurred(error)
            emitAll(
                errorProcessor.process(error)
                    .onEach { logger.onErrorHandled(it) }
            )
        }

    /**
     * Processes FlowHolderAction, expanding it into its stream of actions.
     * For cancelable FlowHolderActions, cancels any previously running
     * flow of the same type before starting the new one.
     * 
     * @param isNested Whether this is a nested FlowHolderAction. Nested actions
     *                 inherit cancellation from their parent and do not create
     *                 their own cancellation flags.
     */
    @Suppress("UNCHECKED_CAST")
    private fun processFlowHolderAction(action: A, isNested: Boolean = false): Flow<A> {
        if (action is FlowHolderAction) {
            val type = action::class
            var myFlag: CancelFlag? = null

            // Only manage cancellation flags for top-level actions
            if (action.cancelable && !isNested) {
                // Cancel previous flow of the same type
                activeFlags[type]?.cancelled = true
                // Create new flag for this flow
                myFlag = CancelFlag()
                activeFlags[type] = myFlag
            }

            return (action.toFlowAction() as Flow<A>)
                .onEach { logger.onFlowHolderActionEmitted(it) }
                .flatMapMerge { processFlowHolderAction(it, isNested = true) }
                .takeWhile { myFlag?.cancelled != true }
        }
        return flowOf(action)
    }

    val state: StateFlow<S> = stateFlow

    val currentState: S get() = stateFlow.value

    fun dispatch(action: A) {
        if (_isClosed) {
            logger.onDispatchAfterClose(action)
            return
        }
        scope.launch {
            try {
                logger.onActionDispatched(action)
                actionFlow.send(action)
            } catch (e: ClosedSendChannelException) {
                // Race condition: close() called between isClosed check and send
                logger.onDispatchAfterClose(action)
            }
        }
    }

    fun close() {
        if (_isClosed) return
        _isClosed = true

        // Cancel all active FlowHolderAction flows
        for (flag in activeFlags.values) {
            flag.cancelled = true
        }
        activeFlags.clear()

        actionFlow.close()
        scope.cancel()
    }

    private fun reduceAction(currentState: S, action: A): S {
        val newState = reducer.reduce(currentState, action)
        logger.onStateReduced(action, currentState, newState)
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
): Store<S, A> =
    Store(
        initialState = initialState,
        reducer = reducer,
        middlewares = middlewares,
        errorProcessor = errorProcessor,
        logger = logger,
        scope = scope,
    )
