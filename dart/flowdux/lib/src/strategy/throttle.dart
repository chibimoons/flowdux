import 'dart:async';

import '../action.dart';
import 'execution_strategy.dart';

/// Limits execution rate by ignoring actions within a time window.
///
/// Use this strategy when you want to limit how frequently an action
/// can trigger processing, such as rate-limiting API calls or
/// preventing rapid button clicks.
///
/// Example:
/// ```dart
/// b.onWithStrategy<RefreshAction>(
///   throttle(Duration(seconds: 1)),
///   (state, action) async* {
///     final data = await api.fetchData();
///     yield DataLoadedAction(data);
///   },
/// );
/// ```
class ThrottleStrategy implements ExecutionStrategy {
  final Duration _duration;
  bool _isThrottled = false;
  Timer? _throttleTimer;

  ThrottleStrategy(this._duration);

  @override
  StrategyCategory get category => StrategyCategory.timing;

  @override
  Stream<A> Function(S state, T action) wrap<S, A extends Action, T extends A>(
    Stream<A> Function(S state, T action) processor,
  ) {
    return (state, action) {
      // If throttled, ignore this action
      if (_isThrottled) {
        return const Stream.empty();
      }

      // Start throttle window
      _isThrottled = true;
      _throttleTimer?.cancel();
      _throttleTimer = Timer(_duration, () {
        _isThrottled = false;
      });

      final controller = StreamController<A>();

      processor(state, action).listen(
        controller.add,
        onError: controller.addError,
        onDone: controller.close,
        cancelOnError: false,
      );

      return controller.stream;
    };
  }
}

/// Creates a [ThrottleStrategy] that limits execution rate.
///
/// The first action is executed immediately. Subsequent actions within
/// the time window are ignored until the window passes.
///
/// Example:
/// ```dart
/// b.onWithStrategy<RefreshAction>(
///   throttle(Duration(seconds: 1)),
///   (state, action) async* {
///     final data = await api.fetchData();
///     yield DataLoadedAction(data);
///   },
/// );
/// ```
ExecutionStrategy throttle(Duration duration) => ThrottleStrategy(duration);

/// Creates a [ThrottleStrategy] with duration specified in milliseconds.
///
/// Example:
/// ```dart
/// b.onWithStrategy<RefreshAction>(
///   throttleMs(1000),
///   (state, action) async* {
///     final data = await api.fetchData();
///     yield DataLoadedAction(data);
///   },
/// );
/// ```
ExecutionStrategy throttleMs(int milliseconds) =>
    ThrottleStrategy(Duration(milliseconds: milliseconds));
