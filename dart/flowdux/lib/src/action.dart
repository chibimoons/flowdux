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
/// By default, FlowHolderAction is cancelable, meaning that when a new
/// FlowHolderAction of the same type is dispatched, the previous one's
/// stream will be cancelled. Override [cancelable] to return false
/// if you want multiple streams of the same type to run concurrently.
///
/// Example:
/// ```dart
/// class BatchAction implements FlowHolderAction {
///   final List<Action> actions;
///   BatchAction(this.actions);
///
///   @override
///   Stream<Action> toStreamAction() => Stream.fromIterable(actions);
/// }
///
/// class FetchAndProcessAction implements FlowHolderAction {
///   @override
///   Stream<Action> toStreamAction() async* {
///     yield LoadingAction();
///     final data = await fetchData();
///     yield DataLoadedAction(data);
///   }
/// }
///
/// // Non-cancelable FlowHolderAction (multiple can run concurrently)
/// class ConcurrentStreamAction implements FlowHolderAction {
///   @override
///   bool get cancelable => false;
///
///   @override
///   Stream<Action> toStreamAction() async* { ... }
/// }
/// ```
/// Use `with FlowHolderAction` to inherit the default [cancelable] value,
/// or `implements FlowHolderAction` and provide your own implementation.
abstract mixin class FlowHolderAction implements Action {
  /// Returns a Stream of actions to be dispatched.
  Stream<Action> toStreamAction();

  /// Whether this action's stream should be cancelled when a new
  /// FlowHolderAction of the same type is dispatched.
  ///
  /// Default is true. Override to return false for concurrent execution.
  bool get cancelable => true;
}
