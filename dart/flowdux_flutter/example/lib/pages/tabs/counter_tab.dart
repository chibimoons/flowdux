import 'package:flutter/material.dart' hide Action;
import 'package:flowdux_flutter/flowdux_flutter.dart';

import '../../state/app_state.dart';
import '../../actions/actions.dart';

class CounterTab extends StatelessWidget {
  const CounterTab({super.key});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Text(
            'Counter (StoreBuilder)',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 16),

          // StoreBuilder: Rebuilds when any state changes
          StoreBuilder<AppState, Action>(
            builder: (context, state) {
              return Text(
                '${state.count}',
                style: Theme.of(context).textTheme.displayLarge,
              );
            },
          ),

          const SizedBox(height: 32),

          // StoreConsumer: Access both store and state
          StoreConsumer<AppState, Action>(
            builder: (context, store, state) {
              return Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  FloatingActionButton(
                    heroTag: 'decrement',
                    onPressed: () => store.dispatch(DecrementAction()),
                    child: const Icon(Icons.remove),
                  ),
                  const SizedBox(width: 16),
                  FloatingActionButton(
                    heroTag: 'increment',
                    onPressed: () => store.dispatch(IncrementAction()),
                    child: const Icon(Icons.add),
                  ),
                ],
              );
            },
          ),

          const SizedBox(height: 32),

          // Using context extension to dispatch
          Row(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              ElevatedButton(
                onPressed: () =>
                    context.dispatch<AppState, Action>(AddAction(10)),
                child: const Text('+10'),
              ),
              const SizedBox(width: 8),
              ElevatedButton(
                onPressed: () =>
                    context.dispatch<AppState, Action>(AddAction(-10)),
                child: const Text('-10'),
              ),
              const SizedBox(width: 8),
              ElevatedButton(
                onPressed: () =>
                    context.dispatch<AppState, Action>(SetCountAction(0)),
                child: const Text('Reset'),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
