import 'dart:async';
import 'dart:math';

import 'package:flowdux_flutter/flowdux_flutter.dart';

/// Cancellation token for FlowHolderAction streams.
/// Call [cancel] to stop the stream.
class CancellationToken {
  bool _isCancelled = false;

  bool get isCancelled => _isCancelled;

  void cancel() {
    _isCancelled = true;
  }
}

/// Global token manager for FlowHolder streams.
/// This demonstrates explicit cancellation management required with FlowHolderAction.
class FlowHolderStreamManager {
  static CancellationToken? _currentToken;

  /// Starts a new stream, cancelling any existing one.
  static CancellationToken startNew() {
    _currentToken?.cancel();
    _currentToken = CancellationToken();
    return _currentToken!;
  }

  /// Cancels the current stream.
  static void cancelCurrent() {
    _currentToken?.cancel();
    _currentToken = null;
  }
}

/// FlowHolderAction that starts an infinite price stream.
///
/// With TakeLatest strategy (default), dispatching a new FlowHolderPriceStreamAction
/// will automatically cancel the previous stream. The CancellationToken is kept
/// for explicit manual cancellation via Stop button.
class FlowHolderPriceStreamAction with FlowHolderAction {
  final String symbol;
  final CancellationToken token;

  FlowHolderPriceStreamAction(this.symbol, this.token);

  // Uses TakeLatest strategy (default) - Store will auto-cancel previous stream

  @override
  Stream<Action> toStreamAction() async* {
    yield FlowHolderStreamStartedAction(symbol);

    final random = Random();
    double basePrice = _getBasePrice(symbol);

    while (!token.isCancelled) {
      await Future.delayed(const Duration(milliseconds: 500));

      if (token.isCancelled) break;

      // Random price fluctuation within ±2%
      final change = (random.nextDouble() - 0.5) * 0.04 * basePrice;
      basePrice += change;
      yield FlowHolderPriceUpdateAction(basePrice);
    }

    yield FlowHolderStreamStoppedAction();
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

/// Internal: FlowHolder streaming started
class FlowHolderStreamStartedAction implements Action {
  final String symbol;
  const FlowHolderStreamStartedAction(this.symbol);
}

/// Internal: FlowHolder price update received
class FlowHolderPriceUpdateAction implements Action {
  final double price;
  const FlowHolderPriceUpdateAction(this.price);
}

/// Internal: FlowHolder streaming stopped
class FlowHolderStreamStoppedAction implements Action {}
