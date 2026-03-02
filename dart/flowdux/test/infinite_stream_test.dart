import 'dart:async';

import 'package:flowdux/flowdux.dart';
import 'package:test/test.dart';

// Test Actions
class SubscribeAction implements Action {
  final String channel;
  SubscribeAction(this.channel);
}

class DataReceivedAction implements Action {
  final String channel;
  final int value;
  DataReceivedAction(this.channel, this.value);
}

class IncrementAction implements Action {}

class SetValueAction implements Action {
  final int value;
  SetValueAction(this.value);
}

// Test State
class AppState {
  final int count;
  final Map<String, List<int>> channelData;

  AppState({this.count = 0, this.channelData = const {}});

  AppState copyWith({int? count, Map<String, List<int>>? channelData}) =>
      AppState(
        count: count ?? this.count,
        channelData: channelData ?? this.channelData,
      );

  @override
  String toString() => 'AppState(count: $count, channelData: $channelData)';
}

// Test Reducer
class AppReducer extends ReducerBase<AppState, Action> {
  AppReducer() {
    on<IncrementAction>((state, _) => state.copyWith(count: state.count + 1));
    on<SetValueAction>((state, action) => state.copyWith(count: action.value));
    on<DataReceivedAction>((state, action) {
      final newData = Map<String, List<int>>.from(state.channelData);
      final channelList = List<int>.from(newData[action.channel] ?? []);
      channelList.add(action.value);
      newData[action.channel] = channelList;
      return state.copyWith(channelData: newData);
    });
  }
}

void main() {
  group('Infinite Stream Tests', () {
    test(
      'other actions are processed while infinite stream is running',
      () async {
        // Simulates WebSocket-like infinite stream
        final streamController = StreamController<int>.broadcast();

        final middleware = _InfiniteStreamMiddleware(streamController.stream);
        final store = createStore<AppState, Action>(
          initialState: AppState(),
          reducer: AppReducer().reducer,
          middlewares: [middleware],
        );

        // Start infinite stream subscription
        store.dispatch(SubscribeAction('price'));
        await Future.delayed(Duration(milliseconds: 10));

        // Send data through stream
        streamController.add(100);
        await Future.delayed(Duration(milliseconds: 10));

        // While stream is running, dispatch other actions
        // These should NOT be blocked by the infinite stream
        store.dispatch(IncrementAction());
        store.dispatch(IncrementAction());
        store.dispatch(IncrementAction());
        await Future.delayed(Duration(milliseconds: 10));

        // Verify both stream data and increment actions were processed
        expect(store.currentState.count, 3);
        expect(store.currentState.channelData['price'], contains(100));

        // Send more data through stream
        streamController.add(200);
        await Future.delayed(Duration(milliseconds: 10));
        expect(store.currentState.channelData['price'], contains(200));

        // Dispatch more actions while stream continues
        store.dispatch(SetValueAction(42));
        await Future.delayed(Duration(milliseconds: 10));
        expect(store.currentState.count, 42);

        await streamController.close();
        await store.close();
      },
    );

    test(
      'takeLatest cancels previous stream - only latest result reaches reducer',
      () async {
        // In Dart, async generators continue running after subscription cancellation,
        // but their yielded values are discarded. The key behavior is that only
        // the latest action's result reaches the reducer.

        final middleware = _TakeLatestDelayedMiddleware();

        final store = createStore<AppState, Action>(
          initialState: AppState(),
          reducer: AppReducer().reducer,
          middlewares: [middleware],
        );

        // Subscribe to first channel - starts processing
        store.dispatch(SubscribeAction('btc'));
        await Future.delayed(
          Duration(milliseconds: 10),
        ); // Short wait, btc still processing

        // Subscribe to second channel while first is still processing
        // takeLatest should cancel first's output
        store.dispatch(SubscribeAction('eth'));
        await Future.delayed(Duration(milliseconds: 10));

        // Subscribe to third channel
        store.dispatch(SubscribeAction('sol'));

        // Wait for all processing to complete
        await Future.delayed(Duration(milliseconds: 150));

        // Only the LAST subscription's result should be in state
        // btc and eth's yields were discarded due to takeLatest
        expect(store.currentState.channelData.keys, contains('sol'));
        expect(store.currentState.channelData.keys, isNot(contains('btc')));
        expect(store.currentState.channelData.keys, isNot(contains('eth')));

        await store.close();
      },
    );

    test(
      'multiple infinite streams can run concurrently without blocking',
      () async {
        final stream1Controller = StreamController<int>.broadcast();
        final stream2Controller = StreamController<int>.broadcast();

        final middleware = _MultiStreamMiddleware({
          'price': stream1Controller.stream,
          'orderbook': stream2Controller.stream,
        });

        final store = createStore<AppState, Action>(
          initialState: AppState(),
          reducer: AppReducer().reducer,
          middlewares: [middleware],
        );

        // Subscribe to both streams concurrently
        store.dispatch(SubscribeAction('price'));
        store.dispatch(SubscribeAction('orderbook'));
        await Future.delayed(Duration(milliseconds: 20));

        // Send data to both streams
        stream1Controller.add(100);
        stream2Controller.add(1);
        await Future.delayed(Duration(milliseconds: 20));

        stream1Controller.add(101);
        stream2Controller.add(2);
        await Future.delayed(Duration(milliseconds: 20));

        // Verify both streams are being processed
        expect(
          store.currentState.channelData['price'],
          containsAll([100, 101]),
        );
        expect(
          store.currentState.channelData['orderbook'],
          containsAll([1, 2]),
        );

        // Other actions should still work (not blocked)
        store.dispatch(IncrementAction());
        await Future.delayed(Duration(milliseconds: 10));
        expect(store.currentState.count, 1);

        await stream1Controller.close();
        await stream2Controller.close();
        await store.close();
      },
    );

    test('concurrent default: actions do not block each other', () async {
      // This test verifies FlowDux's default concurrent behavior
      final results = <String>[];

      final middleware = _ConcurrentTestMiddleware(
        onAction: (name) => results.add('$name-start'),
        onComplete: (name) => results.add('$name-end'),
      );

      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: AppReducer().reducer,
        middlewares: [middleware],
      );

      // Dispatch a slow action followed by fast actions
      store.dispatch(SubscribeAction('slow')); // Takes 100ms
      await Future.delayed(Duration(milliseconds: 10));

      store.dispatch(IncrementAction()); // Fast action
      store.dispatch(IncrementAction()); // Fast action
      await Future.delayed(Duration(milliseconds: 50));

      // Fast actions should complete before slow action
      expect(
        results.where((r) => r.contains('Increment')).length,
        4,
      ); // 2 start + 2 end

      await Future.delayed(Duration(milliseconds: 100));
      expect(results.last, 'slow-end');

      await store.close();
    });

    test('stream continues after individual actions complete', () async {
      final streamController = StreamController<int>.broadcast();
      var streamDataCount = 0;

      final middleware = _CountingStreamMiddleware(
        streamController.stream,
        onData: () => streamDataCount++,
      );

      final store = createStore<AppState, Action>(
        initialState: AppState(),
        reducer: AppReducer().reducer,
        middlewares: [middleware],
      );

      // Start stream
      store.dispatch(SubscribeAction('test'));
      await Future.delayed(Duration(milliseconds: 10));

      // Send initial data
      streamController.add(1);
      await Future.delayed(Duration(milliseconds: 10));
      expect(streamDataCount, 1);

      // Dispatch and complete other actions
      store.dispatch(IncrementAction());
      store.dispatch(IncrementAction());
      await Future.delayed(Duration(milliseconds: 20));

      // Stream should still be active
      streamController.add(2);
      streamController.add(3);
      await Future.delayed(Duration(milliseconds: 10));
      expect(streamDataCount, 3);

      expect(store.currentState.count, 2);

      await streamController.close();
      await store.close();
    });
  });

  group('FlowHolderAction Strategy Tests', () {
    test(
      'TakeLatest FlowHolderAction cancels previous stream when new one is dispatched',
      () async {
        final store = createStore<AppState, Action>(
          initialState: AppState(),
          reducer: ExtendedAppReducer().reducer,
        );

        // Start first infinite stream
        store.dispatch(
          InfiniteStreamFlowAction(
            'stream1',
            emitInterval: Duration(milliseconds: 50),
          ),
        );

        // Wait for a few emissions
        await Future.delayed(Duration(milliseconds: 120));
        final countAfterFirstStream = store.currentState.count;
        expect(countAfterFirstStream, greaterThanOrEqualTo(2));

        final countBeforeNewStream = store.currentState.count;

        // Start second stream - should cancel the first
        store.dispatch(
          InfiniteStreamFlowAction(
            'stream2',
            emitInterval: Duration(milliseconds: 50),
          ),
        );

        // Wait for emissions from the new stream
        await Future.delayed(Duration(milliseconds: 180));

        final countAfter = store.currentState.count;

        // If both streams were running, count would increase much faster
        // Should have roughly countBeforeNewStream + 3 emissions (not double)
        expect(
          countAfter,
          lessThanOrEqualTo(countBeforeNewStream + 5),
          reason:
              'Expected count to be around ${countBeforeNewStream + 3}, but was $countAfter. '
              'Both streams might be running concurrently.',
        );

        await store.close();
      },
    );

    test(
      'Concurrent FlowHolderAction allows multiple streams to run concurrently',
      () async {
        final store = createStore<AppState, Action>(
          initialState: AppState(),
          reducer: ExtendedAppReducer().reducer,
        );

        // Start two concurrent streams
        // stream1: adds 1 three times = 3
        // stream2: adds 10 three times = 30
        // Total: 33
        store.dispatch(
          ConcurrentAdditiveFlowAction(
            'stream1',
            addValue: 1,
            count: 3,
            delayBetween: Duration(milliseconds: 30),
          ),
        );
        store.dispatch(
          ConcurrentAdditiveFlowAction(
            'stream2',
            addValue: 10,
            count: 3,
            delayBetween: Duration(milliseconds: 30),
          ),
        );

        // Wait for both streams to complete
        await Future.delayed(Duration(milliseconds: 200));

        expect(
          store.currentState.count,
          equals(33),
          reason:
              'Expected both streams to contribute (33), but got ${store.currentState.count}',
        );

        await store.close();
      },
    );

    test(
      'TakeLatest FlowHolderAction is cancelled when store is closed',
      () async {
        final store = createStore<AppState, Action>(
          initialState: AppState(),
          reducer: ExtendedAppReducer().reducer,
        );

        // Start infinite stream
        store.dispatch(
          InfiniteStreamFlowAction(
            'stream1',
            emitInterval: Duration(milliseconds: 50),
          ),
        );

        // Wait for a few emissions
        await Future.delayed(Duration(milliseconds: 120));
        expect(store.currentState.count, greaterThanOrEqualTo(2));

        final countBeforeClose = store.currentState.count;

        // Close the store
        await store.close();

        // Give some time for any pending emissions
        await Future.delayed(Duration(milliseconds: 200));

        // Count should not have increased significantly after close
        expect(
          store.currentState.count,
          lessThanOrEqualTo(countBeforeClose + 1),
          reason: 'Stream should have been cancelled on store close',
        );
      },
    );

    test(
      'other actions are processed while TakeLatest infinite stream is running',
      () async {
        final store = createStore<AppState, Action>(
          initialState: AppState(),
          reducer: ExtendedAppReducer().reducer,
        );

        // Start infinite stream
        store.dispatch(
          InfiniteStreamFlowAction(
            'stream1',
            emitInterval: Duration(milliseconds: 100),
          ),
        );

        // Wait for first emission
        await Future.delayed(Duration(milliseconds: 120));
        expect(store.currentState.count, greaterThanOrEqualTo(1));

        // Dispatch a regular action while stream is running
        store.dispatch(SetValueAction(100));

        // Wait a bit
        await Future.delayed(Duration(milliseconds: 50));

        // The SetValueAction should have been processed
        // Stream emissions will continue adding, so count should be >= 100
        expect(
          store.currentState.count,
          greaterThanOrEqualTo(100),
          reason:
              'Regular action should be processed while infinite stream is running',
        );

        await store.close();
      },
    );
  });
}

// Test Middlewares

/// Basic infinite stream middleware - concurrent by default
class _InfiniteStreamMiddleware extends Middleware<AppState, Action> {
  _InfiniteStreamMiddleware(Stream<int> dataStream) {
    on<SubscribeAction>((state, action) async* {
      await for (final value in dataStream) {
        yield DataReceivedAction(action.channel, value);
      }
    });
  }
}

/// Middleware with takeLatest - emits after delay to test cancellation
class _TakeLatestDelayedMiddleware extends Middleware<AppState, Action> {
  _TakeLatestDelayedMiddleware() {
    apply(takeLatest()).on<SubscribeAction>((state, action) async* {
      // Simulate async work
      await Future.delayed(Duration(milliseconds: 50));
      yield DataReceivedAction(action.channel, 1);
    });
  }
}

/// Middleware supporting multiple named streams
class _MultiStreamMiddleware extends Middleware<AppState, Action> {
  _MultiStreamMiddleware(Map<String, Stream<int>> streams) {
    on<SubscribeAction>((state, action) async* {
      final stream = streams[action.channel];
      if (stream != null) {
        await for (final value in stream) {
          yield DataReceivedAction(action.channel, value);
        }
      }
    });
  }
}

/// Middleware for testing concurrent execution
class _ConcurrentTestMiddleware extends Middleware<AppState, Action> {
  _ConcurrentTestMiddleware({
    required void Function(String) onAction,
    required void Function(String) onComplete,
  }) {
    on<SubscribeAction>((state, action) async* {
      onAction(action.channel);
      await Future.delayed(Duration(milliseconds: 100));
      onComplete(action.channel);
      yield DataReceivedAction(action.channel, 0);
    });

    on<IncrementAction>((state, action) async* {
      onAction('Increment');
      await Future.delayed(Duration(milliseconds: 5));
      onComplete('Increment');
      yield action;
    });
  }
}

/// Middleware that counts stream data
class _CountingStreamMiddleware extends Middleware<AppState, Action> {
  _CountingStreamMiddleware(
    Stream<int> dataStream, {
    required void Function() onData,
  }) {
    on<SubscribeAction>((state, action) async* {
      await for (final value in dataStream) {
        onData();
        yield DataReceivedAction(action.channel, value);
      }
    });
  }
}

// FlowHolderAction Tests

/// TakeLatest infinite stream FlowHolderAction (default strategy = TakeLatest).
/// When a new instance is dispatched, the previous stream is cancelled.
class InfiniteStreamFlowAction with FlowHolderAction {
  final String id;
  final Duration emitInterval;

  InfiniteStreamFlowAction(
    this.id, {
    this.emitInterval = const Duration(milliseconds: 50),
  });

  @override
  Stream<Action> toStreamAction() async* {
    while (true) {
      await Future.delayed(emitInterval);
      yield IncrementAction();
    }
  }

  // Uses default TakeLatestStrategy
}

/// Concurrent FlowHolderAction.
/// Multiple streams can run concurrently.
class ConcurrentStreamFlowAction with FlowHolderAction {
  final String id;
  final List<int> values;
  final Duration delayBetween;

  ConcurrentStreamFlowAction(
    this.id,
    this.values, {
    this.delayBetween = const Duration(milliseconds: 30),
  });

  @override
  ExecutionStrategy get strategy => concurrent();

  @override
  Stream<Action> toStreamAction() async* {
    for (final value in values) {
      await Future.delayed(delayBetween);
      yield SetValueAction(value);
    }
  }
}

/// Concurrent additive FlowHolderAction for concurrent test.
class ConcurrentAdditiveFlowAction with FlowHolderAction {
  final String id;
  final int addValue;
  final int count;
  final Duration delayBetween;

  ConcurrentAdditiveFlowAction(
    this.id, {
    required this.addValue,
    required this.count,
    this.delayBetween = const Duration(milliseconds: 30),
  });

  @override
  ExecutionStrategy get strategy => concurrent();

  @override
  Stream<Action> toStreamAction() async* {
    for (var i = 0; i < count; i++) {
      await Future.delayed(delayBetween);
      yield _AddAction(addValue);
    }
  }
}

class _AddAction implements Action {
  final int value;
  _AddAction(this.value);
}

// Extended reducer for FlowHolderAction tests
class ExtendedAppReducer extends ReducerBase<AppState, Action> {
  ExtendedAppReducer() {
    on<IncrementAction>((state, _) => state.copyWith(count: state.count + 1));
    on<SetValueAction>((state, action) => state.copyWith(count: action.value));
    on<_AddAction>(
      (state, action) => state.copyWith(count: state.count + action.value),
    );
    on<DataReceivedAction>((state, action) {
      final newData = Map<String, List<int>>.from(state.channelData);
      final channelList = List<int>.from(newData[action.channel] ?? []);
      channelList.add(action.value);
      newData[action.channel] = channelList;
      return state.copyWith(channelData: newData);
    });
  }
}
