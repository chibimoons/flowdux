import 'package:flutter/material.dart' hide Action;
import 'package:flowdux_flutter/flowdux_flutter.dart';

import 'state/app_state.dart';
import 'store/app_reducer.dart';
import 'store/app_middleware.dart';
import 'app.dart';

void main() {
  final store = createStore<AppState, Action>(
    initialState: const AppState(),
    reducer: AppReducer().reducer,
    middlewares: [AppMiddleware()],
    logger: DebugStoreLogger(tag: 'FlowDux'),
  );

  runApp(StoreProvider<AppState, Action>(store: store, child: const MyApp()));
}
