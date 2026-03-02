/// FlowDux Example
///
/// This example demonstrates the core concepts of FlowDux:
/// - Creating a Store with state, reducer, and middleware
/// - Dispatching actions to update state
/// - Using FlowHolderAction to wrap external streams (no side effects in Action)
/// - Using middleware for async operations with execution strategies
library;

import 'dart:async';

import 'package:flowdux/flowdux.dart';

// =============================================================================
// State
// =============================================================================

class CounterState {
  final int count;
  final String source;
  final List<String> searchResults;
  final bool isLoading;

  const CounterState({
    this.count = 0,
    this.source = '',
    this.searchResults = const [],
    this.isLoading = false,
  });

  CounterState copyWith({
    int? count,
    String? source,
    List<String>? searchResults,
    bool? isLoading,
  }) {
    return CounterState(
      count: count ?? this.count,
      source: source ?? this.source,
      searchResults: searchResults ?? this.searchResults,
      isLoading: isLoading ?? this.isLoading,
    );
  }

  @override
  String toString() {
    final sourceInfo = source.isNotEmpty ? ' [$source]' : '';
    final searchInfo =
        searchResults.isNotEmpty ? ' results=$searchResults' : '';
    return 'CounterState(count: $count$sourceInfo$searchInfo)';
  }
}

// =============================================================================
// Simulated Repository
// =============================================================================

class CounterRepository {
  static Stream<(int, String)> getCount() async* {
    yield (10, 'cache'); // First: cached data
    await Future<void>.delayed(const Duration(milliseconds: 500));
    yield (42, 'api'); // Then: fresh API response
  }
}

// Simulated Search API
class SearchApi {
  static Future<List<String>> search(String query) async {
    await Future<void>.delayed(const Duration(milliseconds: 300));
    return ['$query-result-1', '$query-result-2', '$query-result-3'];
  }
}

// =============================================================================
// Actions
// =============================================================================

sealed class CounterAction implements Action {}

class IncrementAction implements CounterAction {}

class DecrementAction implements CounterAction {}

class AddAction implements CounterAction {
  final int value;
  AddAction(this.value);
}

class ResetAction implements CounterAction {}

class SetCountAction implements CounterAction {
  final int value;
  final String source;
  SetCountAction(this.value, this.source);
}

class SetLoadingAction implements CounterAction {
  final bool isLoading;
  SetLoadingAction(this.isLoading);
}

// FlowHolderAction: wraps external stream, no side effects
// The stream comes from Repository/Socket, not created here
class ObserveCountAction with FlowHolderAction implements CounterAction {
  final Stream<(int, String)> countStream;
  ObserveCountAction(this.countStream);

  @override
  Stream<Action> toStreamAction() =>
      countStream.map((record) => SetCountAction(record.$1, record.$2));
}

// Execution Strategy Actions (processed by middleware)
class SearchAction implements CounterAction {
  final String query;
  SearchAction(this.query);
}

class SearchResultAction implements CounterAction {
  final List<String> results;
  SearchResultAction(this.results);
}

class FetchDataAction implements CounterAction {
  final String id;
  FetchDataAction(this.id);
}

class FetchSuccessAction implements CounterAction {
  final String id;
  final int value;
  FetchSuccessAction(this.id, this.value);
}

class SubmitFormAction implements CounterAction {}

class SubmitSuccessAction implements CounterAction {}

// =============================================================================
// Reducer
// =============================================================================

CounterState counterReducer(CounterState state, CounterAction action) {
  return switch (action) {
    IncrementAction() => state.copyWith(count: state.count + 1),
    DecrementAction() => state.copyWith(count: state.count - 1),
    AddAction(:final value) => state.copyWith(count: state.count + value),
    ResetAction() => CounterState(),
    SetCountAction(:final value, :final source) => state.copyWith(
        count: value,
        source: source,
      ),
    SetLoadingAction(:final isLoading) => state.copyWith(isLoading: isLoading),
    SearchResultAction(:final results) => state.copyWith(
        searchResults: results,
      ),
    FetchSuccessAction(:final id, :final value) => state.copyWith(
        count: value,
        source: 'fetch-$id',
      ),
    SubmitSuccessAction() => state.copyWith(source: 'submitted'),
    // Actions handled by middleware or FlowHolderMiddleware, not reducer
    ObserveCountAction() => state,
    SearchAction() => state,
    FetchDataAction() => state,
    SubmitFormAction() => state,
  };
}

// =============================================================================
// Middleware with Execution Strategies
// =============================================================================

class ExecutionStrategyMiddleware
    extends Middleware<CounterState, CounterAction> {
  ExecutionStrategyMiddleware() {
    // takeLatest: Only the latest search executes, previous ones are canceled
    apply(takeLatest()).on<SearchAction>((state, action) async* {
      print('    [takeLatest] Searching for: ${action.query}');
      final results = await SearchApi.search(action.query);
      print('    [takeLatest] Search completed: ${action.query}');
      yield SearchResultAction(results);
    });

    // debounce: Wait 200ms of no input before executing
    apply(debounce(const Duration(milliseconds: 200))).on<FetchDataAction>((
      state,
      action,
    ) async* {
      print('    [debounce] Fetching data: ${action.id}');
      await Future<void>.delayed(const Duration(milliseconds: 100));
      yield FetchSuccessAction(action.id, 42);
    });

    // takeLeading: Ignore subsequent submissions while one is processing
    apply(takeLeading()).on<SubmitFormAction>((state, action) async* {
      print('    [takeLeading] Processing form submission...');
      await Future<void>.delayed(const Duration(milliseconds: 500));
      print('    [takeLeading] Form submitted!');
      yield SubmitSuccessAction();
    });
  }
}

// =============================================================================
// Main
// =============================================================================

void main() async {
  print('=== Flowdux Sample: Counter ===\n');

  // Create the store
  final store = createStore<CounterState, CounterAction>(
    initialState: const CounterState(),
    reducer: counterReducer,
    middlewares: [ExecutionStrategyMiddleware()],
  );

  // Subscribe to state changes
  final subscription = store.state.listen((state) {
    print('State: $state');
  });

  // Wait for collector to start
  await Future<void>.delayed(const Duration(milliseconds: 50));

  // Simple actions
  print('\n> Dispatching Increment');
  store.dispatch(IncrementAction());
  await Future<void>.delayed(const Duration(milliseconds: 50));

  print('\n> Dispatching Increment');
  store.dispatch(IncrementAction());
  await Future<void>.delayed(const Duration(milliseconds: 50));

  // FlowHolderAction: wraps existing Stream from Repository
  // The Stream comes from Repository (side effect happens there, not in Action)
  print('\n> Dispatching ObserveCount - FlowHolderAction');
  print('  (Repository Stream emits: cache -> api)');
  final repositoryStream = CounterRepository.getCount();
  store.dispatch(ObserveCountAction(repositoryStream));
  await Future<void>.delayed(const Duration(milliseconds: 700));

  print('\n> Dispatching Add(10)');
  store.dispatch(AddAction(10));
  await Future<void>.delayed(const Duration(milliseconds: 50));

  print('\n> Dispatching Reset');
  store.dispatch(ResetAction());
  await Future<void>.delayed(const Duration(milliseconds: 50));

  // ==================== Execution Strategy Examples ====================

  print('\n${'=' * 50}');
  print('=== Execution Strategy Examples ===');
  print('=' * 50);

  // takeLatest: Rapid search - only latest completes
  print('\n> takeLatest: Rapid search (only latest completes)');
  print("  Dispatching Search('a'), Search('ab'), Search('abc') rapidly...");
  store.dispatch(SearchAction('a'));
  await Future<void>.delayed(const Duration(milliseconds: 50));
  store.dispatch(SearchAction('ab'));
  await Future<void>.delayed(const Duration(milliseconds: 50));
  store.dispatch(SearchAction('abc'));
  await Future<void>.delayed(const Duration(milliseconds: 500));
  print("  Result: Only 'abc' search completed!");

  // debounce: Wait for input to stop
  print('\n> debounce: Wait 200ms after last input');
  print('  Dispatching FetchData rapidly...');
  store.dispatch(FetchDataAction('1'));
  await Future<void>.delayed(const Duration(milliseconds: 50));
  store.dispatch(FetchDataAction('2'));
  await Future<void>.delayed(const Duration(milliseconds: 50));
  store.dispatch(FetchDataAction('3'));
  await Future<void>.delayed(const Duration(milliseconds: 400));
  print('  Result: Only last FetchData executed after 200ms quiet period!');

  // takeLeading: Prevent double submission
  print('\n> takeLeading: Prevent double form submission');
  print('  Dispatching SubmitForm 3 times rapidly...');
  store.dispatch(SubmitFormAction());
  await Future<void>.delayed(const Duration(milliseconds: 50));
  store.dispatch(SubmitFormAction()); // ignored
  await Future<void>.delayed(const Duration(milliseconds: 50));
  store.dispatch(SubmitFormAction()); // ignored
  await Future<void>.delayed(const Duration(milliseconds: 600));
  print('  Result: Only first submission processed, others ignored!');

  print('\n${'=' * 50}');
  print('=== Done ===');

  // Clean up
  await subscription.cancel();
  await store.close();
}
