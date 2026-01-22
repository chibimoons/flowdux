import 'action.dart';

/// Defines an action processor with optional execution strategy.
///
/// Processors handle specific action types and can emit zero or more
/// result actions via a Stream.
class Processor<S, A extends Action> {
  /// The processing function that handles the action.
  final Stream<A> Function(S state, A action) process;

  /// Creates a processor with the given processing function.
  Processor({required this.process});
}

/// Exception thrown when attempting to register a duplicate action processor.
///
/// Each action type can only have one processor per middleware.
class DuplicateProcessorException implements Exception {
  /// The action type that was duplicated.
  final Type actionType;

  /// Creates a duplicate processor exception.
  DuplicateProcessorException(this.actionType);

  @override
  String toString() =>
      "Processor for action type '$actionType' is already registered. "
      'Each action type can only have one processor per middleware.';
}
