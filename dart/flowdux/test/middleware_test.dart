import 'dart:async';

import 'package:flowdux/flowdux.dart';
import 'package:test/test.dart';

// Test Actions
class TestAction implements Action {}

class IncrementAction implements Action {}

class DecrementAction implements Action {}

class SetValueAction implements Action {
  final int value;
  SetValueAction(this.value);
}

class FetchDataAction implements Action {}

class LoadingAction implements Action {}

class DataLoadedAction implements Action {
  final String data;
  DataLoadedAction(this.data);
}

class ErrorAction implements Action {
  final String message;
  ErrorAction(this.message);
}

class TransformAction implements Action {}

class TransformedAction implements Action {}

// Test State
class AppState {
  final int count;
  final bool loading;
  final String? data;

  AppState({this.count = 0, this.loading = false, this.data});

  AppState copyWith({int? count, bool? loading, String? data}) =>
      AppState(
        count: count ?? this.count,
        loading: loading ?? this.loading,
        data: data ?? this.data,
      );

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is AppState &&
          runtimeType == other.runtimeType &&
          count == other.count &&
          loading == other.loading &&
          data == other.data;

  @override
  int get hashCode => Object.hash(count, loading, data);

  @override
  String toString() => 'AppState(count: $count, loading: $loading, data: $data)';
}

// Test Middlewares using new DSL
class CounterMiddleware extends Middleware<AppState, Action> {
  CounterMiddleware() {
    on<IncrementAction>((state, action) async* {
      // Simply pass through to reducer
      yield action;
    });
  }
}

class AsyncMiddleware extends Middleware<AppState, Action> {
  AsyncMiddleware({
    Duration delay = const Duration(milliseconds: 50),
    String data = 'test data',
  }) {
    on<FetchDataAction>((state, action) async* {
      yield LoadingAction();
      await Future.delayed(delay);
      yield DataLoadedAction(data);
    });
  }
}

class TransformMiddleware extends Middleware<AppState, Action> {
  TransformMiddleware() {
    on<TransformAction>((state, action) async* {
      yield TransformedAction();
    });
  }
}

class MultiActionMiddleware extends Middleware<AppState, Action> {
  MultiActionMiddleware() {
    on<FetchDataAction>((state, action) async* {
      yield IncrementAction();
      yield IncrementAction();
      yield IncrementAction();
    });
  }
}

class StatefulMiddleware extends Middleware<AppState, Action> {
  StatefulMiddleware() {
    on<IncrementAction>((state, action) async* {
      // Access current state to decide what to emit
      if (state.count >= 5) {
        yield SetValueAction(0); // Reset if count >= 5
      } else {
        yield action;
      }
    });
  }
}

// Test Logger
class TestStoreLogger<S, A extends Action> implements StoreLogger<S, A> {
  final List<String> logs = [];

  @override
  void onActionDispatched(A action) {
    logs.add('dispatched:${action.runtimeType}');
  }

  @override
  void onMiddlewareProcessing(String middlewareName, A action) {
    logs.add('middleware:$middlewareName:${action.runtimeType}');
  }

  @override
  void onMiddlewaresCompleted(A action) {
    logs.add('middlewaresCompleted:${action.runtimeType}');
  }

  @override
  void onFlowHolderActionEmitted(A action) {
    logs.add('flowHolderEmitted:${action.runtimeType}');
  }

  @override
  void onErrorOccurred(Object error, StackTrace stackTrace) {
    logs.add('error:$error');
  }

  @override
  void onErrorHandled(A action) {
    logs.add('errorHandled:${action.runtimeType}');
  }

  @override
  void onStateReduced(A action, S previousState, S newState) {
    logs.add('reduced:${action.runtimeType}');
  }

  @override
  void onDispatchAfterClose(A action) {
    logs.add('dispatchAfterClose:${action.runtimeType}');
  }
}

// Test Reducer
class AppReducer extends ReducerBase<AppState, Action> {
  AppReducer() {
    on<IncrementAction>((state, _) => state.copyWith(count: state.count + 1));
    on<DecrementAction>((state, _) => state.copyWith(count: state.count - 1));
    on<SetValueAction>((state, action) => state.copyWith(count: action.value));
    on<LoadingAction>((state, _) => state.copyWith(loading: true));
    on<DataLoadedAction>((state, action) =>
        state.copyWith(loading: false, data: action.data));
    on<TransformedAction>((state, _) => state.copyWith(count: 999));
  }
}

void main() {
  late Reducer<AppState, Action> reducer;

  setUp(() {
    reducer = AppReducer().reducer;
  });

  group('Middleware', () {
    test('middleware processes action and passes to reducer', () async {
      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [CounterMiddleware()],
      );

      store.dispatch(IncrementAction());
      await Future.delayed(Duration(milliseconds: 50));

      expect(store.currentState.count, 1);

      await store.close();
    });

    test('middleware transforms action', () async {
      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [TransformMiddleware()],
      );

      store.dispatch(TransformAction());
      await Future.delayed(Duration(milliseconds: 50));

      expect(store.currentState.count, 999); // TransformedAction sets count to 999

      await store.close();
    });

    test('middleware emits multiple actions', () async {
      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [MultiActionMiddleware()],
      );

      store.dispatch(FetchDataAction());
      await Future.delayed(Duration(milliseconds: 50));

      expect(store.currentState.count, 3);

      await store.close();
    });

    test('async middleware emits actions over time', () async {
      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [AsyncMiddleware(delay: Duration(milliseconds: 20), data: 'async data')],
      );

      store.dispatch(FetchDataAction());

      // After a short delay, loading should be true
      await Future.delayed(Duration(milliseconds: 10));
      expect(store.currentState.loading, true);

      // After full delay, data should be loaded
      await Future.delayed(Duration(milliseconds: 50));
      expect(store.currentState.loading, false);
      expect(store.currentState.data, 'async data');

      await store.close();
    });

    test('middleware can access current state', () async {
      final store = createStore<AppState, Action>(
        initialState: AppState(count: 5),
        reducer: reducer,
        middlewares: [StatefulMiddleware()],
      );

      store.dispatch(IncrementAction());
      await Future.delayed(Duration(milliseconds: 50));

      // State was >= 5, so it should be reset to 0
      expect(store.currentState.count, 0);

      await store.close();
    });

    test('unhandled action passes through middleware unchanged', () async {
      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [CounterMiddleware()], // Only handles IncrementAction
      );

      store.dispatch(SetValueAction(42));
      await Future.delayed(Duration(milliseconds: 50));

      expect(store.currentState.count, 42);

      await store.close();
    });

    test('multiple middlewares are chained', () async {
      final logger = TestStoreLogger<AppState, Action>();
      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [CounterMiddleware(), TransformMiddleware()],
        logger: logger,
      );

      store.dispatch(IncrementAction());
      await Future.delayed(Duration(milliseconds: 50));

      // CounterMiddleware passes IncrementAction through
      expect(store.currentState.count, 1);

      // Both middlewares should have processed
      expect(logger.logs, contains('middleware:CounterMiddleware:IncrementAction'));
      expect(logger.logs, contains('middleware:TransformMiddleware:IncrementAction'));

      await store.close();
    });
  });

  group('Middleware DSL', () {
    test('on() registers handler without strategy', () {
      final middleware = CounterMiddleware();
      expect(middleware.processors.containsKey(IncrementAction), true);
    });

    test('apply().on() registers handler with strategy', () {
      final middleware = _StrategyMiddleware();
      expect(middleware.processors.containsKey(FetchDataAction), true);
    });

    test('apply().on().on() chains multiple handlers with same strategy', () {
      final middleware = _GroupMiddleware();
      expect(middleware.processors.containsKey(IncrementAction), true);
      expect(middleware.processors.containsKey(DecrementAction), true);
    });

    test('throws DuplicateProcessorException on duplicate registration', () {
      expect(
        () => _DuplicateMiddleware(),
        throwsA(isA<DuplicateProcessorException>()),
      );
    });
  });

  group('DuplicateProcessorException', () {
    test('has correct error message', () {
      final exception = DuplicateProcessorException(IncrementAction);

      expect(
        exception.toString(),
        contains("Processor for action type 'IncrementAction' is already registered"),
      );
    });
  });

  group('Logger callbacks', () {
    test('onMiddlewareProcessing is called for each middleware', () async {
      final logger = TestStoreLogger<AppState, Action>();
      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [CounterMiddleware(), TransformMiddleware()],
        logger: logger,
      );

      store.dispatch(IncrementAction());
      await Future.delayed(Duration(milliseconds: 50));

      final middlewareProcessingLogs =
          logger.logs.where((log) => log.startsWith('middleware:')).toList();
      expect(middlewareProcessingLogs.length, 2);

      await store.close();
    });

    test('onMiddlewaresCompleted is called after all middlewares', () async {
      final logger = TestStoreLogger<AppState, Action>();
      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [CounterMiddleware()],
        logger: logger,
      );

      store.dispatch(IncrementAction());
      await Future.delayed(Duration(milliseconds: 50));

      expect(logger.logs, contains('middlewaresCompleted:IncrementAction'));

      await store.close();
    });
  });

  group('ErrorProcessor', () {
    test('DefaultErrorProcessor swallows errors', () async {
      final errorProcessor = DefaultErrorProcessor<Action>();
      final stream = errorProcessor.process(Exception('test'), StackTrace.current);

      final actions = await stream.toList();
      expect(actions, isEmpty);
    });

    test('custom ErrorProcessor can emit recovery actions', () async {
      final errorProcessor = _TestErrorProcessor();
      final stream = errorProcessor.process(Exception('test'), StackTrace.current);

      final actions = await stream.toList();
      expect(actions.length, 1);
      expect(actions.first, isA<ErrorAction>());
    });
  });
}

// Test middlewares for DSL tests
class _StrategyMiddleware extends Middleware<AppState, Action> {
  _StrategyMiddleware() {
    apply(_NoOpStrategy()).on<FetchDataAction>((state, action) async* {
      yield LoadingAction();
    });
  }
}

class _GroupMiddleware extends Middleware<AppState, Action> {
  _GroupMiddleware() {
    apply(_NoOpStrategy())
        .on<IncrementAction>((state, action) async* {
          yield action;
        })
        .on<DecrementAction>((state, action) async* {
          yield action;
        });
  }
}

class _DuplicateMiddleware extends Middleware<AppState, Action> {
  _DuplicateMiddleware() {
    on<IncrementAction>((state, action) async* {
      yield action;
    });
    // This should throw
    on<IncrementAction>((state, action) async* {
      yield action;
    });
  }
}

class _TestErrorProcessor implements ErrorProcessor<Action> {
  @override
  Stream<Action> process(Object error, StackTrace stackTrace) async* {
    yield ErrorAction(error.toString());
  }
}

class _NoOpStrategy implements ExecutionStrategy {
  @override
  StrategyCategory get category => StrategyCategory.concurrency;

  @override
  Stream<A> Function(S state, T action) wrap<S, A extends Action, T extends A>(
    Stream<A> Function(S state, T action) processor,
  ) {
    return processor;
  }
}
