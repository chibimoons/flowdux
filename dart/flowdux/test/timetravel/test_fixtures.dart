import 'package:flowdux/flowdux.dart';

class CounterState {
  final int count;
  const CounterState([this.count = 0]);

  CounterState copyWith({int? count}) => CounterState(count ?? this.count);

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is CounterState && count == other.count;

  @override
  int get hashCode => count.hashCode;

  @override
  String toString() => 'CounterState(count: $count)';
}

abstract class CounterAction implements Action {}

class IncrementAction implements CounterAction {}

class DecrementAction implements CounterAction {}

class AddAction implements CounterAction {
  final int value;
  AddAction(this.value);
}

CounterState counterReducer(CounterState state, CounterAction action) {
  if (action is IncrementAction) {
    return state.copyWith(count: state.count + 1);
  } else if (action is DecrementAction) {
    return state.copyWith(count: state.count - 1);
  } else if (action is AddAction) {
    return state.copyWith(count: state.count + action.value);
  }
  return state;
}
