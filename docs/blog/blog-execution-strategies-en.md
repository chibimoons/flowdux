# Declarative Execution Strategies: Taming Concurrent Actions in Kotlin

*How flowdux brings takeLatest, debounce, and throttle to Redux-style middleware*

---

## The Concurrency Problem Nobody Talks About

Every mobile developer has faced this scenario:

```kotlin
// User types quickly in search box
searchBox.onTextChanged { query ->
    viewModel.search(query)  // Called for every keystroke
}
```

What happens when the user types "hello"?

```
Request: "h"     → Response arrives 3rd  → Shows results for "h"
Request: "he"    → Response arrives 1st  → Shows results for "he"
Request: "hel"   → Response arrives 5th  → Shows results for "hel"  ← Final state!
Request: "hell"  → Response arrives 4th  → Shows results for "hell"
Request: "hello" → Response arrives 2nd  → Shows results for "hello"
```

The user sees flickering results, and the final state shows "hel" instead of "hello". This is the **race condition problem** — the slowest response wins, not the latest input.

---

## Traditional Solutions Are Verbose

### RxJava/RxKotlin

```kotlin
searchSubject
    .debounce(300, TimeUnit.MILLISECONDS)
    .switchMap { query ->
        api.search(query).toObservable()
    }
    .subscribe { results -> /* update UI */ }
```

### Kotlin Coroutines (Manual)

```kotlin
private var searchJob: Job? = null

fun search(query: String) {
    searchJob?.cancel()
    searchJob = viewModelScope.launch {
        delay(300)  // debounce
        val results = api.search(query)
        _state.value = results
    }
}
```

### The Problems

1. **Logic scattered** — Cancellation logic mixed with business logic
2. **Boilerplate** — Every async operation needs the same pattern
3. **Error-prone** — Easy to forget cleanup, hard to test
4. **Not declarative** — The "what" is buried in "how"

---

## The flowdux Solution: Declarative Execution Strategies

flowdux introduces **Execution Strategies** — declarative wrappers for middleware processors that handle concurrency automatically.

```kotlin
class SearchMiddleware : Middleware<AppState, AppAction> {
    override val processors = buildProcessors {
        // Just declare: "use takeLatest for search"
        on<SearchAction>(takeLatest()) { state, action ->
            val results = api.search(action.query)
            emit(SearchResults(results))
        }
    }
}
```

That's it. No manual job tracking, no cancellation logic, no race conditions.

---

## The Four Strategies

### 1. takeLatest()

**Behavior:** Cancels previous processing when a new action arrives. Only the latest result is emitted.

```kotlin
on<SearchAction>(takeLatest()) { state, action ->
    val results = api.search(action.query)  // Previous call is canceled
    emit(SearchResults(results))
}
```

**Timeline:**
```
Action:   Search("h") ──────────────────────────────> (canceled)
Action:        Search("he") ────────────────────────> (canceled)
Action:             Search("hel") ──────────────────> (canceled)
Action:                  Search("hello") ───────────> SearchResults ✓
```

**Use cases:**
- Search autocomplete
- API refresh / pull-to-refresh
- Real-time filtering
- Any operation where only the latest matters

> **Note: Cancellation is Cooperative**
>
> Kotlin coroutine cancellation only works at suspension points. If your processor contains blocking calls (legacy SDKs, blocking IO), cancellation won't interrupt them:
> ```kotlin
> // ✅ Works - suspend function provides cancellation point
> val results = api.search(query)
>
> // ❌ Won't cancel - blocking call
> val results = legacySdk.blockingSearch(query)
> ```
> `withContext(Dispatchers.IO)` prevents UI blocking, but it doesn't make a blocking call cancellable by itself. Prefer cancellable suspend APIs, or use `suspendCancellableCoroutine` when bridging legacy code.

---

### 2. takeLeading()

**Behavior:** Ignores new actions while one is still processing. Only the first action executes.

```kotlin
on<SubmitFormAction>(takeLeading()) { state, action ->
    val result = api.submitForm(action.data)  // Subsequent calls ignored
    emit(FormSubmitted(result))
}
```

**Timeline:**
```
Action:   SubmitForm ─────────────────────> FormSubmitted ✓
Action:        SubmitForm (ignored)
Action:             SubmitForm (ignored)
```

**Use cases:**
- Form submission (prevent double-submit)
- Payment processing
- Delete confirmation
- Any operation that should not run concurrently

---

### 3. debounce(duration)

**Behavior:** Delays execution. If another action arrives before the delay completes, the timer restarts.

```kotlin
on<TextChangedAction>(debounce(300.milliseconds)) { state, action ->
    api.saveAsDraft(action.text)  // Only saves after user stops typing
    emit(DraftSaved)
}
```

**Timeline:**
```
Action:   TextChanged("h") ──[wait]──> (reset)
Action:        TextChanged("he") ──[wait]──> (reset)
Action:             TextChanged("hel") ──[wait]──> (reset)
Action:                  TextChanged("hello") ──[300ms]──> DraftSaved ✓
```

**Use cases:**
- Autosave
- Search suggestions (combined with takeLatest)
- Input validation
- Resize/scroll handlers

---

### 4. throttle(duration)

**Behavior:** Executes the first action immediately, then ignores subsequent actions until the time window passes.

```kotlin
on<ScrollAction>(throttle(1000.milliseconds)) { state, action ->
    analytics.logScrollPosition(action.position)  // At most once per second
    emit(action)
}
```

**Timeline:**
```
Action:   Scroll(100) ─────> logged ✓
Action:        Scroll(200) (ignored)
Action:             Scroll(300) (ignored)
          ─────[1000ms passes]─────
Action:                            Scroll(400) ─────> logged ✓
```

**Use cases:**
- Analytics events
- Rate-limited API calls
- Progress updates
- Any high-frequency event that needs throttling

---

## Strategy Groups: Cross-Action Coordination

Here's where flowdux really shines. Sometimes different action types should share the same strategy state.

### The Problem

```kotlin
// User searches, then pulls to refresh
store.dispatch(SearchAction("hello"))  // In progress...
store.dispatch(RefreshAction)          // Should cancel the search!
```

With separate strategies, these don't know about each other.

### The Solution: Strategy Groups

```kotlin
override val processors = buildProcessors {
    // Both actions share the SAME takeLatest instance
    group(takeLatest()) {
        on<SearchAction> { state, action ->
            val results = api.search(action.query)
            emit(SearchResults(results))
        }
        on<RefreshAction> { state, action ->
            val results = api.refresh()
            emit(SearchResults(results))  // Cancels in-progress search!
        }
    }
}
```

**Timeline:**
```
Action:   SearchAction("hello") ────────────> (canceled by RefreshAction)
Action:             RefreshAction ──────────> SearchResults ✓
```

### Real-World Group Patterns

**Pattern 1: Multiple Input Sources**

```kotlin
group(debounce(300.milliseconds)) {
    on<TextChanged> { state, action -> emit(ValidateInput(action.text)) }
    on<FilterChanged> { state, action -> emit(ApplyFilter(action.filter)) }
    on<SortChanged> { state, action -> emit(ApplySort(action.sort)) }
}
// Any input change resets the debounce timer for all
```

**Pattern 2: Mutually Exclusive Operations**

```kotlin
group(takeLeading()) {
    on<CreateItem> { state, action -> /* ... */ }
    on<UpdateItem> { state, action -> /* ... */ }
    on<DeleteItem> { state, action -> /* ... */ }
}
// Only one mutation at a time, others are ignored
```

---

## Under the Hood

How does this magic work? Each strategy wraps your processor:

```kotlin
sealed interface ExecutionStrategy {
    fun <S, A, T : A> wrap(
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit
    ): suspend FlowCollector<A>.(state: S, action: T) -> Unit
}
```

For example, `TakeLatest`:

```kotlin
class TakeLatest : ExecutionStrategy {
    private val mutex = Mutex()
    private var currentJob: Job? = null

    override fun <S, A, T : A> wrap(processor: ...) = { state, action ->
        val job = currentCoroutineContext()[Job]!!

        mutex.withLock {
            currentJob?.cancel()  // Cancel previous
            currentJob = job
        }

        try {
            processor(state, action)  // Execute
        } finally {
            mutex.withLock {
                if (currentJob === job) {
                    currentJob = null
                }
            }
        }
    }
}
```

**Key implementation details:**

1. **Mutex for thread safety** — Strategy state is protected
2. **Job tracking** — Current coroutine's Job is captured and managed
3. **Cleanup in finally** — Resources are released even on cancellation
4. **Instance-based grouping** — Use `group()` to share strategy across action types

---

## Comparison: flowdux vs Others

| Feature | flowdux | Redux-Saga | MobX | Manual Coroutines |
|---------|---------|------------|------|-------------------|
| takeLatest | `takeLatest()` | `takeLatest(pattern, saga)` | N/A | Manual Job tracking |
| takeLeading | `takeLeading()` | `takeLeading(pattern, saga)` | N/A | Manual flag |
| debounce | `debounce(duration)` | `debounce(ms, pattern, saga)` | N/A (custom impl needed) | `delay()` + cancel |
| throttle | `throttle(duration)` | `throttle(ms, pattern, saga)` | `reaction`/`autorun` delay | Manual timestamp |
| Strategy Groups | `group(strategy) { }` | N/A | N/A | N/A |
| Type Safety | Full Kotlin types | Runtime strings | Full | Full |
| Multiplatform | KMP (JVM, iOS, JS, WASM) | JS only | JS primarily | KMP |

**flowdux's Strategy Groups** — flowdux's `group { }` DSL lets you share a single strategy instance across multiple action types while keeping each handler separate. Redux-saga can achieve similar coordination via array patterns (e.g., `takeLatest([A, B], worker)`), but typically requires action type branching inside a single worker function.

*Note: MobX uses a reaction-based model rather than an action pipeline, so direct comparison is difficult. The "delay" option in `reaction`/`autorun` provides throttle-like behavior.*

---

## Testing Strategies

Execution strategies are fully testable:

```kotlin
@Test
fun `takeLatest cancels previous search`() = runTest {
    val store = createStore(
        initialState = SearchState(),
        reducer = searchReducer,
        middlewares = listOf(SearchMiddleware()),
        scope = backgroundScope
    )

    store.state.test {
        awaitItem()  // Initial state

        // Dispatch rapid searches
        store.dispatch(SearchAction("a"))
        store.dispatch(SearchAction("ab"))
        store.dispatch(SearchAction("abc"))

        // Only the last search completes
        val result = awaitItem()
        assertEquals(listOf("abc-result"), result.results)

        // No intermediate results
        expectNoEvents()
    }
}
```

---

## Best Practices

### 1. Choose the Right Strategy

| Scenario | Strategy |
|----------|----------|
| User is still typing | `debounce` |
| Only latest result matters | `takeLatest` |
| Prevent duplicate actions | `takeLeading` |
| Rate limit high-frequency events | `throttle` |

### 2. Use Groups for Cross-Action Coordination

```kotlin
// GOOD: Related actions share strategy via group
group(takeLatest()) {
    on<SearchAction> { ... }
    on<RefreshAction> { ... }  // Cancels in-progress SearchAction
}

// INDEPENDENT: Separate strategies = no coordination
on<SearchAction>(takeLatest()) { ... }   // Own instance
on<RefreshAction>(takeLatest()) { ... }  // Own instance - won't cancel SearchAction
```

### 3. Group Related Actions

```kotlin
// GOOD: Related actions share strategy
group(takeLatest()) {
    on<LoadUser> { ... }
    on<RefreshUser> { ... }
    on<UpdateUser> { ... }
}

// BAD: Unrelated actions grouped
group(takeLatest()) {
    on<LoadUser> { ... }
    on<SendMessage> { ... }  // Why would these cancel each other?
}
```

### 4. Combine Strategies When Needed

```kotlin
// Debounce input, then takeLatest for the API call
on<SearchInputChanged>(debounce(300.milliseconds)) { state, action ->
    emit(SearchAction(action.query))  // Triggers takeLatest search
}

on<SearchAction>(takeLatest()) { state, action ->
    val results = api.search(action.query)
    emit(SearchResults(results))
}
```

---

## Conclusion

Execution Strategies transform how you handle concurrency in state management:

- **Declarative** — State your intent, not the implementation
- **Composable** — Strategies work together via groups
- **Type-safe** — Full Kotlin type system support
- **Testable** — Standard coroutine testing works
- **Multiplatform** — Same code on JVM, iOS, JS, and WASM

Stop writing boilerplate cancellation logic. Use `group { }` to coordinate different action types under the same concurrency rule. Let your middleware declarations tell the story of your app's behavior.

---

*flowdux is a lightweight Redux-style state management library for Kotlin Multiplatform. Find it on [GitHub](https://github.com/chibimoons/flowdux).*

```kotlin
implementation("com.github.chibimoons:flowdux:1.6.1")
```

---

**Tags:** #Kotlin #KotlinMultiplatform #StateManagement #Redux #Coroutines #Concurrency #Architecture #MobileDevelopment
