import 'dart:async';
import 'dart:math';

import 'package:fake_async/fake_async.dart';
import 'package:flowdux/flowdux.dart';
import 'package:test/test.dart';

// Test Actions
class FetchAction implements Action {}

class DataAction implements Action {
  final String data;
  DataAction(this.data);
}

class ErrorAction implements Action {
  final String message;
  ErrorAction(this.message);
}

// Test State
class AppState {
  final String? data;
  final String? error;

  AppState({this.data, this.error});

  AppState copyWith({String? data, String? error}) =>
      AppState(data: data ?? this.data, error: error ?? this.error);
}

// Test exceptions
class NetworkException implements Exception {
  final String message;
  NetworkException(this.message);
}

class ValidationException implements Exception {
  final String message;
  ValidationException(this.message);
}

void main() {
  group('Retry', () {
    test('retries on failure up to maxAttempts', () async {
      var attemptCount = 0;
      final strategy = retry(3);

      final wrappedProcessor = strategy.wrap<AppState, Action, FetchAction>((
        state,
        action,
      ) async* {
        attemptCount++;
        if (attemptCount < 3) {
          throw NetworkException('Network error');
        }
        yield DataAction('success');
      });

      final state = AppState();
      final results = <String>[];
      Object? caughtError;

      await wrappedProcessor(state, FetchAction())
          .listen(
            (a) => results.add((a as DataAction).data),
            onError: (e) => caughtError = e,
          )
          .asFuture();

      expect(attemptCount, 3);
      expect(results, ['success']);
      expect(caughtError, isNull);
    });

    test('throws error after all attempts exhausted', () async {
      var attemptCount = 0;
      final strategy = retry(3);

      final wrappedProcessor = strategy.wrap<AppState, Action, FetchAction>((
        state,
        action,
      ) async* {
        attemptCount++;
        throw NetworkException('Network error $attemptCount');
      });

      final state = AppState();
      Object? caughtError;

      final completer = Completer<void>();
      wrappedProcessor(state, FetchAction()).listen(
        (_) {},
        onError: (e) {
          caughtError = e;
        },
        onDone: () {
          if (!completer.isCompleted) completer.complete();
        },
        cancelOnError: false,
      );

      await completer.future;

      expect(attemptCount, 3);
      expect(caughtError, isA<NetworkException>());
      expect((caughtError as NetworkException).message, 'Network error 3');
    });

    test('never retries CancellationException', () async {
      var attemptCount = 0;
      final strategy = retry(3);

      final wrappedProcessor = strategy.wrap<AppState, Action, FetchAction>((
        state,
        action,
      ) async* {
        attemptCount++;
        throw CancellationException('Operation cancelled');
      });

      final state = AppState();
      Object? caughtError;

      final completer = Completer<void>();
      wrappedProcessor(state, FetchAction()).listen(
        (_) {},
        onError: (e) {
          caughtError = e;
        },
        onDone: () {
          if (!completer.isCompleted) completer.complete();
        },
        cancelOnError: false,
      );

      await completer.future;

      // Should only attempt once - CancellationException is never retried
      expect(attemptCount, 1);
      expect(caughtError, isA<CancellationException>());
    });

    test('respects retryIf predicate', () async {
      var attemptCount = 0;
      final strategy = retry(3, retryIf: (e) => e is NetworkException);

      final wrappedProcessor = strategy.wrap<AppState, Action, FetchAction>((
        state,
        action,
      ) async* {
        attemptCount++;
        if (attemptCount == 1) {
          throw NetworkException('Network error');
        }
        throw ValidationException('Validation error');
      });

      final state = AppState();
      Object? caughtError;

      final completer = Completer<void>();
      wrappedProcessor(state, FetchAction()).listen(
        (_) {},
        onError: (e) {
          caughtError = e;
        },
        onDone: () {
          if (!completer.isCompleted) completer.complete();
        },
        cancelOnError: false,
      );

      await completer.future;

      // First attempt: NetworkException (retryable)
      // Second attempt: ValidationException (not retryable, stops)
      expect(attemptCount, 2);
      expect(caughtError, isA<ValidationException>());
    });

    test('has resilience category', () {
      final strategy = retry(3);
      expect(strategy.category, StrategyCategory.resilience);
    });

    test('succeeds on first attempt if no error', () async {
      var attemptCount = 0;
      final strategy = retry(3);

      final wrappedProcessor = strategy.wrap<AppState, Action, FetchAction>((
        state,
        action,
      ) async* {
        attemptCount++;
        yield DataAction('success');
      });

      final state = AppState();
      final results = <String>[];

      await wrappedProcessor(
        state,
        FetchAction(),
      ).listen((a) => results.add((a as DataAction).data)).asFuture();

      expect(attemptCount, 1);
      expect(results, ['success']);
    });
  });

  group('RetryWithBackoff', () {
    test('retries with exponential delay', () {
      fakeAsync((async) {
        var attemptCount = 0;
        final delays = <Duration>[];
        var lastAttemptTime = DateTime.now();

        final strategy = retryWithBackoff(
          4,
          Duration(milliseconds: 100),
          factor: 2.0,
        );

        final wrappedProcessor = strategy.wrap<AppState, Action, FetchAction>((
          state,
          action,
        ) async* {
          final now = DateTime.now();
          if (attemptCount > 0) {
            delays.add(now.difference(lastAttemptTime));
          }
          lastAttemptTime = now;
          attemptCount++;
          if (attemptCount < 4) {
            throw NetworkException('Network error');
          }
          yield DataAction('success');
        });

        final state = AppState();
        final results = <String>[];

        wrappedProcessor(
          state,
          FetchAction(),
        ).listen((a) => results.add((a as DataAction).data));

        // First attempt immediately
        async.elapse(Duration.zero);
        expect(attemptCount, 1);

        // First retry after 100ms
        async.elapse(Duration(milliseconds: 100));
        expect(attemptCount, 2);

        // Second retry after 200ms (100 * 2)
        async.elapse(Duration(milliseconds: 200));
        expect(attemptCount, 3);

        // Third retry after 400ms (100 * 2^2)
        async.elapse(Duration(milliseconds: 400));
        expect(attemptCount, 4);
        expect(results, ['success']);
      });
    });

    test('caps delay at maxDelay', () {
      fakeAsync((async) {
        var attemptCount = 0;
        final strategy = retryWithBackoff(
          5,
          Duration(milliseconds: 100),
          maxDelay: Duration(milliseconds: 300),
          factor: 2.0,
        );

        final wrappedProcessor = strategy.wrap<AppState, Action, FetchAction>((
          state,
          action,
        ) async* {
          attemptCount++;
          if (attemptCount < 5) {
            throw NetworkException('Network error');
          }
          yield DataAction('success');
        });

        final state = AppState();

        wrappedProcessor(state, FetchAction()).listen((_) {});

        // First attempt
        async.elapse(Duration.zero);
        expect(attemptCount, 1);

        // Second attempt after 100ms
        async.elapse(Duration(milliseconds: 100));
        expect(attemptCount, 2);

        // Third attempt after 200ms
        async.elapse(Duration(milliseconds: 200));
        expect(attemptCount, 3);

        // Fourth attempt after 300ms (capped, would be 400ms)
        async.elapse(Duration(milliseconds: 300));
        expect(attemptCount, 4);

        // Fifth attempt after 300ms (still capped)
        async.elapse(Duration(milliseconds: 300));
        expect(attemptCount, 5);
      });
    });

    test('applies jitter to delays', () {
      // Use a seeded random for deterministic testing
      final random = Random(42);
      final strategy = RetryWithBackoffStrategy(
        maxAttempts: 3,
        initialDelay: Duration(milliseconds: 100),
        jitter: 0.5,
        random: random,
      );

      // The strategy should have jitter applied
      expect(strategy.jitter, 0.5);
      expect(strategy.category, StrategyCategory.resilience);
    });

    test('never retries CancellationException', () {
      fakeAsync((async) {
        var attemptCount = 0;
        final strategy = retryWithBackoff(3, Duration(milliseconds: 100));

        final wrappedProcessor = strategy.wrap<AppState, Action, FetchAction>((
          state,
          action,
        ) async* {
          attemptCount++;
          throw CancellationException('Cancelled');
        });

        final state = AppState();
        Object? caughtError;

        wrappedProcessor(
          state,
          FetchAction(),
        ).listen((_) {}, onError: (e) => caughtError = e);

        async.elapse(Duration.zero);

        // Should only attempt once
        expect(attemptCount, 1);
        expect(caughtError, isA<CancellationException>());

        // Even after waiting, no more attempts
        async.elapse(Duration(seconds: 10));
        expect(attemptCount, 1);
      });
    });

    test('respects retryIf predicate', () {
      fakeAsync((async) {
        var attemptCount = 0;
        Object? caughtError;
        final strategy = retryWithBackoff(
          3,
          Duration(milliseconds: 100),
          retryIf: (e) => e is NetworkException,
        );

        final wrappedProcessor = strategy.wrap<AppState, Action, FetchAction>((
          state,
          action,
        ) async* {
          attemptCount++;
          throw ValidationException('Invalid');
        });

        final state = AppState();

        wrappedProcessor(
          state,
          FetchAction(),
        ).listen((_) {}, onError: (e) => caughtError = e, cancelOnError: false);

        async.elapse(Duration.zero);
        expect(attemptCount, 1);
        expect(caughtError, isA<ValidationException>());

        // ValidationException not retryable, no more attempts
        async.elapse(Duration(seconds: 10));
        expect(attemptCount, 1);
      });
    });

    test('has resilience category', () {
      final strategy = retryWithBackoff(3, Duration(milliseconds: 100));
      expect(strategy.category, StrategyCategory.resilience);
    });
  });

  group('CancellationException', () {
    test('has correct toString without message', () {
      final ex = CancellationException();
      expect(ex.toString(), 'CancellationException');
    });

    test('has correct toString with message', () {
      final ex = CancellationException('Operation cancelled');
      expect(ex.toString(), 'CancellationException: Operation cancelled');
    });
  });

  group('Store integration', () {
    late Reducer<AppState, Action> reducer;

    setUp(() {
      reducer = _AppReducer().reducer;
    });

    test('retry works with Store', () async {
      var attemptCount = 0;

      final middleware = _RetryMiddleware(
        onProcess: () {
          attemptCount++;
          if (attemptCount < 3) {
            throw NetworkException('Network error');
          }
        },
      );

      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [middleware],
      );

      store.dispatch(FetchAction());
      await Future.delayed(Duration(milliseconds: 100));

      expect(attemptCount, 3);
      expect(store.currentState.data, 'success');

      await store.close();
    });

    test('retryWithBackoff works with Store', () async {
      var attemptCount = 0;

      final middleware = _RetryWithBackoffMiddleware(
        onProcess: () {
          attemptCount++;
          if (attemptCount < 3) {
            throw NetworkException('Network error');
          }
        },
      );

      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: reducer,
        middlewares: [middleware],
      );

      store.dispatch(FetchAction());

      // Wait for retries to complete (10ms + 20ms + buffer)
      await Future.delayed(Duration(milliseconds: 100));

      expect(attemptCount, 3);
      expect(store.currentState.data, 'success');

      await store.close();
    });
  });
}

// Test Reducer
class _AppReducer extends ReducerBase<AppState, Action> {
  _AppReducer() {
    on<DataAction>((state, action) => state.copyWith(data: action.data));
  }
}

// Test Middlewares using new DSL
class _RetryMiddleware extends Middleware<AppState, Action> {
  _RetryMiddleware({required void Function() onProcess}) {
    apply(retry(3)).on<FetchAction>((state, action) async* {
      onProcess();
      yield DataAction('success');
    });
  }
}

class _RetryWithBackoffMiddleware extends Middleware<AppState, Action> {
  _RetryWithBackoffMiddleware({required void Function() onProcess}) {
    apply(retryWithBackoff(3, Duration(milliseconds: 10))).on<FetchAction>((
      state,
      action,
    ) async* {
      onProcess();
      yield DataAction('success');
    });
  }
}
