# FlowDux

A predictable state management library for Dart with execution strategies.

## Features

- Redux-style unidirectional data flow
- Middleware support for side effects
- Execution strategies (takeLatest, takeLeading, sequential, debounce, throttle, retry)
- Strategy chaining and groups
- Error handling with ErrorProcessor
- FlowHolderAction for wrapping existing Streams

## Installation

```yaml
dependencies:
  flowdux: ^0.1.0
```

## Usage

```dart
import 'package:flowdux/flowdux.dart';

// Define State
class CounterState implements State {
  final int count;
  CounterState({this.count = 0});
}

// Define Actions
abstract class CounterAction implements Action {}
class Increment implements CounterAction {}
class Decrement implements CounterAction {}

// Define Reducer
CounterState counterReducer(CounterState state, Action action) {
  if (action is Increment) {
    return CounterState(count: state.count + 1);
  } else if (action is Decrement) {
    return CounterState(count: state.count - 1);
  }
  return state;
}

// Create Store
final store = Store<CounterState, CounterAction>(
  initialState: CounterState(),
  reducer: counterReducer,
);

// Observe state changes
store.state.listen((state) {
  print('Count: ${state.count}');
});

// Dispatch actions
store.dispatch(Increment());
```

## Middleware with Execution Strategies

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
```

## Documentation

For full documentation, see the [FlowDux repository](https://github.com/chibimoons/flowdux).

## License

Apache License 2.0
