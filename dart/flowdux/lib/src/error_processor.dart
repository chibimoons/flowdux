import 'action.dart';

/// Error processor interface for handling errors in the middleware chain.
///
/// Implement this interface to provide custom error handling logic.
abstract class ErrorProcessor<A extends Action> {
  /// Processes an error and returns a stream of recovery actions.
  ///
  /// Return an empty stream to swallow the error.
  Stream<A> process(Object error, StackTrace stackTrace);
}

/// Default error processor that swallows all errors.
///
/// Used as the default when no error processor is provided.
class DefaultErrorProcessor<A extends Action> implements ErrorProcessor<A> {
  @override
  Stream<A> process(Object error, StackTrace stackTrace) => const Stream.empty();
}
