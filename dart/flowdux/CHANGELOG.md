# Changelog

## 0.1.0

- Initial release
- Redux-style state management with Store, Reducer, Action
- Middleware support with execution strategies
- Execution strategies: takeLatest, takeLeading, sequential, concurrent
- Timing strategies: debounce, throttle
- Resilience strategies: retry, retryWithBackoff
- Strategy chaining and groups
- FlowHolderAction for wrapping existing Streams
- FlowHolderMiddleware for processing FlowHolderAction
- Error handling with ErrorProcessor
- StoreLogger for debugging
