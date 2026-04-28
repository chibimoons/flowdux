import 'package:flutter/widgets.dart' hide Action;
import 'package:flowdux/flowdux.dart';

import 'store_provider.dart';

/// Provides a [Store] with lifecycle ownership.
///
/// Unlike [StoreProvider], which expects an externally-managed [Store],
/// [StoreScope] creates the store via [create] exactly once in [State.initState]
/// and closes it in [State.dispose]. This makes it safe to use in places where
/// the build function may run multiple times — e.g. inside a `PageRoute`'s
/// `pageBuilder`, a `NestedRoute` builder, or any custom route builder that
/// rebuilds on system events such as keyboard/theme/`MediaQuery` changes.
///
/// Without this widget, building a fresh [Store] inline in a route builder
/// causes the store to be replaced on every rebuild while child [State]
/// objects retain stale references, dropping dispatched actions and resetting
/// derived state silently.
///
/// Example — store-per-screen via a route builder:
/// ```dart
/// PageRouteBuilder(
///   pageBuilder: (_, __, ___) => StoreScope<AppState, AppAction>(
///     create: () => createStore<AppState, AppAction>(
///       initialState: AppState(),
///       reducer: appReducer,
///     ),
///     child: MyScreen(),
///   ),
/// );
/// ```
///
/// The created store is exposed to descendants via [StoreProvider], so all
/// existing widgets ([StoreBuilder], [StoreSelector], [StoreConsumer],
/// [StoreListener], `context.store`, `context.dispatch`) work unchanged.
class StoreScope<S, A extends Action> extends StatefulWidget {
  /// Factory invoked once in [State.initState] to create the store.
  ///
  /// Must return a fresh [Store]. The returned store will be closed
  /// automatically when this widget is removed from the tree.
  final Store<S, A> Function() create;

  /// The widget below this one in the tree.
  final Widget child;

  /// Creates a [StoreScope] that owns the lifecycle of a [Store].
  const StoreScope({super.key, required this.create, required this.child});

  @override
  State<StoreScope<S, A>> createState() => _StoreScopeState<S, A>();
}

class _StoreScopeState<S, A extends Action> extends State<StoreScope<S, A>> {
  late final Store<S, A> _store;

  @override
  void initState() {
    super.initState();
    _store = widget.create();
  }

  @override
  void dispose() {
    _store.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return StoreProvider<S, A>(store: _store, child: widget.child);
  }
}
