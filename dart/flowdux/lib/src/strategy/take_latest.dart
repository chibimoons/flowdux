import 'dart:async';

import '../action.dart';
import 'execution_strategy.dart';

/// Cancels any previous execution when a new action arrives.
/// Only the latest action's result will be emitted.
///
/// Use this strategy when you only care about the result of the most recent
/// action, such as search-as-you-type or fetching data for the current view.
///
/// Example:
/// ```dart
/// b.onWithStrategy<SearchAction>(
///   takeLatest(),
///   (state, action) async* {
///     final results = await api.search(action.query);
///     yield SearchResultsAction(results);
///   },
/// );
/// ```
class TakeLatestStrategy implements ExecutionStrategy {
  StreamSubscription<dynamic>? _currentSubscription;
  StreamController<dynamic>? _currentController;

  @override
  StrategyCategory get category => StrategyCategory.concurrency;

  @override
  Stream<A> Function(S state, T action) wrap<S, A extends Action, T extends A>(
    Stream<A> Function(S state, T action) processor,
  ) {
    return (state, action) {
      // Cancel and close previous execution
      _currentSubscription?.cancel();
      _currentController?.close();

      final controller = StreamController<A>();
      _currentController = controller;

      // Start processing
      _currentSubscription = processor(state, action).listen(
        (result) {
          if (!controller.isClosed) {
            controller.add(result);
          }
        },
        onError: (Object error, StackTrace stackTrace) {
          if (!controller.isClosed) {
            controller.addError(error, stackTrace);
          }
        },
        onDone: () {
          if (!controller.isClosed) {
            controller.close();
          }
        },
        cancelOnError: false,
      );

      return controller.stream;
    };
  }
}

/// Creates a [TakeLatestStrategy] that cancels previous executions when a new action arrives.
///
/// Example:
/// ```dart
/// b.onWithStrategy<SearchAction>(
///   takeLatest(),
///   (state, action) async* {
///     final results = await api.search(action.query);
///     yield SearchResultsAction(results);
///   },
/// );
/// ```
ExecutionStrategy takeLatest() => TakeLatestStrategy();
