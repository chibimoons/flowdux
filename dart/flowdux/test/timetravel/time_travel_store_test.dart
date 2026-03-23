import 'package:flowdux/flowdux.dart';
import 'package:test/test.dart';

import 'test_fixtures.dart';

void main() {
  group('TimeTravelStore', () {
    test('initial state is recorded in history', () async {
      final store = createTimeTravelStore<CounterState, CounterAction>(
        initialState: CounterState(5),
        reducer: counterReducer,
      );

      expect(store.history.length, 1);
      expect(store.currentIndex, 0);
      expect(store.history[0].currentState.count, 5);
      expect(store.history[0].action, isNull);
      expect(store.history[0].previousState, isNull);

      await store.close();
    });

    test('dispatch records state changes in history', () async {
      final store = createTimeTravelStore<CounterState, CounterAction>(
        initialState: CounterState(),
        reducer: counterReducer,
      );

      final states = <CounterState>[];
      store.state.listen(states.add);

      store.dispatch(IncrementAction());
      await Future.delayed(Duration(milliseconds: 50));

      store.dispatch(AddAction(10));
      await Future.delayed(Duration(milliseconds: 50));

      expect(store.history.length, 3);
      expect(store.currentIndex, 2);

      expect(store.history[0].currentState.count, 0);
      expect(store.history[1].currentState.count, 1);
      expect(store.history[2].currentState.count, 11);

      expect(store.history[1].action, isA<IncrementAction>());
      expect(store.history[2].action, isA<AddAction>());

      await store.close();
    });

    test('undo moves to previous state', () async {
      final store = createTimeTravelStore<CounterState, CounterAction>(
        initialState: CounterState(),
        reducer: counterReducer,
      );

      store.dispatch(IncrementAction());
      await Future.delayed(Duration(milliseconds: 50));

      store.dispatch(IncrementAction());
      await Future.delayed(Duration(milliseconds: 50));

      expect(store.currentState.count, 2);

      expect(store.canUndo, true);
      expect(await store.undo(), true);
      expect(store.currentState.count, 1);
      expect(store.currentIndex, 1);

      expect(await store.undo(), true);
      expect(store.currentState.count, 0);
      expect(store.currentIndex, 0);

      expect(store.canUndo, false);
      expect(await store.undo(), false);

      await store.close();
    });

    test('redo moves to next state', () async {
      final store = createTimeTravelStore<CounterState, CounterAction>(
        initialState: CounterState(),
        reducer: counterReducer,
      );

      store.dispatch(IncrementAction());
      await Future.delayed(Duration(milliseconds: 50));

      store.dispatch(IncrementAction());
      await Future.delayed(Duration(milliseconds: 50));

      await store.undo();
      await store.undo();
      expect(store.currentState.count, 0);

      expect(store.canRedo, true);
      expect(await store.redo(), true);
      expect(store.currentState.count, 1);
      expect(store.currentIndex, 1);

      expect(await store.redo(), true);
      expect(store.currentState.count, 2);
      expect(store.currentIndex, 2);

      expect(store.canRedo, false);
      expect(await store.redo(), false);

      await store.close();
    });

    test('jumpTo moves to specific state', () async {
      final store = createTimeTravelStore<CounterState, CounterAction>(
        initialState: CounterState(),
        reducer: counterReducer,
      );

      store.dispatch(AddAction(10));
      await Future.delayed(Duration(milliseconds: 50));

      store.dispatch(AddAction(20));
      await Future.delayed(Duration(milliseconds: 50));

      store.dispatch(AddAction(30));
      await Future.delayed(Duration(milliseconds: 50));

      expect(await store.jumpTo(1), true);
      expect(store.currentState.count, 10);
      expect(store.currentIndex, 1);

      expect(await store.jumpTo(3), true);
      expect(store.currentState.count, 60);
      expect(store.currentIndex, 3);

      expect(await store.jumpTo(0), true);
      expect(store.currentState.count, 0);
      expect(store.currentIndex, 0);

      expect(await store.jumpTo(-1), false);
      expect(await store.jumpTo(100), false);

      await store.close();
    });

    test('dispatch from past state truncates future history', () async {
      final store = createTimeTravelStore<CounterState, CounterAction>(
        initialState: CounterState(),
        reducer: counterReducer,
      );

      store.dispatch(AddAction(10));
      await Future.delayed(Duration(milliseconds: 50));

      store.dispatch(AddAction(20));
      await Future.delayed(Duration(milliseconds: 50));

      store.dispatch(AddAction(30));
      await Future.delayed(Duration(milliseconds: 50));

      expect(store.history.length, 4);

      await store.jumpTo(1);
      expect(store.currentState.count, 10);

      store.dispatch(AddAction(5));
      await Future.delayed(Duration(milliseconds: 50));

      expect(store.currentState.count, 15);
      expect(store.history.length, 3);
      expect(store.currentIndex, 2);
      expect(store.history[0].currentState.count, 0);
      expect(store.history[1].currentState.count, 10);
      expect(store.history[2].currentState.count, 15);

      await store.close();
    });

    test('reset moves to initial state', () async {
      final store = createTimeTravelStore<CounterState, CounterAction>(
        initialState: CounterState(),
        reducer: counterReducer,
      );

      store.dispatch(AddAction(10));
      await Future.delayed(Duration(milliseconds: 50));

      store.dispatch(AddAction(20));
      await Future.delayed(Duration(milliseconds: 50));

      expect(await store.reset(), true);
      expect(store.currentState.count, 0);
      expect(store.currentIndex, 0);
      expect(store.history.length, 3);

      await store.close();
    });

    test('clear resets history with current state', () async {
      final store = createTimeTravelStore<CounterState, CounterAction>(
        initialState: CounterState(),
        reducer: counterReducer,
      );

      store.dispatch(AddAction(10));
      await Future.delayed(Duration(milliseconds: 50));

      store.dispatch(AddAction(20));
      await Future.delayed(Duration(milliseconds: 50));

      await store.clear();

      expect(store.history.length, 1);
      expect(store.currentIndex, 0);
      expect(store.history[0].currentState.count, 30);
      expect(store.currentState.count, 30);

      await store.close();
    });

    test('maxHistorySize limits history', () async {
      final store = createTimeTravelStore<CounterState, CounterAction>(
        initialState: CounterState(),
        reducer: counterReducer,
        maxHistorySize: 3,
      );

      store.dispatch(AddAction(1));
      await Future.delayed(Duration(milliseconds: 50));

      store.dispatch(AddAction(2));
      await Future.delayed(Duration(milliseconds: 50));

      expect(store.history.length, 3);

      store.dispatch(AddAction(3));
      await Future.delayed(Duration(milliseconds: 50));

      expect(store.history.length, 3);
      expect(store.currentIndex, 2);
      expect(store.history[0].currentState.count, 1);
      expect(store.history[1].currentState.count, 3);
      expect(store.history[2].currentState.count, 6);

      store.dispatch(AddAction(4));
      await Future.delayed(Duration(milliseconds: 50));

      expect(store.history.length, 3);
      expect(store.currentIndex, 2);
      expect(store.history[0].currentState.count, 3);
      expect(store.history[1].currentState.count, 6);
      expect(store.history[2].currentState.count, 10);

      await store.close();
    });

    test('close prevents further dispatch', () async {
      final store = createTimeTravelStore<CounterState, CounterAction>(
        initialState: CounterState(),
        reducer: counterReducer,
      );

      expect(store.isClosed, false);

      await store.close();

      expect(store.isClosed, true);
    });

    test('timestamps are recorded', () async {
      final store = createTimeTravelStore<CounterState, CounterAction>(
        initialState: CounterState(),
        reducer: counterReducer,
      );

      final beforeDispatch = DateTime.now();

      store.dispatch(IncrementAction());
      await Future.delayed(Duration(milliseconds: 50));

      final afterDispatch = DateTime.now();

      final snapshot = store.history[1];
      expect(
        snapshot.timestamp.isAfter(beforeDispatch) ||
            snapshot.timestamp.isAtSameMomentAs(beforeDispatch),
        true,
      );
      expect(
        snapshot.timestamp.isBefore(afterDispatch) ||
            snapshot.timestamp.isAtSameMomentAs(afterDispatch),
        true,
      );

      await store.close();
    });

    test('history indices are correct after truncation', () async {
      final store = createTimeTravelStore<CounterState, CounterAction>(
        initialState: CounterState(),
        reducer: counterReducer,
        maxHistorySize: 5,
      );

      for (var i = 0; i < 10; i++) {
        store.dispatch(AddAction(i + 1));
        await Future.delayed(Duration(milliseconds: 50));
      }

      for (var i = 0; i < store.history.length; i++) {
        expect(store.history[i].index, i);
      }

      await store.close();
    });

    test('initialHistory restores history and state', () async {
      final savedHistory = <StateSnapshot<CounterState, CounterAction>>[
        StateSnapshot(
          index: 0,
          currentState: CounterState(0),
          timestamp: DateTime.fromMillisecondsSinceEpoch(1000),
        ),
        StateSnapshot(
          index: 1,
          action: AddAction(10),
          previousState: CounterState(0),
          currentState: CounterState(10),
          timestamp: DateTime.fromMillisecondsSinceEpoch(2000),
        ),
        StateSnapshot(
          index: 2,
          action: AddAction(20),
          previousState: CounterState(10),
          currentState: CounterState(30),
          timestamp: DateTime.fromMillisecondsSinceEpoch(3000),
        ),
      ];

      final store =
          createTimeTravelStoreFromHistory<CounterState, CounterAction>(
        initialHistory: savedHistory,
        reducer: counterReducer,
      );

      expect(store.history.length, 3);
      expect(store.currentIndex, 2);
      expect(store.currentState.count, 30);

      expect(store.canUndo, true);
      expect(store.canRedo, false);

      await store.undo();
      expect(store.currentState.count, 10);

      await store.undo();
      expect(store.currentState.count, 0);

      await store.close();
    });

    test('initialHistory allows dispatch to continue from restored state',
        () async {
      final savedHistory = <StateSnapshot<CounterState, CounterAction>>[
        StateSnapshot(
          index: 0,
          currentState: CounterState(100),
          timestamp: DateTime.fromMillisecondsSinceEpoch(1000),
        ),
      ];

      final store =
          createTimeTravelStoreFromHistory<CounterState, CounterAction>(
        initialHistory: savedHistory,
        reducer: counterReducer,
      );

      expect(store.currentState.count, 100);

      store.dispatch(AddAction(50));
      await Future.delayed(Duration(milliseconds: 50));

      expect(store.currentState.count, 150);
      expect(store.history.length, 2);
      expect(store.history[0].currentState.count, 100);
      expect(store.history[1].currentState.count, 150);

      await store.close();
    });

    test('empty initialHistory throws exception', () {
      expect(
        () => createTimeTravelStoreFromHistory<CounterState, CounterAction>(
          initialHistory: [],
          reducer: counterReducer,
        ),
        throwsA(isA<ArgumentError>().having(
          (e) => e.message,
          'message',
          'initialHistory must not be empty',
        )),
      );
    });

    test('canUndo and canRedo reflect navigation state', () async {
      final store = createTimeTravelStore<CounterState, CounterAction>(
        initialState: CounterState(),
        reducer: counterReducer,
      );

      expect(store.canUndo, false);
      expect(store.canRedo, false);

      store.dispatch(IncrementAction());
      await Future.delayed(Duration(milliseconds: 50));

      expect(store.canUndo, true);
      expect(store.canRedo, false);

      await store.undo();

      expect(store.canUndo, false);
      expect(store.canRedo, true);

      await store.redo();

      expect(store.canUndo, true);
      expect(store.canRedo, false);

      await store.close();
    });
  });
}
