package io.flowdux.timetravel

import io.flowdux.Action
import io.flowdux.DefaultErrorProcessor
import io.flowdux.ErrorProcessor
import io.flowdux.Middleware
import io.flowdux.NoOpStoreLogger
import io.flowdux.Reducer
import io.flowdux.State
import io.flowdux.Store
import io.flowdux.createStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

class TimeTravelStore<S : State, A : Action> internal constructor(
    private val innerStore: Store<S, A>,
    private val maxHistorySize: Int,
    initialState: S?,
    initialHistory: List<StateSnapshot<S, A>>?,
) {
    private val mutex = Mutex()

    private val _history = mutableListOf<StateSnapshot<S, A>>()
    val history: List<StateSnapshot<S, A>> get() = _history.toList()

    private var _currentIndex = 0
    val currentIndex: Int get() = _currentIndex

    private val _state: MutableStateFlow<S>
    val state: StateFlow<S> get() = _state.asStateFlow()

    val currentState: S get() = _state.value

    val isClosed: Boolean get() = innerStore.isClosed

    val canUndo: Boolean get() = _currentIndex > 0
    val canRedo: Boolean get() = _currentIndex < _history.size - 1

    init {
        when {
            !initialHistory.isNullOrEmpty() -> {
                _history.addAll(
                    initialHistory.mapIndexed { idx, snapshot ->
                        snapshot.copy(index = idx)
                    },
                )
                _currentIndex = _history.size - 1
                _state = MutableStateFlow(_history.last().currentState)
            }
            initialState != null -> {
                _history.add(
                    StateSnapshot(
                        index = 0,
                        action = null,
                        previousState = null,
                        currentState = initialState,
                        timestamp = currentTimeMillis(),
                    ),
                )
                _state = MutableStateFlow(initialState)
            }
            else -> error("Either initialState or initialHistory must be provided")
        }
    }

    internal fun recordStateChange(action: A, previousState: S, newState: S) {
        if (_currentIndex < _history.size - 1) {
            while (_history.size > _currentIndex + 1) {
                _history.removeAt(_history.size - 1)
            }
        }

        val newIndex = _history.size
        _history.add(
            StateSnapshot(
                index = newIndex,
                action = action,
                previousState = previousState,
                currentState = newState,
                timestamp = currentTimeMillis(),
            ),
        )

        while (_history.size > maxHistorySize && _history.size > 1) {
            _history.removeAt(0)
            _history.forEachIndexed { idx, snapshot ->
                if (snapshot.index != idx) {
                    _history[idx] = snapshot.copy(index = idx)
                }
            }
        }

        _currentIndex = _history.size - 1
        _state.value = newState
    }

    fun dispatch(action: A) {
        innerStore.dispatch(action)
    }

    suspend fun undo(): Boolean = mutex.withLock {
        if (!canUndo) return false
        _currentIndex--
        _state.value = _history[_currentIndex].currentState
        true
    }

    suspend fun redo(): Boolean = mutex.withLock {
        if (!canRedo) return false
        _currentIndex++
        _state.value = _history[_currentIndex].currentState
        true
    }

    suspend fun jumpTo(index: Int): Boolean = mutex.withLock {
        if (index < 0 || index >= _history.size) return false
        _currentIndex = index
        _state.value = _history[_currentIndex].currentState
        true
    }

    suspend fun reset(): Boolean = jumpTo(0)

    suspend fun clear() = mutex.withLock {
        val currentState = _state.value
        _history.clear()
        _history.add(
            StateSnapshot(
                index = 0,
                action = null,
                previousState = null,
                currentState = currentState,
                timestamp = currentTimeMillis(),
            ),
        )
        _currentIndex = 0
    }

    fun close() {
        innerStore.close()
    }
}

fun <S : State, A : Action> createTimeTravelStore(
    initialState: S,
    reducer: Reducer<S, A>,
    middlewares: List<Middleware<S, A>> = emptyList(),
    errorProcessor: ErrorProcessor<A> = DefaultErrorProcessor(),
    maxHistorySize: Int = 100,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): TimeTravelStore<S, A> = createTimeTravelStoreInternal(
    initialState = initialState,
    initialHistory = null,
    reducer = reducer,
    middlewares = middlewares,
    errorProcessor = errorProcessor,
    maxHistorySize = maxHistorySize,
    scope = scope,
)

fun <S : State, A : Action> createTimeTravelStore(
    initialHistory: List<StateSnapshot<S, A>>,
    reducer: Reducer<S, A>,
    middlewares: List<Middleware<S, A>> = emptyList(),
    errorProcessor: ErrorProcessor<A> = DefaultErrorProcessor(),
    maxHistorySize: Int = 100,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
): TimeTravelStore<S, A> {
    require(initialHistory.isNotEmpty()) { "initialHistory must not be empty" }
    return createTimeTravelStoreInternal(
        initialState = null,
        initialHistory = initialHistory,
        reducer = reducer,
        middlewares = middlewares,
        errorProcessor = errorProcessor,
        maxHistorySize = maxHistorySize,
        scope = scope,
    )
}

private fun <S : State, A : Action> createTimeTravelStoreInternal(
    initialState: S?,
    initialHistory: List<StateSnapshot<S, A>>?,
    reducer: Reducer<S, A>,
    middlewares: List<Middleware<S, A>>,
    errorProcessor: ErrorProcessor<A>,
    maxHistorySize: Int,
    scope: CoroutineScope,
): TimeTravelStore<S, A> {
    lateinit var timeTravelStore: TimeTravelStore<S, A>

    val timeTravelReducer =
        Reducer<S, A> { _, action ->
            reducer.reduce(timeTravelStore.currentState, action)
        }

    val historyLogger =
        object : NoOpStoreLogger<S, A>() {
            override fun onStateReduced(action: A, previousState: S, newState: S) {
                timeTravelStore.recordStateChange(action, timeTravelStore.currentState, newState)
            }
        }

    val effectiveInitialState = initialHistory?.lastOrNull()?.currentState ?: initialState!!

    val innerStore =
        createStore(
            initialState = effectiveInitialState,
            reducer = timeTravelReducer,
            middlewares = middlewares,
            errorProcessor = errorProcessor,
            logger = historyLogger,
            scope = scope,
        )

    timeTravelStore =
        TimeTravelStore(
            innerStore = innerStore,
            maxHistorySize = maxHistorySize,
            initialState = initialState,
            initialHistory = initialHistory,
        )

    return timeTravelStore
}

private fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
