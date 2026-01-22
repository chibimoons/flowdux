/// Exception thrown when an action's execution is cancelled.
///
/// This exception is thrown when strategies like [TakeLatest] cancel
/// a previous execution in favor of a new one.
///
/// Resilience strategies like [Retry] and [RetryWithBackoff] will
/// NEVER retry a [CancellationException] - it is always rethrown.
class CancellationException implements Exception {
  /// Optional message describing why the operation was cancelled.
  final String? message;

  /// Creates a [CancellationException] with an optional message.
  const CancellationException([this.message]);

  @override
  String toString() {
    if (message != null) {
      return 'CancellationException: $message';
    }
    return 'CancellationException';
  }
}
