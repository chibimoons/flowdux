import 'package:flutter/material.dart' hide Action;
import 'package:flowdux_flutter/flowdux_flutter.dart';

import '../../state/app_state.dart';
import '../../actions/actions.dart';

class BatchTab extends StatelessWidget {
  const BatchTab({super.key});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Text(
              'FlowHolderAction Demo',
              style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            const Text(
              'Dispatch multiple actions from a single action',
              style: TextStyle(color: Colors.grey),
            ),
            const SizedBox(height: 24),

            // Show current count
            StoreSelector<AppState, Action, int>(
              selector: (state) => state.count,
              builder: (context, count) {
                return Text(
                  'Count: $count',
                  style: Theme.of(context).textTheme.headlineMedium,
                );
              },
            ),

            const SizedBox(height: 32),

            // BatchIncrementAction
            _buildSection(
              title: 'BatchIncrementAction',
              description: 'Increments count multiple times instantly',
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  ElevatedButton(
                    onPressed: () {
                      context.dispatch<AppState, Action>(BatchIncrementAction(3));
                    },
                    child: const Text('+3 (instant)'),
                  ),
                  const SizedBox(width: 8),
                  ElevatedButton(
                    onPressed: () {
                      context.dispatch<AppState, Action>(BatchIncrementAction(5));
                    },
                    child: const Text('+5 (instant)'),
                  ),
                ],
              ),
            ),

            const SizedBox(height: 24),

            // AsyncBatchIncrementAction
            _buildSection(
              title: 'AsyncBatchIncrementAction',
              description: 'Increments count with delay between each',
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  ElevatedButton(
                    onPressed: () {
                      context.dispatch<AppState, Action>(
                        AsyncBatchIncrementAction(3, delay: const Duration(milliseconds: 300)),
                      );
                    },
                    child: const Text('+3 (animated)'),
                  ),
                  const SizedBox(width: 8),
                  ElevatedButton(
                    onPressed: () {
                      context.dispatch<AppState, Action>(
                        AsyncBatchIncrementAction(5, delay: const Duration(milliseconds: 200)),
                      );
                    },
                    child: const Text('+5 (animated)'),
                  ),
                ],
              ),
            ),

            const SizedBox(height: 24),

            // ResetAndSetAction
            _buildSection(
              title: 'ResetAndSetAction',
              description: 'Resets to 0, then sets to a value',
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  ElevatedButton(
                    onPressed: () {
                      context.dispatch<AppState, Action>(ResetAndSetAction(42));
                    },
                    child: const Text('Reset → 42'),
                  ),
                  const SizedBox(width: 8),
                  ElevatedButton(
                    onPressed: () {
                      context.dispatch<AppState, Action>(ResetAndSetAction(100));
                    },
                    child: const Text('Reset → 100'),
                  ),
                ],
              ),
            ),

            const SizedBox(height: 24),

            // Reset button
            OutlinedButton(
              onPressed: () {
                context.dispatch<AppState, Action>(SetCountAction(0));
              },
              child: const Text('Reset to 0'),
            ),

            const SizedBox(height: 32),

            // Code example
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Colors.grey.shade100,
                borderRadius: BorderRadius.circular(8),
              ),
              child: const Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'FlowHolderAction Example:',
                    style: TextStyle(fontWeight: FontWeight.bold),
                  ),
                  SizedBox(height: 8),
                  Text(
                    'class BatchIncrementAction implements FlowHolderAction {\n'
                    '  final int count;\n'
                    '  BatchIncrementAction(this.count);\n\n'
                    '  @override\n'
                    '  Stream<Action> toStreamAction() async* {\n'
                    '    for (var i = 0; i < count; i++) {\n'
                    '      yield IncrementAction();\n'
                    '    }\n'
                    '  }\n'
                    '}',
                    style: TextStyle(
                      fontFamily: 'monospace',
                      fontSize: 12,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSection({
    required String title,
    required String description,
    required Widget child,
  }) {
    return Column(
      children: [
        Text(
          title,
          style: const TextStyle(fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: 4),
        Text(
          description,
          style: const TextStyle(fontSize: 12, color: Colors.grey),
        ),
        const SizedBox(height: 8),
        child,
      ],
    );
  }
}
