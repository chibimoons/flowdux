import 'action.dart';

/// Reducer type definition.
///
/// A pure function that takes the current state and an action,
/// and returns the new state.
typedef Reducer<S, A extends Action> = S Function(S state, A action);

/// Base class for creating reducers with type-safe action handlers.
///
/// Extend this class and register handlers in the constructor using [on].
///
/// Example:
/// ```dart
/// class AppReducer extends ReducerBase<AppState, AppAction> {
///   AppReducer() {
///     on<IncrementAction>((state, _) => state.copyWith(count: state.count + 1));
///     on<SetValueAction>((state, action) => state.copyWith(value: action.value));
///   }
/// }
///
/// // Usage:
/// final store = createStore<AppState, AppAction>(
///   initialState: AppState(),
///   reducer: AppReducer().reducer,
///   middlewares: [...],
/// );
/// ```
abstract class ReducerBase<S, A extends Action> {
  final Map<Type, S Function(S, A)> _handlers = {};

  /// Registers a handler for a specific action type.
  ///
  /// The handler will be called when an action of type [T] is dispatched.
  void on<T extends A>(S Function(S state, T action) handler) {
    if (_handlers.containsKey(T)) {
      throw DuplicateHandlerException(T);
    }
    _handlers[T] = (state, action) => handler(state, action as T);
  }

  /// Returns the built reducer function.
  ///
  /// If no handler is registered for an action type, the state is returned unchanged.
  Reducer<S, A> get reducer {
    return (state, action) {
      final handler = _handlers[action.runtimeType];
      return handler?.call(state, action) ?? state;
    };
  }
}

/// Exception thrown when attempting to register a duplicate handler for the same action type.
class DuplicateHandlerException implements Exception {
  /// The action type that was duplicated.
  final Type actionType;

  /// Creates a [DuplicateHandlerException] for the given [actionType].
  DuplicateHandlerException(this.actionType);

  @override
  String toString() =>
      'DuplicateHandlerException: Handler for $actionType is already registered.';
}
