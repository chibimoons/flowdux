import 'dart:async';
import 'dart:math';

import '../action.dart';
import '../cancellation_exception.dart';
import 'execution_strategy.dart';

/// Retries failed processor executions with exponential backoff.
///
/// Use this strategy when you want to automatically retry operations
/// that may fail transiently, with increasing delays between retries
/// to avoid overwhelming the system.
///
/// The delay formula is:
/// ```
/// baseDelay = initialDelay * (factor ^ attempt)
/// cappedDelay = min(baseDelay, maxDelay)
/// jitterAmount = cappedDelay * jitter * random(-1, 1)
/// finalDelay = max(cappedDelay + jitterAmount, 0)
/// ```
///
/// Example:
/// ```dart
/// b.onWithStrategy<FetchDataAction>(
///   retryWithBackoff(
///     3,
///     Duration(milliseconds: 100),
///     maxDelay: Duration(seconds: 10),
///     jitter: 0.1,
///   ),
///   (state, action) async* {
///     final data = await api.fetchData();
///     yield DataLoadedAction(data);
///   },
/// );
/// ```
class RetryWithBackoffStrategy implements ExecutionStrategy {
  /// The maximum number of attempts including the initial one.
  final int maxAttempts;

  /// The delay before the first retry attempt.
  final Duration initialDelay;

  /// The maximum delay between retries (caps exponential growth).
  final Duration maxDelay;

  /// The exponential multiplier applied to delays.
  final double factor;

  /// Randomness factor (0.0 to 1.0) to prevent thundering herd.
  final double jitter;

  /// Predicate to determine if an error should trigger a retry.
  ///
  /// [CancellationException] is never retried regardless of this predicate.
  final bool Function(Object error) retryIf;

  final Random _random;

  /// Creates a retry with backoff strategy.
  ///
  /// [maxAttempts] is the total number of attempts including the initial one.
  /// Must be >= 1.
  ///
  /// [initialDelay] is the delay before the first retry.
  ///
  /// [maxDelay] caps the exponential growth. Defaults to a very large duration.
  ///
  /// [factor] is the exponential multiplier. Must be >= 1.0. Defaults to 2.0.
  ///
  /// [jitter] adds randomness to delays to prevent thundering herd.
  /// Value between 0.0 (no jitter) and 1.0 (full jitter). Defaults to 0.0.
  ///
  /// [retryIf] determines whether an error should trigger a retry.
  /// Defaults to retrying all errors except [CancellationException].
  RetryWithBackoffStrategy({
    required this.maxAttempts,
    required this.initialDelay,
    Duration? maxDelay,
    this.factor = 2.0,
    this.jitter = 0.0,
    bool Function(Object error)? retryIf,
    Random? random,
  })  : assert(maxAttempts >= 1, 'maxAttempts must be >= 1'),
        assert(factor >= 1.0, 'factor must be >= 1.0'),
        assert(
          jitter >= 0.0 && jitter <= 1.0,
          'jitter must be between 0.0 and 1.0',
        ),
        maxDelay = maxDelay ?? const Duration(days: 365 * 100),
        retryIf = retryIf ?? ((_) => true),
        _random = random ?? Random();

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

  Duration _calculateDelay(int attemptNumber) {
    // baseDelay = initialDelay * (factor ^ attempt)
    final baseDelayMs =
        initialDelay.inMicroseconds * pow(factor, attemptNumber);

    // cappedDelay = min(baseDelay, maxDelay)
    final cappedDelayMs = min(baseDelayMs, maxDelay.inMicroseconds.toDouble());

    // jitterAmount = cappedDelay * jitter * random(-1, 1)
    final jitterAmount =
        cappedDelayMs * jitter * (_random.nextDouble() * 2 - 1);

    // finalDelay = max(cappedDelay + jitterAmount, 0)
    final finalDelayMs = max(cappedDelayMs + jitterAmount, 0);

    return Duration(microseconds: finalDelayMs.round());
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
          // Calculate delay and schedule retry
          final delay = _calculateDelay(attemptNumber);
          Timer(delay, () {
            _executeWithRetry(
              processor: processor,
              state: state,
              action: action,
              controller: controller,
              attemptNumber: nextAttempt,
            );
          });
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

/// Creates a [RetryWithBackoffStrategy] that retries failed executions
/// with exponential backoff.
///
/// [maxAttempts] is the total number of attempts including the initial one.
/// Must be >= 1.
///
/// [initialDelay] is the delay before the first retry.
///
/// [maxDelay] caps the exponential growth. Defaults to a very large duration.
///
/// [factor] is the exponential multiplier. Must be >= 1.0. Defaults to 2.0.
///
/// [jitter] adds randomness to delays to prevent thundering herd.
/// Value between 0.0 and 1.0. Defaults to 0.0.
///
/// [retryIf] determines whether an error should trigger a retry.
/// [CancellationException] is never retried regardless of this predicate.
///
/// Example:
/// ```dart
/// b.onWithStrategy<FetchDataAction>(
///   retryWithBackoff(
///     3,
///     Duration(milliseconds: 100),
///     maxDelay: Duration(seconds: 10),
///     factor: 2.0,
///     jitter: 0.1,
///   ),
///   (state, action) async* {
///     final data = await api.fetchData();
///     yield DataLoadedAction(data);
///   },
/// );
/// ```
ExecutionStrategy retryWithBackoff(
  int maxAttempts,
  Duration initialDelay, {
  Duration? maxDelay,
  double factor = 2.0,
  double jitter = 0.0,
  bool Function(Object error)? retryIf,
}) =>
    RetryWithBackoffStrategy(
      maxAttempts: maxAttempts,
      initialDelay: initialDelay,
      maxDelay: maxDelay,
      factor: factor,
      jitter: jitter,
      retryIf: retryIf,
    );
