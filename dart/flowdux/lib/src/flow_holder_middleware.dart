import 'dart:async';

import 'action.dart';
import 'middleware.dart';
import 'store_logger.dart';
import 'strategy/execution_strategy.dart';

/// Internal middleware that processes FlowHolderAction with its execution strategy.
///
/// This middleware is automatically added to the end of the middleware chain
/// by the Store. It handles:
/// - Applying the action's [ExecutionStrategy]
/// - Recursive processing of nested FlowHolderActions
/// - Logging of emitted actions
///
/// The [dispatch] function is used when a FlowHolderAction has
/// [FlowActionDelivery.dispatch] delivery mode.
class FlowHolderMiddleware<S, A extends Action> extends Middleware<S, A> {
  final StoreLogger<S, A> _logger;
  final void Function(A) _dispatch;

  /// Cached wrapped processors for FlowHolderActions, keyed by runtimeType.
  final Map<Type, Stream<A> Function(S, A)> _wrappedProcessors = {};

  /// Creates a [FlowHolderMiddleware] with the specified [logger] and [dispatch].
  FlowHolderMiddleware(StoreLogger<S, A> logger, void Function(A) dispatch)
      : _logger = logger,
        _dispatch = dispatch;

  @override
  Stream<A> process(S Function() getState, A action) {
    if (action is! FlowHolderAction) {
      return Stream.value(action);
    }

    final wrapped = _getOrCreateWrappedProcessor(action);

    switch (action.delivery) {
      case FlowActionDelivery.emit:
        return wrapped(getState(), action).asyncExpand(
          (innerAction) => _processRecursively(getState, innerAction),
        );

      case FlowActionDelivery.dispatch:
        // Re-dispatch through full pipeline; nested FlowHolderActions
        // will be processed when they reach this middleware again
        return wrapped(getState(), action).asyncExpand((innerAction) {
          _dispatch(innerAction);
          return const Stream.empty();
        });
    }
  }

  /// Recursively processes nested FlowHolderActions.
  Stream<A> _processRecursively(S Function() getState, A action) {
    _logger.onFlowHolderActionEmitted(action);
    if (action is FlowHolderAction) {
      return process(getState, action);
    }
    return Stream.value(action);
  }

  /// Gets or creates a wrapped processor for the given FlowHolderAction type.
  Stream<A> Function(S, A) _getOrCreateWrappedProcessor(FlowHolderAction action) {
    return _wrappedProcessors.putIfAbsent(action.runtimeType, () {
      Stream<A> baseProcessor(S state, A a) {
        return (a as FlowHolderAction).toStreamAction().cast<A>();
      }
      return action.strategy.wrap<S, A, A>(baseProcessor);
    });
  }
}
