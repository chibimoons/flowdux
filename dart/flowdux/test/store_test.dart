import 'dart:async';

import 'package:flowdux/flowdux.dart';
import 'package:test/test.dart';

// Test Actions
class TestAction implements Action {}

class IncrementAction implements Action {}

class DecrementAction implements Action {}

class SetValueAction implements Action {
  final int value;
  SetValueAction(this.value);
}

class UnhandledAction implements Action {}

// FlowHolderAction implementations
// Using 'with' to inherit default cancelable value
class BatchAction with FlowHolderAction {
  final List<Action> actions;
  BatchAction(this.actions);

  @override
  Stream<Action> toStreamAction() => Stream.fromIterable(actions);

  @override
  bool get cancelable => false; // Batch actions should not cancel each other
}

class AsyncBatchAction with FlowHolderAction {
  final List<Action> actions;
  final Duration delay;
  AsyncBatchAction(this.actions, {this.delay = const Duration(milliseconds: 10)});

  @override
  Stream<Action> toStreamAction() async* {
    for (final action in actions) {
      await Future.delayed(delay);
      yield action;
    }
  }

  @override
  bool get cancelable => false; // Batch actions should not cancel each other
}

class NestedFlowHolderAction with FlowHolderAction {
  @override
  Stream<Action> toStreamAction() async* {
    yield IncrementAction();
    yield BatchAction([IncrementAction(), IncrementAction()]);
    yield IncrementAction();
  }

  @override
  bool get cancelable => false; // Should complete all nested actions
}

// Test State
class CounterState {
  final int count;
  CounterState(this.count);

  CounterState copyWith({int? count}) => CounterState(count ?? this.count);

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is CounterState && runtimeType == other.runtimeType && count == other.count;

  @override
  int get hashCode => count.hashCode;

  @override
  String toString() => 'CounterState(count: $count)';
}

// Test Reducer
class CounterReducer extends ReducerBase<CounterState, Action> {
  CounterReducer() {
    on<IncrementAction>((state, _) => state.copyWith(count: state.count + 1));
    on<DecrementAction>((state, _) => state.copyWith(count: state.count - 1));
    on<SetValueAction>((state, action) => state.copyWith(count: action.value));
  }
}

// Test Logger
class TestStoreLogger<S, A extends Action> implements StoreLogger<S, A> {
  final List<String> logs = [];

  @override
  void onActionDispatched(A action) {
    logs.add('dispatched:${action.runtimeType}');
  }

  @override
  void onMiddlewareProcessing(String middlewareName, A action) {
    logs.add('middleware:$middlewareName:${action.runtimeType}');
  }

  @override
  void onMiddlewaresCompleted(A action) {
    logs.add('middlewaresCompleted:${action.runtimeType}');
  }

  @override
  void onFlowHolderActionEmitted(A action) {
    logs.add('flowHolderEmitted:${action.runtimeType}');
  }

  @override
  void onErrorOccurred(Object error, StackTrace stackTrace) {
    logs.add('error:$error');
  }

  @override
  void onErrorHandled(A action) {
    logs.add('errorHandled:${action.runtimeType}');
  }

  @override
  void onStateReduced(A action, S previousState, S newState) {
    logs.add('reduced:${action.runtimeType}:$previousState->$newState');
  }

  @override
  void onDispatchAfterClose(A action) {
    logs.add('dispatchAfterClose:${action.runtimeType}');
  }
}

void main() {
  group('Store', () {
    late Reducer<CounterState, Action> reducer;

    setUp(() {
      reducer = CounterReducer().reducer;
    });

    test('createStore creates store with initial state', () {
      final store = createStore<CounterState, Action>(
        initialState: CounterState(0),
        reducer: reducer,
      );

      expect(store.currentState, CounterState(0));
      expect(store.isClosed, false);

      store.close();
    });

    test('dispatch updates state via reducer', () async {
      final store = createStore<CounterState, Action>(
        initialState: CounterState(0),
        reducer: reducer,
      );

      final states = <CounterState>[];
      final subscription = store.state.listen(states.add);

      store.dispatch(IncrementAction());
      await Future.delayed(Duration.zero);

      expect(store.currentState, CounterState(1));
      expect(states, [CounterState(0), CounterState(1)]);

      await subscription.cancel();
      await store.close();
    });

    test('multiple dispatches update state correctly', () async {
      final store = createStore<CounterState, Action>(
        initialState: CounterState(0),
        reducer: reducer,
      );

      store.dispatch(IncrementAction());
      store.dispatch(IncrementAction());
      store.dispatch(DecrementAction());
      store.dispatch(SetValueAction(10));

      await Future.delayed(Duration(milliseconds: 50));

      expect(store.currentState, CounterState(10));

      await store.close();
    });

    test('unhandled action does not change state', () async {
      final store = createStore<CounterState, Action>(
        initialState: CounterState(5),
        reducer: reducer,
      );

      store.dispatch(UnhandledAction());
      await Future.delayed(Duration.zero);

      expect(store.currentState, CounterState(5));

      await store.close();
    });

    group('FlowHolderAction', () {
      test('BatchAction dispatches all contained actions', () async {
        final store = createStore<CounterState, Action>(
          initialState: CounterState(0),
          reducer: reducer,
        );

        store.dispatch(BatchAction([
          IncrementAction(),
          IncrementAction(),
          IncrementAction(),
        ]));

        await Future.delayed(Duration(milliseconds: 50));

        expect(store.currentState, CounterState(3));

        await store.close();
      });

      test('AsyncBatchAction dispatches actions asynchronously', () async {
        final store = createStore<CounterState, Action>(
          initialState: CounterState(0),
          reducer: reducer,
        );

        store.dispatch(AsyncBatchAction(
          [IncrementAction(), IncrementAction()],
          delay: Duration(milliseconds: 10),
        ));

        // Before delay completes
        await Future.delayed(Duration(milliseconds: 5));
        expect(store.currentState.count, lessThan(2));

        // After all actions complete
        await Future.delayed(Duration(milliseconds: 50));
        expect(store.currentState, CounterState(2));

        await store.close();
      });

      test('nested FlowHolderAction dispatches recursively', () async {
        final store = createStore<CounterState, Action>(
          initialState: CounterState(0),
          reducer: reducer,
        );

        // NestedFlowHolderAction emits:
        // 1. IncrementAction (+1)
        // 2. BatchAction([IncrementAction, IncrementAction]) (+2)
        // 3. IncrementAction (+1)
        // Total: +4
        store.dispatch(NestedFlowHolderAction());

        await Future.delayed(Duration(milliseconds: 100));

        expect(store.currentState, CounterState(4));

        await store.close();
      });
    });

    group('isClosed', () {
      test('isClosed is false before close()', () {
        final store = createStore<CounterState, Action>(
          initialState: CounterState(0),
          reducer: reducer,
        );

        expect(store.isClosed, false);

        store.close();
      });

      test('isClosed is true after close()', () async {
        final store = createStore<CounterState, Action>(
          initialState: CounterState(0),
          reducer: reducer,
        );

        await store.close();

        expect(store.isClosed, true);
      });

      test('dispatch after close is ignored', () async {
        final logger = TestStoreLogger<CounterState, Action>();
        final store = createStore<CounterState, Action>(
          initialState: CounterState(0),
          reducer: reducer,
          logger: logger,
        );

        await store.close();
        store.dispatch(IncrementAction());

        expect(store.currentState, CounterState(0));
        expect(
          logger.logs,
          contains('dispatchAfterClose:IncrementAction'),
        );
      });

      test('close() is idempotent', () async {
        final store = createStore<CounterState, Action>(
          initialState: CounterState(0),
          reducer: reducer,
        );

        await store.close();
        await store.close(); // Should not throw

        expect(store.isClosed, true);
      });
    });

    group('StoreLogger', () {
      test('logger receives onActionDispatched callback', () async {
        final logger = TestStoreLogger<CounterState, Action>();
        final store = createStore<CounterState, Action>(
          initialState: CounterState(0),
          reducer: reducer,
          logger: logger,
        );

        store.dispatch(IncrementAction());
        await Future.delayed(Duration.zero);

        expect(logger.logs, contains('dispatched:IncrementAction'));

        await store.close();
      });

      test('logger receives onStateReduced callback', () async {
        final logger = TestStoreLogger<CounterState, Action>();
        final store = createStore<CounterState, Action>(
          initialState: CounterState(0),
          reducer: reducer,
          logger: logger,
        );

        store.dispatch(IncrementAction());
        await Future.delayed(Duration.zero);

        expect(
          logger.logs,
          contains('reduced:IncrementAction:CounterState(count: 0)->CounterState(count: 1)'),
        );

        await store.close();
      });

      test('logger receives onFlowHolderActionEmitted callback', () async {
        final logger = TestStoreLogger<CounterState, Action>();
        final store = createStore<CounterState, Action>(
          initialState: CounterState(0),
          reducer: reducer,
          logger: logger,
        );

        store.dispatch(BatchAction([IncrementAction()]));
        await Future.delayed(Duration(milliseconds: 50));

        expect(logger.logs, contains('flowHolderEmitted:IncrementAction'));

        await store.close();
      });

      test('DebugStoreLogger does not throw', () async {
        final store = createStore<CounterState, Action>(
          initialState: CounterState(0),
          reducer: reducer,
          logger: DebugStoreLogger(tag: 'Test'),
        );

        store.dispatch(IncrementAction());
        await Future.delayed(Duration.zero);

        // Should not throw
        await store.close();
      });
    });
  });

  group('ReducerBase', () {
    test('builds reducer with type-safe handlers', () {
      final reducer = _TypeSafeReducer().reducer;

      var state = CounterState(0);
      state = reducer(state, IncrementAction());
      expect(state, CounterState(1));

      state = reducer(state, SetValueAction(42));
      expect(state, CounterState(42));
    });

    test('unhandled action returns original state', () {
      final reducer = _IncrementOnlyReducer().reducer;

      final state = CounterState(5);
      final newState = reducer(state, UnhandledAction());

      expect(newState, state);
    });

    test('throws DuplicateHandlerException for duplicate handler', () {
      expect(
        () => _DuplicateReducer(),
        throwsA(isA<DuplicateHandlerException>()),
      );
    });
  });
}

// Test Reducers for ReducerBase tests
class _TypeSafeReducer extends ReducerBase<CounterState, Action> {
  _TypeSafeReducer() {
    on<IncrementAction>((state, _) => state.copyWith(count: state.count + 1));
    on<SetValueAction>((state, action) => state.copyWith(count: action.value));
  }
}

class _IncrementOnlyReducer extends ReducerBase<CounterState, Action> {
  _IncrementOnlyReducer() {
    on<IncrementAction>((state, _) => state.copyWith(count: state.count + 1));
  }
}

class _DuplicateReducer extends ReducerBase<CounterState, Action> {
  _DuplicateReducer() {
    on<IncrementAction>((state, _) => state.copyWith(count: state.count + 1));
    on<IncrementAction>((state, _) => state.copyWith(count: state.count + 2));
  }
}
