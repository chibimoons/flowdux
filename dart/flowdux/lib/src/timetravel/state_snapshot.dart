import '../action.dart';

/// Represents a snapshot of state at a specific point in time.
///
/// Each snapshot records the state before and after an action was applied,
/// along with the action itself and a timestamp.
class StateSnapshot<S, A extends Action> {
  /// The index of this snapshot in the history list.
  final int index;

  /// The action that caused this state change. Null for the initial state.
  final A? action;

  /// The state before the action was applied. Null for the initial state.
  final S? previousState;

  /// The state after the action was applied.
  final S currentState;

  /// When this state change occurred.
  final DateTime timestamp;

  /// Creates a new [StateSnapshot].
  const StateSnapshot({
    required this.index,
    this.action,
    this.previousState,
    required this.currentState,
    required this.timestamp,
  });

  /// Creates a copy with the given fields replaced.
  StateSnapshot<S, A> copyWith({int? index}) {
    return StateSnapshot(
      index: index ?? this.index,
      action: action,
      previousState: previousState,
      currentState: currentState,
      timestamp: timestamp,
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is StateSnapshot<S, A> &&
          runtimeType == other.runtimeType &&
          index == other.index &&
          action == other.action &&
          previousState == other.previousState &&
          currentState == other.currentState &&
          timestamp == other.timestamp;

  @override
  int get hashCode => Object.hash(
        index,
        action,
        previousState,
        currentState,
        timestamp,
      );

  @override
  String toString() =>
      'StateSnapshot(index: $index, action: $action, currentState: $currentState)';
}
