# Concurrency Strategies Research

*Research conducted: 2026-01-20*

This document analyzes concurrency patterns from Redux-Saga, RxJS, and Kotlin Flow to identify potential strategies for flowdux.

---

## Current flowdux Strategies

| flowdux | RxJS | Kotlin Flow | Redux-Saga | Description |
|---------|------|-------------|------------|-------------|
| `takeLatest()` | switchMap | flatMapLatest | takeLatest | Cancels previous, only latest executes |
| `takeLeading()` | exhaustMap | - | takeLeading | Ignores new while processing |
| `debounce(duration)` | debounceTime | debounce | debounce | Delays execution, resets on new action |
| `throttle(duration)` | throttleFirst | - | throttle | First in time window, ignores rest |

---

## Potential New Strategies

### 1. takeEvery / Concurrent

**Priority: High**

**Behavior:** Allows all actions to execute concurrently without cancellation.

**Equivalents:**
- RxJS: `mergeMap`
- Kotlin Flow: `flatMapMerge`
- Redux-Saga: `takeEvery`

**Timeline:**
```
Action:   Fetch(1) ─────────────> Result(1) ✓
Action:        Fetch(2) ────────────────> Result(2) ✓
Action:             Fetch(3) ──────> Result(3) ✓
```

**Use cases:**
- Parallel data loading
- Notification processing
- Bulk operations where order doesn't matter
- Independent background tasks

**Implementation complexity:** Low (essentially no strategy - default behavior)

**Notes:**
- Results may arrive out of order
- Can cause race conditions if modifying shared state
- Consider adding optional `concurrency` limit parameter (like `flatMapMerge`)

---

### 2. Sequential / ConcatMap

**Priority: High**

**Behavior:** Queues actions and processes them one at a time, preserving order.

**Equivalents:**
- RxJS: `concatMap`
- Kotlin Flow: `flatMapConcat`
- Redux-Saga: (manual implementation with channels)

**Timeline:**
```
Action:   Save(1) ─────────────> Saved(1) ✓
Action:        Save(2) (queued)      ─────────────> Saved(2) ✓
Action:             Save(3) (queued)                     ─────> Saved(3) ✓
```

**Use cases:**
- Form saves requiring order preservation
- Sequential API calls
- FIFO task processing
- Operations that must not overlap

**Implementation complexity:** Medium (Mutex + Channel/Queue)

**Potential API:**
```kotlin
on<SaveAction>(sequential()) { state, action ->
    val result = api.save(action.data)
    emit(SaveCompleted(result))
}
```

---

### 3. Retry / RetryWithBackoff

**Priority: Medium**

**Behavior:** Retries failed operations with optional exponential backoff.

**Timeline:**
```
Action:   Fetch ──> ❌ fail ──[1s]──> ❌ fail ──[2s]──> ❌ fail ──[4s]──> ✓ success
```

**Use cases:**
- Network error recovery
- Transient server errors
- API rate limiting responses
- Flaky external services

**Implementation complexity:** Medium (try/catch + delay loop)

**Potential API:**
```kotlin
// Simple retry
on<FetchAction>(retry(maxAttempts = 3)) { state, action ->
    val result = api.fetch(action.id)
    emit(FetchSuccess(result))
}

// With exponential backoff
on<FetchAction>(retryWithBackoff(
    maxAttempts = 5,
    initialDelay = 1.seconds,
    maxDelay = 30.seconds,
    factor = 2.0
)) { state, action ->
    val result = api.fetch(action.id)
    emit(FetchSuccess(result))
}
```

**Considerations:**
- Should emit failure action after all retries exhausted
- Consider adding jitter to prevent thundering herd
- May want to allow custom retry conditions (which exceptions to retry)

---

### 4. Buffer / Batch

**Priority: Medium**

**Behavior:** Collects actions and processes them together as a batch.

**Timeline:**
```
Action:   Track(1) ──┐
Action:   Track(2) ──┼── [300ms or 10 items] ──> BatchTrack([1,2,3]) ✓
Action:   Track(3) ──┘
```

**Use cases:**
- Analytics batch sending
- Bulk API calls
- Aggregating frequent updates
- Database batch inserts

**Implementation complexity:** High (Channel + timeout + type transformation)

**Potential API:**
```kotlin
on<TrackEvent>(buffer(
    maxSize = 10,
    maxWait = 300.milliseconds
)) { state, actions -> // Note: receives List<TrackEvent>
    api.trackBatch(actions.map { it.event })
    emit(BatchTracked(actions.size))
}
```

**Challenges:**
- Processor signature changes (receives list instead of single action)
- Need to handle both size-based and time-based triggers
- What happens on shutdown? Flush remaining?

---

### 5. CircuitBreaker

**Priority: Low**

**Behavior:** Stops executing after consecutive failures, allows recovery after timeout.

**State Machine:**
```
CLOSED ──[failures >= threshold]──> OPEN ──[timeout]──> HALF_OPEN ──[success]──> CLOSED
                                      │                      │
                                      └──────────────────────┘
                                              [failure]
```

**Timeline:**
```
Attempt:  ❌ ─> ❌ ─> ❌ ─> [OPEN: skip all for 30s] ─> [HALF-OPEN: try one] ─> ✓ ─> [CLOSED]
```

**Use cases:**
- Server protection
- Cascading failure prevention
- Resource conservation
- Graceful degradation

**Implementation complexity:** High (state machine with timers)

**Potential API:**
```kotlin
on<ApiCallAction>(circuitBreaker(
    failureThreshold = 5,
    resetTimeout = 30.seconds,
    halfOpenAttempts = 1
)) { state, action ->
    val result = api.call(action.request)
    emit(ApiCallSuccess(result))
}
```

**Considerations:**
- Need to emit specific action when circuit is open
- Should track failure/success metrics
- Consider integrating with existing libraries (Resilience4j pattern)

---

## Strategy Classification

Strategies can be categorized into three types:

| Category | Strategies | Purpose |
|----------|------------|---------|
| **Timing** | debounce, throttle | *When* to execute |
| **Concurrency** | takeLatest, takeLeading, takeEvery, sequential | *How* to handle concurrent executions |
| **Resilience** | retry, circuitBreaker | *How* to handle failures |

### Valid Chaining Combinations

Cross-category chaining makes sense:

| First | Second | Example Use Case |
|-------|--------|------------------|
| Timing | Concurrency | `debounce → takeLatest`: Wait for input pause, then cancel previous |
| Timing | Resilience | `throttle → retry`: Rate limit with auto-retry |
| Concurrency | Resilience | `takeLatest → retry`: Latest only with retry on failure |
| Timing | Concurrency → Resilience | `debounce → sequential → retry`: Full pipeline |

### Invalid/Redundant Combinations

Same-category combinations are usually redundant or conflicting:

- `takeLatest + takeLeading`: Mutually exclusive (one dominates)
- `debounce + debounce`: Redundant (just use longer duration)
- `retry + circuitBreaker`: Could work but complex interaction

---

## Implementation Priority Recommendation

### Phase 1: Core Additions
1. **sequential** - Opposite of takeEvery, common need for ordered operations
2. **takeEvery** - Explicit "no strategy" for documentation/clarity (may be optional)

### Phase 2: Resilience
3. **retry** - Almost essential for network apps
4. **retryWithBackoff** - Enhanced retry with backoff

### Phase 3: Advanced (Future)
5. **buffer** - Complex but useful for batching
6. **circuitBreaker** - Complex, consider external library integration

---

## References

- [Redux-Saga Concurrency Patterns](https://redux-saga.js.org/docs/advanced/Concurrency/)
- [Redux-Saga API Reference](https://redux-saga.js.org/docs/api/)
- [RxJS Higher-Order Mapping Operators](https://blog.angular-university.io/rxjs-higher-order-mapping/)
- [RxJS mergeMap vs switchMap vs concatMap vs exhaustMap](https://dev.to/kinginit/rxjs-mergemap-vs-switchmap-vs-concatmap-vs-exhaustmap-5gpg)
- [Kotlin Flow Flattening Operators](https://www.baeldung.com/kotlin/flows-advanced-flattening)
- [Kotlin Flow flatMap Operators](https://kt.academy/article/cc-flatmap)
- [Retry with Backoff Pattern - AWS](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/retry-backoff.html)
- [Exponential Backoff and Jitter - Baeldung](https://www.baeldung.com/resilience4j-backoff-jitter)
