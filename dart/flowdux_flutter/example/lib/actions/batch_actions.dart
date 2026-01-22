import 'package:flowdux_flutter/flowdux_flutter.dart';

import 'counter_actions.dart';

/// BatchAction: Dispatches multiple actions at once
/// Demonstrates FlowHolderAction for sequential action dispatch
class BatchIncrementAction with FlowHolderAction {
  final int count;

  BatchIncrementAction(this.count);

  @override
  ExecutionStrategy get strategy => concurrent(); // Batch actions should complete fully

  @override
  Stream<Action> toStreamAction() async* {
    for (var i = 0; i < count; i++) {
      yield IncrementAction();
    }
  }
}

/// AsyncBatchAction: Dispatches actions with delay
/// Demonstrates async FlowHolderAction pattern
class AsyncBatchIncrementAction with FlowHolderAction {
  final int count;
  final Duration delay;

  AsyncBatchIncrementAction(this.count, {this.delay = const Duration(milliseconds: 200)});

  @override
  ExecutionStrategy get strategy => concurrent(); // Batch actions should complete fully

  @override
  Stream<Action> toStreamAction() async* {
    for (var i = 0; i < count; i++) {
      await Future.delayed(delay);
      yield IncrementAction();
    }
  }
}

/// Reset and set action: Multiple actions in one dispatch
class ResetAndSetAction with FlowHolderAction {
  final int value;

  ResetAndSetAction(this.value);

  @override
  ExecutionStrategy get strategy => concurrent(); // Should complete both actions

  @override
  Stream<Action> toStreamAction() async* {
    yield SetCountAction(0);
    await Future.delayed(const Duration(milliseconds: 100));
    yield SetCountAction(value);
  }
}
