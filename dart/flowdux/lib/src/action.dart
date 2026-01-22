import 'strategy/execution_strategy.dart';
import 'strategy/take_latest.dart';

/// Base interface for all actions.
///
/// All action classes should implement this interface.
/// Actions should be immutable.
abstract class Action {}

/// Action that emits multiple actions via a Stream.
///
/// When dispatched, the Store automatically subscribes to the Stream
/// and dispatches each emitted action individually.
///
/// By default, FlowHolderAction uses [TakeLatestStrategy], meaning that when a new
/// FlowHolderAction of the same type is dispatched, the previous one's
/// stream will be cancelled. Override [strategy] to use a different
/// execution strategy.
///
/// Example:
/// ```dart
/// class BatchAction with FlowHolderAction {
///   final List<Action> actions;
///   BatchAction(this.actions);
///
///   @override
///   Stream<Action> toStreamAction() => Stream.fromIterable(actions);
/// }
///
/// class FetchAndProcessAction with FlowHolderAction {
///   @override
///   Stream<Action> toStreamAction() async* {
///     yield LoadingAction();
///     final data = await fetchData();
///     yield DataLoadedAction(data);
///   }
/// }
///
/// // Concurrent FlowHolderAction (multiple can run concurrently)
/// class ConcurrentStreamAction with FlowHolderAction {
///   @override
///   ExecutionStrategy get strategy => concurrent();
///
///   @override
///   Stream<Action> toStreamAction() async* { ... }
/// }
/// ```
/// Use `with FlowHolderAction` to inherit the default [strategy] value,
/// or `implements FlowHolderAction` and provide your own implementation.
abstract mixin class FlowHolderAction implements Action {
  /// Returns a Stream of actions to be dispatched.
  Stream<Action> toStreamAction();

  /// The execution strategy for this action.
  ///
  /// Default is [TakeLatestStrategy]. Override to use a different strategy.
  /// Use [concurrent()] to allow multiple streams to run simultaneously.
  ExecutionStrategy get strategy => TakeLatestStrategy();
}
