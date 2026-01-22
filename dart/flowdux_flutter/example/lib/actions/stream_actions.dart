import 'package:flowdux_flutter/flowdux_flutter.dart';

/// Start streaming prices for a symbol
class SubscribePriceAction implements Action {
  final String symbol;
  const SubscribePriceAction(this.symbol);
}

/// Stop streaming
class UnsubscribeAction implements Action {}

/// Internal: streaming started
class StreamingStartedAction implements Action {
  final String symbol;
  const StreamingStartedAction(this.symbol);
}

/// Internal: price update received
class PriceUpdateAction implements Action {
  final double price;
  const PriceUpdateAction(this.price);
}

/// Internal: streaming stopped
class StreamingStoppedAction implements Action {}
