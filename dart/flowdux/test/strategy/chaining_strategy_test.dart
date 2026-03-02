import 'dart:async';

import 'package:fake_async/fake_async.dart';
import 'package:flowdux/flowdux.dart';
import 'package:test/test.dart';

// Test Actions
class FetchAction implements Action {
  final int id;
  FetchAction(this.id);
}

class DataAction implements Action {
  final int id;
  DataAction(this.id);
}

// Test State
class AppState {
  final List<int> results;
  AppState([this.results = const []]);
  AppState copyWith({List<int>? results}) => AppState(results ?? this.results);
}

void main() {
  group('ChainedStrategy', () {
    test('chains two strategies correctly', () {
      fakeAsync((async) {
        final executionOrder = <String>[];

        // debounce(100ms) then takeLatest
        final strategy = debounce(
          Duration(milliseconds: 100),
        ).then(takeLatest());

        final wrappedProcessor = strategy.wrap<AppState, Action, FetchAction>((
          state,
          action,
        ) async* {
          executionOrder.add('executed:${action.id}');
          yield DataAction(action.id);
        });

        final state = AppState();
        final results = <int>[];

        // First action
        wrappedProcessor(state, FetchAction(1)).listen((a) {
          results.add((a as DataAction).id);
        });

        // Second action within debounce window
        async.elapse(Duration(milliseconds: 50));
        wrappedProcessor(state, FetchAction(2)).listen((a) {
          results.add((a as DataAction).id);
        });

        // Third action within debounce window (restarts debounce)
        async.elapse(Duration(milliseconds: 50));
        wrappedProcessor(state, FetchAction(3)).listen((a) {
          results.add((a as DataAction).id);
        });

        // Wait for debounce to complete
        async.elapse(Duration(milliseconds: 100));

        // Only the last action should execute due to debounce
        expect(executionOrder, ['executed:3']);
        expect(results, [3]);
      });
    });

    test('has chained category', () {
      final strategy = debounce(Duration(milliseconds: 100)).then(takeLatest());
      expect(strategy.category, StrategyCategory.chained);
    });

    test('throws DuplicateCategoryException for same category', () {
      expect(
        () => takeLatest().then(takeLeading()),
        throwsA(isA<DuplicateCategoryException>()),
      );
    });

    test('throws DuplicateCategoryException with correct message', () {
      try {
        takeLatest().then(takeLeading());
        fail('Should have thrown DuplicateCategoryException');
      } on DuplicateCategoryException catch (e) {
        expect(e.category, StrategyCategory.concurrency);
        expect(
          e.toString(),
          contains('Cannot chain strategies of the same category'),
        );
        expect(e.toString(), contains('concurrency'));
        expect(e.toString(), contains('TakeLatestStrategy'));
        expect(e.toString(), contains('TakeLeadingStrategy'));
      }
    });

    test('allows chaining different categories', () {
      // This should not throw
      final strategy = debounce(
        Duration(milliseconds: 100),
      ).then(takeLatest()).then(retry(3));

      expect(strategy.category, StrategyCategory.chained);
    });

    test('validates nested chains for duplicate categories', () {
      // debounce -> takeLatest (OK)
      final firstChain = debounce(
        Duration(milliseconds: 100),
      ).then(takeLatest());

      // Trying to add another concurrency strategy should fail
      expect(
        () => firstChain.then(takeLeading()),
        throwsA(isA<DuplicateCategoryException>()),
      );
    });

    test('validates nested chains recursively', () {
      // debounce -> retry (OK)
      final chain1 = debounce(Duration(milliseconds: 100)).then(retry(3));

      // throttle -> takeLatest (OK)
      final chain2 = throttle(Duration(milliseconds: 100)).then(takeLeading());

      // Combining should fail because both have timing strategies
      expect(
        () => chain1.then(chain2),
        throwsA(isA<DuplicateCategoryException>()),
      );
    });

    test('first strategy is outer layer', () {
      fakeAsync((async) {
        var debounceExecuted = false;
        var takeLatestExecuted = false;

        // Use debounce(100ms).then(takeLatest())
        // debounce is outer, so it should debounce first, then takeLatest applies
        final strategy = debounce(
          Duration(milliseconds: 100),
        ).then(takeLatest());

        final wrappedProcessor = strategy.wrap<AppState, Action, FetchAction>((
          state,
          action,
        ) async* {
          debounceExecuted = true;
          takeLatestExecuted = true;
          yield DataAction(action.id);
        });

        final state = AppState();

        wrappedProcessor(state, FetchAction(1)).listen((_) {});

        // Before debounce fires
        async.elapse(Duration(milliseconds: 50));
        expect(debounceExecuted, false);

        // After debounce fires
        async.elapse(Duration(milliseconds: 50));
        expect(debounceExecuted, true);
        expect(takeLatestExecuted, true);
      });
    });
  });

  group('Strategy chaining with Store', () {
    late Reducer<AppState, Action> reducer;

    setUp(() {
      reducer = _AppReducer().reducer;
    });

    test('debounce.then(takeLatest) works with Store', () async {
      final middleware = _DebounceTakeLatestMiddleware();

      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [middleware],
      );

      // Rapid dispatches
      store.dispatch(FetchAction(1));
      await Future.delayed(Duration(milliseconds: 20));
      store.dispatch(FetchAction(2));
      await Future.delayed(Duration(milliseconds: 20));
      store.dispatch(FetchAction(3));

      // Wait for debounce and processing
      await Future.delayed(Duration(milliseconds: 100));

      // Only the last action should produce results (debounced)
      expect(store.currentState.results, [3]);

      await store.close();
    });

    test('throttle.then(retry) works with Store', () async {
      var attemptCount = 0;

      final middleware = _ThrottleRetryMiddleware(
        onProcess: () {
          attemptCount++;
          if (attemptCount < 3) {
            throw Exception('Transient error');
          }
        },
      );

      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [middleware],
      );

      store.dispatch(FetchAction(1));

      // Wait for retries
      await Future.delayed(Duration(milliseconds: 100));

      expect(attemptCount, 3);
      expect(store.currentState.results, [1]);

      await store.close();
    });

    test('three-way chain works', () async {
      var executed = false;

      final middleware = _ThreeWayChainMiddleware(
        onProcess: () {
          executed = true;
        },
      );

      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [middleware],
      );

      store.dispatch(FetchAction(1));

      // Wait for debounce and processing
      await Future.delayed(Duration(milliseconds: 100));

      expect(executed, true);
      expect(store.currentState.results, [1]);

      await store.close();
    });
  });

  group('DuplicateCategoryException', () {
    test('has correct properties', () {
      final exception = DuplicateCategoryException(
        category: StrategyCategory.concurrency,
        firstName: 'TakeLatestStrategy',
        secondName: 'TakeLeadingStrategy',
      );

      expect(exception.category, StrategyCategory.concurrency);
      expect(exception.firstName, 'TakeLatestStrategy');
      expect(exception.secondName, 'TakeLeadingStrategy');
    });

    test('has correct toString', () {
      final exception = DuplicateCategoryException(
        category: StrategyCategory.timing,
        firstName: 'DebounceStrategy',
        secondName: 'ThrottleStrategy',
      );

      final message = exception.toString();
      expect(message, contains('Cannot chain strategies of the same category'));
      expect(message, contains('timing'));
      expect(message, contains('DebounceStrategy'));
      expect(message, contains('ThrottleStrategy'));
    });
  });
}

// Test Reducer
class _AppReducer extends ReducerBase<AppState, Action> {
  _AppReducer() {
    on<DataAction>(
      (state, action) => state.copyWith(results: [...state.results, action.id]),
    );
  }
}

// Test Middlewares using new DSL
class _DebounceTakeLatestMiddleware extends Middleware<AppState, Action> {
  _DebounceTakeLatestMiddleware() {
    apply(
      debounce(Duration(milliseconds: 50)).then(takeLatest()),
    ).on<FetchAction>((state, action) async* {
      yield DataAction(action.id);
    });
  }
}

class _ThrottleRetryMiddleware extends Middleware<AppState, Action> {
  _ThrottleRetryMiddleware({required void Function() onProcess}) {
    apply(throttle(Duration(milliseconds: 100)).then(retry(3))).on<FetchAction>(
      (state, action) async* {
        onProcess();
        yield DataAction(action.id);
      },
    );
  }
}

class _ThreeWayChainMiddleware extends Middleware<AppState, Action> {
  _ThreeWayChainMiddleware({required void Function() onProcess}) {
    apply(
      debounce(Duration(milliseconds: 30)).then(takeLatest()).then(retry(2)),
    ).on<FetchAction>((state, action) async* {
      onProcess();
      yield DataAction(action.id);
    });
  }
}
