import '../action.dart';
import 'execution_strategy.dart';

/// Allows concurrent executions without any coordination.
/// All actions execute independently without cancelling previous ones.
///
/// Use this strategy when you need multiple streams to run simultaneously,
/// such as independent background tasks or parallel operations.
///
/// Example:
/// ```dart
/// class BackgroundSyncAction with FlowHolderAction {
///   @override
///   ExecutionStrategy get strategy => concurrent();
///
///   @override
///   Stream<Action> toStreamAction() async* {
///     yield SyncStartedAction();
///     // ... sync logic
///     yield SyncCompletedAction();
///   }
/// }
/// ```
class ConcurrentStrategy implements ExecutionStrategy {
  /// Creates a [ConcurrentStrategy] that allows concurrent executions.
  ConcurrentStrategy();

  @override
  StrategyCategory get category => StrategyCategory.concurrency;

  @override
  Stream<A> Function(S state, T action) wrap<S, A extends Action, T extends A>(
    Stream<A> Function(S state, T action) processor,
  ) {
    // Pass through without any coordination
    return processor;
  }
}

/// Creates a [ConcurrentStrategy] that allows concurrent executions.
///
/// Example:
/// ```dart
/// class BackgroundSyncAction with FlowHolderAction {
///   @override
///   ExecutionStrategy get strategy => concurrent();
///
///   @override
///   Stream<Action> toStreamAction() async* { ... }
/// }
/// ```
ExecutionStrategy concurrent() => ConcurrentStrategy();
