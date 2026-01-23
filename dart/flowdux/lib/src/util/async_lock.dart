import 'dart:async';

/// Completer-based async lock for preventing async interleaving.
///
/// In Dart's single-threaded event loop model, async operations can still
/// interleave at await points. This lock ensures only one async operation
/// runs at a time within a critical section.
///
/// Example:
/// ```dart
/// final lock = AsyncLock();
///
/// // These will execute sequentially, not concurrently
/// lock.synchronized(() async {
///   await fetchData();
///   await processData();
/// });
///
/// lock.synchronized(() async {
///   await fetchMoreData();
/// });
/// ```
class AsyncLock {
  Completer<void>? _completer;

  /// Creates a new [AsyncLock] instance.

  /// Acquires the lock, executes the function, then releases the lock.
  ///
  /// If another operation is holding the lock, this will wait until
  /// the lock is released before executing.
  Future<T> synchronized<T>(Future<T> Function() fn) async {
    // Wait for any previous operation to complete
    while (_completer != null) {
      await _completer!.future;
    }

    // Acquire lock
    _completer = Completer<void>();
    try {
      return await fn();
    } finally {
      // Release lock
      final c = _completer!;
      _completer = null;
      c.complete();
    }
  }
}
