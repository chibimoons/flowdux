import 'package:flowdux_flutter/flowdux_flutter.dart';

import '../state/app_state.dart';
import '../actions/actions.dart';

class AppReducer extends ReducerBase<AppState, Action> {
  AppReducer() {
    // Counter
    on<IncrementAction>((state, _) => state.copyWith(count: state.count + 1));
    on<DecrementAction>((state, _) => state.copyWith(count: state.count - 1));
    on<AddAction>(
        (state, action) => state.copyWith(count: state.count + action.value));
    on<SetCountAction>((state, action) => state.copyWith(count: action.value));

    // Async Fetch
    on<FetchStartedAction>(
        (state, _) => state.copyWith(isLoading: true, error: null));
    on<FetchSuccessAction>((state, action) =>
        state.copyWith(isLoading: false, count: action.value));
    on<FetchErrorAction>((state, action) =>
        state.copyWith(isLoading: false, error: action.error));

    // Search
    on<SearchResultsAction>((state, action) => state.copyWith(
          searchQuery: action.query,
          searchResults: action.results,
          isLoading: false,
        ));

    // Messages
    on<ShowMessageAction>(
        (state, action) => state.copyWith(message: action.message));
    on<ClearMessageAction>((state, _) => state.copyWith(message: null));

    // Stream (Middleware approach)
    on<StreamingStartedAction>((state, action) => state.copyWith(
          activeSymbol: action.symbol,
          isStreaming: true,
          priceHistory: [],
        ));
    on<PriceUpdateAction>((state, action) => state.copyWith(
          currentPrice: action.price,
          priceHistory: [...state.priceHistory, action.price].take(20).toList(),
        ));
    on<StreamingStoppedAction>((state, _) => state.copyWith(
          isStreaming: false,
        ));

    // FlowHolder Stream
    on<FlowHolderStreamStartedAction>((state, action) => state.copyWith(
          flowHolderSymbol: action.symbol,
          isFlowHolderStreaming: true,
          flowHolderHistory: [],
        ));
    on<FlowHolderPriceUpdateAction>((state, action) => state.copyWith(
          flowHolderPrice: action.price,
          flowHolderHistory:
              [...state.flowHolderHistory, action.price].take(20).toList(),
        ));
    on<FlowHolderStreamStoppedAction>((state, _) => state.copyWith(
          isFlowHolderStreaming: false,
        ));
  }
}
