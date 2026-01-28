import 'strategy/execution_strategy.dart';
import 'strategy/take_latest.dart';

/// Base interface for all actions.
///
/// All action classes should implement this interface.
/// Actions should be immutable.
abstract class Action {}

/// Determines how inner actions emitted by a [FlowHolderAction] are delivered.
///
/// - [emit]: Inner actions bypass user middlewares and go directly to the reducer.
///   This is the default and most efficient delivery mode.
/// - [dispatch]: Inner actions are re-dispatched through the entire middleware pipeline,
///   allowing user middlewares to observe and process them.
enum FlowActionDelivery {
  /// Inner actions go directly to the reducer, bypassing user middlewares.
  emit,

  /// Inner actions are re-dispatched through the full middleware pipeline.
  dispatch,
}

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
///
/// Example with Dispatch delivery (inner actions pass through middlewares):
/// ```dart
/// class DispatchedStreamAction with FlowHolderAction {
///   @override
///   FlowActionDelivery get delivery => FlowActionDelivery.dispatch;
///
///   @override
///   Stream<Action> toStreamAction() async* { ... }
/// }
/// ```
abstract mixin class FlowHolderAction implements Action {
  /// Returns a Stream of actions to be dispatched.
  Stream<Action> toStreamAction();

  /// The execution strategy for this action.
  ///
  /// Default is [TakeLatestStrategy]. Override to use a different strategy.
  /// Use [concurrent()] to allow multiple streams to run simultaneously.
  ExecutionStrategy get strategy => TakeLatestStrategy();

  /// Delivery mode for inner actions emitted by this FlowHolderAction.
  ///
  /// Default is [FlowActionDelivery.emit], which sends inner actions directly
  /// to the reducer, bypassing user middlewares.
  ///
  /// Override with [FlowActionDelivery.dispatch] to re-dispatch inner actions
  /// through the full middleware pipeline.
  FlowActionDelivery get delivery => FlowActionDelivery.emit;
}
