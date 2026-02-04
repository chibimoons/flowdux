# Changelog

## 0.3.2

- Optimize Store and FlowHolderMiddleware performance by skipping logger calls when NoOpStoreLogger is used

## 0.3.1

- Update example to align with Kotlin JVM sample pattern
- FlowHolderAction example now wraps external Repository stream (no side effects in Action)
- Move async operations (search, fetch, submit) to middleware with execution strategies
- Add CounterRepository and SearchApi as external side effect sources

## 0.3.0

- Add `FlowActionDelivery` enum for `FlowHolderAction` delivery mode
- `emit` (default): Inner actions bypass user middlewares, go directly to reducer
- `dispatch`: Inner actions are re-dispatched through full middleware pipeline

## 0.2.4

- Improve README documentation with comprehensive examples
- Add detailed execution strategies documentation (takeLatest, takeLeading, sequential, debounce, throttle, retry)
- Add FlowHolderAction usage examples
- Add ErrorProcessor documentation
- Add Architecture section

## 0.2.3

- Add distinct() to state stream to filter consecutive identical states
- Match Kotlin StateFlow's built-in distinctUntilChanged behavior

## 0.2.2

- Fix middleware blocking when emitting FlowHolderAction with infinite streams
- Change middleware chain from asyncExpand to flatMap for concurrent processing

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
