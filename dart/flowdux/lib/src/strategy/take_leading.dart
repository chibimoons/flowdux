import 'dart:async';

import '../action.dart';
import 'execution_strategy.dart';

/// Ignores new actions while one is still processing.
/// Only the first action in a series will execute.
///
/// Use this strategy when you want to prevent duplicate requests,
/// such as form submissions or one-time operations.
///
/// Example:
/// ```dart
/// b.onWithStrategy<SubmitFormAction>(
///   takeLeading(),
///   (state, action) async* {
///     yield SubmittingAction();
///     await api.submitForm(action.data);
///     yield SubmittedAction();
///   },
/// );
/// ```
class TakeLeadingStrategy implements ExecutionStrategy {
  bool _isActive = false;

  @override
  StrategyCategory get category => StrategyCategory.concurrency;

  @override
  Stream<A> Function(S state, T action) wrap<S, A extends Action, T extends A>(
    Stream<A> Function(S state, T action) processor,
  ) {
    return (state, action) {
      // If already processing, return empty stream (ignore this action)
      if (_isActive) {
        return const Stream.empty();
      }

      // Mark as active before starting
      _isActive = true;

      final controller = StreamController<A>();

      processor(state, action).listen(
        controller.add,
        onError: controller.addError,
        onDone: () {
          _isActive = false;
          controller.close();
        },
        cancelOnError: false,
      );

      // Handle cancellation
      controller.onCancel = () {
        // Note: Even if cancelled externally, we still wait for the processor
        // to complete before allowing new actions
      };

      return controller.stream;
    };
  }
}

/// Creates a [TakeLeadingStrategy] that ignores new actions while one is processing.
///
/// Example:
/// ```dart
/// b.onWithStrategy<SubmitFormAction>(
///   takeLeading(),
///   (state, action) async* {
///     yield SubmittingAction();
///     await api.submitForm(action.data);
///     yield SubmittedAction();
///   },
/// );
/// ```
ExecutionStrategy takeLeading() => TakeLeadingStrategy();
