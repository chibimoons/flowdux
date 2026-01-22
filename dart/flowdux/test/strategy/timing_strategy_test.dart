import 'dart:async';

import 'package:fake_async/fake_async.dart';
import 'package:flowdux/flowdux.dart';
import 'package:test/test.dart';

// Test Actions
class SearchAction implements Action {
  final String query;
  SearchAction(this.query);
}

class SearchResultAction implements Action {
  final String query;
  SearchResultAction(this.query);
}

class RefreshAction implements Action {
  final int id;
  RefreshAction(this.id);
}

class DataLoadedAction implements Action {
  final int id;
  DataLoadedAction(this.id);
}

// Test State
class AppState {
  final List<String> searchResults;
  final List<int> refreshResults;

  AppState({
    this.searchResults = const [],
    this.refreshResults = const [],
  });

  AppState copyWith({
    List<String>? searchResults,
    List<int>? refreshResults,
  }) =>
      AppState(
        searchResults: searchResults ?? this.searchResults,
        refreshResults: refreshResults ?? this.refreshResults,
      );
}

// Test Reducer
class AppReducer extends ReducerBase<AppState, Action> {
  AppReducer() {
    on<SearchResultAction>((state, action) =>
        state.copyWith(searchResults: [...state.searchResults, action.query]));
    on<DataLoadedAction>((state, action) =>
        state.copyWith(refreshResults: [...state.refreshResults, action.id]));
  }
}

void main() {
  late Reducer<AppState, Action> reducer;

  setUp(() {
    reducer = AppReducer().reducer;
  });

  group('Debounce', () {
    test('delays execution until quiet period', () {
      fakeAsync((async) {
        final executionOrder = <String>[];
        final strategy = debounce(Duration(milliseconds: 100));

        final wrappedProcessor = strategy.wrap<AppState, Action, SearchAction>(
          (state, action) async* {
            executionOrder.add('executed:${action.query}');
            yield SearchResultAction(action.query);
          },
        );

        final state = AppState();

        // First action
        wrappedProcessor(state, SearchAction('a')).listen((_) {});

        // Advance 50ms (not enough to trigger)
        async.elapse(Duration(milliseconds: 50));
        expect(executionOrder, isEmpty);

        // Second action - restarts timer
        wrappedProcessor(state, SearchAction('ab')).listen((_) {});

        // Advance 50ms (still not enough since timer restarted)
        async.elapse(Duration(milliseconds: 50));
        expect(executionOrder, isEmpty);

        // Third action - restarts timer again
        wrappedProcessor(state, SearchAction('abc')).listen((_) {});

        // Advance 100ms (should trigger now)
        async.elapse(Duration(milliseconds: 100));
        expect(executionOrder, ['executed:abc']);
      });
    });

    test('only executes latest action after quiet period', () {
      fakeAsync((async) {
        final results = <String>[];
        final strategy = debounce(Duration(milliseconds: 100));

        final wrappedProcessor = strategy.wrap<AppState, Action, SearchAction>(
          (state, action) async* {
            yield SearchResultAction(action.query);
          },
        );

        final state = AppState();

        // Dispatch multiple actions rapidly
        wrappedProcessor(state, SearchAction('a')).listen((a) {
          results.add((a as SearchResultAction).query);
        });
        async.elapse(Duration(milliseconds: 20));

        wrappedProcessor(state, SearchAction('ab')).listen((a) {
          results.add((a as SearchResultAction).query);
        });
        async.elapse(Duration(milliseconds: 20));

        wrappedProcessor(state, SearchAction('abc')).listen((a) {
          results.add((a as SearchResultAction).query);
        });

        // Wait for debounce period
        async.elapse(Duration(milliseconds: 100));

        // Only the last one should be executed
        expect(results, ['abc']);
      });
    });

    test('executes immediately if no new action arrives', () {
      fakeAsync((async) {
        final results = <String>[];
        final strategy = debounce(Duration(milliseconds: 100));

        final wrappedProcessor = strategy.wrap<AppState, Action, SearchAction>(
          (state, action) async* {
            yield SearchResultAction(action.query);
          },
        );

        final state = AppState();

        wrappedProcessor(state, SearchAction('test')).listen((a) {
          results.add((a as SearchResultAction).query);
        });

        // Advance past debounce duration
        async.elapse(Duration(milliseconds: 100));

        expect(results, ['test']);
      });
    });

    test('has timing category', () {
      final strategy = debounce(Duration(milliseconds: 100));
      expect(strategy.category, StrategyCategory.timing);
    });

    test('debounceMs creates strategy with correct duration', () {
      fakeAsync((async) {
        final results = <String>[];
        final strategy = debounceMs(50);

        final wrappedProcessor = strategy.wrap<AppState, Action, SearchAction>(
          (state, action) async* {
            yield SearchResultAction(action.query);
          },
        );

        final state = AppState();

        wrappedProcessor(state, SearchAction('test')).listen((a) {
          results.add((a as SearchResultAction).query);
        });

        // Not enough time
        async.elapse(Duration(milliseconds: 30));
        expect(results, isEmpty);

        // Enough time
        async.elapse(Duration(milliseconds: 20));
        expect(results, ['test']);
      });
    });
  });

  group('Throttle', () {
    test('executes first action immediately', () {
      fakeAsync((async) {
        final executionOrder = <String>[];
        final strategy = throttle(Duration(milliseconds: 100));

        final wrappedProcessor = strategy.wrap<AppState, Action, RefreshAction>(
          (state, action) async* {
            executionOrder.add('executed:${action.id}');
            yield DataLoadedAction(action.id);
          },
        );

        final state = AppState();

        // First action - executes immediately
        wrappedProcessor(state, RefreshAction(1)).listen((_) {});
        async.elapse(Duration.zero);

        expect(executionOrder, ['executed:1']);
      });
    });

    test('ignores actions within throttle window', () {
      fakeAsync((async) {
        final results = <int>[];
        final strategy = throttle(Duration(milliseconds: 100));

        final wrappedProcessor = strategy.wrap<AppState, Action, RefreshAction>(
          (state, action) async* {
            yield DataLoadedAction(action.id);
          },
        );

        final state = AppState();

        // First action - executes
        wrappedProcessor(state, RefreshAction(1)).listen((a) {
          results.add((a as DataLoadedAction).id);
        });
        async.elapse(Duration.zero);

        // Second action at 20ms - ignored
        async.elapse(Duration(milliseconds: 20));
        wrappedProcessor(state, RefreshAction(2)).listen((a) {
          results.add((a as DataLoadedAction).id);
        });

        // Third action at 50ms - ignored
        async.elapse(Duration(milliseconds: 30));
        wrappedProcessor(state, RefreshAction(3)).listen((a) {
          results.add((a as DataLoadedAction).id);
        });

        async.elapse(Duration(milliseconds: 100));

        // Only first action should have executed
        expect(results, [1]);
      });
    });

    test('allows action after throttle window passes', () {
      fakeAsync((async) {
        final results = <int>[];
        final strategy = throttle(Duration(milliseconds: 100));

        final wrappedProcessor = strategy.wrap<AppState, Action, RefreshAction>(
          (state, action) async* {
            yield DataLoadedAction(action.id);
          },
        );

        final state = AppState();

        // First action
        wrappedProcessor(state, RefreshAction(1)).listen((a) {
          results.add((a as DataLoadedAction).id);
        });
        async.elapse(Duration.zero);

        expect(results, [1]);

        // Wait past throttle window
        async.elapse(Duration(milliseconds: 100));

        // Second action - should execute
        wrappedProcessor(state, RefreshAction(2)).listen((a) {
          results.add((a as DataLoadedAction).id);
        });
        async.elapse(Duration.zero);

        expect(results, [1, 2]);
      });
    });

    test('has timing category', () {
      final strategy = throttle(Duration(milliseconds: 100));
      expect(strategy.category, StrategyCategory.timing);
    });

    test('throttleMs creates strategy with correct duration', () {
      fakeAsync((async) {
        final results = <int>[];
        final strategy = throttleMs(50);

        final wrappedProcessor = strategy.wrap<AppState, Action, RefreshAction>(
          (state, action) async* {
            yield DataLoadedAction(action.id);
          },
        );

        final state = AppState();

        // First action
        wrappedProcessor(state, RefreshAction(1)).listen((a) {
          results.add((a as DataLoadedAction).id);
        });
        async.elapse(Duration.zero);

        // Action at 30ms - ignored
        async.elapse(Duration(milliseconds: 30));
        wrappedProcessor(state, RefreshAction(2)).listen((a) {
          results.add((a as DataLoadedAction).id);
        });

        // Wait for window to pass
        async.elapse(Duration(milliseconds: 20));

        // Action after window - executes
        wrappedProcessor(state, RefreshAction(3)).listen((a) {
          results.add((a as DataLoadedAction).id);
        });
        async.elapse(Duration.zero);

        expect(results, [1, 3]);
      });
    });
  });

  group('Store integration', () {
    test('debounce works with Store', () async {
      final middleware = _DebounceMiddleware();

      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [middleware],
      );

      // Dispatch multiple actions quickly
      store.dispatch(SearchAction('a'));
      await Future.delayed(Duration(milliseconds: 10));
      store.dispatch(SearchAction('ab'));
      await Future.delayed(Duration(milliseconds: 10));
      store.dispatch(SearchAction('abc'));

      // Wait for debounce to complete
      await Future.delayed(Duration(milliseconds: 100));

      // Only the last action should produce results
      expect(store.currentState.searchResults, ['abc']);

      await store.close();
    });

    test('throttle works with Store', () async {
      final middleware = _ThrottleMiddleware();

      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [middleware],
      );

      // First action executes immediately
      store.dispatch(RefreshAction(1));
      await Future.delayed(Duration(milliseconds: 10));

      // These should be ignored (within throttle window)
      store.dispatch(RefreshAction(2));
      store.dispatch(RefreshAction(3));

      await Future.delayed(Duration(milliseconds: 100));

      // Only first action should have produced results
      expect(store.currentState.refreshResults, [1]);

      await store.close();
    });
  });
}

// Test Middlewares using new DSL
class _DebounceMiddleware extends Middleware<AppState, Action> {
  _DebounceMiddleware() {
    apply(debounce(Duration(milliseconds: 50))).on<SearchAction>((state, action) async* {
      yield SearchResultAction(action.query);
    });
  }
}

class _ThrottleMiddleware extends Middleware<AppState, Action> {
  _ThrottleMiddleware() {
    apply(throttle(Duration(milliseconds: 50))).on<RefreshAction>((state, action) async* {
      yield DataLoadedAction(action.id);
    });
  }
}
