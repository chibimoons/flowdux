import 'dart:async';

import 'package:flowdux/flowdux.dart';
import 'package:test/test.dart';

/// Tests for emitting multiple FlowHolderActions from within a middleware processor.
///
/// These tests verify that the middleware chain uses flatMap (not asyncExpand)
/// to allow concurrent processing of emitted actions. This prevents blocking when
/// a middleware emits FlowHolderActions with infinite streams.

// Test Actions
class StartMultipleObserversAction implements Action {}

class SetupCompleteAction implements Action {
  final DateTime timestamp;
  SetupCompleteAction(this.timestamp);
}

class IncrementAction implements Action {}

class AddAction implements Action {
  final int value;
  AddAction(this.value);
}

// FlowHolderAction that emits increment actions infinitely
class InfiniteObserverFlowAction with FlowHolderAction {
  final String id;
  final Duration emitInterval;

  InfiniteObserverFlowAction(
    this.id, {
    this.emitInterval = const Duration(milliseconds: 50),
  });

  @override
  Stream<Action> toStreamAction() async* {
    while (true) {
      await Future.delayed(emitInterval);
      yield IncrementAction();
    }
  }
}

// FlowHolderAction that emits add(10) actions infinitely
class SecondaryObserverFlowAction with FlowHolderAction {
  final String id;
  final Duration emitInterval;

  SecondaryObserverFlowAction(
    this.id, {
    this.emitInterval = const Duration(milliseconds: 50),
  });

  @override
  Stream<Action> toStreamAction() async* {
    while (true) {
      await Future.delayed(emitInterval);
      yield AddAction(10);
    }
  }
}

// Test State
class AppState {
  final int count;

  AppState({this.count = 0});

  AppState copyWith({int? count}) => AppState(count: count ?? this.count);

  @override
  String toString() => 'AppState(count: $count)';
}

// Test Reducer
class AppReducer extends ReducerBase<AppState, Action> {
  bool setupCompleteReceived = false;

  AppReducer() {
    on<IncrementAction>((state, _) => state.copyWith(count: state.count + 1));
    on<AddAction>(
      (state, action) => state.copyWith(count: state.count + action.value),
    );
    on<SetupCompleteAction>((state, action) {
      setupCompleteReceived = true;
      return state;
    });
  }
}

/// Middleware that emits multiple FlowHolderActions when StartMultipleObserversAction is dispatched.
/// This simulates the real-world scenario where an app starts observing multiple data streams.
class MultiObserverMiddleware extends Middleware<AppState, Action> {
  MultiObserverMiddleware() {
    on<StartMultipleObserversAction>((state, action) async* {
      // Emit first infinite FlowHolderAction
      yield InfiniteObserverFlowAction(
        'observer1',
        emitInterval: Duration(milliseconds: 50),
      );

      // Emit second infinite FlowHolderAction
      // With flatMap, this executes concurrently (not blocked by first yield)
      yield SecondaryObserverFlowAction(
        'observer2',
        emitInterval: Duration(milliseconds: 50),
      );

      // Emit a marker action to indicate setup is complete
      // With flatMap, this also executes without blocking
      yield SetupCompleteAction(DateTime.now());
    });
  }
}

void main() {
  group('Middleware Emit FlowHolderAction Tests', () {
    test(
      'emitting multiple FlowHolderActions from middleware should not block',
      () async {
        final appReducer = AppReducer();
        final store = createStore<AppState, Action>(
          initialState: AppState(),
          reducer: appReducer.reducer,
          middlewares: [MultiObserverMiddleware()],
        );

        // Dispatch action that triggers multiple FlowHolderAction emissions
        store.dispatch(StartMultipleObserversAction());

        // We should see emissions from BOTH streams
        // InfiniteObserverFlowAction adds 1 per emission
        // SecondaryObserverFlowAction adds 10 per emission
        // If both are running, we should see both +1 and +10 increments

        var sawIncrementByOne = false;
        var sawIncrementByTen = false;
        var previousCount = 0;

        // Listen to state changes
        final subscription = store.state.listen((state) {
          final increment = state.count - previousCount;
          if (increment == 1) sawIncrementByOne = true;
          if (increment == 10) sawIncrementByTen = true;
          previousCount = state.count;
        });

        // Wait for some emissions (with timeout to detect blocking)
        await Future.delayed(Duration(milliseconds: 500));

        await subscription.cancel();
        await store.close();

        expect(
          sawIncrementByOne && sawIncrementByTen,
          isTrue,
          reason: 'Expected both FlowHolderActions to run concurrently. '
              'sawIncrementByOne=$sawIncrementByOne, sawIncrementByTen=$sawIncrementByTen. '
              'If only sawIncrementByOne=true, the second yield was blocked.',
        );
      },
    );

    test('code after yielding FlowHolderAction should execute', () async {
      final appReducer = AppReducer();
      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: appReducer.reducer,
        middlewares: [MultiObserverMiddleware()],
      );

      store.dispatch(StartMultipleObserversAction());

      // Wait for some emissions
      await Future.delayed(Duration(milliseconds: 500));

      await store.close();

      expect(
        appReducer.setupCompleteReceived,
        isTrue,
        reason:
            'SetupCompleteAction should have been yielded after the FlowHolderActions, '
            'but the code after yield was blocked.',
      );
    });
  });
}
