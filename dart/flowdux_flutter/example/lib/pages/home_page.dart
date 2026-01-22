import 'package:flutter/material.dart' hide Action;
import 'package:flowdux_flutter/flowdux_flutter.dart';

import '../state/app_state.dart';
import '../actions/actions.dart';
import 'tabs/counter_tab.dart';
import 'tabs/async_tab.dart';
import 'tabs/search_tab.dart';
import 'tabs/batch_tab.dart';
import 'tabs/stream_tab.dart';
import 'tabs/flow_holder_stream_tab.dart';

class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    // StoreListener: Listen to state changes for side effects
    // Shows snackbar when message changes
    return StoreListener<AppState, Action>(
      listenWhen: (previous, current) =>
          current.message != null && current.message != previous.message,
      listener: (context, store, state) {
        if (state.message != null) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text(state.message!),
              duration: const Duration(seconds: 2),
            ),
          );
          // Clear message after showing
          Future.delayed(const Duration(milliseconds: 100), () {
            store.dispatch(ClearMessageAction());
          });
        }
      },
      child: DefaultTabController(
        length: 6,
        child: Scaffold(
          appBar: AppBar(
            title: const Text('FlowDux Flutter Example'),
            bottom: const TabBar(
              isScrollable: true,
              tabs: [
                Tab(text: 'Counter', icon: Icon(Icons.add)),
                Tab(text: 'Async', icon: Icon(Icons.cloud_download)),
                Tab(text: 'Search', icon: Icon(Icons.search)),
                Tab(text: 'Batch', icon: Icon(Icons.layers)),
                Tab(text: 'Stream', icon: Icon(Icons.show_chart)),
                Tab(text: 'FlowHolder', icon: Icon(Icons.stream)),
              ],
            ),
          ),
          body: const TabBarView(
            children: [
              CounterTab(),
              AsyncTab(),
              SearchTab(),
              BatchTab(),
              StreamTab(),
              FlowHolderStreamTab(),
            ],
          ),
        ),
      ),
    );
  }
}
