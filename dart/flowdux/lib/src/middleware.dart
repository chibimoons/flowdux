import 'action.dart';
import 'processor.dart';
import 'strategy/execution_strategy.dart';

/// Abstract base class for middleware.
///
/// Middleware intercepts actions before they reach the reducer,
/// allowing for side effects, async operations, and action transformation.
///
/// ## Basic Usage
///
/// Register handlers directly in the constructor:
///
/// ```dart
/// class CounterMiddleware extends Middleware<CounterState, CounterAction> {
///   CounterMiddleware() {
///     // Simple handler without strategy
///     on<FetchCountAction>((state, action) async* {
///       yield LoadingAction();
///       final count = await api.fetchCount();
///       yield SetCountAction(count);
///     });
///
///     // Handler with execution strategy
///     apply(takeLatest())
///       .on<SearchAction>((state, action) async* {
///         final results = await api.search(action.query);
///         yield SearchResultsAction(results);
///       });
///
///     // Multiple handlers sharing the same strategy
///     apply(debounce(Duration(milliseconds: 300)).then(takeLatest()))
///       .on<RefreshAction>((state, action) async* { ... })
///       .on<ReloadAction>((state, action) async* { ... });
///   }
/// }
/// ```
abstract class Middleware<S, A extends Action> {
  final Map<Type, Processor<S, A>> _processors = {};

  /// The name of this middleware, used for logging.
  ///
  /// Defaults to the runtime type name.
  String get name => runtimeType.toString();

  /// Map of action types to their processors.
  ///
  /// Returns an unmodifiable view of the registered processors.
  Map<Type, Processor<S, A>> get processors => Map.unmodifiable(_processors);

  /// Registers a handler for a specific action type without an execution strategy.
  ///
  /// Throws [DuplicateProcessorException] if a handler for [T] is already registered.
  ///
  /// Example:
  /// ```dart
  /// on<FetchDataAction>((state, action) async* {
  ///   yield LoadingAction();
  ///   final data = await api.fetchData();
  ///   yield DataLoadedAction(data);
  /// });
  /// ```
  void on<T extends A>(Stream<A> Function(S state, T action) handler) {
    _checkDuplicate(T);
    _processors[T] = Processor<S, A>(
      process: (state, action) => handler(state, action as T),
    );
  }

  /// Creates a strategy builder for registering handlers with an execution strategy.
  ///
  /// Returns a [StrategyBuilder] that allows chaining multiple handlers
  /// that share the same strategy instance.
  ///
  /// Example:
  /// ```dart
  /// // Single handler with strategy
  /// apply(takeLatest())
  ///   .on<SearchAction>((state, action) async* {
  ///     final results = await api.search(action.query);
  ///     yield SearchResultsAction(results);
  ///   });
  ///
  /// // Multiple handlers sharing a strategy
  /// apply(debounce(Duration(milliseconds: 300)).then(takeLatest()))
  ///   .on<RefreshAction>((state, action) async* { ... })
  ///   .on<ReloadAction>((state, action) async* { ... });
  /// ```
  StrategyBuilder<S, A> apply(ExecutionStrategy strategy) {
    return StrategyBuilder<S, A>(_processors, strategy);
  }

  void _checkDuplicate(Type actionType) {
    if (_processors.containsKey(actionType)) {
      throw DuplicateProcessorException(actionType);
    }
  }

  /// Processes an action and returns a stream of result actions.
  ///
  /// If no processor is registered for the action type, the original action
  /// is emitted unchanged.
  Stream<A> process(S Function() getState, A action) async* {
    final processor = processors[action.runtimeType];
    if (processor != null) {
      yield* processor.process(getState(), action);
    } else {
      yield action;
    }
  }
}

/// Builder for registering handlers with a shared execution strategy.
///
/// Supports method chaining for registering multiple handlers.
///
/// Example:
/// ```dart
/// apply(takeLatest())
///   .on<SearchAction>((state, action) async* { ... })
///   .on<FilterAction>((state, action) async* { ... });
/// ```
class StrategyBuilder<S, A extends Action> {
  final Map<Type, Processor<S, A>> _processors;
  final ExecutionStrategy _strategy;

  /// Creates a strategy builder that adds processors to the given map.
  StrategyBuilder(this._processors, this._strategy);

  /// Registers a handler for a specific action type with the shared strategy.
  ///
  /// Returns this builder for method chaining.
  ///
  /// Throws [DuplicateProcessorException] if a handler for [T] is already registered.
  StrategyBuilder<S, A> on<T extends A>(
    Stream<A> Function(S state, T action) handler,
  ) {
    if (_processors.containsKey(T)) {
      throw DuplicateProcessorException(T);
    }
    final wrappedProcessor = _strategy.wrap<S, A, T>(handler);
    _processors[T] = Processor<S, A>(
      process: (state, action) => wrappedProcessor(state, action as T),
    );
    return this;
  }
}
