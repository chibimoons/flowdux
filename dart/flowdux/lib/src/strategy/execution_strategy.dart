import '../action.dart';

/// Categories of execution strategies for validation during chaining.
enum StrategyCategory {
  /// Timing strategies control when to execute (debounce, throttle)
  timing,

  /// Concurrency strategies control how to handle concurrent executions (takeLatest, takeLeading, sequential)
  concurrency,

  /// Resilience strategies control how to handle failures (retry, retryWithBackoff)
  resilience,

  /// Chained strategies composed of multiple strategies
  chained,
}

/// Defines how action processors should handle concurrent executions.
///
/// Execution strategies wrap processor functions to control their execution behavior.
/// For example, [TakeLatest] cancels previous executions when a new action arrives,
/// while [Debounce] delays execution until no new actions arrive.
abstract class ExecutionStrategy {
  /// The category of this strategy, used for validation during chaining.
  StrategyCategory get category;

  /// Wraps a processor function with this strategy's behavior.
  ///
  /// The wrapped function manages the execution lifecycle according to the strategy.
  Stream<A> Function(S state, T action) wrap<S, A extends Action, T extends A>(
    Stream<A> Function(S state, T action) processor,
  );
}
