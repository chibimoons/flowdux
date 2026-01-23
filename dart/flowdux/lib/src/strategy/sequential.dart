import 'dart:async';

import '../action.dart';
import '../util/async_lock.dart';
import 'execution_strategy.dart';

/// Queues actions and processes them one at a time, preserving order.
///
/// Unlike [TakeLeading] which ignores new actions, this strategy waits
/// for the current action to complete before processing the next one.
///
/// Use this strategy when actions must be processed in order and none
/// should be dropped, such as saving operations or sequential API calls.
///
/// Example:
/// ```dart
/// b.onWithStrategy<SaveAction>(
///   sequential(),
///   (state, action) async* {
///     await api.save(action.data);
///     yield SavedAction();
///   },
/// );
/// ```
class SequentialStrategy implements ExecutionStrategy {
  final _lock = AsyncLock();

  /// Creates a [SequentialStrategy] that queues and processes actions in order.
  SequentialStrategy();

  @override
  StrategyCategory get category => StrategyCategory.concurrency;

  @override
  Stream<A> Function(S state, T action) wrap<S, A extends Action, T extends A>(
    Stream<A> Function(S state, T action) processor,
  ) {
    return (state, action) {
      final controller = StreamController<A>();

      // Use the lock to ensure sequential execution
      _lock.synchronized(() async {
        try {
          await for (final result in processor(state, action)) {
            controller.add(result);
          }
        } catch (e, st) {
          controller.addError(e, st);
        } finally {
          await controller.close();
        }
      });

      return controller.stream;
    };
  }
}

/// Creates a [SequentialStrategy] that queues actions and processes them one at a time.
///
/// Unlike [takeLeading] which ignores new actions, this strategy waits for
/// the current action to complete before processing the next one in order.
///
/// Example:
/// ```dart
/// b.onWithStrategy<SaveAction>(
///   sequential(),
///   (state, action) async* {
///     await api.save(action.data);
///     yield SavedAction();
///   },
/// );
/// ```
ExecutionStrategy sequential() => SequentialStrategy();
