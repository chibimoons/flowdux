# FlowDux Flutter

Flutter bindings for [FlowDux](https://pub.dev/packages/flowdux) state management library.

[![pub package](https://img.shields.io/pub/v/flowdux_flutter.svg)](https://pub.dev/packages/flowdux_flutter)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

## Features

- **StoreProvider** - Provide store to widget tree via InheritedWidget
- **StoreScope** - Lifecycle-aware provider that creates/closes a store automatically
- **StoreBuilder** - Rebuild widgets when state changes
- **StoreSelector** - Optimized rebuilds with selectors
- **StoreConsumer** - Access both store and state
- **StoreListener** - Side effects on state changes

## Installation

```yaml
dependencies:
  flowdux: ^0.3.2
  flowdux_flutter: ^0.3.0
```

## Quick Start

### 1. Wrap Your App with StoreProvider

```dart
void main() {
  final store = createStore<AppState, AppAction>(
    initialState: AppState(),
    reducer: appReducer,
    middlewares: [AppMiddleware()],
  );

  runApp(
    StoreProvider<AppState, AppAction>(
      store: store,
      child: MyApp(),
    ),
  );
}
```

### 2. Use StoreBuilder for Reactive UI

```dart
class CounterWidget extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return StoreBuilder<AppState, AppAction>(
      builder: (context, state) {
        return Text('Count: ${state.count}');
      },
    );
  }
}
```

### 3. Dispatch Actions

```dart
// Using context extension
ElevatedButton(
  onPressed: () => context.dispatch<AppState, AppAction>(IncrementAction()),
  child: Text('Increment'),
)

// Or access the store directly
ElevatedButton(
  onPressed: () {
    final store = StoreProvider.of<AppState, AppAction>(context);
    store.dispatch(IncrementAction());
  },
  child: Text('Increment'),
)
```

## Widgets

### StoreProvider

Provides a store to all descendant widgets using InheritedWidget.

```dart
StoreProvider<AppState, AppAction>(
  store: store,
  child: MyApp(),
)

// Access the store
final store = StoreProvider.of<AppState, AppAction>(context);

// Or use the extension
final store = context.store<AppState, AppAction>();

// Safe access (returns null if not found)
final store = StoreProvider.maybeOf<AppState, AppAction>(context);
```

### StoreScope

Owns a store's lifecycle: creates it once via `create` and closes it on dispose.
Use this anywhere the build function may run multiple times — e.g. inside a
`PageRoute`'s `pageBuilder`, a custom route builder, or a `NestedRoute`. Without
it, building a fresh `Store` inline causes the store to be replaced on rebuild
while child `State` objects keep stale references, dropping dispatched actions
silently.

```dart
PageRouteBuilder(
  pageBuilder: (_, __, ___) => StoreScope<AppState, AppAction>(
    create: () => createStore<AppState, AppAction>(
      initialState: AppState(),
      reducer: appReducer,
    ),
    child: MyScreen(),
  ),
);
```

The created store is exposed to descendants via `StoreProvider`, so all the
widgets below (`StoreBuilder`, `StoreSelector`, `StoreConsumer`,
`StoreListener`, `context.store`, `context.dispatch`) work unchanged.

### StoreBuilder

Rebuilds its child when the store's state changes.

```dart
StoreBuilder<AppState, AppAction>(
  builder: (context, state) {
    return Column(
      children: [
        Text('Count: ${state.count}'),
        Text('Name: ${state.name}'),
      ],
    );
  },
)
```

You can also pass a store directly:

```dart
StoreBuilder<AppState, AppAction>(
  store: myStore, // Optional, uses provider if not specified
  builder: (context, state) {
    return Text('Count: ${state.count}');
  },
)
```

### StoreSelector

Optimized widget that only rebuilds when the selected value changes.

```dart
StoreSelector<AppState, AppAction, int>(
  selector: (state) => state.count,
  builder: (context, count) {
    return Text('Count: $count');
  },
)

// Complex selector
StoreSelector<AppState, AppAction, String>(
  selector: (state) => '${state.firstName} ${state.lastName}',
  builder: (context, fullName) {
    return Text('Name: $fullName');
  },
)
```

### StoreConsumer

Combines StoreBuilder with store access for dispatching actions.

```dart
StoreConsumer<AppState, AppAction>(
  builder: (context, store, state) {
    return Column(
      children: [
        Text('Count: ${state.count}'),
        ElevatedButton(
          onPressed: () => store.dispatch(IncrementAction()),
          child: Text('Increment'),
        ),
      ],
    );
  },
)
```

With listener for side effects:

```dart
StoreConsumer<AppState, AppAction>(
  listener: (context, store, state) {
    if (state.error != null) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(state.error!)),
      );
    }
  },
  builder: (context, store, state) {
    return MyWidget(state: state);
  },
)
```

### StoreListener

Listens to state changes without rebuilding. Use for side effects like navigation or showing dialogs.

```dart
StoreListener<AppState, AppAction>(
  listener: (context, store, state) {
    if (state.shouldNavigate) {
      Navigator.of(context).pushNamed('/next');
    }
  },
  child: MyWidget(),
)
```

With conditional listening:

```dart
StoreListener<AppState, AppAction>(
  listenWhen: (previous, current) => previous.route != current.route,
  listener: (context, store, state) {
    Navigator.of(context).pushNamed(state.route);
  },
  child: MyWidget(),
)
```

## Complete Example

```dart
import 'package:flutter/material.dart';
import 'package:flowdux_flutter/flowdux_flutter.dart';

// State
class CounterState {
  final int count;
  CounterState({this.count = 0});
  CounterState copyWith({int? count}) =>
      CounterState(count: count ?? this.count);
}

// Actions
class IncrementAction implements Action {}
class DecrementAction implements Action {}

// Reducer
final counterReducer = buildReducer<CounterState, Action>((b) {
  b.on<IncrementAction>((state, _) => state.copyWith(count: state.count + 1));
  b.on<DecrementAction>((state, _) => state.copyWith(count: state.count - 1));
});

void main() {
  final store = createStore<CounterState, Action>(
    initialState: CounterState(),
    reducer: counterReducer,
  );

  runApp(
    StoreProvider<CounterState, Action>(
      store: store,
      child: MaterialApp(
        home: CounterPage(),
      ),
    ),
  );
}

class CounterPage extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('Counter')),
      body: Center(
        child: StoreBuilder<CounterState, Action>(
          builder: (context, state) {
            return Text(
              '${state.count}',
              style: Theme.of(context).textTheme.headlineLarge,
            );
          },
        ),
      ),
      floatingActionButton: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          FloatingActionButton(
            onPressed: () =>
                context.dispatch<CounterState, Action>(IncrementAction()),
            child: Icon(Icons.add),
          ),
          SizedBox(height: 8),
          FloatingActionButton(
            onPressed: () =>
                context.dispatch<CounterState, Action>(DecrementAction()),
            child: Icon(Icons.remove),
          ),
        ],
      ),
    );
  }
}
```

## Testing

```dart
testWidgets('counter increments', (tester) async {
  final store = createStore<CounterState, Action>(
    initialState: CounterState(),
    reducer: counterReducer,
  );

  await tester.pumpWidget(
    MaterialApp(
      home: StoreProvider<CounterState, Action>(
        store: store,
        child: CounterPage(),
      ),
    ),
  );

  expect(find.text('0'), findsOneWidget);

  await tester.tap(find.byIcon(Icons.add));
  await tester.runAsync(() => Future.delayed(Duration(milliseconds: 50)));
  await tester.pumpAndSettle();

  expect(find.text('1'), findsOneWidget);
});
```

## API Reference

See the [API documentation](https://pub.dev/documentation/flowdux_flutter/latest/) for complete details.

## License

Apache License 2.0 - see [LICENSE](LICENSE) for details.
