# FlowDux

A predictable state management library for Dart with execution strategies.

[![pub package](https://img.shields.io/pub/v/flowdux.svg)](https://pub.dev/packages/flowdux)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## Features

- Redux-style unidirectional data flow
- Middleware support for side effects
- Execution strategies (takeLatest, takeLeading, sequential, debounce, throttle, retry)
- Strategy chaining and groups
- Error handling with ErrorProcessor
- FlowHolderAction for wrapping existing Streams
- `distinct()` for filtering consecutive identical states

## Installation

```yaml
dependencies:
  flowdux: ^0.3.1
```

## Quick Start

### Define State and Actions

```dart
// State
class CounterState {
  final int count;
  CounterState(this.count);

  CounterState copyWith({int? count}) => CounterState(count ?? this.count);
}

// Actions
class IncrementAction implements Action {}
class DecrementAction implements Action {}
class AddAction implements Action {
  final int value;
  AddAction(this.value);
}
```

### Create a Reducer

```dart
final counterReducer = ReducerBuilder<CounterState, Action>()
  ..on<IncrementAction>((state, _) => state.copyWith(count: state.count + 1))
  ..on<DecrementAction>((state, _) => state.copyWith(count: state.count - 1))
  ..on<AddAction>((state, action) => state.copyWith(count: state.count + action.value));
```

### Create and Use the Store

```dart
final store = createStore<CounterState, Action>(
  initialState: CounterState(0),
  reducer: counterReducer.build(),
);

// Dispatch actions
store.dispatch(IncrementAction());

// Listen to state changes
store.state.listen((state) => print('Count: ${state.count}'));
```

## FlowHolderAction

Wrap existing Streams and convert them to Actions:

```dart
// Default: emit - inner actions bypass middlewares (efficient)
class ObserveUser with FlowHolderAction {
  final Stream<User> userStream;
  ObserveUser(this.userStream);

  @override
  Stream<Action> toStreamAction() => userStream.map(SetUser.new);
}

// Dispatch - inner actions pass through full middleware pipeline
class ObserveUserWithLogging with FlowHolderAction {
  final Stream<User> userStream;
  ObserveUserWithLogging(this.userStream);

  @override
  FlowActionDelivery get delivery => FlowActionDelivery.dispatch;

  @override
  Stream<Action> toStreamAction() => userStream.map(SetUser.new);
}
```

| Mode | Behavior | Use Case |
|------|----------|----------|
| `emit` (default) | Inner actions go directly to reducer | Most cases, best performance |
| `dispatch` | Inner actions pass through all middlewares | When middlewares need to observe inner actions |

## Middleware with Execution Strategies

Middleware allows you to handle side effects like API calls. Use execution strategies to control how concurrent actions are processed.

```dart
class SearchMiddleware extends Middleware<AppState, Action> {
  SearchMiddleware() {
    // takeLatest cancels previous search when new one arrives
    apply(takeLatest()).on<SearchAction>((state, action) async* {
      final results = await api.search(action.query);
      yield SearchResultsAction(results);
    });
  }
}

final store = createStore<AppState, Action>(
  initialState: AppState(),
  reducer: appReducer,
  middlewares: [SearchMiddleware()],
);
```

## Execution Strategies

FlowDux provides execution strategies to control how concurrent actions are processed in middleware.

| Category | Strategies | Purpose |
|----------|------------|---------|
| **Concurrency** | `takeLatest()`, `takeLeading()`, `sequential()` | How to handle concurrent executions |
| **Timing** | `debounce(duration)`, `throttle(duration)` | When to execute |
| **Resilience** | `retry(n)`, `retryWithBackoff(...)` | How to handle failures |

### Concurrency Strategies

#### takeLatest()

Cancels previous processing when a new action arrives. Only the latest action's result is emitted.

```dart
apply(takeLatest()).on<SearchAction>((state, action) async* {
  final results = await api.search(action.query);
  yield SearchResultsAction(results);
});
```

Use cases: Search, API refresh, pagination with pull-to-refresh

#### takeLeading()

Ignores new actions while one is still processing. Only the first action in a series executes.

```dart
apply(takeLeading()).on<SubmitAction>((state, action) async* {
  // Prevents duplicate submissions
  final result = await api.submit(action.data);
  yield SubmitSuccessAction(result);
});
```

Use cases: Form submission, payment processing, preventing double-clicks

#### sequential()

Queues actions and processes them one at a time, preserving order.

```dart
apply(sequential()).on<SaveAction>((state, action) async* {
  // All save requests are processed in order
  await api.save(action.data);
  yield SaveCompleteAction(action.id);
});
```

Use cases: Sequential API calls, ordered form saves, FIFO task processing

### Timing Strategies

#### debounce(duration)

Delays execution. If another action arrives before the delay completes, the timer restarts.

```dart
apply(debounce(Duration(milliseconds: 500))).on<TextChangedAction>((state, action) async* {
  // Only saves after user stops typing for 500ms
  await api.save(action.text);
  yield SaveCompleteAction();
});
```

Use cases: Search autocomplete, autosave, input validation

#### throttle(duration)

Limits execution rate. Executes the first action immediately, then ignores subsequent actions until the time window passes.

```dart
apply(throttle(Duration(seconds: 1))).on<ScrollAction>((state, action) async* {
  // Logs scroll position at most once per second
  analytics.logScroll(action.position);
  yield action;
});
```

Use cases: Analytics events, scroll handling, rate limiting

### Resilience Strategies

#### retry(maxAttempts)

Retries the processor execution on failure.

```dart
apply(retry(3)).on<FetchDataAction>((state, action) async* {
  // Retries up to 3 times on failure
  final data = await api.fetchData(action.id);
  yield FetchSuccessAction(data);
});

// With custom retry condition
apply(retry(3, shouldRetry: (e) => e is SocketException)).on<FetchDataAction>((state, action) async* {
  // Only retries on SocketException
});
```

#### retryWithBackoff(...)

Retries with exponential backoff delay between attempts.

```dart
apply(retryWithBackoff(
  maxAttempts: 5,
  initialDelay: Duration(milliseconds: 100),
  maxDelay: Duration(seconds: 10),
  factor: 2.0,      // Exponential multiplier
  jitter: 0.1,      // Random jitter to prevent thundering herd
)).on<FetchDataAction>((state, action) async* {
  final data = await api.fetchData(action.id);
  yield FetchSuccessAction(data);
});
```

Use cases: Network error recovery, transient server errors, rate limiting

### Strategy Chaining

Combine strategies from different categories using the `then` operator:

```dart
// Debounce input, then cancel previous search, then retry on failure
apply(debounce(Duration(milliseconds: 300)).then(takeLatest()).then(retry(3)))
  .on<SearchAction>((state, action) async* {
    final results = await api.search(action.query);
    yield SearchResultsAction(results);
  });
```

**Rules:**
- Strategies from different categories can be chained
- Strategies from the same category cannot be chained (throws exception)

## FlowHolderAction

Use `FlowHolderAction` to wrap existing Streams (Repository, Socket) and convert them to Actions. No side effects in the Action—just holds and transforms the Stream:

```dart
// FlowHolderAction wraps an existing Stream and converts to Stream<Action>
class ObserveUserAction with FlowHolderAction {
  final Stream<User> userStream;

  ObserveUserAction(this.userStream);

  @override
  Stream<Action> toStreamAction() {
    return userStream.map((user) => SetUserAction(user));
  }

  // Default: TakeLatest strategy (auto-cancels previous)
  // Override for concurrent execution:
  // @override
  // ExecutionStrategy get strategy => concurrent();
}

// Usage: pass the Stream from Repository/Socket
final repositoryStream = userRepository.getUser(123);  // Stream creation (cold)
store.dispatch(ObserveUserAction(repositoryStream));   // Store collects it
```

## Error Handling

Use `ErrorProcessor` to catch errors in middleware and convert them to Actions:

```dart
class AppErrorProcessor implements ErrorProcessor<Action> {
  @override
  Stream<Action> process(Object error, StackTrace stackTrace) async* {
    if (error is NetworkException) {
      yield NetworkErrorAction(error.message);
    } else {
      yield UnknownErrorAction(error.toString());
    }
  }
}

final store = createStore<AppState, Action>(
  initialState: AppState(),
  reducer: appReducer,
  middlewares: [AppMiddleware()],
  errorProcessor: AppErrorProcessor(),
);
```

## Filtering Consecutive Identical States

Use `distinct()` to filter out consecutive identical states:

```dart
// Only emits when state actually changes
store.state.distinct().listen((state) {
  print('State changed: $state');
});
```

This is useful when:
- Multiple actions produce the same state
- You want to avoid unnecessary UI rebuilds
- You need to deduplicate state emissions

## Architecture

```
dispatch(action) → Middleware Chain → Reducer → StateFlow
                        ↓
              [Strategy Processing]
              [Error Handling]
              [FlowHolderAction]
```

| Component | Role |
|-----------|------|
| **Middleware** | Side effects (API calls, logging), action transformation |
| **ExecutionStrategy** | Control concurrent action processing |
| **FlowHolderAction** | Convert existing Stream to Action stream |
| **ErrorProcessor** | Catch errors and convert to Actions |
| **Reducer** | Pure function: (State, Action) → NewState |

## Flutter Integration

For Flutter apps, use the [flowdux_flutter](https://pub.dev/packages/flowdux_flutter) package which provides widgets like `StoreProvider`, `StoreBuilder`, `StoreSelector`, `StoreConsumer`, and `StoreListener`.

```yaml
dependencies:
  flowdux: ^0.3.1
  flowdux_flutter: ^0.2.3
```

## Documentation

For full documentation and examples, see the [FlowDux repository](https://github.com/chibimoons/flowdux).

## License

Apache License 2.0
