class AppState {
  final int count;
  final bool isLoading;
  final String? searchQuery;
  final List<String> searchResults;
  final String? error;
  final String? message;
  // Stream state (Middleware approach)
  final String? activeSymbol;
  final double? currentPrice;
  final List<double> priceHistory;
  final bool isStreaming;
  // FlowHolder stream state
  final String? flowHolderSymbol;
  final double? flowHolderPrice;
  final List<double> flowHolderHistory;
  final bool isFlowHolderStreaming;

  const AppState({
    this.count = 0,
    this.isLoading = false,
    this.searchQuery,
    this.searchResults = const [],
    this.error,
    this.message,
    this.activeSymbol,
    this.currentPrice,
    this.priceHistory = const [],
    this.isStreaming = false,
    this.flowHolderSymbol,
    this.flowHolderPrice,
    this.flowHolderHistory = const [],
    this.isFlowHolderStreaming = false,
  });

  AppState copyWith({
    int? count,
    bool? isLoading,
    String? searchQuery,
    List<String>? searchResults,
    String? error,
    String? message,
    String? activeSymbol,
    double? currentPrice,
    List<double>? priceHistory,
    bool? isStreaming,
    String? flowHolderSymbol,
    double? flowHolderPrice,
    List<double>? flowHolderHistory,
    bool? isFlowHolderStreaming,
  }) {
    return AppState(
      count: count ?? this.count,
      isLoading: isLoading ?? this.isLoading,
      searchQuery: searchQuery ?? this.searchQuery,
      searchResults: searchResults ?? this.searchResults,
      error: error,
      message: message,
      activeSymbol: activeSymbol ?? this.activeSymbol,
      currentPrice: currentPrice ?? this.currentPrice,
      priceHistory: priceHistory ?? this.priceHistory,
      isStreaming: isStreaming ?? this.isStreaming,
      flowHolderSymbol: flowHolderSymbol ?? this.flowHolderSymbol,
      flowHolderPrice: flowHolderPrice ?? this.flowHolderPrice,
      flowHolderHistory: flowHolderHistory ?? this.flowHolderHistory,
      isFlowHolderStreaming:
          isFlowHolderStreaming ?? this.isFlowHolderStreaming,
    );
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is AppState &&
          count == other.count &&
          isLoading == other.isLoading &&
          searchQuery == other.searchQuery &&
          searchResults.length == other.searchResults.length &&
          error == other.error &&
          message == other.message &&
          activeSymbol == other.activeSymbol &&
          currentPrice == other.currentPrice &&
          priceHistory.length == other.priceHistory.length &&
          isStreaming == other.isStreaming &&
          flowHolderSymbol == other.flowHolderSymbol &&
          flowHolderPrice == other.flowHolderPrice &&
          flowHolderHistory.length == other.flowHolderHistory.length &&
          isFlowHolderStreaming == other.isFlowHolderStreaming;

  @override
  int get hashCode => Object.hash(
        count,
        isLoading,
        searchQuery,
        searchResults.length,
        error,
        message,
        activeSymbol,
        currentPrice,
        priceHistory.length,
        isStreaming,
        flowHolderSymbol,
        flowHolderPrice,
        flowHolderHistory.length,
        isFlowHolderStreaming,
      );
}
