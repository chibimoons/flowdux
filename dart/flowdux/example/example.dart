/// FlowDux Example
///
/// This example demonstrates the core concepts of FlowDux:
/// - Creating a Store with state, reducer, and middleware
/// - Dispatching actions to update state
/// - Using FlowHolderAction for async operations
/// - Applying execution strategies
library;

import 'dart:async';

import 'package:flowdux/flowdux.dart';

// =============================================================================
// State
// =============================================================================

class CounterState {
  final int count;
  final bool isLoading;

  const CounterState({this.count = 0, this.isLoading = false});

  CounterState copyWith({int? count, bool? isLoading}) {
    return CounterState(
      count: count ?? this.count,
      isLoading: isLoading ?? this.isLoading,
    );
  }

  @override
  String toString() => 'CounterState(count: $count, isLoading: $isLoading)';
}

// =============================================================================
// Actions
// =============================================================================

sealed class CounterAction implements Action {}

class IncrementAction implements CounterAction {}

class DecrementAction implements CounterAction {}

class SetCountAction implements CounterAction {
  final int count;
  SetCountAction(this.count);
}

class SetLoadingAction implements CounterAction {
  final bool isLoading;
  SetLoadingAction(this.isLoading);
}

/// FlowHolderAction example - emits multiple actions over time
class IncrementAsyncAction with FlowHolderAction implements CounterAction {
  @override
  Stream<Action> toStreamAction() async* {
    yield SetLoadingAction(true);
    await Future<void>.delayed(const Duration(milliseconds: 100));
    yield IncrementAction();
    yield SetLoadingAction(false);
  }
}

/// FlowHolderAction with takeLatest strategy (default)
class SearchAction with FlowHolderAction implements CounterAction {
  final String query;
  SearchAction(this.query);

  @override
  Stream<Action> toStreamAction() async* {
    yield SetLoadingAction(true);
    // Simulate API call
    await Future<void>.delayed(const Duration(milliseconds: 200));
    yield SetCountAction(query.length);
    yield SetLoadingAction(false);
  }

  // Default strategy is takeLatest - previous search cancelled when new one starts
}

/// FlowHolderAction with concurrent strategy
class BackgroundSyncAction with FlowHolderAction implements CounterAction {
  @override
  ExecutionStrategy get strategy => concurrent();

  @override
  Stream<Action> toStreamAction() async* {
    await Future<void>.delayed(const Duration(milliseconds: 50));
    yield IncrementAction();
  }
}

// =============================================================================
// Reducer
// =============================================================================

CounterState counterReducer(CounterState state, CounterAction action) {
  return switch (action) {
    IncrementAction() => state.copyWith(count: state.count + 1),
    DecrementAction() => state.copyWith(count: state.count - 1),
    SetCountAction(:final count) => state.copyWith(count: count),
    SetLoadingAction(:final isLoading) => state.copyWith(isLoading: isLoading),
    // FlowHolderActions are processed by FlowHolderMiddleware, not reducer
    IncrementAsyncAction() => state,
    SearchAction() => state,
    BackgroundSyncAction() => state,
  };
}

// =============================================================================
// Middleware (optional)
// =============================================================================

class LoggingMiddleware extends Middleware<CounterState, CounterAction> {
  LoggingMiddleware() {
    // Log all increment actions
    on<IncrementAction>((state, action) async* {
      print('Incrementing from ${state.count}');
      yield action;
    });
  }
}

// =============================================================================
// Main
// =============================================================================

void main() async {
  // Create the store
  final store = createStore<CounterState, CounterAction>(
    initialState: const CounterState(),
    reducer: counterReducer,
    middlewares: [LoggingMiddleware()],
  );

  // Subscribe to state changes
  final subscription = store.state.listen((state) {
    print('State changed: $state');
  });

  // Dispatch simple actions
  print('\n--- Simple Actions ---');
  store.dispatch(IncrementAction());
  store.dispatch(IncrementAction());
  store.dispatch(DecrementAction());

  // Wait for actions to process
  await Future<void>.delayed(const Duration(milliseconds: 50));

  // Dispatch FlowHolderAction (async)
  print('\n--- FlowHolderAction (Async) ---');
  store.dispatch(IncrementAsyncAction());

  // Wait for async action to complete
  await Future<void>.delayed(const Duration(milliseconds: 200));

  // Dispatch concurrent actions
  print('\n--- Concurrent Actions ---');
  store.dispatch(BackgroundSyncAction());
  store.dispatch(BackgroundSyncAction());
  store.dispatch(BackgroundSyncAction());

  // Wait for all actions to complete
  await Future<void>.delayed(const Duration(milliseconds: 200));

  print('\n--- Final State ---');
  print('Final count: ${store.currentState.count}');

  // Clean up
  await subscription.cancel();
  await store.close();
}
