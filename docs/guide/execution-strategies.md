# Execution Strategies

FlowDux provides execution strategies to control how concurrent actions are processed in middleware.

| Category | Strategies | Purpose |
|----------|------------|---------|
| **Concurrency** | `takeLatest()`, `takeLeading()`, `sequential()` | How to handle concurrent executions |
| **Timing** | `debounce(duration)`, `throttle(duration)` | When to execute |
| **Resilience** | `retry(n)`, `retryWithBackoff(...)` | How to handle failures |

## Concurrency Strategies

### takeLatest()

Cancels previous processing when a new action arrives. Only the latest action's result is emitted.

```kotlin
on<AppAction.Search>(takeLatest()) { state, action ->
    val results = searchApi.search(action.query)
    emit(AppAction.SearchResults(results))
}
```

Use cases: Search, API refresh, pagination with pull-to-refresh

### takeLeading()

Ignores new actions while one is still processing. Only the first action in a series executes.

```kotlin
on<AppAction.Submit>(takeLeading()) { state, action ->
    // Prevents duplicate submissions
    val result = api.submit(action.data)
    emit(AppAction.SubmitSuccess(result))
}
```

Use cases: Form submission, payment processing, preventing double-clicks

### sequential()

Queues actions and processes them one at a time, preserving order. Unlike `takeLeading()` which ignores new actions, `sequential()` waits for the current action to complete before processing the next one.

```kotlin
on<AppAction.Save>(sequential()) { state, action ->
    // All save requests are processed in order
    api.save(action.data)
    emit(AppAction.SaveComplete(action.id))
}
```

Use cases: Sequential API calls, ordered form saves, FIFO task processing

## Timing Strategies

### debounce(duration)

Delays execution. If another action arrives before the delay completes, the previous action is canceled and the timer restarts.

```kotlin
on<AppAction.TextChanged>(debounce(500.milliseconds)) { state, action ->
    // Only saves after user stops typing for 500ms
    api.save(action.text)
    emit(AppAction.SaveComplete)
}
```

Use cases: Search autocomplete, autosave, input validation

### throttle(duration)

Limits execution rate. Executes the first action immediately, then ignores subsequent actions until the time window passes.

```kotlin
on<AppAction.Scroll>(throttle(1000.milliseconds)) { state, action ->
    // Logs scroll position at most once per second
    analytics.logScroll(action.position)
    emit(action)
}
```

Use cases: Analytics events, scroll handling, rate limiting

## Resilience Strategies

### retry(maxAttempts)

Retries the processor execution on failure.

```kotlin
on<AppAction.FetchData>(retry(3)) { state, action ->
    // Retries up to 3 times on failure
    val data = api.fetchData(action.id)
    emit(AppAction.FetchSuccess(data))
}

// With custom retry condition
on<AppAction.FetchData>(retry(3) { e -> e is IOException }) { state, action ->
    // Only retries on IOException
}
```

### retryWithBackoff(maxAttempts, initialDelay, ...)

Retries with exponential backoff delay between attempts.

```kotlin
on<AppAction.FetchData>(retryWithBackoff(
    maxAttempts = 5,
    initialDelay = 100.milliseconds,
    maxDelay = 10.seconds,
    factor = 2.0,      // Exponential multiplier
    jitter = 0.1       // Random jitter to prevent thundering herd
)) { state, action ->
    val data = api.fetchData(action.id)
    emit(AppAction.FetchSuccess(data))
}
```

Use cases: Network error recovery, transient server errors, rate limiting

## Strategy Groups

Use `group` to share a strategy instance across multiple action types. Actions within the same group will coordinate their execution (e.g., one action can cancel another):

```kotlin
override val processors = buildProcessors {
    // SearchAction and RefreshAction share the same takeLatest instance
    // Dispatching RefreshAction will cancel an in-progress SearchAction
    group(takeLatest()) {
        on<SearchAction> { state, action ->
            val results = searchApi.search(action.query)
            emit(SearchResults(results))
        }
        on<RefreshAction> { state, action ->
            val results = searchApi.refresh()
            emit(SearchResults(results))
        }
    }
}
```

## Strategy Chaining

Combine strategies from different categories using the `then` operator:

```kotlin
// Debounce input, then cancel previous search, then retry on failure
on<AppAction.Search>(
    debounce(300.milliseconds) then takeLatest() then retry(3)
) { state, action ->
    val results = searchApi.search(action.query)
    emit(AppAction.SearchResults(results))
}
```

**Rules:**
- Strategies from different categories can be chained
- Strategies from the same category cannot be chained (throws exception)
- Chained strategies work with `group()` as well
