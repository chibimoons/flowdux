import 'package:flutter/material.dart' hide Action;
import 'package:flowdux_flutter/flowdux_flutter.dart';

import '../../state/app_state.dart';
import '../../actions/actions.dart';

class AsyncTab extends StatelessWidget {
  const AsyncTab({super.key});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          const Text(
            'Async Fetch (StoreSelector)',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 8),
          const Text(
            'Uses takeLatest() strategy',
            style: TextStyle(color: Colors.grey),
          ),
          const SizedBox(height: 24),

          // StoreSelector: Only rebuilds when selected value changes
          StoreSelector<AppState, Action, bool>(
            selector: (state) => state.isLoading,
            builder: (context, isLoading) {
              if (isLoading) {
                return const Column(
                  children: [
                    CircularProgressIndicator(),
                    SizedBox(height: 16),
                    Text('Loading...'),
                  ],
                );
              }
              return const SizedBox.shrink();
            },
          ),

          // Show count from selector
          StoreSelector<AppState, Action, int>(
            selector: (state) => state.count,
            builder: (context, count) {
              return Text(
                'Current Value: $count',
                style: Theme.of(context).textTheme.headlineMedium,
              );
            },
          ),

          const SizedBox(height: 16),

          // Show error if any
          StoreSelector<AppState, Action, String?>(
            selector: (state) => state.error,
            builder: (context, error) {
              if (error != null) {
                return Container(
                  padding: const EdgeInsets.all(8),
                  margin: const EdgeInsets.symmetric(vertical: 8),
                  decoration: BoxDecoration(
                    color: Colors.red.shade100,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(error, style: const TextStyle(color: Colors.red)),
                );
              }
              return const SizedBox.shrink();
            },
          ),

          const SizedBox(height: 24),

          StoreConsumer<AppState, Action>(
            builder: (context, store, state) {
              return ElevatedButton.icon(
                onPressed: state.isLoading
                    ? null
                    : () => store.dispatch(FetchRandomNumberAction()),
                icon: const Icon(Icons.cloud_download),
                label: const Text('Fetch Random Number'),
              );
            },
          ),

          const SizedBox(height: 8),

          const Text(
            'Click multiple times to see takeLatest() in action',
            style: TextStyle(fontSize: 12, color: Colors.grey),
          ),
        ],
      ),
    );
  }
}
