import 'dart:async';

import 'package:flowdux/flowdux.dart';
import 'package:test/test.dart';

// Test Actions
class IncrementAction implements Action {}

class AddAction implements Action {
  final int value;
  AddAction(this.value);
}

// Default delivery (Emit) - inner actions bypass middlewares
class StreamConnectedAction with FlowHolderAction {
  final Stream<int> valueStream;
  StreamConnectedAction(this.valueStream);

  @override
  Stream<Action> toStreamAction() => valueStream.map((v) => AddAction(v));

  @override
  ExecutionStrategy get strategy => concurrent();
}

// Explicit Dispatch delivery - inner actions pass through middlewares
class DispatchDeliveryStreamAction with FlowHolderAction {
  final Stream<int> valueStream;
  DispatchDeliveryStreamAction(this.valueStream);

  @override
  FlowActionDelivery get delivery => FlowActionDelivery.dispatch;

  @override
  Stream<Action> toStreamAction() => valueStream.map((v) => AddAction(v));

  @override
  ExecutionStrategy get strategy => concurrent();
}

// Test State
class CounterState {
  final int count;
  CounterState(this.count);

  CounterState copyWith({int? count}) => CounterState(count ?? this.count);

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is CounterState &&
          runtimeType == other.runtimeType &&
          count == other.count;

  @override
  int get hashCode => count.hashCode;

  @override
  String toString() => 'CounterState(count: $count)';
}

// Test Reducer
class CounterReducer extends ReducerBase<CounterState, Action> {
  CounterReducer() {
    on<IncrementAction>((state, _) => state.copyWith(count: state.count + 1));
    on<AddAction>(
      (state, action) => state.copyWith(count: state.count + action.value),
    );
  }
}

// Tracking Middleware - records all actions that pass through
class TrackingMiddleware extends Middleware<CounterState, Action> {
  final List<Action> processedActions = [];

  @override
  Stream<Action> process(CounterState Function() getState, Action action) {
    processedActions.add(action);
    return Stream.value(action);
  }
}

void main() {
  group('FlowActionDelivery', () {
    late Reducer<CounterState, Action> reducer;

    setUp(() {
      reducer = CounterReducer().reducer;
    });

    test('default Emit delivery bypasses user middlewares', () async {
      final trackingMiddleware = TrackingMiddleware();
      final store = createStore<CounterState, Action>(
        initialState: CounterState(0),
        reducer: reducer,
        middlewares: [trackingMiddleware],
      );

      final controller = StreamController<int>();

      store.dispatch(StreamConnectedAction(controller.stream));

      controller.add(5);
      await Future.delayed(const Duration(milliseconds: 50));
      expect(store.currentState, CounterState(5));

      controller.add(3);
      await Future.delayed(const Duration(milliseconds: 50));
      expect(store.currentState, CounterState(8));

      await controller.close();

      // StreamConnectedAction itself passes through the middleware,
      // but inner AddAction should NOT appear in the tracking middleware
      final innerActions =
          trackingMiddleware.processedActions.whereType<AddAction>().toList();
      expect(
        innerActions,
        isEmpty,
        reason: 'Default Emit delivery should bypass user middlewares',
      );

      await store.close();
    });

    test(
      'explicit Dispatch delivery sends inner actions through full middleware pipeline',
      () async {
        final trackingMiddleware = TrackingMiddleware();
        final store = createStore<CounterState, Action>(
          initialState: CounterState(0),
          reducer: reducer,
          middlewares: [trackingMiddleware],
        );

        final controller = StreamController<int>();

        store.dispatch(DispatchDeliveryStreamAction(controller.stream));

        controller.add(5);
        await Future.delayed(const Duration(milliseconds: 50));
        expect(store.currentState, CounterState(5));

        controller.add(3);
        await Future.delayed(const Duration(milliseconds: 50));
        expect(store.currentState, CounterState(8));

        await controller.close();

        // Inner AddAction should pass through the tracking middleware
        final innerActions =
            trackingMiddleware.processedActions.whereType<AddAction>().toList();
        expect(
          innerActions,
          isNotEmpty,
          reason:
              'Dispatch delivery should send inner actions through middlewares',
        );
        expect(innerActions.length, 2);
        expect(innerActions[0].value, 5);
        expect(innerActions[1].value, 3);

        await store.close();
      },
    );

    test('FlowHolderAction has default delivery of Emit', () {
      final action = StreamConnectedAction(const Stream.empty());
      expect(action.delivery, FlowActionDelivery.emit);
    });

    test('delivery can be overridden to Dispatch', () {
      final action = DispatchDeliveryStreamAction(const Stream.empty());
      expect(action.delivery, FlowActionDelivery.dispatch);
    });
  });
}
