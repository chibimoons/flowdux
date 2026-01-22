import 'package:flutter/widgets.dart' hide Action;
import 'package:flowdux/flowdux.dart';

import 'store_provider.dart';

/// A widget that provides both store access and state-based rebuilding.
///
/// [StoreConsumer] combines the functionality of [StoreProvider.of] and
/// [StoreBuilder], giving the builder access to both the store (for dispatching)
/// and the current state.
///
/// Example:
/// ```dart
/// StoreConsumer<AppState, AppAction>(
///   builder: (context, store, state) {
///     return Column(
///       children: [
///         Text('Count: ${state.count}'),
///         ElevatedButton(
///           onPressed: () => store.dispatch(IncrementAction()),
///           child: Text('Increment'),
///         ),
///       ],
///     );
///   },
/// )
/// ```
class StoreConsumer<S, A extends Action> extends StatelessWidget {
  /// Builder function that receives both the store and current state.
  final Widget Function(BuildContext context, Store<S, A> store, S state)
      builder;

  /// Optional store to use instead of getting it from context.
  final Store<S, A>? store;

  /// Optional listener that is called when the state changes.
  ///
  /// Unlike [builder], the listener is not used for building the widget tree.
  /// Use it for side effects like navigation or showing dialogs.
  ///
  /// Example:
  /// ```dart
  /// StoreConsumer<AppState, AppAction>(
  ///   listener: (context, store, state) {
  ///     if (state.error != null) {
  ///       ScaffoldMessenger.of(context).showSnackBar(
  ///         SnackBar(content: Text(state.error!)),
  ///       );
  ///     }
  ///   },
  ///   builder: (context, store, state) {
  ///     return Text('Count: ${state.count}');
  ///   },
  /// )
  /// ```
  final void Function(BuildContext context, Store<S, A> store, S state)?
      listener;

  /// Creates a [StoreConsumer].
  const StoreConsumer({
    super.key,
    required this.builder,
    this.store,
    this.listener,
  });

  @override
  Widget build(BuildContext context) {
    final effectiveStore = store ?? StoreProvider.of<S, A>(context);

    return StreamBuilder<S>(
      stream: effectiveStore.state,
      initialData: effectiveStore.currentState,
      builder: (context, snapshot) {
        final state = snapshot.data as S;

        if (listener != null) {
          // Schedule listener to be called after the build
          WidgetsBinding.instance.addPostFrameCallback((_) {
            listener!(context, effectiveStore, state);
          });
        }

        return builder(context, effectiveStore, state);
      },
    );
  }
}

/// A widget that listens to state changes without rebuilding.
///
/// Use this when you need to perform side effects (like navigation or
/// showing dialogs) in response to state changes, but don't need to
/// rebuild the widget tree.
///
/// Example:
/// ```dart
/// StoreListener<AppState, AppAction>(
///   listener: (context, store, state) {
///     if (state.navigateTo != null) {
///       Navigator.of(context).pushNamed(state.navigateTo!);
///     }
///   },
///   child: MyWidget(),
/// )
/// ```
class StoreListener<S, A extends Action> extends StatefulWidget {
  /// Listener function called when state changes.
  final void Function(BuildContext context, Store<S, A> store, S state)
      listener;

  /// Optional condition to determine whether to call the listener.
  ///
  /// If provided, the listener is only called when this returns true.
  final bool Function(S previous, S current)? listenWhen;

  /// The child widget.
  final Widget child;

  /// Optional store to use instead of getting it from context.
  final Store<S, A>? store;

  /// Creates a [StoreListener].
  const StoreListener({
    super.key,
    required this.listener,
    required this.child,
    this.listenWhen,
    this.store,
  });

  @override
  State<StoreListener<S, A>> createState() => _StoreListenerState<S, A>();
}

class _StoreListenerState<S, A extends Action>
    extends State<StoreListener<S, A>> {
  S? _previousState;
  bool _initialized = false;

  @override
  Widget build(BuildContext context) {
    final store = widget.store ?? StoreProvider.of<S, A>(context);

    return StreamBuilder<S>(
      stream: store.state,
      initialData: store.currentState,
      builder: (context, snapshot) {
        final currentState = snapshot.data as S;

        if (!_initialized) {
          _previousState = currentState;
          _initialized = true;
          return widget.child;
        }

        final shouldListen = widget.listenWhen?.call(_previousState as S, currentState) ??
            (_previousState != currentState);

        if (shouldListen) {
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (mounted) {
              widget.listener(context, store, currentState);
            }
          });
        }

        _previousState = currentState;

        return widget.child;
      },
    );
  }
}
