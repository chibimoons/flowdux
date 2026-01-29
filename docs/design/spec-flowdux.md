# FlowDux Specification

Single Source of Truth for FlowDux API

Version: 1.0.0
Based on: Kotlin Implementation (main + feature/strategy-chaining)

---

## 1. Core Interfaces

### 1.1 State

Marker interface for state objects.

```kotlin
interface State
```

**Requirements:**
- All state classes must implement this interface
- State should be immutable (data class recommended)
- State changes only through Reducer

---

### 1.2 Action

Base interface for all actions.

```kotlin
interface Action
```

**Requirements:**
- All action classes must implement this interface
- Actions should be immutable (data class or sealed class recommended)
- Actions represent events/intents in the system

---

### 1.3 FlowHolderAction

Action that emits multiple actions via reactive stream.

```kotlin
interface FlowHolderAction : Action {
    fun toFlowAction(): Flow<Action>
}
```

**Behavior:**
- When dispatched, the Store automatically subscribes to the Flow
- Each emitted action is dispatched to the Store individually
- Logger receives `onFlowHolderActionEmitted` callback for each emitted action
- Useful for batching multiple actions or async action sequences

**Use Cases:**
- Batch updates (e.g., `BatchAction` emitting multiple update actions)
- Fetch-then-process patterns (e.g., `FetchAndProcessAction`)

---

## 2. Reducer

Pure function that transforms state based on action.

### 2.1 Reducer Interface

```kotlin
fun interface Reducer<S : State, A : Action> {
    fun reduce(state: S, action: A): S
}
```

**Contract:**
- MUST be a pure function (no side effects)
- MUST return same state if action is not handled
- MUST NOT throw exceptions

### 2.2 ReducerBuilder DSL

```kotlin
class ReducerBuilder<S : State, A : Action> {
    inline fun <reified T : A> on(noinline handler: (S, T) -> S)
    fun build(): Reducer<S, A>
}

fun <S : State, A : Action> buildReducer(
    block: ReducerBuilder<S, A>.() -> Unit
): Reducer<S, A>
```

**Usage:**
```kotlin
val reducer = buildReducer<AppState, AppAction> {
    on<IncrementAction> { state, _ -> state.copy(count = state.count + 1) }
    on<SetValueAction> { state, action -> state.copy(value = action.value) }
}
```

**Behavior:**
- Actions not registered return the original state unchanged
- Type-safe action dispatching via reified type parameter

---

## 3. Middleware

Side effect handler for actions.

### 3.1 Middleware Interface

```kotlin
interface Middleware<S : State, A : Action> {
    val name: String  // Default: class name
    val processors: ActionProcessorMap<S, A>

    fun process(getState: () -> S, action: A): Flow<A>
}
```

**Type Aliases:**
```kotlin
typealias ActionProcessor<S, A> = suspend FlowCollector<A>.(S, A) -> Unit
typealias ActionProcessorMap<S, A> = Map<KClass<*>, ActionProcessor<S, A>>
```

**Default Behavior:**
- If no processor registered for action type, emits the original action
- Processors can emit zero, one, or multiple actions
- Processors have access to current state via `getState()`

### 3.2 ActionProcessorBuilder DSL

```kotlin
class ActionProcessorBuilder<S, A> {
    // Basic registration
    inline fun <reified T : A> on(
        noinline processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit
    )

    // Without parameters
    inline fun <reified T : A> on(
        noinline processor: suspend FlowCollector<A>.() -> Unit
    )

    // With execution strategy
    inline fun <reified T : A> on(
        strategy: ExecutionStrategy,
        noinline processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit
    )

    // Strategy without parameters
    inline fun <reified T : A> on(
        strategy: ExecutionStrategy,
        noinline processor: suspend FlowCollector<A>.() -> Unit
    )

    // Strategy group
    fun group(strategy: ExecutionStrategy, block: StrategyGroupBuilder<S, A>.() -> Unit)

    fun build(): ActionProcessorMap<S, A>
}
```

**Rules:**
- Throws `DuplicateProcessorException` if same action type registered twice
- Processor emits actions via `emit()` function on FlowCollector

### 3.3 StrategyGroupBuilder

```kotlin
class StrategyGroupBuilder<S, A>(
    strategy: ExecutionStrategy,
    processors: MutableMap<KClass<*>, ActionProcessor<S, A>>
) {
    inline fun <reified T : A> on(
        noinline processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit
    )

    inline fun <reified T : A> on(
        noinline processor: suspend FlowCollector<A>.() -> Unit
    )
}
```

**Purpose:**
- Groups multiple action processors under a shared strategy instance
- Actions in same group share strategy state (e.g., cancellation, throttle window)

**Usage:**
```kotlin
val middleware = object : Middleware<AppState, AppAction> {
    override val processors = buildProcessors {
        group(takeLatest()) {
            on<SearchAction> { state, action -> ... }
            on<RefreshAction> { state, action -> ... }
        }
    }
}
```

### 3.4 DuplicateProcessorException

```kotlin
class DuplicateProcessorException(actionClass: KClass<*>) : IllegalArgumentException
```

Message format: `"Processor for action type '${actionClass.simpleName}' is already registered. Each action type can only have one processor per middleware."`

---

## 4. Execution Strategy

Controls how action processors handle concurrent executions.

### 4.1 ExecutionStrategy Interface

```kotlin
sealed interface ExecutionStrategy {
    val category: StrategyCategory

    fun <S, A, T : A> wrap(
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit
    ): suspend FlowCollector<A>.(state: S, action: T) -> Unit
}
```

### 4.2 StrategyCategory

```kotlin
enum class StrategyCategory {
    TIMING,      // Controls WHEN to execute (debounce, throttle)
    CONCURRENCY, // Controls HOW to handle concurrent executions (takeLatest, takeLeading, sequential)
    RESILIENCE,  // Controls HOW to handle failures (retry, retryWithBackoff)
    CHAINED      // Composed of multiple strategies
}
```

**Purpose:**
- Used for validation during strategy chaining
- Prevents chaining strategies of the same category

### 4.3 Concurrency Strategies

#### TakeLatest
Cancels previous execution when a new action arrives.

```kotlin
class TakeLatest : ExecutionStrategy {
    override val category = StrategyCategory.CONCURRENCY
}

fun takeLatest(): ExecutionStrategy
```

**Behavior:**
- Only the latest action's result will be emitted
- Previous in-flight executions are cancelled
- Throws CancellationException to previous processors

#### TakeLeading
Ignores new actions while one is processing.

```kotlin
class TakeLeading : ExecutionStrategy {
    override val category = StrategyCategory.CONCURRENCY
}

fun takeLeading(): ExecutionStrategy
```

**Behavior:**
- First action in a series executes
- Subsequent actions are silently ignored until first completes
- No cancellation, just dropping

#### Sequential
Queues actions and processes them one at a time.

```kotlin
class Sequential : ExecutionStrategy {
    override val category = StrategyCategory.CONCURRENCY
}

fun sequential(): ExecutionStrategy
```

**Behavior:**
- Actions are queued and processed in order
- Unlike TakeLeading, does NOT ignore new actions
- Waits for current action to complete before processing next
- Uses Mutex for ordering guarantee

### 4.4 Timing Strategies

#### Debounce
Delays execution until no new actions arrive.

```kotlin
class Debounce(duration: Duration) : ExecutionStrategy {
    override val category = StrategyCategory.TIMING
}

fun debounce(duration: Duration): ExecutionStrategy
fun debounce(timeMs: Long): ExecutionStrategy
```

**Behavior:**
- Waits for `duration` after last action
- If new action arrives during wait, timer restarts
- Previous pending action is cancelled
- Only executes after quiet period

**Parameters:**
- `duration`: Debounce delay (Duration or milliseconds)

#### Throttle
Limits execution rate.

```kotlin
class Throttle(duration: Duration) : ExecutionStrategy {
    override val category = StrategyCategory.TIMING
}

fun throttle(duration: Duration): ExecutionStrategy
fun throttle(timeMs: Long): ExecutionStrategy
```

**Behavior:**
- Executes first action immediately
- Ignores subsequent actions until time window passes
- Uses monotonic time source for accurate timing

**Parameters:**
- `duration`: Throttle window (Duration or milliseconds)

### 4.5 Resilience Strategies

#### Retry
Retries failed executions.

```kotlin
class Retry(
    maxAttempts: Int,
    retryIf: (Throwable) -> Boolean = { true }
) : ExecutionStrategy {
    override val category = StrategyCategory.RESILIENCE
}

fun retry(
    maxAttempts: Int,
    retryIf: (Throwable) -> Boolean = { true }
): ExecutionStrategy
```

**Behavior:**
- Retries processor execution on failure
- CancellationException is NEVER retried (always rethrown)
- Throws original exception after all attempts exhausted

**Parameters:**
- `maxAttempts`: Total attempts including initial (must be >= 1)
- `retryIf`: Predicate to determine if exception should trigger retry (default: all)

#### RetryWithBackoff
Retries with exponential backoff.

```kotlin
class RetryWithBackoff(
    maxAttempts: Int,
    initialDelay: Duration,
    maxDelay: Duration = Duration.INFINITE,
    factor: Double = 2.0,
    jitter: Double = 0.0,
    retryIf: (Throwable) -> Boolean = { true }
) : ExecutionStrategy {
    override val category = StrategyCategory.RESILIENCE
}

fun retryWithBackoff(
    maxAttempts: Int,
    initialDelay: Duration,
    maxDelay: Duration = Duration.INFINITE,
    factor: Double = 2.0,
    jitter: Double = 0.0,
    retryIf: (Throwable) -> Boolean = { true }
): ExecutionStrategy
```

**Delay Formula:**
```
baseDelay = initialDelay * (factor ^ attempt)
cappedDelay = min(baseDelay, maxDelay)
jitterAmount = cappedDelay * jitter * random(-1, 1)
finalDelay = max(cappedDelay + jitterAmount, 0)
```

**Parameters:**
- `maxAttempts`: Total attempts including initial (must be >= 1)
- `initialDelay`: Delay before first retry
- `maxDelay`: Cap for exponential growth (default: infinite)
- `factor`: Exponential multiplier (must be >= 1.0, default: 2.0)
- `jitter`: Random jitter factor 0.0-1.0 (default: 0.0)
- `retryIf`: Predicate for retryable exceptions

### 4.6 Strategy Chaining

#### ChainedStrategy

```kotlin
class ChainedStrategy(
    first: ExecutionStrategy,
    second: ExecutionStrategy
) : ExecutionStrategy {
    override val category = StrategyCategory.CHAINED
}

infix fun ExecutionStrategy.then(next: ExecutionStrategy): ExecutionStrategy
```

**Behavior:**
- First strategy wraps second (first = outer layer)
- Strategies of same category CANNOT be chained
- Validates recursively for nested chains
- Throws `IllegalArgumentException` on category conflict

**Example:**
```kotlin
// debounce first, then takeLatest
val strategy = debounce(300.milliseconds) then takeLatest()

// With retry
val resilientStrategy = debounce(300.milliseconds) then takeLatest() then retry(3)
```

**Validation Error Message:**
```
"Cannot chain strategies of the same category. Conflicting category: ${category}. First: ${firstClassName}, Second: ${secondClassName}"
```

---

## 5. Error Processor

Handles errors in the middleware chain.

### 5.1 ErrorProcessor Interface

```kotlin
interface ErrorProcessor<A: Action> {
    fun process(throwable: Throwable): Flow<A>
}
```

**Contract:**
- Called when exception occurs in middleware processing
- Can emit zero or more recovery actions
- Empty flow means error is swallowed

### 5.2 DefaultErrorProcessor

```kotlin
class DefaultErrorProcessor<A: Action> : ErrorProcessor<A> {
    override fun process(throwable: Throwable): Flow<A> = emptyFlow()
}
```

**Behavior:**
- Swallows all errors (emits nothing)
- Used as default when no error processor provided

---

## 6. Store Logger

Debugging and monitoring interface.

### 6.1 StoreLogger Interface

```kotlin
interface StoreLogger<S : State, A : Action> {
    fun onActionDispatched(action: A)
    fun onMiddlewareProcessing(middlewareName: String, action: A)
    fun onMiddlewaresCompleted(action: A)
    fun onFlowHolderActionEmitted(action: A)
    fun onErrorOccurred(throwable: Throwable)
    fun onErrorHandled(action: A)
    fun onStateReduced(action: A, previousState: S, newState: S)
    fun onDispatchAfterClose(action: A)
}
```

**Callback Descriptions:**

| Method | When Called |
|--------|-------------|
| `onActionDispatched` | When `dispatch()` is called (before processing) |
| `onMiddlewareProcessing` | Before each middleware processes an action |
| `onMiddlewaresCompleted` | After all middlewares finish processing |
| `onFlowHolderActionEmitted` | For each action emitted by FlowHolderAction |
| `onErrorOccurred` | When exception occurs in middleware chain |
| `onErrorHandled` | For each action emitted by ErrorProcessor |
| `onStateReduced` | After reducer produces new state |
| `onDispatchAfterClose` | When dispatch called on closed store |

### 6.2 NoOpStoreLogger

```kotlin
open class NoOpStoreLogger<S : State, A : Action> : StoreLogger<S, A>
```

**Behavior:**
- All methods are empty (no-op)
- Default logger when none provided
- `open` class for selective override

### 6.3 DebugStoreLogger

```kotlin
class DebugStoreLogger<S : State, A : Action>(
    tag: String = "Store"
) : StoreLogger<S, A>
```

**Behavior:**
- Prints all events to console with `[$tag]` prefix
- Useful for development debugging

---

## 7. Store

Central state container.

### 7.1 Store Class

```kotlin
class Store<S : State, A : Action>(
    initialState: S,
    reducer: Reducer<S, A>,
    middlewares: List<Middleware<S, A>>,
    errorProcessor: ErrorProcessor<A>,
    logger: StoreLogger<S, A>,
    scope: CoroutineScope
) {
    val state: StateFlow<S>
    val currentState: S
    val isClosed: Boolean

    fun dispatch(action: A)
    fun close()
}
```

**Properties:**
- `state`: Reactive state stream (StateFlow)
- `currentState`: Synchronous state access
- `isClosed`: Whether store has been closed

**Methods:**
- `dispatch(action)`: Dispatches action for processing
- `close()`: Closes the store and cancels scope

### 7.2 Action Processing Pipeline

```
dispatch(action)
    ↓
logger.onActionDispatched(action)
    ↓
[Middleware 1] → logger.onMiddlewareProcessing("Middleware1", action)
    ↓
[Middleware 2] → logger.onMiddlewareProcessing("Middleware2", action)
    ↓
[Middleware N] → ...
    ↓
logger.onMiddlewaresCompleted(resultAction)
    ↓
[If FlowHolderAction] → Subscribe to flow, dispatch each emitted action
    ↓                    logger.onFlowHolderActionEmitted(emittedAction)
[If error] → logger.onErrorOccurred(error)
    ↓        errorProcessor.process(error)
             logger.onErrorHandled(recoveryAction)
    ↓
reducer.reduce(currentState, action)
    ↓
logger.onStateReduced(action, previousState, newState)
    ↓
state updated
```

**Processing Characteristics:**
- Middlewares are processed sequentially (flatMapConcat)
- Multiple result actions are processed concurrently (flatMapMerge)
- Errors in middleware chain are caught and sent to ErrorProcessor

### 7.3 createStore Factory

```kotlin
fun <S : State, A : Action> createStore(
    initialState: S,
    middlewares: List<Middleware<S, A>> = emptyList(),
    reducer: Reducer<S, A>,
    errorProcessor: ErrorProcessor<A> = DefaultErrorProcessor(),
    logger: StoreLogger<S, A> = NoOpStoreLogger(),
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
): Store<S, A>
```

**Default Values:**
- `middlewares`: Empty list
- `errorProcessor`: DefaultErrorProcessor (swallows errors)
- `logger`: NoOpStoreLogger (silent)
- `scope`: SupervisorJob + Dispatchers.Default

### 7.4 Dispatch After Close Behavior

```kotlin
fun dispatch(action: A) {
    if (_isClosed) {
        logger.onDispatchAfterClose(action)
        return  // Silently returns, no exception
    }
    // ... normal processing
}
```

**Behavior:**
- Dispatch on closed store is logged but not an error
- Action is silently dropped
- Handles race condition with ClosedSendChannelException

---

## 8. Platform Considerations

### 8.1 Kotlin Multiplatform

FlowDux uses Kotlin Multiplatform common code:
- `kotlinx.coroutines.flow.Flow` for reactive streams
- `kotlinx.coroutines.sync.Mutex` for synchronization
- `kotlin.time.Duration` for time durations
- `kotlin.time.TimeSource.Monotonic` for accurate timing
- `kotlin.reflect.KClass` for type-safe registration

### 8.2 Threading Model

- Default dispatcher: `Dispatchers.Default`
- All strategy implementations use `Mutex` for thread safety
- Safe for multi-threaded environments

### 8.3 Porting Guidelines

When porting to other platforms:

1. **Reactive Streams**: Map Flow to platform equivalent (e.g., Dart Stream, RxJS Observable)
2. **Synchronization**: Implement appropriate locking for platform (e.g., Completer-based AsyncLock for Dart)
3. **Time**: Use platform monotonic time source for Throttle
4. **Type System**: Map KClass to platform reflection/type system
5. **Coroutines**: Map to platform async primitives (e.g., async/await, Future)

---

## 9. Version History

| Version | Changes |
|---------|---------|
| 1.0.0 | Initial specification based on Kotlin implementation |

---

## 10. Appendix: Complete API Summary

### Interfaces
- `State`
- `Action`
- `FlowHolderAction`
- `Reducer<S, A>`
- `Middleware<S, A>`
- `ExecutionStrategy`
- `ErrorProcessor<A>`
- `StoreLogger<S, A>`

### Classes
- `ReducerBuilder<S, A>`
- `ActionProcessorBuilder<S, A>`
- `StrategyGroupBuilder<S, A>`
- `DuplicateProcessorException`
- `TakeLatest`
- `TakeLeading`
- `Sequential`
- `Debounce`
- `Throttle`
- `Retry`
- `RetryWithBackoff`
- `ChainedStrategy`
- `DefaultErrorProcessor<A>`
- `NoOpStoreLogger<S, A>`
- `DebugStoreLogger<S, A>`
- `Store<S, A>`

### Enums
- `StrategyCategory` (TIMING, CONCURRENCY, RESILIENCE, CHAINED)

### Factory Functions
- `buildReducer<S, A>(block)`
- `takeLatest()`
- `takeLeading()`
- `sequential()`
- `debounce(duration)` / `debounce(timeMs)`
- `throttle(duration)` / `throttle(timeMs)`
- `retry(maxAttempts, retryIf?)`
- `retryWithBackoff(maxAttempts, initialDelay, maxDelay?, factor?, jitter?, retryIf?)`
- `createStore<S, A>(...)`

### Extension Functions
- `ExecutionStrategy.then(next): ExecutionStrategy`
