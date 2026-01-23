import 'action.dart';

/// Logger interface for Store debugging and monitoring.
///
/// Implement this interface to receive callbacks for various store events.
abstract class StoreLogger<S, A extends Action> {
  /// Called when an action is dispatched to the store.
  void onActionDispatched(A action);

  /// Called before a middleware processes an action.
  void onMiddlewareProcessing(String middlewareName, A action);

  /// Called after all middlewares have finished processing an action.
  void onMiddlewaresCompleted(A action);

  /// Called for each action emitted by a FlowHolderAction.
  void onFlowHolderActionEmitted(A action);

  /// Called when an error occurs in the middleware chain.
  void onErrorOccurred(Object error, StackTrace stackTrace);

  /// Called for each action emitted by the ErrorProcessor.
  void onErrorHandled(A action);

  /// Called after the reducer produces a new state.
  void onStateReduced(A action, S previousState, S newState);

  /// Called when dispatch() is invoked after the store has been closed.
  void onDispatchAfterClose(A action);
}

/// No-op logger that does nothing.
///
/// Used as the default logger when no logger is provided.
class NoOpStoreLogger<S, A extends Action> implements StoreLogger<S, A> {
  /// Creates a [NoOpStoreLogger] that does nothing.
  NoOpStoreLogger();

  @override
  void onActionDispatched(A action) {}

  @override
  void onMiddlewareProcessing(String middlewareName, A action) {}

  @override
  void onMiddlewaresCompleted(A action) {}

  @override
  void onFlowHolderActionEmitted(A action) {}

  @override
  void onErrorOccurred(Object error, StackTrace stackTrace) {}

  @override
  void onErrorHandled(A action) {}

  @override
  void onStateReduced(A action, S previousState, S newState) {}

  @override
  void onDispatchAfterClose(A action) {}
}

/// Debug logger that prints all events to the console.
///
/// Useful for development and debugging.
class DebugStoreLogger<S, A extends Action> implements StoreLogger<S, A> {
  /// Tag prefix for log messages.
  final String tag;

  /// Creates a debug logger with an optional tag.
  DebugStoreLogger({this.tag = 'Store'});

  @override
  void onActionDispatched(A action) {
    print('[$tag] Dispatched: $action');
  }

  @override
  void onMiddlewareProcessing(String middlewareName, A action) {
    print('[$tag] Middleware($middlewareName) processing: $action');
  }

  @override
  void onMiddlewaresCompleted(A action) {
    print('[$tag] Middlewares completed: $action');
  }

  @override
  void onFlowHolderActionEmitted(A action) {
    print('[$tag] FlowHolderAction emitted: $action');
  }

  @override
  void onErrorOccurred(Object error, StackTrace stackTrace) {
    print('[$tag] Error: $error');
  }

  @override
  void onErrorHandled(A action) {
    print('[$tag] Error handled with: $action');
  }

  @override
  void onStateReduced(A action, S previousState, S newState) {
    print('[$tag] State reduced: $action');
    print('[$tag]   Previous: $previousState');
    print('[$tag]   New: $newState');
  }

  @override
  void onDispatchAfterClose(A action) {
    print('[$tag] WARNING: Dispatch after close: $action');
  }
}
