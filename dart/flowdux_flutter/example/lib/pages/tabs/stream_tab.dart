import 'package:flutter/material.dart' hide Action;
import 'package:flowdux_flutter/flowdux_flutter.dart';

import '../../state/app_state.dart';
import '../../actions/actions.dart';

class StreamTab extends StatelessWidget {
  const StreamTab({super.key});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text(
            'Price Stream (takeLatest)',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 8),
          const Text(
            'Demonstrates infinite stream handling.\n'
            'Switching symbols cancels the previous stream.',
            style: TextStyle(color: Colors.grey),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 24),

          // Symbol selection buttons
          StoreConsumer<AppState, Action>(
            builder: (context, store, state) {
              return Row(
                mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                children: [
                  _SymbolButton(
                    symbol: 'BTC',
                    isActive: state.activeSymbol == 'BTC',
                    onPressed: () =>
                        store.dispatch(const SubscribePriceAction('BTC')),
                  ),
                  _SymbolButton(
                    symbol: 'ETH',
                    isActive: state.activeSymbol == 'ETH',
                    onPressed: () =>
                        store.dispatch(const SubscribePriceAction('ETH')),
                  ),
                  _SymbolButton(
                    symbol: 'SOL',
                    isActive: state.activeSymbol == 'SOL',
                    onPressed: () =>
                        store.dispatch(const SubscribePriceAction('SOL')),
                  ),
                ],
              );
            },
          ),

          const SizedBox(height: 32),

          // Current price display
          StoreSelector<AppState, Action, _PriceDisplay>(
            selector: (state) => _PriceDisplay(
              symbol: state.activeSymbol,
              price: state.currentPrice,
              isStreaming: state.isStreaming,
            ),
            builder: (context, data) {
              if (data.symbol == null) {
                return const Center(
                  child: Text(
                    'Select a symbol to start streaming',
                    style: TextStyle(fontSize: 16, color: Colors.grey),
                  ),
                );
              }

              return Column(
                children: [
                  Text(
                    data.symbol!,
                    style: const TextStyle(
                      fontSize: 24,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    data.price != null
                        ? '\$${data.price!.toStringAsFixed(2)}'
                        : '--',
                    style: TextStyle(
                      fontSize: 48,
                      fontWeight: FontWeight.bold,
                      color: Theme.of(context).colorScheme.primary,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(
                        data.isStreaming ? Icons.circle : Icons.circle_outlined,
                        size: 12,
                        color: data.isStreaming ? Colors.green : Colors.grey,
                      ),
                      const SizedBox(width: 4),
                      Text(
                        data.isStreaming ? 'Live' : 'Stopped',
                        style: TextStyle(
                          color: data.isStreaming ? Colors.green : Colors.grey,
                        ),
                      ),
                    ],
                  ),
                ],
              );
            },
          ),

          const SizedBox(height: 32),

          // Price history chart
          Expanded(
            child: StoreSelector<AppState, Action, List<double>>(
              selector: (state) => state.priceHistory,
              builder: (context, history) {
                if (history.isEmpty) {
                  return const Center(
                    child: Text(
                      'Price history will appear here',
                      style: TextStyle(color: Colors.grey),
                    ),
                  );
                }

                return _PriceChart(prices: history);
              },
            ),
          ),

          const SizedBox(height: 16),

          // Stop button
          StoreConsumer<AppState, Action>(
            builder: (context, store, state) {
              return ElevatedButton.icon(
                onPressed: state.isStreaming
                    ? () => store.dispatch(UnsubscribeAction())
                    : null,
                icon: const Icon(Icons.stop),
                label: const Text('Stop Stream'),
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.red.shade400,
                  foregroundColor: Colors.white,
                ),
              );
            },
          ),
        ],
      ),
    );
  }
}

class _PriceDisplay {
  final String? symbol;
  final double? price;
  final bool isStreaming;

  _PriceDisplay({
    required this.symbol,
    required this.price,
    required this.isStreaming,
  });

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is _PriceDisplay &&
          symbol == other.symbol &&
          price == other.price &&
          isStreaming == other.isStreaming;

  @override
  int get hashCode => Object.hash(symbol, price, isStreaming);
}

class _SymbolButton extends StatelessWidget {
  final String symbol;
  final bool isActive;
  final VoidCallback onPressed;

  const _SymbolButton({
    required this.symbol,
    required this.isActive,
    required this.onPressed,
  });

  @override
  Widget build(BuildContext context) {
    return ElevatedButton(
      onPressed: onPressed,
      style: ElevatedButton.styleFrom(
        backgroundColor: isActive
            ? Theme.of(context).colorScheme.primary
            : null,
        foregroundColor: isActive ? Colors.white : null,
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
      ),
      child: Text(
        symbol,
        style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
      ),
    );
  }
}

class _PriceChart extends StatelessWidget {
  final List<double> prices;

  const _PriceChart({required this.prices});

  @override
  Widget build(BuildContext context) {
    if (prices.isEmpty) return const SizedBox.shrink();

    final minPrice = prices.reduce((a, b) => a < b ? a : b);
    final maxPrice = prices.reduce((a, b) => a > b ? a : b);
    final range = maxPrice - minPrice;
    final effectiveRange = range == 0 ? 1.0 : range;

    return CustomPaint(
      painter: _ChartPainter(
        prices: prices,
        minPrice: minPrice,
        effectiveRange: effectiveRange,
        lineColor: Theme.of(context).colorScheme.primary,
      ),
      size: Size.infinite,
    );
  }
}

class _ChartPainter extends CustomPainter {
  final List<double> prices;
  final double minPrice;
  final double effectiveRange;
  final Color lineColor;

  _ChartPainter({
    required this.prices,
    required this.minPrice,
    required this.effectiveRange,
    required this.lineColor,
  });

  @override
  void paint(Canvas canvas, Size size) {
    if (prices.length < 2) return;

    final paint = Paint()
      ..color = lineColor
      ..strokeWidth = 2
      ..style = PaintingStyle.stroke;

    final fillPaint = Paint()
      ..color = lineColor.withOpacity(0.1)
      ..style = PaintingStyle.fill;

    final path = Path();
    final fillPath = Path();

    final stepX = size.width / (prices.length - 1);

    for (var i = 0; i < prices.length; i++) {
      final x = i * stepX;
      final normalizedPrice = (prices[i] - minPrice) / effectiveRange;
      final y = size.height - (normalizedPrice * size.height * 0.8) - 20;

      if (i == 0) {
        path.moveTo(x, y);
        fillPath.moveTo(x, size.height);
        fillPath.lineTo(x, y);
      } else {
        path.lineTo(x, y);
        fillPath.lineTo(x, y);
      }
    }

    fillPath.lineTo(size.width, size.height);
    fillPath.close();

    canvas.drawPath(fillPath, fillPaint);
    canvas.drawPath(path, paint);

    // Draw current price dot
    final lastX = (prices.length - 1) * stepX;
    final lastNormalized = (prices.last - minPrice) / effectiveRange;
    final lastY = size.height - (lastNormalized * size.height * 0.8) - 20;

    canvas.drawCircle(Offset(lastX, lastY), 5, Paint()..color = lineColor);
  }

  @override
  bool shouldRepaint(covariant _ChartPainter oldDelegate) {
    return prices != oldDelegate.prices;
  }
}
