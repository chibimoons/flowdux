# Changelog

## 0.2.1

- Fix LICENSE file format to standard Apache 2.0 template for pub.dev recognition

## 0.2.0

- Fix LICENSE format for pub.dev OSI license recognition
- Add dartdoc comments to all public API elements
- Add example file demonstrating Store, Actions, FlowHolderAction, and strategies

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
