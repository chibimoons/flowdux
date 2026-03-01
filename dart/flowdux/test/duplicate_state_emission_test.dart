import 'dart:async';

import 'package:flowdux/flowdux.dart';
import 'package:test/test.dart';

/// Tests for duplicate state emission filtering.
///
/// These tests verify that the store does not emit consecutive identical states,
/// similar to Kotlin's StateFlow behavior (distinctUntilChanged).

// Test State
class CounterState {
  final int count;

  const CounterState({this.count = 0});

  CounterState copyWith({int? count}) {
    return CounterState(count: count ?? this.count);
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is CounterState &&
          runtimeType == other.runtimeType &&
          count == other.count;

  @override
  int get hashCode => count.hashCode;

  @override
  String toString() => 'CounterState(count: $count)';
}

// Test Actions
abstract class CounterAction extends Action {}

class IncrementAction extends CounterAction {}

class SetValueAction extends CounterAction {
  final int value;
  SetValueAction(this.value);
}

class FetchDataAction extends CounterAction {
  final String id;
  FetchDataAction(this.id);
}

class NoOpAction extends CounterAction {}

// Test Reducer
class CounterReducer extends ReducerBase<CounterState, CounterAction> {
  CounterReducer() {
    on<IncrementAction>((state, _) => state.copyWith(count: state.count + 1));
    on<SetValueAction>((state, action) => state.copyWith(count: action.value));
    on<FetchDataAction>((state, _) => state); // Returns same state
    on<NoOpAction>((state, _) => state); // Returns same state
  }
}

void main() {
  group('Duplicate State Emission Tests', () {
    test('store does not emit duplicate states', () async {
      final emissions = <int>[];

      final store = createStore<CounterState, CounterAction>(
        initialState: const CounterState(count: 5),
        reducer: CounterReducer().reducer,
      );

      final subscription = store.state.listen((state) {
        emissions.add(state.count);
      });

      // Wait for initial state emission
      await Future.delayed(const Duration(milliseconds: 50));
      expect(emissions, [5]);

      // Dispatch action that sets the same value
      store.dispatch(SetValueAction(5));
      await Future.delayed(const Duration(milliseconds: 50));

      // No new emission should occur because state is the same
      expect(emissions, [5], reason: 'Should not emit duplicate state');

      // Dispatch action that actually changes the state
      store.dispatch(SetValueAction(10));
      await Future.delayed(const Duration(milliseconds: 50));
      expect(emissions, [5, 10]);

      // Dispatch same value again
      store.dispatch(SetValueAction(10));
      await Future.delayed(const Duration(milliseconds: 50));
      expect(emissions, [5, 10], reason: 'Should not emit duplicate state');

      // Change state and verify emission
      store.dispatch(IncrementAction());
      await Future.delayed(const Duration(milliseconds: 50));
      expect(emissions, [5, 10, 11]);

      await subscription.cancel();
      await store.close();
    });

    test(
      'store does not emit when reducer returns same state reference',
      () async {
        final emissions = <int>[];

        final store = createStore<CounterState, CounterAction>(
          initialState: const CounterState(count: 0),
          reducer: CounterReducer().reducer,
        );

        final subscription = store.state.listen((state) {
          emissions.add(state.count);
        });

        // Wait for initial state emission
        await Future.delayed(const Duration(milliseconds: 50));
        expect(emissions, [0]);

        // FetchData returns state unchanged
        store.dispatch(FetchDataAction('test'));
        await Future.delayed(const Duration(milliseconds: 50));
        expect(emissions, [0], reason: 'Should not emit when state unchanged');

        // NoOpAction also returns state unchanged
        store.dispatch(NoOpAction());
        await Future.delayed(const Duration(milliseconds: 50));
        expect(emissions, [0], reason: 'Should not emit when state unchanged');

        // Actual state change should emit
        store.dispatch(IncrementAction());
        await Future.delayed(const Duration(milliseconds: 50));
        expect(emissions, [0, 1]);

        await subscription.cancel();
        await store.close();
      },
    );

    test('consecutive identical state updates are deduplicated', () async {
      final emissions = <int>[];

      final store = createStore<CounterState, CounterAction>(
        initialState: const CounterState(count: 0),
        reducer: CounterReducer().reducer,
      );

      final subscription = store.state.listen((state) {
        emissions.add(state.count);
      });

      // Wait for initial state emission
      await Future.delayed(const Duration(milliseconds: 50));

      // First change
      store.dispatch(SetValueAction(10));
      await Future.delayed(const Duration(milliseconds: 50));

      // Multiple actions that result in same state
      store.dispatch(SetValueAction(10));
      store.dispatch(SetValueAction(10));
      store.dispatch(SetValueAction(10));
      await Future.delayed(const Duration(milliseconds: 50));

      // Final change
      store.dispatch(SetValueAction(20));
      await Future.delayed(const Duration(milliseconds: 50));

      // Only initial, first change, and final change should be recorded
      expect(emissions, [
        0,
        10,
        20,
      ], reason: 'Consecutive identical states should be deduplicated');

      await subscription.cancel();
      await store.close();
    });
  });
}
