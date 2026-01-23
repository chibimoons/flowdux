import 'dart:async';

import 'package:rxdart/rxdart.dart';

import 'action.dart';
import 'error_processor.dart';
import 'flow_holder_middleware.dart';
import 'middleware.dart';
import 'reducer.dart';
import 'store_logger.dart';

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
  final FlowHolderMiddleware<S, A> _flowHolderMiddleware;
  final ErrorProcessor<A> _errorProcessor;
  final StoreLogger<S, A> _logger;

  /// All middlewares including FlowHolderMiddleware at the end.
  late final List<Middleware<S, A>> _allMiddlewares;

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
        _flowHolderMiddleware = FlowHolderMiddleware<S, A>(logger),
        _errorProcessor = errorProcessor,
        _logger = logger {
    _allMiddlewares = [..._middlewares, _flowHolderMiddleware];
    _currentState = initialState;
    _state = _buildStateStream(initialState);
    _subscription = _state.listen(null);
  }

  /// Builds the complete action → state stream pipeline.
  /// Similar to Kotlin's stateFlow construction.
  ///
  /// Uses distinct() to prevent emitting consecutive identical states,
  /// matching Kotlin StateFlow's built-in distinctUntilChanged behavior.
  /// The initial state is injected via startWith() before distinct() to ensure
  /// the first action resulting in the same state is filtered correctly.
  ValueStream<S> _buildStateStream(S initialState) {
    return _actionController.stream
        .doOnData(_logger.onActionDispatched)
        .flatMap(_processAction)
        .map(_reduceAction)
        .doOnError(_handleError)
        .startWith(initialState)
        .distinct()
        .shareValue();
  }

  /// Processes an action through middlewares.
  /// Equivalent to Kotlin's processAction function.
  Stream<A> _processAction(A action) {
    return _processMiddlewares(action)
        .doOnData(_logger.onMiddlewaresCompleted)
        .onErrorResume((error, stackTrace) {
          _handleError(error, stackTrace);
          return _errorProcessor
              .process(error, stackTrace)
              .doOnData(_logger.onErrorHandled);
        });
  }

  /// Processes an action through all middlewares concurrently.
  /// FlowHolderMiddleware is included at the end of the chain.
  ///
  /// Uses flatMap instead of asyncExpand to allow concurrent processing
  /// of emitted actions. This prevents blocking when a middleware emits
  /// FlowHolderActions with infinite streams.
  Stream<A> _processMiddlewares(A action) {
    Stream<A> currentStream = Stream.value(action);

    for (final middleware in _allMiddlewares) {
      currentStream = currentStream.flatMap((currentAction) {
        _logger.onMiddlewareProcessing(middleware.name, currentAction);
        return middleware.process(() => currentState, currentAction);
      });
    }

    return currentStream;
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
