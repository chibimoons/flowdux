import 'dart:async';

import '../action.dart';
import '../cancellation_exception.dart';
import 'execution_strategy.dart';

/// Retries failed processor executions.
///
/// Use this strategy when you want to automatically retry operations
/// that may fail transiently, such as network requests.
///
/// Example:
/// ```dart
/// b.onWithStrategy<FetchDataAction>(
///   retry(3),
///   (state, action) async* {
///     final data = await api.fetchData();
///     yield DataLoadedAction(data);
///   },
/// );
/// ```
class RetryStrategy implements ExecutionStrategy {
  /// The maximum number of attempts including the initial one.
  final int maxAttempts;

  /// Predicate to determine if an error should trigger a retry.
  ///
  /// [CancellationException] is never retried regardless of this predicate.
  final bool Function(Object error) retryIf;

  /// Creates a retry strategy.
  ///
  /// [maxAttempts] is the total number of attempts including the initial one.
  /// Must be >= 1.
  ///
  /// [retryIf] is a predicate that determines whether an error should trigger
  /// a retry. Defaults to retrying all errors except [CancellationException].
  RetryStrategy({
    required this.maxAttempts,
    bool Function(Object error)? retryIf,
  })  : assert(maxAttempts >= 1, 'maxAttempts must be >= 1'),
        retryIf = retryIf ?? ((_) => true);

  @override
  StrategyCategory get category => StrategyCategory.resilience;

  @override
  Stream<A> Function(S state, T action) wrap<S, A extends Action, T extends A>(
    Stream<A> Function(S state, T action) processor,
  ) {
    return (state, action) {
      final controller = StreamController<A>();

      _executeWithRetry(
        processor: processor,
        state: state,
        action: action,
        controller: controller,
        attemptNumber: 0,
      );

      return controller.stream;
    };
  }

  void _executeWithRetry<S, A extends Action, T extends A>({
    required Stream<A> Function(S state, T action) processor,
    required S state,
    required T action,
    required StreamController<A> controller,
    required int attemptNumber,
  }) {
    if (controller.isClosed) return;

    processor(state, action).listen(
      (result) {
        if (!controller.isClosed) {
          controller.add(result);
        }
      },
      onError: (Object error, StackTrace stackTrace) {
        if (controller.isClosed) return;

        // Never retry CancellationException
        if (error is CancellationException) {
          controller.addError(error, stackTrace);
          controller.close();
          return;
        }

        // Check if we should retry
        final nextAttempt = attemptNumber + 1;
        if (nextAttempt < maxAttempts && retryIf(error)) {
          // Retry immediately
          _executeWithRetry(
            processor: processor,
            state: state,
            action: action,
            controller: controller,
            attemptNumber: nextAttempt,
          );
        } else {
          // All attempts exhausted or error not retryable
          controller.addError(error, stackTrace);
          controller.close();
        }
      },
      onDone: () {
        if (!controller.isClosed) {
          controller.close();
        }
      },
      cancelOnError: true,
    );
  }
}

/// Creates a [RetryStrategy] that retries failed executions.
///
/// [maxAttempts] is the total number of attempts including the initial one.
/// Must be >= 1.
///
/// [retryIf] is an optional predicate that determines whether an error
/// should trigger a retry. [CancellationException] is never retried regardless
/// of this predicate.
///
/// Example:
/// ```dart
/// b.onWithStrategy<FetchDataAction>(
///   retry(3),
///   (state, action) async* {
///     final data = await api.fetchData();
///     yield DataLoadedAction(data);
///   },
/// );
///
/// // Only retry network errors
/// b.onWithStrategy<FetchDataAction>(
///   retry(3, retryIf: (e) => e is NetworkException),
///   (state, action) async* {
///     final data = await api.fetchData();
///     yield DataLoadedAction(data);
///   },
/// );
/// ```
ExecutionStrategy retry(
  int maxAttempts, {
  bool Function(Object error)? retryIf,
}) =>
    RetryStrategy(
      maxAttempts: maxAttempts,
      retryIf: retryIf,
    );
