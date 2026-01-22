import 'dart:async';

import 'package:rxdart/rxdart.dart';

import 'action.dart';
import 'error_processor.dart';
import 'middleware.dart';
import 'reducer.dart';
import 'store_logger.dart';

/// Cancellation flag for FlowHolderAction streams.
class _CancelFlag {
  bool cancelled = false;
}

/// Central state container for FlowDux.
///
/// The Store holds the application state and provides methods to:
/// - Dispatch actions to update state
/// - Subscribe to state changes via a Stream
/// - Access the current state synchronously
///
/// Example:
/// ```dart
/// final store = createStore<AppState, AppAction>(
///   initialState: AppState(),
///   reducer: appReducer,
///   middlewares: [AppMiddleware()],
/// );
///
/// store.state.listen((state) => print('State: $state'));
/// store.dispatch(IncrementAction());
/// ```
class Store<S, A extends Action> {
  final StreamController<A> _actionController;
  final Reducer<S, A> _reducer;
  final List<Middleware<S, A>> _middlewares;
  final ErrorProcessor<A> _errorProcessor;
  final StoreLogger<S, A> _logger;

  /// Active cancel flags for cancelable FlowHolderActions, keyed by runtimeType.
  final Map<Type, _CancelFlag> _activeFlags = {};

  late final ValueStream<S> _state;
  late final StreamSubscription<S> _subscription;
  late S _currentState;
  bool _isClosed = false;

  /// Creates a new Store.
  ///
  /// Prefer using [createStore] factory function instead.
  Store._({
    required S initialState,
    required Reducer<S, A> reducer,
    required List<Middleware<S, A>> middlewares,
    required ErrorProcessor<A> errorProcessor,
    required StoreLogger<S, A> logger,
  })  : _actionController = StreamController<A>.broadcast(),
        _reducer = reducer,
        _middlewares = middlewares,
        _errorProcessor = errorProcessor,
        _logger = logger {
    _currentState = initialState;
    _state = _buildStateStream(initialState);
    _subscription = _state.listen(null);
  }

  /// Builds the complete action → state stream pipeline.
  /// Similar to Kotlin's stateFlow construction.
  ValueStream<S> _buildStateStream(S initialState) {
    return _actionController.stream
        .doOnData(_logger.onActionDispatched)
        .flatMap(_processAction)
        .map(_reduceAction)
        .doOnError(_handleError)
        .shareValueSeeded(initialState);
  }

  /// Processes an action through middlewares and FlowHolderAction handling.
  /// Equivalent to Kotlin's processAction function.
  Stream<A> _processAction(A action) {
    return _processMiddlewares(action)
        .doOnData(_logger.onMiddlewaresCompleted)
        .flatMap(_processFlowHolderAction)
        .onErrorResume((error, stackTrace) {
          _handleError(error, stackTrace);
          return _errorProcessor
              .process(error, stackTrace)
              .doOnData(_logger.onErrorHandled);
        });
  }

  /// Processes an action through all middlewares sequentially.
  Stream<A> _processMiddlewares(A action) {
    if (_middlewares.isEmpty) {
      return Stream.value(action);
    }

    Stream<A> currentStream = Stream.value(action);

    for (final middleware in _middlewares) {
      currentStream = currentStream.asyncExpand((currentAction) {
        _logger.onMiddlewareProcessing(middleware.name, currentAction);
        return middleware.process(() => currentState, currentAction);
      });
    }

    return currentStream;
  }

  /// Expands FlowHolderAction into its stream of actions.
  /// Normal actions pass through unchanged.
  /// Recursively expands nested FlowHolderActions.
  ///
  /// For cancelable FlowHolderActions, cancels any previously running
  /// stream of the same type before starting the new one.
  Stream<A> _processFlowHolderAction(A action) {
    if (action is FlowHolderAction) {
      _CancelFlag? myFlag;

      if (action.cancelable) {
        final type = action.runtimeType;
        // Cancel previous stream of the same type
        _activeFlags[type]?.cancelled = true;
        // Create new flag for this stream
        myFlag = _CancelFlag();
        _activeFlags[type] = myFlag;
      }

      return action
          .toStreamAction()
          .cast<A>()
          .doOnData(_logger.onFlowHolderActionEmitted)
          .flatMap(_processFlowHolderAction)
          .takeWhile((_) => myFlag?.cancelled != true);
    }
    return Stream.value(action);
  }

  /// Reduces an action to produce a new state.
  S _reduceAction(A action) {
    final previousState = _currentState;
    final newState = _reducer(previousState, action);
    _currentState = newState;
    _logger.onStateReduced(action, previousState, newState);
    return newState;
  }

  void _handleError(Object error, StackTrace stackTrace) {
    _logger.onErrorOccurred(error, stackTrace);
  }

  /// Whether the store has been closed.
  bool get isClosed => _isClosed;

  /// Stream of state changes.
  ///
  /// Emits the current state immediately upon subscription,
  /// then emits new states whenever the state changes.
  Stream<S> get state => _state;

  /// Current state value (synchronous access).
  S get currentState => _currentState;

  /// Dispatches an action to the store.
  ///
  /// If the store has been closed, the action is logged and ignored.
  /// All actions (including FlowHolderAction) go through the stream pipeline.
  void dispatch(A action) {
    if (_isClosed) {
      _logger.onDispatchAfterClose(action);
      return;
    }

    _actionController.add(action);
  }

  /// Closes the store and releases all resources.
  ///
  /// After closing, dispatch() calls will be ignored.
  /// This method is idempotent - calling it multiple times has no effect.
  Future<void> close() async {
    if (_isClosed) return;
    _isClosed = true;

    // Cancel all active FlowHolderAction streams
    for (final flag in _activeFlags.values) {
      flag.cancelled = true;
    }
    _activeFlags.clear();

    await _subscription.cancel();
    await _actionController.close();
  }
}

/// Creates a new Store with the given configuration.
///
/// Parameters:
/// - [initialState]: The initial state of the store
/// - [reducer]: The reducer function that handles state transitions
/// - [middlewares]: Optional list of middlewares for side effects (default: empty)
/// - [errorProcessor]: Optional error processor for handling errors (default: swallows errors)
/// - [logger]: Optional logger for debugging (default: no-op)
///
/// Example:
/// ```dart
/// final store = createStore<AppState, AppAction>(
///   initialState: AppState(count: 0),
///   reducer: buildReducer((b) {
///     b.on<IncrementAction>((state, _) => state.copyWith(count: state.count + 1));
///   }),
///   middlewares: [CounterMiddleware()],
///   logger: DebugStoreLogger(),
/// );
/// ```
Store<S, A> createStore<S, A extends Action>({
  required S initialState,
  required Reducer<S, A> reducer,
  List<Middleware<S, A>> middlewares = const [],
  ErrorProcessor<A>? errorProcessor,
  StoreLogger<S, A>? logger,
}) {
  return Store._(
    initialState: initialState,
    reducer: reducer,
    middlewares: middlewares,
    errorProcessor: errorProcessor ?? DefaultErrorProcessor<A>(),
    logger: logger ?? NoOpStoreLogger<S, A>(),
  );
}
