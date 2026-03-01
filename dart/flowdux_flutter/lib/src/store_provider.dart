import 'package:flutter/widgets.dart' hide Action;
import 'package:flowdux/flowdux.dart';

/// Provides a [Store] to all descendant widgets.
///
/// Use [StoreProvider.of] to access the store from descendant widgets.
///
/// Example:
/// ```dart
/// StoreProvider<AppState, AppAction>(
///   store: store,
///   child: MyApp(),
/// )
/// ```
class StoreProvider<S, A extends Action> extends InheritedWidget {
  /// The store to provide to descendants.
  final Store<S, A> store;

  /// Creates a [StoreProvider] with the given [store].
  const StoreProvider({super.key, required this.store, required super.child});

  /// Retrieves the [Store] from the nearest [StoreProvider] ancestor.
  ///
  /// Throws [FlutterError] if no [StoreProvider] is found.
  ///
  /// Example:
  /// ```dart
  /// final store = StoreProvider.of<AppState, AppAction>(context);
  /// store.dispatch(IncrementAction());
  /// ```
  static Store<S, A> of<S, A extends Action>(BuildContext context) {
    final provider = context
        .dependOnInheritedWidgetOfExactType<StoreProvider<S, A>>();

    if (provider == null) {
      throw FlutterError(
        'StoreProvider.of() called with a context that does not contain a '
        'StoreProvider<$S, $A>.\n'
        'No ancestor could be found starting from the context that was passed '
        'to StoreProvider.of<$S, $A>(). This can happen if the context you '
        'used comes from a widget above the StoreProvider.\n'
        'The context used was: $context',
      );
    }

    return provider.store;
  }

  /// Retrieves the [Store] from the nearest [StoreProvider] ancestor,
  /// or returns `null` if not found.
  ///
  /// Unlike [of], this method does not throw if no provider is found.
  ///
  /// Example:
  /// ```dart
  /// final store = StoreProvider.maybeOf<AppState, AppAction>(context);
  /// if (store != null) {
  ///   store.dispatch(IncrementAction());
  /// }
  /// ```
  static Store<S, A>? maybeOf<S, A extends Action>(BuildContext context) {
    final provider = context
        .dependOnInheritedWidgetOfExactType<StoreProvider<S, A>>();
    return provider?.store;
  }

  @override
  bool updateShouldNotify(StoreProvider<S, A> oldWidget) {
    return store != oldWidget.store;
  }
}

/// Extension on [BuildContext] for convenient store access.
extension StoreProviderExtension on BuildContext {
  /// Retrieves the [Store] from the nearest [StoreProvider] ancestor.
  ///
  /// Shorthand for `StoreProvider.of<S, A>(context)`.
  ///
  /// Example:
  /// ```dart
  /// final store = context.store<AppState, AppAction>();
  /// store.dispatch(IncrementAction());
  /// ```
  Store<S, A> store<S, A extends Action>() => StoreProvider.of<S, A>(this);

  /// Dispatches an action to the store.
  ///
  /// Shorthand for `StoreProvider.of<S, A>(context).dispatch(action)`.
  ///
  /// Example:
  /// ```dart
  /// context.dispatch<AppState, AppAction>(IncrementAction());
  /// ```
  void dispatch<S, A extends Action>(A action) =>
      StoreProvider.of<S, A>(this).dispatch(action);
}
