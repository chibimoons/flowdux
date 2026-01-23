import 'dart:async';

import '../action.dart';
import 'execution_strategy.dart';

/// Exception thrown when attempting to chain strategies of the same category.
class DuplicateCategoryException implements Exception {
  /// The conflicting strategy category.
  final StrategyCategory category;

  /// The name of the first strategy with this category.
  final String firstName;

  /// The name of the second strategy with this category.
  final String secondName;

  /// Creates a [DuplicateCategoryException] with the conflicting details.
  DuplicateCategoryException({
    required this.category,
    required this.firstName,
    required this.secondName,
  });

  @override
  String toString() =>
      'Cannot chain strategies of the same category. '
      'Conflicting category: $category. '
      'First: $firstName, Second: $secondName';
}

/// A strategy composed of two chained strategies.
///
/// The first strategy wraps the second (first = outer layer).
/// Strategies of the same category cannot be chained.
///
/// Example:
/// ```dart
/// // debounce first, then takeLatest
/// final strategy = debounce(Duration(milliseconds: 300)).then(takeLatest());
///
/// // With retry
/// final resilientStrategy = debounce(Duration(milliseconds: 300))
///     .then(takeLatest())
///     .then(retry(3));
/// ```
class ChainedStrategy implements ExecutionStrategy {
  /// The outer (first) strategy in the chain.
  ///
  /// This strategy wraps the [second] strategy and is executed first.
  final ExecutionStrategy first;

  /// The inner (second) strategy in the chain.
  ///
  /// This strategy is wrapped by [first] and executed after it.
  final ExecutionStrategy second;

  /// Creates a chained strategy.
  ///
  /// Throws [DuplicateCategoryException] if any strategy in the chain
  /// shares the same category (excluding CHAINED).
  ChainedStrategy(this.first, this.second) {
    _validateCategories(first, second);
  }

  @override
  StrategyCategory get category => StrategyCategory.chained;

  @override
  Stream<A> Function(S state, T action) wrap<S, A extends Action, T extends A>(
    Stream<A> Function(S state, T action) processor,
  ) {
    // First strategy wraps second (first = outer layer)
    // So we first wrap with second, then wrap the result with first
    final wrappedWithSecond = second.wrap<S, A, T>(processor);
    return first.wrap<S, A, T>(wrappedWithSecond);
  }

  /// Validates that no two strategies in the chain share the same category.
  static void _validateCategories(
    ExecutionStrategy first,
    ExecutionStrategy second,
  ) {
    final firstCategories = _collectCategories(first);
    final secondCategories = _collectCategories(second);

    for (final category in firstCategories) {
      if (category != StrategyCategory.chained && secondCategories.contains(category)) {
        throw DuplicateCategoryException(
          category: category,
          firstName: _getStrategyName(first, category),
          secondName: _getStrategyName(second, category),
        );
      }
    }
  }

  /// Collects all categories from a strategy, including nested chained strategies.
  static Set<StrategyCategory> _collectCategories(ExecutionStrategy strategy) {
    final categories = <StrategyCategory>{};

    if (strategy is ChainedStrategy) {
      categories.addAll(_collectCategories(strategy.first));
      categories.addAll(_collectCategories(strategy.second));
    } else {
      categories.add(strategy.category);
    }

    return categories;
  }

  /// Gets the name of the strategy with the given category.
  static String _getStrategyName(ExecutionStrategy strategy, StrategyCategory category) {
    if (strategy is ChainedStrategy) {
      // Search in nested strategies
      final firstName = _tryGetStrategyName(strategy.first, category);
      if (firstName != null) return firstName;
      final secondName = _tryGetStrategyName(strategy.second, category);
      if (secondName != null) return secondName;
    }

    if (strategy.category == category) {
      return strategy.runtimeType.toString();
    }

    return strategy.runtimeType.toString();
  }

  static String? _tryGetStrategyName(ExecutionStrategy strategy, StrategyCategory category) {
    if (strategy is ChainedStrategy) {
      final firstName = _tryGetStrategyName(strategy.first, category);
      if (firstName != null) return firstName;
      return _tryGetStrategyName(strategy.second, category);
    }

    if (strategy.category == category) {
      return strategy.runtimeType.toString();
    }

    return null;
  }
}

/// Extension to enable strategy chaining with the `then` method.
extension ExecutionStrategyChaining on ExecutionStrategy {
  /// Chains this strategy with another strategy.
  ///
  /// This strategy becomes the outer layer (executed first), and [next]
  /// becomes the inner layer.
  ///
  /// Throws [DuplicateCategoryException] if any strategy in the chain
  /// shares the same category.
  ///
  /// Example:
  /// ```dart
  /// final strategy = debounce(Duration(milliseconds: 300)).then(takeLatest());
  /// ```
  ExecutionStrategy then(ExecutionStrategy next) => ChainedStrategy(this, next);
}
