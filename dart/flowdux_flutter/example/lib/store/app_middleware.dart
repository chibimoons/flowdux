import 'dart:math';

import 'package:flowdux_flutter/flowdux_flutter.dart';

import '../state/app_state.dart';
import '../actions/actions.dart';

class AppMiddleware extends Middleware<AppState, Action> {
  AppMiddleware() {
    // Fetch random number with takeLatest strategy
    // If user clicks multiple times, only the latest request completes
    apply(takeLatest()).on<FetchRandomNumberAction>((state, action) async* {
      yield FetchStartedAction();

      // Simulate network delay
      await Future.delayed(const Duration(seconds: 1));

      // Randomly succeed or fail for demo purposes
      final random = Random();
      if (random.nextBool()) {
        final value = random.nextInt(100);
        yield FetchSuccessAction(value);
        yield ShowMessageAction('Fetched: $value');
      } else {
        yield FetchErrorAction('Random failure occurred');
        yield ShowMessageAction('Error: Failed to fetch');
      }
    });

    // Search with debounce strategy
    // Waits for user to stop typing before searching
    apply(debounce(const Duration(milliseconds: 500))).on<SearchAction>((
      state,
      action,
    ) async* {
      if (action.query.isEmpty) {
        yield SearchResultsAction('', []);
        return;
      }

      // Simulate search API call
      await Future.delayed(const Duration(milliseconds: 300));

      // Generate fake results
      final results = List.generate(
        5,
        (i) => '${action.query} result ${i + 1}',
      );
      yield SearchResultsAction(action.query, results);
    });

    // Price stream with takeLatest strategy
    // When user switches symbols, the previous subscription is cancelled
    // This demonstrates FlowDux's strength with infinite streams
    apply(takeLatest()).on<SubscribePriceAction>((state, action) async* {
      yield StreamingStartedAction(action.symbol);

      // Simulate WebSocket-like price stream
      final random = Random();
      double basePrice = _getBasePrice(action.symbol);

      while (true) {
        await Future.delayed(const Duration(milliseconds: 500));
        // Random price fluctuation within ±2%
        final change = (random.nextDouble() - 0.5) * 0.04 * basePrice;
        basePrice += change;
        yield PriceUpdateAction(basePrice);
      }
    });

    // Unsubscribe doesn't need special handling
    // The takeLatest will cancel when a new SubscribePriceAction comes
    // For explicit stop, we just yield the stopped action
    on<UnsubscribeAction>((state, action) async* {
      yield StreamingStoppedAction();
    });
  }

  double _getBasePrice(String symbol) {
    switch (symbol) {
      case 'BTC':
        return 67500.0;
      case 'ETH':
        return 3200.0;
      case 'SOL':
        return 145.0;
      default:
        return 100.0;
    }
  }
}
