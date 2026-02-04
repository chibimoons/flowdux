import 'dart:async';

import 'package:flutter/widgets.dart' hide Action;
import 'package:flowdux/flowdux.dart';

import 'store_provider.dart';

/// A widget that rebuilds when the store's state changes.
///
/// [StoreBuilder] wraps a [StreamBuilder] and listens to the store's state
/// stream, rebuilding the widget tree when the state changes.
///
/// **Performance tip:** When using a [selector], define it as a static method
/// or top-level function to avoid creating new function instances on every
/// rebuild:
///
/// ```dart
/// // Good - stable reference, no unnecessary didUpdateWidget work
/// static int _selectCount(AppState state) => state.count;
///
/// StoreBuilder<AppState, AppAction>(
///   selector: _selectCount,
///   builder: (context, state) => Text('Count: ${state.count}'),
/// )
///
/// // Avoid - creates new closure on every build
/// StoreBuilder<AppState, AppAction>(
///   selector: (state) => state.count,
///   builder: (context, state) => Text('Count: ${state.count}'),
/// )
/// ```
///
/// Example:
/// ```dart
/// StoreBuilder<AppState, AppAction>(
///   builder: (context, state) {
///     return Text('Count: ${state.count}');
///   },
/// )
/// ```
class StoreBuilder<S, A extends Action> extends StatelessWidget {
  /// Builder function that receives the current state and returns a widget.
  final Widget Function(BuildContext context, S state) builder;

  /// Optional store to use instead of getting it from context.
  ///
  /// If not provided, the store is retrieved from the nearest [StoreProvider].
  final Store<S, A>? store;

  /// Optional selector to extract a subset of the state.
  ///
  /// When provided, the widget only rebuilds when the selected value changes.
  /// This can improve performance by avoiding unnecessary rebuilds.
  ///
  /// Example:
  /// ```dart
  /// StoreBuilder<AppState, AppAction, int>(
  ///   selector: (state) => state.count,
  ///   builder: (context, count) {
  ///     return Text('Count: $count');
  ///   },
  /// )
  /// ```
  final dynamic Function(S state)? selector;

  /// Creates a [StoreBuilder].
  ///
  /// Either [store] must be provided or a [StoreProvider] must be an ancestor.
  const StoreBuilder({
    super.key,
    required this.builder,
    this.store,
    this.selector,
  });

  @override
  Widget build(BuildContext context) {
    final effectiveStore = store ?? StoreProvider.of<S, A>(context);

    if (selector != null) {
      return _SelectorBuilder<S, A>(
        store: effectiveStore,
        selector: selector!,
        builder: builder,
      );
    }

    return StreamBuilder<S>(
      stream: effectiveStore.state,
      initialData: effectiveStore.currentState,
      builder: (context, snapshot) {
        return builder(context, snapshot.data as S);
      },
    );
  }
}

/// Internal widget that handles selector-based rebuilding.
class _SelectorBuilder<S, A extends Action> extends StatefulWidget {
  final Store<S, A> store;
  final dynamic Function(S state) selector;
  final Widget Function(BuildContext context, S state) builder;

  const _SelectorBuilder({
    required this.store,
    required this.selector,
    required this.builder,
  });

  @override
  State<_SelectorBuilder<S, A>> createState() => _SelectorBuilderState<S, A>();
}

class _SelectorBuilderState<S, A extends Action>
    extends State<_SelectorBuilder<S, A>> {
  late S _currentState;
  late dynamic _selectedValue;
  StreamSubscription<S>? _subscription;

  @override
  void initState() {
    super.initState();
    _currentState = widget.store.currentState;
    _selectedValue = widget.selector(_currentState);
    _subscribe();
  }

  @override
  void didUpdateWidget(covariant _SelectorBuilder<S, A> oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.store != widget.store) {
      _subscription?.cancel();
      _currentState = widget.store.currentState;
      _selectedValue = widget.selector(_currentState);
      _subscribe();
    } else if (oldWidget.selector != widget.selector) {
      _currentState = widget.store.currentState;
      final newSelectedValue = widget.selector(_currentState);
      if (newSelectedValue != _selectedValue) {
        _subscription?.cancel();
        _selectedValue = newSelectedValue;
        _subscribe();
        setState(() {});
      }
    }
  }

  void _subscribe() {
    _subscription = widget.store.state.listen((newState) {
      final newSelectedValue = widget.selector(newState);
      if (newSelectedValue != _selectedValue) {
        setState(() {
          _currentState = newState;
          _selectedValue = newSelectedValue;
        });
      }
    });
  }

  @override
  void dispose() {
    _subscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return widget.builder(context, _currentState);
  }
}

/// A typed version of [StoreBuilder] that uses a selector to extract
/// a specific value from the state.
///
/// This is useful when you only need a subset of the state and want to
/// avoid unnecessary rebuilds when other parts of the state change.
///
/// **Performance tip:** Define selectors as static methods or top-level
/// functions to avoid creating new function instances on every rebuild:
///
/// ```dart
/// // Good - stable reference, no unnecessary didUpdateWidget work
/// static int _selectCount(AppState state) => state.count;
///
/// StoreSelector<AppState, AppAction, int>(
///   selector: _selectCount,
///   builder: (context, count) => Text('Count: $count'),
/// )
///
/// // Avoid - creates new closure on every build
/// StoreSelector<AppState, AppAction, int>(
///   selector: (state) => state.count,
///   builder: (context, count) => Text('Count: $count'),
/// )
/// ```
///
/// Example:
/// ```dart
/// StoreSelector<AppState, AppAction, int>(
///   selector: (state) => state.count,
///   builder: (context, count) {
///     return Text('Count: $count');
///   },
/// )
/// ```
class StoreSelector<S, A extends Action, T> extends StatefulWidget {
  /// Selector function to extract a value from the state.
  final T Function(S state) selector;

  /// Builder function that receives the selected value.
  final Widget Function(BuildContext context, T value) builder;

  /// Optional store to use instead of getting it from context.
  final Store<S, A>? store;

  /// Creates a [StoreSelector].
  const StoreSelector({
    super.key,
    required this.selector,
    required this.builder,
    this.store,
  });

  @override
  State<StoreSelector<S, A, T>> createState() => _StoreSelectorState<S, A, T>();
}

class _StoreSelectorState<S, A extends Action, T>
    extends State<StoreSelector<S, A, T>> {
  late T _selectedValue;
  Store<S, A>? _store;
  StreamSubscription<S>? _subscription;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final newStore = widget.store ?? StoreProvider.of<S, A>(context);
    if (newStore != _store) {
      _subscription?.cancel();
      _store = newStore;
      _selectedValue = widget.selector(newStore.currentState);
      _subscribe();
    }
  }

  @override
  void didUpdateWidget(covariant StoreSelector<S, A, T> oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.selector != widget.selector) {
      final newValue = widget.selector(_store!.currentState);
      if (newValue != _selectedValue) {
        _subscription?.cancel();
        _selectedValue = newValue;
        _subscribe();
        setState(() {});
      }
    }
  }

  void _subscribe() {
    _subscription = _store!.state.listen((newState) {
      final newValue = widget.selector(newState);
      if (newValue != _selectedValue) {
        setState(() {
          _selectedValue = newValue;
        });
      }
    });
  }

  @override
  void dispose() {
    _subscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return widget.builder(context, _selectedValue);
  }
}
