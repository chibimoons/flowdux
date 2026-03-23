import 'package:rxdart/rxdart.dart';

import '../action.dart';
import '../error_processor.dart';
import '../middleware.dart';
import '../reducer.dart';
import '../store.dart';
import '../store_logger.dart';
import '../util/async_lock.dart';
import 'state_snapshot.dart';

/// A store wrapper that provides time travel debugging capabilities.
///
/// TimeTravelStore wraps a regular [Store] and tracks all state changes
/// in a history list, enabling undo, redo, and jump-to operations.
///
/// Example:
/// ```dart
/// final store = createTimeTravelStore<CounterState, CounterAction>(
///   initialState: CounterState(0),
///   reducer: counterReducer,
/// );
///
/// store.dispatch(IncrementAction());
/// store.dispatch(IncrementAction());
/// await store.undo(); // Back to count: 1
/// await store.redo(); // Forward to count: 2
/// await store.jumpTo(0); // Back to initial state
/// ```
class TimeTravelStore<S, A extends Action> {
  final Store<S, A> _innerStore;

  /// Maximum number of history entries to keep.
  final int maxHistorySize;

  final AsyncLock _lock = AsyncLock();
  final List<StateSnapshot<S, A>> _history = [];
  int _currentIndex = 0;
  final BehaviorSubject<S> _stateSubject;

  TimeTravelStore._({
    required Store<S, A> innerStore,
    required this.maxHistorySize,
    required S seedState,
    List<StateSnapshot<S, A>>? initialHistory,
  })  : _innerStore = innerStore,
        _stateSubject = BehaviorSubject.seeded(seedState) {
    if (initialHistory != null && initialHistory.isNotEmpty) {
      for (var i = 0; i < initialHistory.length; i++) {
        _history.add(initialHistory[i].copyWith(index: i));
      }
      _currentIndex = _history.length - 1;
    } else {
      _history.add(StateSnapshot(
        index: 0,
        currentState: seedState,
        timestamp: DateTime.now(),
      ));
    }
  }

  /// Stream of state changes.
  ///
  /// Emits the current state immediately upon subscription,
  /// then emits new states whenever the state changes.
  Stream<S> get state => _stateSubject.stream;

  /// Current state value (synchronous access).
  S get currentState => _stateSubject.value;

  /// A copy of the current history list.
  List<StateSnapshot<S, A>> get history => List.unmodifiable(_history);

  /// Current position in the history.
  int get currentIndex => _currentIndex;

  /// Whether undo is available (not at the beginning of history).
  bool get canUndo => _currentIndex > 0;

  /// Whether redo is available (not at the end of history).
  bool get canRedo => _currentIndex < _history.length - 1;

  /// Whether the store has been closed.
  bool get isClosed => _innerStore.isClosed;

  /// Records a state change in the history.
  void _recordStateChange(A action, S previousState, S newState) {
    // Truncate future history if we're not at the end
    if (_currentIndex < _history.length - 1) {
      while (_history.length > _currentIndex + 1) {
        _history.removeLast();
      }
    }

    final newIndex = _history.length;
    _history.add(StateSnapshot(
      index: newIndex,
      action: action,
      previousState: previousState,
      currentState: newState,
      timestamp: DateTime.now(),
    ));

    // Enforce max history size
    while (_history.length > maxHistorySize && _history.length > 1) {
      _history.removeAt(0);
      for (var i = 0; i < _history.length; i++) {
        if (_history[i].index != i) {
          _history[i] = _history[i].copyWith(index: i);
        }
      }
    }

    _currentIndex = _history.length - 1;
    if (newState != _stateSubject.value) {
      _stateSubject.add(newState);
    }
  }

  /// Dispatches an action to the underlying store.
  void dispatch(A action) {
    _innerStore.dispatch(action);
  }

  /// Moves to the previous state in history.
  ///
  /// Returns `true` if undo was successful, `false` if already at the beginning.
  Future<bool> undo() => _lock.synchronized(() async {
        if (!canUndo) return false;
        _currentIndex--;
        final newState = _history[_currentIndex].currentState;
        if (newState != _stateSubject.value) {
          _stateSubject.add(newState);
        }
        return true;
      });

  /// Moves to the next state in history.
  ///
  /// Returns `true` if redo was successful, `false` if already at the end.
  Future<bool> redo() => _lock.synchronized(() async {
        if (!canRedo) return false;
        _currentIndex++;
        final newState = _history[_currentIndex].currentState;
        if (newState != _stateSubject.value) {
          _stateSubject.add(newState);
        }
        return true;
      });

  /// Jumps to a specific index in the history.
  ///
  /// Returns `true` if jump was successful, `false` if the index is out of bounds.
  Future<bool> jumpTo(int index) => _lock.synchronized(() async {
        if (index < 0 || index >= _history.length) return false;
        _currentIndex = index;
        final newState = _history[_currentIndex].currentState;
        if (newState != _stateSubject.value) {
          _stateSubject.add(newState);
        }
        return true;
      });

  /// Resets to the initial state (index 0).
  ///
  /// History is preserved. Returns `true` if successful.
  Future<bool> reset() => jumpTo(0);

  /// Clears all history and keeps only the current state as the new initial state.
  Future<void> clear() => _lock.synchronized(() async {
        final current = _stateSubject.value;
        _history.clear();
        _history.add(StateSnapshot(
          index: 0,
          currentState: current,
          timestamp: DateTime.now(),
        ));
        _currentIndex = 0;
      });

  /// Closes the underlying store and releases resources.
  ///
  /// This method is idempotent - calling it multiple times has no effect.
  Future<void> close() async {
    await _innerStore.close();
    if (!_stateSubject.isClosed) {
      await _stateSubject.close();
    }
  }
}

/// Creates a new [TimeTravelStore] with the given configuration.
///
/// Example:
/// ```dart
/// final store = createTimeTravelStore<CounterState, CounterAction>(
///   initialState: CounterState(0),
///   reducer: counterReducer,
///   maxHistorySize: 50,
/// );
/// ```
TimeTravelStore<S, A> createTimeTravelStore<S, A extends Action>({
  required S initialState,
  required Reducer<S, A> reducer,
  List<Middleware<S, A>> middlewares = const [],
  ErrorProcessor<A>? errorProcessor,
  int maxHistorySize = 100,
}) {
  return _createTimeTravelStoreInternal(
    initialState: initialState,
    initialHistory: null,
    reducer: reducer,
    middlewares: middlewares,
    errorProcessor: errorProcessor,
    maxHistorySize: maxHistorySize,
  );
}

/// Creates a [TimeTravelStore] from a previously saved history.
///
/// Throws [ArgumentError] if [initialHistory] is empty.
///
/// Example:
/// ```dart
/// final store = createTimeTravelStoreFromHistory<CounterState, CounterAction>(
///   initialHistory: savedHistory,
///   reducer: counterReducer,
/// );
/// ```
TimeTravelStore<S, A> createTimeTravelStoreFromHistory<S, A extends Action>({
  required List<StateSnapshot<S, A>> initialHistory,
  required Reducer<S, A> reducer,
  List<Middleware<S, A>> middlewares = const [],
  ErrorProcessor<A>? errorProcessor,
  int maxHistorySize = 100,
}) {
  if (initialHistory.isEmpty) {
    throw ArgumentError('initialHistory must not be empty');
  }
  return _createTimeTravelStoreInternal(
    initialState: null,
    initialHistory: initialHistory,
    reducer: reducer,
    middlewares: middlewares,
    errorProcessor: errorProcessor,
    maxHistorySize: maxHistorySize,
  );
}

TimeTravelStore<S, A> _createTimeTravelStoreInternal<S, A extends Action>({
  S? initialState,
  List<StateSnapshot<S, A>>? initialHistory,
  required Reducer<S, A> reducer,
  required List<Middleware<S, A>> middlewares,
  ErrorProcessor<A>? errorProcessor,
  required int maxHistorySize,
}) {
  late TimeTravelStore<S, A> timeTravelStore;

  S timeTravelReducer(S _, A action) {
    return reducer(timeTravelStore.currentState, action);
  }

  final historyLogger = _TimeTravelLogger<S, A>(
    (action, previousState, newState) {
      timeTravelStore._recordStateChange(
        action,
        timeTravelStore.currentState,
        newState,
      );
    },
  );

  final effectiveInitialState =
      initialHistory != null && initialHistory.isNotEmpty
          ? initialHistory.last.currentState
          : initialState!;

  final innerStore = createStore<S, A>(
    initialState: effectiveInitialState,
    reducer: timeTravelReducer,
    middlewares: middlewares,
    errorProcessor: errorProcessor,
    logger: historyLogger,
  );

  timeTravelStore = TimeTravelStore<S, A>._(
    innerStore: innerStore,
    maxHistorySize: maxHistorySize,
    seedState: effectiveInitialState,
    initialHistory: initialHistory,
  );

  return timeTravelStore;
}

/// Internal logger that records state changes for time travel.
///
/// Implements [StoreLogger] directly (not extending [NoOpStoreLogger])
/// so that the Store's logging pipeline is active and [onStateReduced]
/// is called for every state transition.
class _TimeTravelLogger<S, A extends Action> implements StoreLogger<S, A> {
  final void Function(A action, S previousState, S newState) _onStateReduced;

  _TimeTravelLogger(this._onStateReduced);

  @override
  void onStateReduced(A action, S previousState, S newState) {
    _onStateReduced(action, previousState, newState);
  }

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
  void onDispatchAfterClose(A action) {}
}
