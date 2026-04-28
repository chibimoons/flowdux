/// Flutter bindings for FlowDux state management library.
///
/// This library provides Flutter widgets for integrating FlowDux stores
/// into Flutter applications.
///
/// ## Getting Started
///
/// 1. Wrap your app with [StoreProvider]:
///
/// ```dart
/// void main() {
///   final store = createStore<AppState, AppAction>(
///     initialState: AppState(),
///     reducer: appReducer,
///   );
///
///   runApp(
///     StoreProvider<AppState, AppAction>(
///       store: store,
///       child: MyApp(),
///     ),
///   );
/// }
/// ```
///
/// 2. Use [StoreBuilder] to rebuild widgets when state changes:
///
/// ```dart
/// StoreBuilder<AppState, AppAction>(
///   builder: (context, state) {
///     return Text('Count: ${state.count}');
///   },
/// )
/// ```
///
/// 3. Use [StoreConsumer] to access both store and state:
///
/// ```dart
/// StoreConsumer<AppState, AppAction>(
///   builder: (context, store, state) {
///     return ElevatedButton(
///       onPressed: () => store.dispatch(IncrementAction()),
///       child: Text('Count: ${state.count}'),
///     );
///   },
/// )
/// ```
///
/// 4. Use [StoreSelector] for optimized rebuilds:
///
/// ```dart
/// StoreSelector<AppState, AppAction, int>(
///   selector: (state) => state.count,
///   builder: (context, count) {
///     return Text('Count: $count');
///   },
/// )
/// ```
library flowdux_flutter;

export 'package:flowdux/flowdux.dart';

export 'src/store_provider.dart';
export 'src/store_scope.dart';
export 'src/store_builder.dart';
export 'src/store_consumer.dart';
