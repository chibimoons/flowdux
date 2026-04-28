import 'package:flutter/material.dart' hide Action;
import 'package:flutter_test/flutter_test.dart';
import 'package:flowdux_flutter/flowdux_flutter.dart';

// Test Actions
class IncrementAction implements Action {}

class DecrementAction implements Action {}

class SetValueAction implements Action {
  final int value;
  SetValueAction(this.value);
}

class SetMessageAction implements Action {
  final String message;
  SetMessageAction(this.message);
}

// Test State
class CounterState {
  final int count;
  final String? message;

  CounterState({this.count = 0, this.message});

  CounterState copyWith({int? count, String? message}) => CounterState(
        count: count ?? this.count,
        message: message ?? this.message,
      );

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is CounterState &&
          runtimeType == other.runtimeType &&
          count == other.count &&
          message == other.message;

  @override
  int get hashCode => Object.hash(count, message);
}

// Test Reducer
class CounterReducer extends ReducerBase<CounterState, Action> {
  CounterReducer() {
    on<IncrementAction>((state, _) => state.copyWith(count: state.count + 1));
    on<DecrementAction>((state, _) => state.copyWith(count: state.count - 1));
    on<SetValueAction>((state, action) => state.copyWith(count: action.value));
    on<SetMessageAction>(
      (state, action) => state.copyWith(message: action.message),
    );
  }
}

void main() {
  late Store<CounterState, Action> store;
  late Reducer<CounterState, Action> reducer;

  setUp(() {
    reducer = CounterReducer().reducer;

    store = createStore<CounterState, Action>(
      initialState: CounterState(),
      reducer: reducer,
    );
  });

  tearDown(() async {
    await store.close();
  });

  group('StoreProvider', () {
    testWidgets('provides store to descendants', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: StoreProvider<CounterState, Action>(
            store: store,
            child: Builder(
              builder: (context) {
                final providedStore = StoreProvider.of<CounterState, Action>(
                  context,
                );
                return Text('Store: ${providedStore.currentState.count}');
              },
            ),
          ),
        ),
      );

      expect(find.text('Store: 0'), findsOneWidget);
    });

    testWidgets('throws error when no provider found', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (context) {
              expect(
                () => StoreProvider.of<CounterState, Action>(context),
                throwsA(isA<FlutterError>()),
              );
              return const Text('Test');
            },
          ),
        ),
      );
    });

    testWidgets('maybeOf returns null when no provider found', (tester) async {
      Store<CounterState, Action>? foundStore;

      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (context) {
              foundStore = StoreProvider.maybeOf<CounterState, Action>(context);
              return const Text('Test');
            },
          ),
        ),
      );

      expect(foundStore, isNull);
    });

    testWidgets('context extension works', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: StoreProvider<CounterState, Action>(
            store: store,
            child: Builder(
              builder: (context) {
                final providedStore = context.store<CounterState, Action>();
                return Text('Count: ${providedStore.currentState.count}');
              },
            ),
          ),
        ),
      );

      expect(find.text('Count: 0'), findsOneWidget);
    });
  });

  group('StoreBuilder', () {
    testWidgets('builds with initial state', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: StoreProvider<CounterState, Action>(
            store: store,
            child: StoreBuilder<CounterState, Action>(
              builder: (context, state) {
                return Text('Count: ${state.count}');
              },
            ),
          ),
        ),
      );

      expect(find.text('Count: 0'), findsOneWidget);
    });

    testWidgets('rebuilds when state changes', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: StoreProvider<CounterState, Action>(
            store: store,
            child: StoreBuilder<CounterState, Action>(
              builder: (context, state) {
                return Text('Count: ${state.count}');
              },
            ),
          ),
        ),
      );

      expect(find.text('Count: 0'), findsOneWidget);

      await tester.runAsync(() async {
        store.dispatch(IncrementAction());
        await Future.delayed(const Duration(milliseconds: 50));
      });
      await tester.pumpAndSettle();

      expect(find.text('Count: 1'), findsOneWidget);
    });

    testWidgets('works with explicit store parameter', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: StoreBuilder<CounterState, Action>(
            store: store,
            builder: (context, state) {
              return Text('Count: ${state.count}');
            },
          ),
        ),
      );

      expect(find.text('Count: 0'), findsOneWidget);

      await tester.runAsync(() async {
        store.dispatch(IncrementAction());
        await Future.delayed(const Duration(milliseconds: 50));
      });
      await tester.pumpAndSettle();

      expect(find.text('Count: 1'), findsOneWidget);
    });
  });

  group('StoreSelector', () {
    testWidgets('selector extracts correct value', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: StoreProvider<CounterState, Action>(
            store: store,
            child: StoreSelector<CounterState, Action, int>(
              selector: (state) => state.count * 2,
              builder: (context, doubleCount) {
                return Text('Double: $doubleCount');
              },
            ),
          ),
        ),
      );

      expect(find.text('Double: 0'), findsOneWidget);

      await tester.runAsync(() async {
        store.dispatch(SetValueAction(5));
        await Future.delayed(const Duration(milliseconds: 50));
      });
      await tester.pumpAndSettle();

      expect(find.text('Double: 10'), findsOneWidget);
    });
  });

  group('StoreConsumer', () {
    testWidgets('provides store and state to builder', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: StoreProvider<CounterState, Action>(
            store: store,
            child: StoreConsumer<CounterState, Action>(
              builder: (context, store, state) {
                return Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text('Count: ${state.count}'),
                    ElevatedButton(
                      onPressed: () => store.dispatch(IncrementAction()),
                      child: const Text('Increment'),
                    ),
                  ],
                );
              },
            ),
          ),
        ),
      );

      expect(find.text('Count: 0'), findsOneWidget);

      await tester.tap(find.text('Increment'));
      await tester.runAsync(() async {
        await Future.delayed(const Duration(milliseconds: 50));
      });
      await tester.pumpAndSettle();

      expect(find.text('Count: 1'), findsOneWidget);
    });
  });

  group('StoreSelector optimization', () {
    testWidgets('does not rebuild when unrelated state changes', (
      tester,
    ) async {
      var buildCount = 0;

      await tester.pumpWidget(
        MaterialApp(
          home: StoreProvider<CounterState, Action>(
            store: store,
            child: StoreSelector<CounterState, Action, int>(
              selector: (state) => state.count,
              builder: (context, count) {
                buildCount++;
                return Text('Count: $count');
              },
            ),
          ),
        ),
      );

      expect(find.text('Count: 0'), findsOneWidget);
      final initialBuildCount = buildCount;

      // Dispatch an action that only changes message, not count
      await tester.runAsync(() async {
        store.dispatch(SetMessageAction('hello'));
        await Future.delayed(const Duration(milliseconds: 50));
      });
      await tester.pumpAndSettle();

      // Builder should NOT have been called again
      expect(buildCount, initialBuildCount);
      expect(find.text('Count: 0'), findsOneWidget);

      // Now dispatch an action that changes count
      await tester.runAsync(() async {
        store.dispatch(IncrementAction());
        await Future.delayed(const Duration(milliseconds: 50));
      });
      await tester.pumpAndSettle();

      // Builder SHOULD have been called
      expect(buildCount, initialBuildCount + 1);
      expect(find.text('Count: 1'), findsOneWidget);
    });
  });

  group('StoreConsumer listener', () {
    testWidgets('listener is not called on initial build', (tester) async {
      final listenerCalls = <int>[];

      await tester.pumpWidget(
        MaterialApp(
          home: StoreProvider<CounterState, Action>(
            store: store,
            child: StoreConsumer<CounterState, Action>(
              listener: (context, store, state) {
                listenerCalls.add(state.count);
              },
              builder: (context, store, state) {
                return Text('Count: ${state.count}');
              },
            ),
          ),
        ),
      );

      await tester.pumpAndSettle();

      // Listener should NOT be called on initial build
      expect(listenerCalls, isEmpty);
    });

    testWidgets('listener is called only on state change', (tester) async {
      final listenerCalls = <int>[];

      await tester.pumpWidget(
        MaterialApp(
          home: StoreProvider<CounterState, Action>(
            store: store,
            child: StoreConsumer<CounterState, Action>(
              listener: (context, store, state) {
                listenerCalls.add(state.count);
              },
              builder: (context, store, state) {
                return Text('Count: ${state.count}');
              },
            ),
          ),
        ),
      );

      await tester.pumpAndSettle();
      expect(listenerCalls, isEmpty);

      // Dispatch a real state change
      await tester.runAsync(() async {
        store.dispatch(IncrementAction());
        await Future.delayed(const Duration(milliseconds: 50));
      });
      await tester.pumpAndSettle();

      expect(listenerCalls, [1]);

      // Dispatch another state change
      await tester.runAsync(() async {
        store.dispatch(IncrementAction());
        await Future.delayed(const Duration(milliseconds: 50));
      });
      await tester.pumpAndSettle();

      expect(listenerCalls, [1, 2]);
    });
  });

  group('StoreScope', () {
    testWidgets('creates store and provides it to descendants', (tester) async {
      var createCalls = 0;

      await tester.pumpWidget(
        MaterialApp(
          home: StoreScope<CounterState, Action>(
            create: () {
              createCalls++;
              return createStore<CounterState, Action>(
                initialState: CounterState(),
                reducer: reducer,
              );
            },
            child: Builder(
              builder: (context) {
                final providedStore =
                    StoreProvider.of<CounterState, Action>(context);
                return Text('Count: ${providedStore.currentState.count}');
              },
            ),
          ),
        ),
      );

      expect(createCalls, 1);
      expect(find.text('Count: 0'), findsOneWidget);
    });

    testWidgets('does not recreate store across rebuilds', (tester) async {
      var createCalls = 0;

      Widget buildTree(String label) => MaterialApp(
            home: StoreScope<CounterState, Action>(
              create: () {
                createCalls++;
                return createStore<CounterState, Action>(
                  initialState: CounterState(),
                  reducer: reducer,
                );
              },
              child: Text(label),
            ),
          );

      await tester.pumpWidget(buildTree('first'));
      expect(createCalls, 1);

      // Rebuild parent — `create` must not run again because the State is reused.
      await tester.pumpWidget(buildTree('second'));
      await tester.pumpWidget(buildTree('third'));

      expect(createCalls, 1);
    });

    testWidgets('closes store on dispose', (tester) async {
      late Store<CounterState, Action> created;

      await tester.pumpWidget(
        MaterialApp(
          home: StoreScope<CounterState, Action>(
            create: () {
              created = createStore<CounterState, Action>(
                initialState: CounterState(),
                reducer: reducer,
              );
              return created;
            },
            child: const SizedBox(),
          ),
        ),
      );

      // Replace the tree so the StoreScope is removed and disposed.
      await tester.pumpWidget(const MaterialApp(home: SizedBox()));
      await tester.pumpAndSettle();

      // Dispatching after close is logged and ignored — state stays put.
      created.dispatch(IncrementAction());
      await tester.runAsync(() async {
        await Future.delayed(const Duration(milliseconds: 50));
      });

      expect(created.currentState.count, 0);
    });

    testWidgets('descendants can dispatch actions through the scoped store',
        (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: StoreScope<CounterState, Action>(
            create: () => createStore<CounterState, Action>(
              initialState: CounterState(),
              reducer: reducer,
            ),
            child: StoreConsumer<CounterState, Action>(
              builder: (context, store, state) {
                return Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text('Count: ${state.count}'),
                    ElevatedButton(
                      onPressed: () => store.dispatch(IncrementAction()),
                      child: const Text('Increment'),
                    ),
                  ],
                );
              },
            ),
          ),
        ),
      );

      expect(find.text('Count: 0'), findsOneWidget);

      await tester.tap(find.text('Increment'));
      await tester.runAsync(() async {
        await Future.delayed(const Duration(milliseconds: 50));
      });
      await tester.pumpAndSettle();

      expect(find.text('Count: 1'), findsOneWidget);
    });
  });

  group('StoreListener', () {
    testWidgets('calls listener on state change', (tester) async {
      final listenerCalls = <int>[];

      await tester.pumpWidget(
        MaterialApp(
          home: StoreProvider<CounterState, Action>(
            store: store,
            child: StoreListener<CounterState, Action>(
              listener: (context, store, state) {
                listenerCalls.add(state.count);
              },
              child: const Text('Static child'),
            ),
          ),
        ),
      );

      await tester.pump();

      await tester.runAsync(() async {
        store.dispatch(IncrementAction());
        await Future.delayed(const Duration(milliseconds: 50));
      });
      await tester.pumpAndSettle();

      expect(listenerCalls, contains(1));
    });
  });
}
