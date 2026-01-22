import 'dart:async';

import 'package:flowdux/flowdux.dart';
import 'package:test/test.dart';

// Test Actions
class TestAction implements Action {
  final int id;
  TestAction(this.id);

  @override
  String toString() => 'TestAction($id)';
}

class ResultAction implements Action {
  final int id;
  ResultAction(this.id);

  @override
  String toString() => 'ResultAction($id)';
}

class SearchAction implements Action {
  final String query;
  SearchAction(this.query);
}

class SearchResultAction implements Action {
  final String query;
  final List<String> results;
  SearchResultAction(this.query, this.results);
}

class SubmitAction implements Action {}

class SubmittingAction implements Action {}

class SubmittedAction implements Action {}

class SaveAction implements Action {
  final int value;
  SaveAction(this.value);
}

class SavedAction implements Action {
  final int value;
  SavedAction(this.value);
}

// Test State
class AppState {
  final List<int> results;
  final bool submitting;
  final List<int> savedValues;
  final List<String> searchResults;

  AppState({
    this.results = const [],
    this.submitting = false,
    this.savedValues = const [],
    this.searchResults = const [],
  });

  AppState copyWith({
    List<int>? results,
    bool? submitting,
    List<int>? savedValues,
    List<String>? searchResults,
  }) =>
      AppState(
        results: results ?? this.results,
        submitting: submitting ?? this.submitting,
        savedValues: savedValues ?? this.savedValues,
        searchResults: searchResults ?? this.searchResults,
      );

  @override
  String toString() => 'AppState(results: $results, submitting: $submitting, savedValues: $savedValues)';
}

// Test Reducer
class AppReducer extends ReducerBase<AppState, Action> {
  AppReducer() {
    on<ResultAction>((state, action) =>
        state.copyWith(results: [...state.results, action.id]));
    on<SubmittingAction>((state, _) => state.copyWith(submitting: true));
    on<SubmittedAction>((state, _) => state.copyWith(submitting: false));
    on<SavedAction>((state, action) =>
        state.copyWith(savedValues: [...state.savedValues, action.value]));
    on<SearchResultAction>((state, action) =>
        state.copyWith(searchResults: action.results));
  }
}

void main() {
  late Reducer<AppState, Action> reducer;

  setUp(() {
    reducer = AppReducer().reducer;
  });

  group('TakeLatest', () {
    test('cancels previous execution when new action arrives', () async {
      final executionOrder = <String>[];

      final middleware = _TakeLatestMiddleware(executionOrder);

      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [middleware],
      );

      // Dispatch multiple actions quickly
      store.dispatch(TestAction(1));
      await Future.delayed(Duration(milliseconds: 10));
      store.dispatch(TestAction(2));
      await Future.delayed(Duration(milliseconds: 10));
      store.dispatch(TestAction(3));

      // Wait for all processing to complete
      await Future.delayed(Duration(milliseconds: 150));

      // Only the last action's result should be emitted to state
      // Note: In Dart, async generators continue running after subscription cancellation,
      // but their yielded values are discarded. The key behavior is that only
      // the latest action's result reaches the reducer.
      expect(store.currentState.results, [3]);

      // All processors start immediately (concurrent execution)
      expect(executionOrder, contains('start:1'));
      expect(executionOrder, contains('start:2'));
      expect(executionOrder, contains('start:3'));

      await store.close();
    });

    test('executes single action normally', () async {
      final middleware = _TakeLatestSingleMiddleware();

      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [middleware],
      );

      store.dispatch(TestAction(1));
      await Future.delayed(Duration(milliseconds: 50));

      expect(store.currentState.results, [1]);

      await store.close();
    });

    test('shared strategy coordinates across action types in group', () async {
      final executionOrder = <String>[];

      final middleware = _TakeLatestGroupMiddleware(executionOrder);

      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [middleware],
      );

      // TestAction starts, then SearchAction cancels it via shared strategy
      store.dispatch(TestAction(1));
      await Future.delayed(Duration(milliseconds: 10));
      store.dispatch(SearchAction('query'));

      await Future.delayed(Duration(milliseconds: 100));

      // Both processors start (TestAction cancelled but continues running in background)
      // SearchAction's result is the only one that reaches reducer
      expect(executionOrder, contains('test:start:1'));
      expect(executionOrder, contains('search:start:query'));
      expect(executionOrder, contains('search:end:query'));

      // Verify search results are in state (TestAction result was discarded)
      expect(store.currentState.searchResults, ['query']);
      expect(store.currentState.results, isEmpty);

      await store.close();
    });
  });

  group('TakeLeading', () {
    test('ignores new actions while processing', () async {
      final executionOrder = <String>[];

      final middleware = _TakeLeadingMiddleware(executionOrder);

      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [middleware],
      );

      // First submission starts
      store.dispatch(SubmitAction());
      await Future.delayed(Duration(milliseconds: 10));

      // These should be ignored
      store.dispatch(SubmitAction());
      store.dispatch(SubmitAction());
      store.dispatch(SubmitAction());

      await Future.delayed(Duration(milliseconds: 100));

      // Only one execution should have happened
      expect(executionOrder, ['start', 'end']);

      await store.close();
    });

    test('allows new action after previous completes', () async {
      final counter = _Counter();

      final middleware = _TakeLeadingCounterMiddleware(counter);

      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [middleware],
      );

      // First submission
      store.dispatch(SubmitAction());
      await Future.delayed(Duration(milliseconds: 50));

      // Second submission after first completes
      store.dispatch(SubmitAction());
      await Future.delayed(Duration(milliseconds: 50));

      expect(counter.value, 2);

      await store.close();
    });
  });

  group('Sequential', () {
    test('processes actions in order', () async {
      final executionOrder = <int>[];

      final middleware = _SequentialMiddleware(executionOrder);

      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [middleware],
      );

      // Dispatch multiple save actions
      store.dispatch(SaveAction(1));
      store.dispatch(SaveAction(2));
      store.dispatch(SaveAction(3));

      // Wait for all to complete
      await Future.delayed(Duration(milliseconds: 150));

      // All should be saved in order
      expect(store.currentState.savedValues, [1, 2, 3]);
      expect(executionOrder, [1, 2, 3]);

      await store.close();
    });

    test('does not drop any actions', () async {
      final counter = _Counter();

      final middleware = _SequentialCounterMiddleware(counter);

      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [middleware],
      );

      // Dispatch 5 actions
      for (var i = 0; i < 5; i++) {
        store.dispatch(SaveAction(i));
      }

      await Future.delayed(Duration(milliseconds: 200));

      // All 5 should be processed
      expect(counter.value, 5);
      expect(store.currentState.savedValues.length, 5);

      await store.close();
    });
  });

  group('AsyncLock', () {
    test('ensures sequential execution', () async {
      final lock = AsyncLock();
      final executionOrder = <String>[];

      // Start three concurrent operations
      unawaited(lock.synchronized(() async {
        executionOrder.add('a:start');
        await Future.delayed(Duration(milliseconds: 30));
        executionOrder.add('a:end');
      }));

      unawaited(lock.synchronized(() async {
        executionOrder.add('b:start');
        await Future.delayed(Duration(milliseconds: 20));
        executionOrder.add('b:end');
      }));

      unawaited(lock.synchronized(() async {
        executionOrder.add('c:start');
        await Future.delayed(Duration(milliseconds: 10));
        executionOrder.add('c:end');
      }));

      await Future.delayed(Duration(milliseconds: 150));

      // Should execute in order: a complete, then b complete, then c complete
      expect(executionOrder, [
        'a:start', 'a:end',
        'b:start', 'b:end',
        'c:start', 'c:end',
      ]);
    });

    test('returns value from function', () async {
      final lock = AsyncLock();

      final result = await lock.synchronized(() async {
        await Future.delayed(Duration(milliseconds: 10));
        return 42;
      });

      expect(result, 42);
    });
  });

  group('StrategyCategory', () {
    test('takeLatest has concurrency category', () {
      final strategy = takeLatest();
      expect(strategy.category, StrategyCategory.concurrency);
    });

    test('takeLeading has concurrency category', () {
      final strategy = takeLeading();
      expect(strategy.category, StrategyCategory.concurrency);
    });

    test('sequential has concurrency category', () {
      final strategy = sequential();
      expect(strategy.category, StrategyCategory.concurrency);
    });
  });
}

// Helper class for counting
class _Counter {
  int value = 0;
}

// Test Middlewares using new DSL
class _TakeLatestMiddleware extends Middleware<AppState, Action> {
  _TakeLatestMiddleware(List<String> executionOrder) {
    apply(takeLatest()).on<TestAction>((state, action) async* {
      executionOrder.add('start:${action.id}');
      await Future.delayed(Duration(milliseconds: 50));
      executionOrder.add('end:${action.id}');
      yield ResultAction(action.id);
    });
  }
}

class _TakeLatestSingleMiddleware extends Middleware<AppState, Action> {
  _TakeLatestSingleMiddleware() {
    apply(takeLatest()).on<TestAction>((state, action) async* {
      await Future.delayed(Duration(milliseconds: 10));
      yield ResultAction(action.id);
    });
  }
}

class _TakeLatestGroupMiddleware extends Middleware<AppState, Action> {
  _TakeLatestGroupMiddleware(List<String> executionOrder) {
    final strategy = takeLatest();
    apply(strategy)
        .on<TestAction>((state, action) async* {
          executionOrder.add('test:start:${action.id}');
          await Future.delayed(Duration(milliseconds: 50));
          executionOrder.add('test:end:${action.id}');
          yield ResultAction(action.id);
        })
        .on<SearchAction>((state, action) async* {
          executionOrder.add('search:start:${action.query}');
          await Future.delayed(Duration(milliseconds: 50));
          executionOrder.add('search:end:${action.query}');
          yield SearchResultAction(action.query, [action.query]);
        });
  }
}

class _TakeLeadingMiddleware extends Middleware<AppState, Action> {
  _TakeLeadingMiddleware(List<String> executionOrder) {
    apply(takeLeading()).on<SubmitAction>((state, action) async* {
      executionOrder.add('start');
      yield SubmittingAction();
      await Future.delayed(Duration(milliseconds: 50));
      executionOrder.add('end');
      yield SubmittedAction();
    });
  }
}

class _TakeLeadingCounterMiddleware extends Middleware<AppState, Action> {
  _TakeLeadingCounterMiddleware(_Counter counter) {
    apply(takeLeading()).on<SubmitAction>((state, action) async* {
      counter.value++;
      yield SubmittingAction();
      await Future.delayed(Duration(milliseconds: 20));
      yield SubmittedAction();
    });
  }
}

class _SequentialMiddleware extends Middleware<AppState, Action> {
  _SequentialMiddleware(List<int> executionOrder) {
    apply(sequential()).on<SaveAction>((state, action) async* {
      executionOrder.add(action.value);
      await Future.delayed(Duration(milliseconds: 20));
      yield SavedAction(action.value);
    });
  }
}

class _SequentialCounterMiddleware extends Middleware<AppState, Action> {
  _SequentialCounterMiddleware(_Counter counter) {
    apply(sequential()).on<SaveAction>((state, action) async* {
      counter.value++;
      await Future.delayed(Duration(milliseconds: 10));
      yield SavedAction(action.value);
    });
  }
}
