import 'package:flutter/material.dart' hide Action;
import 'package:flowdux_flutter/flowdux_flutter.dart';

import '../../state/app_state.dart';
import '../../actions/actions.dart';

class FlowHolderStreamTab extends StatelessWidget {
  const FlowHolderStreamTab({super.key});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text(
            'FlowHolderAction Stream',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 8),
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: Colors.amber.shade50,
              borderRadius: BorderRadius.circular(8),
              border: Border.all(color: Colors.amber.shade200),
            ),
            child: const Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'FlowHolderAction 방식:',
                  style: TextStyle(fontWeight: FontWeight.bold),
                ),
                SizedBox(height: 4),
                Text(
                  '• TakeLatest 전략으로 심볼 전환 시 자동 취소\n'
                  '• Stop 버튼용 CancellationToken (선택적)\n'
                  '• 별도 Middleware 작성 없이 Action만으로 스트림 처리',
                  style: TextStyle(fontSize: 12),
                ),
              ],
            ),
          ),
          const SizedBox(height: 16),

          // Symbol selection buttons
          StoreConsumer<AppState, Action>(
            builder: (context, store, state) {
              return Row(
                mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                children: [
                  _SymbolButton(
                    symbol: 'BTC',
                    isActive: state.flowHolderSymbol == 'BTC',
                    onPressed: () => _startStream(store, 'BTC'),
                  ),
                  _SymbolButton(
                    symbol: 'ETH',
                    isActive: state.flowHolderSymbol == 'ETH',
                    onPressed: () => _startStream(store, 'ETH'),
                  ),
                  _SymbolButton(
                    symbol: 'SOL',
                    isActive: state.flowHolderSymbol == 'SOL',
                    onPressed: () => _startStream(store, 'SOL'),
                  ),
                ],
              );
            },
          ),

          const SizedBox(height: 24),

          // Current price display
          StoreSelector<AppState, Action, _PriceDisplay>(
            selector: (state) => _PriceDisplay(
              symbol: state.flowHolderSymbol,
              price: state.flowHolderPrice,
              isStreaming: state.isFlowHolderStreaming,
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
                      color: Colors.amber.shade700,
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
                        data.isStreaming ? 'Live (FlowHolder)' : 'Stopped',
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

          const SizedBox(height: 24),

          // Price history chart
          Expanded(
            child: StoreSelector<AppState, Action, List<double>>(
              selector: (state) => state.flowHolderHistory,
              builder: (context, history) {
                if (history.isEmpty) {
                  return const Center(
                    child: Text(
                      'Price history will appear here',
                      style: TextStyle(color: Colors.grey),
                    ),
                  );
                }

                return _PriceChart(prices: history, color: Colors.amber);
              },
            ),
          ),

          const SizedBox(height: 16),

          // Stop button
          StoreConsumer<AppState, Action>(
            builder: (context, store, state) {
              return ElevatedButton.icon(
                onPressed: state.isFlowHolderStreaming
                    ? () {
                        FlowHolderStreamManager.cancelCurrent();
                      }
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

  void _startStream(Store<AppState, Action> store, String symbol) {
    // Create new token (cancels any existing stream)
    final token = FlowHolderStreamManager.startNew();
    // Dispatch FlowHolderAction
    store.dispatch(FlowHolderPriceStreamAction(symbol, token));
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
        backgroundColor: isActive ? Colors.amber.shade700 : null,
        foregroundColor: isActive ? Colors.white : null,
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
      ),
      child: Text(
        symbol,
        style: const TextStyle(
          fontSize: 16,
          fontWeight: FontWeight.bold,
        ),
      ),
    );
  }
}

class _PriceChart extends StatelessWidget {
  final List<double> prices;
  final Color color;

  const _PriceChart({required this.prices, required this.color});

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
        lineColor: color,
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
      ..color = lineColor.withValues(alpha: 0.1)
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

    canvas.drawCircle(
      Offset(lastX, lastY),
      5,
      Paint()..color = lineColor,
    );
  }

  @override
  bool shouldRepaint(covariant _ChartPainter oldDelegate) {
    return prices != oldDelegate.prices;
  }
}
