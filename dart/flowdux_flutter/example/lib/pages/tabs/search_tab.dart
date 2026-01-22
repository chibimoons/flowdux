import 'package:flutter/material.dart' hide Action;
import 'package:flowdux_flutter/flowdux_flutter.dart';

import '../../state/app_state.dart';
import '../../actions/actions.dart';

class SearchTab extends StatelessWidget {
  const SearchTab({super.key});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(16.0),
      child: Column(
        children: [
          const Text(
            'Search (Debounce)',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 8),
          const Text(
            'Uses debounce(500ms) strategy',
            style: TextStyle(color: Colors.grey),
          ),
          const SizedBox(height: 16),

          // Search input
          TextField(
            decoration: const InputDecoration(
              hintText: 'Type to search...',
              prefixIcon: Icon(Icons.search),
              border: OutlineInputBorder(),
            ),
            onChanged: (value) {
              context.dispatch<AppState, Action>(SearchAction(value));
            },
          ),

          const SizedBox(height: 16),

          // Search results using StoreSelector
          Expanded(
            child: StoreSelector<AppState, Action, List<String>>(
              selector: (state) => state.searchResults,
              builder: (context, results) {
                if (results.isEmpty) {
                  return const Center(
                    child: Text('No results. Start typing to search.'),
                  );
                }

                return ListView.builder(
                  itemCount: results.length,
                  itemBuilder: (context, index) {
                    return ListTile(
                      leading: const Icon(Icons.article),
                      title: Text(results[index]),
                    );
                  },
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
