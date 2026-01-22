# FlowDux Code Review Instructions

## Project Overview

FlowDux is a reactive state management library for **Kotlin Multiplatform** and **Dart/Flutter**. It provides a unidirectional data flow architecture with middleware support and execution strategies.

### Core Architecture

```
Action → Middleware Chain → FlowHolderAction Expansion → Reducer → State
```

### Key Components

| Component | Kotlin | Dart | Purpose |
|-----------|--------|------|---------|
| Store | `Store<S, A>` | `Store<S, A>` | Central state container |
| Action | `interface Action` | `abstract class Action` | Immutable event/command |
| FlowHolderAction | `interface FlowHolderAction` | `abstract mixin class FlowHolderAction` | Action that emits stream of actions |
| Middleware | `abstract class Middleware` | `class Middleware` | Side effects, async operations |
| Reducer | `Reducer<S, A>` | `Reducer<S, A>` | Pure function: (State, Action) → State |
| ExecutionStrategy | `ExecutionStrategy` | `ExecutionStrategy` | Controls action execution (takeLatest, sequential, etc.) |

---

## Code Review Checklist

### 1. Action Design

- [ ] Actions are **immutable** (use `data class` in Kotlin, immutable fields in Dart)
- [ ] Actions have **descriptive names** indicating intent (e.g., `FetchUserAction`, not `DoAction`)
- [ ] FlowHolderAction correctly implements `toFlowAction()` / `toStreamAction()`
- [ ] FlowHolderAction.strategy is set appropriately:
  - `takeLatest()` (default): For actions where only the latest matters (e.g., search, fetch)
  - `concurrent()`: For actions that can run in parallel (e.g., independent fetches)
  - `sequential()`: For actions that must run in order (e.g., message queue)
  - `takeLeading()`: For actions where first wins (e.g., prevent double-submit)

```kotlin
// Good: TakeLatest search (only latest results matter)
class SearchAction(val query: String) : FlowHolderAction {
    override fun toFlowAction() = flow {
        emit(SearchLoadingAction)
        emit(SearchResultAction(api.search(query)))
    }
    // strategy = takeLatest() (default)
}

// Good: Concurrent batch (all operations run in parallel)
class BatchSaveAction(val items: List<Item>) : FlowHolderAction {
    override val strategy = concurrent()
    override fun toFlowAction() = flow {
        items.forEach { emit(SaveItemAction(it)) }
    }
}

// Good: Sequential processing (maintain order)
class MessageQueueAction(val messages: List<Message>) : FlowHolderAction {
    override val strategy = sequential()
    override fun toFlowAction() = flow {
        messages.forEach { emit(SendMessageAction(it)) }
    }
}
```

### 2. Middleware Implementation

- [ ] Middleware uses `on<ActionType>` for type-safe action handling
- [ ] Async operations use `async*` generators (Dart) or `flow { }` (Kotlin)
- [ ] Execution strategies are applied appropriately:
  - `takeLatest()`: Cancel previous, keep latest (search, autocomplete)
  - `takeLeading()`: Ignore new while processing (prevent double-submit)
  - `sequential()`: Process in order (message queue)
  - `concurrent()`: Run all in parallel (independent operations)
  - `throttle()`: Rate limit (scroll events)
  - `debounce()`: Wait for pause (input validation)
  - `retryWithBackoff()`: Retry with exponential backoff (network calls)

```kotlin
// Good: Appropriate strategy usage
class SearchMiddleware : Middleware<AppState, AppAction>() {
    init {
        apply(takeLatest()).on<SearchAction> { state, action ->
            emit(SearchLoadingAction)
            emit(SearchResultAction(api.search(action.query)))
        }
    }
}
```

- [ ] Middleware does NOT directly modify state (use emitted actions instead)
- [ ] Error handling is implemented with try-catch or `retryWithBackoff()`

### 3. Reducer Implementation

- [ ] Reducer is a **pure function** (no side effects)
- [ ] Reducer handles **all action types** (use exhaustive when/switch)
- [ ] State is copied, not mutated (`copy()` in Kotlin, `copyWith()` in Dart)
- [ ] No async operations in reducer

```kotlin
// Good: Pure reducer
val reducer = Reducer<AppState, AppAction> { state, action ->
    when (action) {
        is LoadingAction -> state.copy(isLoading = true)
        is DataLoadedAction -> state.copy(isLoading = false, data = action.data)
        is ErrorAction -> state.copy(isLoading = false, error = action.message)
        // Handle all cases
    }
}

// Bad: Side effect in reducer
val reducer = Reducer<AppState, AppAction> { state, action ->
    when (action) {
        is SaveAction -> {
            database.save(action.data) // BAD: Side effect!
            state.copy(saved = true)
        }
    }
}
```

### 4. Store Usage

- [ ] Store is created with `createStore()` factory function
- [ ] Store is properly closed when no longer needed (`store.close()`)
- [ ] `dispatch()` is not called after store is closed
- [ ] State subscription is cancelled when component is disposed

```dart
// Good: Proper lifecycle management
class MyWidget extends StatefulWidget {
  @override
  _MyWidgetState createState() => _MyWidgetState();
}

class _MyWidgetState extends State<MyWidget> {
  late StreamSubscription _subscription;

  @override
  void initState() {
    super.initState();
    _subscription = store.state.listen((state) => setState(() {}));
  }

  @override
  void dispose() {
    _subscription.cancel();
    super.dispose();
  }
}
```

### 5. Stream/Flow Handling

- [ ] Infinite streams have proper cancellation mechanism
- [ ] Streams are not leaked (proper cleanup on cancellation)
- [ ] `takeWhile`, `takeUntil` used appropriately for cancellation
- [ ] No blocking operations in stream handlers

```kotlin
// Good: Proper infinite stream with cancellation
class PriceStreamAction : FlowHolderAction {
    override fun toFlowAction() = flow {
        while (currentCoroutineContext().isActive) {
            emit(PriceUpdateAction(fetchPrice()))
            delay(1000)
        }
    }
}
```

---

## Language-Specific Guidelines

### Kotlin

- [ ] Use `data class` for Actions and State
- [ ] Use `sealed interface` for Action hierarchies
- [ ] Use `Flow` for async streams
- [ ] Leverage Kotlin coroutines properly (structured concurrency)
- [ ] Use `StateFlow` for state observation
- [ ] Handle `CancellationException` appropriately (don't catch and ignore)

```kotlin
// Good: Sealed interface for type-safe action handling
sealed interface CounterAction : Action {
    object Increment : CounterAction
    object Decrement : CounterAction
    data class SetValue(val value: Int) : CounterAction
}
```

### Dart

- [ ] Use `abstract mixin class` for FlowHolderAction (allows `with` keyword)
- [ ] Use `async*` for generator functions
- [ ] Use RxDart operators when appropriate (`flatMap`, `switchMap`, etc.)
- [ ] Implement `copyWith()` for state classes
- [ ] Use `ReducerBase` for type-safe reducer building
- [ ] Import with `hide Action` to avoid Flutter conflict

```dart
// Good: Proper FlowHolderAction in Dart
class FetchDataAction with FlowHolderAction {
  final String id;
  FetchDataAction(this.id);

  @override
  Stream<Action> toStreamAction() async* {
    yield LoadingAction();
    try {
      final data = await api.fetch(id);
      yield DataLoadedAction(data);
    } catch (e) {
      yield ErrorAction(e.toString());
    }
  }

  // Override strategy if needed (default is takeLatest)
  // @override
  // ExecutionStrategy get strategy => concurrent();
}
```

---

## Common Issues to Catch

### Critical Issues

1. **Side effects in Reducer**: Reducers must be pure functions
2. **Memory leaks**: Uncancelled subscriptions, streams not closed
3. **Race conditions**: Multiple concurrent operations modifying shared state
4. **Wrong strategy**: FlowHolderAction using wrong execution strategy for its use case

### Performance Issues

1. **Unnecessary state updates**: Emitting same state repeatedly
2. **Missing execution strategy**: Search/autocomplete without `takeLatest()`
3. **Blocking operations**: Synchronous I/O in stream handlers
4. **Excessive middleware chain**: Too many middlewares processing every action

### Code Quality Issues

1. **Non-exhaustive action handling**: Missing cases in reducer
2. **Magic strings/numbers**: Use constants or enums
3. **Overly complex FlowHolderAction**: Consider splitting into multiple actions
4. **Missing error handling**: Unhandled exceptions in middleware

---

## Testing Requirements

### Unit Tests

- [ ] Reducer tests: Verify state transitions for each action
- [ ] Middleware tests: Verify emitted actions and side effects
- [ ] Strategy tests: Verify execution behavior (cancellation, ordering)

### Integration Tests

- [ ] Store tests: Full flow from dispatch to state update
- [ ] FlowHolderAction tests: Stream completion and cancellation
- [ ] Error handling tests: Error processor behavior

### Test Patterns

```kotlin
// Good: Comprehensive reducer test
@Test
fun `reducer handles LoadingAction`() = runTest {
    val initialState = AppState(isLoading = false)
    val newState = reducer.reduce(initialState, LoadingAction)
    assertEquals(true, newState.isLoading)
}

// Good: Middleware test with Turbine
@Test
fun `middleware emits correct actions`() = runTest {
    store.state.test {
        assertEquals(initialState, awaitItem())
        store.dispatch(FetchAction("123"))
        assertEquals(loadingState, awaitItem())
        assertEquals(successState, awaitItem())
    }
}
```

---

## PR Review Focus Areas

1. **Architecture Compliance**: Does the code follow unidirectional data flow?
2. **Strategy Correctness**: Is the right `ExecutionStrategy` used for FlowHolderAction?
3. **Strategy Appropriateness**: Is the right execution strategy used in Middleware?
4. **Resource Management**: Are streams/subscriptions properly cleaned up?
5. **Test Coverage**: Are new features adequately tested?
6. **Cross-Platform Consistency**: Do Kotlin and Dart implementations match in behavior?

---

## References

- [Execution Strategies Blog](docs/blog/)
- Kotlin: `kotlin/flowdux/src/commonMain/kotlin/io/flowdux/`
- Dart: `dart/flowdux/lib/src/`
