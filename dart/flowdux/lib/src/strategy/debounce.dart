import 'dart:async';

import '../action.dart';
import 'execution_strategy.dart';

/// Delays execution until no new actions arrive for the specified duration.
///
/// Use this strategy when you want to wait for user input to settle before
/// executing, such as search-as-you-type or form validation.
///
/// Example:
/// ```dart
/// b.onWithStrategy<SearchAction>(
///   debounce(Duration(milliseconds: 300)),
///   (state, action) async* {
///     final results = await api.search(action.query);
///     yield SearchResultsAction(results);
///   },
/// );
/// ```
class DebounceStrategy implements ExecutionStrategy {
  final Duration _duration;
  Timer? _pendingTimer;
  StreamController<dynamic>? _pendingController;

  DebounceStrategy(this._duration);

  @override
  StrategyCategory get category => StrategyCategory.timing;

  @override
  Stream<A> Function(S state, T action) wrap<S, A extends Action, T extends A>(
    Stream<A> Function(S state, T action) processor,
  ) {
    return (state, action) {
      // Cancel any pending timer and controller
      _pendingTimer?.cancel();
      _pendingController?.close();

      final controller = StreamController<A>();
      _pendingController = controller;

      // Start a new timer
      _pendingTimer = Timer(_duration, () {
        // Timer fired, execute the processor
        processor(state, action).listen(
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
      });

      return controller.stream;
    };
  }
}

/// Creates a [DebounceStrategy] that delays execution until no new actions
/// arrive for the specified duration.
///
/// Example:
/// ```dart
/// b.onWithStrategy<SearchAction>(
///   debounce(Duration(milliseconds: 300)),
///   (state, action) async* {
///     final results = await api.search(action.query);
///     yield SearchResultsAction(results);
///   },
/// );
/// ```
ExecutionStrategy debounce(Duration duration) => DebounceStrategy(duration);

/// Creates a [DebounceStrategy] with duration specified in milliseconds.
///
/// Example:
/// ```dart
/// b.onWithStrategy<SearchAction>(
///   debounceMs(300),
///   (state, action) async* {
///     final results = await api.search(action.query);
///     yield SearchResultsAction(results);
///   },
/// );
/// ```
ExecutionStrategy debounceMs(int milliseconds) =>
    DebounceStrategy(Duration(milliseconds: milliseconds));
