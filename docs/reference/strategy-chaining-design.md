# Strategy Chaining Design Proposal

*Created: 2026-01-20*

This document outlines design options for supporting strategy chaining in flowdux.

---

## Background

Based on the [concurrency strategies research](./concurrency-strategies-research.md), strategies can be classified into three categories:

| Category | Strategies | Purpose |
|----------|------------|---------|
| **Timing** | debounce, throttle | *When* to execute |
| **Concurrency** | takeLatest, takeLeading, takeEvery, sequential | *How* to handle concurrent executions |
| **Resilience** | retry, circuitBreaker | *How* to handle failures |

Cross-category chaining is valid and useful:
- `debounce → takeLatest` (wait for input pause, then cancel previous)
- `takeLatest → retry` (latest only with retry on failure)

Same-category chaining is invalid or redundant:
- `takeLatest + takeLeading` (mutually exclusive)
- `debounce + throttle` (conflicting timing)

---

## Current Structure

```kotlin
sealed interface ExecutionStrategy {
    fun <S, A, T : A> wrap(
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit
    ): suspend FlowCollector<A>.(state: S, action: T) -> Unit
}

// Usage
on<SearchAction>(takeLatest()) { state, action -> ... }
group(debounce(300.ms)) { ... }
```

---

## Design Options

### Option 1: Simple Extension (Keep Current Structure)

Add new strategies implementing the same interface without chaining support.

```kotlin
// New strategies implement same interface
class Sequential : ExecutionStrategy { ... }
class Retry(val maxAttempts: Int) : ExecutionStrategy { ... }

// Usage
on<SaveAction>(sequential()) { ... }
on<FetchAction>(retry(3)) { ... }
```

**Pros:**
- Simple implementation
- Backward compatible

**Cons:**
- No chaining support
- Users must implement manual composition if needed

---

### Option 2: Category-Separated Interfaces

Split `ExecutionStrategy` into category-specific interfaces with type-safe chaining.

```kotlin
sealed interface TimingStrategy : ExecutionStrategy
sealed interface ConcurrencyStrategy : ExecutionStrategy
sealed interface ResilienceStrategy : ExecutionStrategy

class Debounce(...) : TimingStrategy
class TakeLatest : ConcurrencyStrategy
class Retry(...) : ResilienceStrategy

// Type-safe chaining
fun TimingStrategy.then(next: ConcurrencyStrategy): ExecutionStrategy
fun ConcurrencyStrategy.then(next: ResilienceStrategy): ExecutionStrategy

// Usage
on<SearchAction>(debounce(300.ms) then takeLatest()) { ... }
on<FetchAction>(takeLatest() then retry(3)) { ... }

// Compile error - same category chaining not allowed
on<Action>(takeLatest() then takeLeading()) // ❌ Error
```

**Pros:**
- Type-level prevention of invalid combinations
- Clear intent in API design

**Cons:**
- Complex implementation
- Requires breaking change to `sealed interface ExecutionStrategy`
- Type complexity grows with 3+ strategy chains
- Many overloads needed for all valid combinations

---

### Option 3: Builder Pattern (ProcessorConfig)

Use a builder to compose strategies by category.

```kotlin
class ProcessorConfig private constructor(
    val timing: TimingStrategy?,
    val concurrency: ConcurrencyStrategy?,
    val resilience: ResilienceStrategy?
) : ExecutionStrategy {

    class Builder {
        fun timing(strategy: TimingStrategy): Builder
        fun concurrency(strategy: ConcurrencyStrategy): Builder
        fun resilience(strategy: ResilienceStrategy): Builder
        fun build(): ProcessorConfig
    }

    override fun wrap(...) {
        // Execution order: timing → concurrency → resilience
        var result = processor
        resilience?.let { result = it.wrap(result) }
        concurrency?.let { result = it.wrap(result) }
        timing?.let { result = it.wrap(result) }
        return result
    }
}

// DSL helper
fun config(block: ProcessorConfig.Builder.() -> Unit): ExecutionStrategy

// Usage
on<SearchAction>(config {
    timing { debounce(300.ms) }
    concurrency { takeLatest() }
}) { ... }

on<FetchAction>(config {
    concurrency { takeLatest() }
    resilience { retry(3) }
}) { ... }
```

**Pros:**
- Enforces one strategy per category (compile-time guarantee)
- Automatic execution order
- Easily extensible

**Cons:**
- Verbose for single strategy (unless old API kept)
- More complex implementation
- Requires understanding the builder pattern

---

### Option 4: Chaining Operator + Runtime Validation

Keep current structure, add chaining with runtime category validation.

```kotlin
// Add category property to ExecutionStrategy
sealed interface ExecutionStrategy {
    val category: StrategyCategory
    fun <S, A, T : A> wrap(...)
}

enum class StrategyCategory {
    TIMING,      // debounce, throttle
    CONCURRENCY, // takeLatest, takeLeading, sequential
    RESILIENCE   // retry, circuitBreaker
}

// Chaining operator
infix fun ExecutionStrategy.then(next: ExecutionStrategy): ExecutionStrategy =
    ChainedStrategy(this, next)

class ChainedStrategy(
    private val first: ExecutionStrategy,
    private val second: ExecutionStrategy
) : ExecutionStrategy {

    override val category = StrategyCategory.CHAINED // or compute from components

    init {
        // Runtime validation
        require(first.category != second.category) {
            "Cannot chain strategies of same category: ${first.category}"
        }
    }

    override fun wrap(processor) = first.wrap(second.wrap(processor))
}

// Usage
on<SearchAction>(debounce(300.ms) then takeLatest()) { ... }
on<FetchAction>(takeLatest() then retry(3)) { ... }

// Runtime error (IllegalArgumentException)
on<Action>(takeLatest() then takeLeading()) // ❌ Throws at registration
```

**Pros:**
- Simple implementation (~20 lines)
- Fully backward compatible
- Flexible chaining syntax
- Errors caught at middleware registration (app startup)

**Cons:**
- Runtime validation instead of compile-time
- Need to add `category` property to all strategies

---

## Recommendation

**Option 4: Chaining Operator + Runtime Validation**

**Rationale:**

1. **Incremental adoption** - Existing code unchanged, new strategies can be added
2. **Simplicity** - No complex type system required
3. **Practical** - Invalid combinations are rare; runtime validation is sufficient
4. **Early failure** - Errors occur at middleware registration (app startup), not at action dispatch
5. **Extensibility** - New categories can be added without major refactoring

**Implementation Changes:**

```kotlin
// 1. Add category to ExecutionStrategy
sealed interface ExecutionStrategy {
    val category: StrategyCategory
    fun <S, A, T : A> wrap(...)
}

// 2. Add StrategyCategory enum
enum class StrategyCategory {
    TIMING,
    CONCURRENCY,
    RESILIENCE,
    CHAINED  // For composed strategies
}

// 3. Update existing strategies
class TakeLatest : ExecutionStrategy {
    override val category = StrategyCategory.CONCURRENCY
    // ... existing implementation
}

// 4. Add ChainedStrategy
class ChainedStrategy(...) : ExecutionStrategy { ... }

// 5. Add extension function
infix fun ExecutionStrategy.then(next: ExecutionStrategy): ExecutionStrategy
```

---

## Open Questions

1. **Execution order in chains** - Should `a then b` mean `a.wrap(b.wrap(processor))` or `b.wrap(a.wrap(processor))`?
   - Proposed: `a then b` = a wraps b, so a's logic runs first (outer layer)
   - `debounce then takeLatest` = debounce delay, then takeLatest cancellation

2. **Three-way chains** - Should `a then b then c` be supported?
   - Proposed: Yes, via left-associativity: `(a then b) then c`

3. **Group compatibility** - Should chained strategies work with `group { }`?
   - Proposed: Yes, same as single strategies

4. **Error messages** - How to make runtime errors clear?
   - Proposed: Include strategy names and categories in error message

---

## Alternative Consideration

If compile-time safety is strongly desired, consider **Option 2** with a simplified type system:

```kotlin
// Simplified: just two levels
sealed interface BaseStrategy : ExecutionStrategy
sealed interface ComposedStrategy : ExecutionStrategy

fun BaseStrategy.then(next: BaseStrategy): ComposedStrategy
// No chaining on ComposedStrategy - max 2 strategies

// This prevents infinite chains while keeping some type safety
```

This limits chains to 2 strategies but maintains compile-time safety.

---

## How Other Libraries Handle Composition

### RxJS: Pipe Operator

RxJS uses a `pipe()` method for linear chaining of operators.

```javascript
// Clean linear composition
searchInput$.pipe(
  debounceTime(300),
  distinctUntilChanged(),
  switchMap(query => api.search(query))
)
```

**Key characteristics:**
- Each operator takes an Observable and returns a new Observable
- Order is explicit and linear (left to right)
- Composable operators can be extracted into reusable functions

```javascript
// Custom composite operator
const debounceInput = pipe(debounceTime(400), distinctUntilChanged());
valueChanges.pipe(debounceInput, switchMap(...))
```

**Applicability to flowdux:**
- RxJS operates on data streams; flowdux strategies wrap processors at registration time
- Different abstraction levels, but the `pipe` concept is inspirational

**Reference:** [RxJS Operators Guide](https://rxjs.dev/guide/operators)

---

### Kotlin Flow: Extension Function Chaining

Similar to RxJS, Kotlin Flow chains operators as extension functions.

```kotlin
searchQueryFlow
    .debounce(300.milliseconds)
    .flatMapLatest { query -> fetchSearchResults(query) }
    .stateIn(...)
```

**Key characteristics:**
- Operators are extension functions on `Flow<T>`
- Each returns a new `Flow<T>`, enabling chaining
- Order is explicit (top to bottom or left to right)

**Applicability to flowdux:**
- flowdux strategies are not Flow operators but processor wrappers
- The chaining syntax could be adopted via infix functions

**Reference:** [Kotlin Flow Documentation](https://kotlinlang.org/docs/flow.html)

---

### Redux-Saga: Manual Composition

Redux-Saga doesn't have a built-in composition API. Instead, patterns are combined manually inside saga functions.

```javascript
function* handleInput({ input }) {
  yield delay(500)  // debounce manually
  // ... logic here
}

function* watchInput() {
  yield takeLatest('INPUT_CHANGED', handleInput);
}
```

**Key characteristics:**
- `takeLatest` + `delay` achieves "debounce then takeLatest" manually
- No declarative composition API
- Flexibility at the cost of more boilerplate

**Applicability to flowdux:**
- Shows that manual composition is common
- flowdux's declarative approach (`debounce then takeLatest`) would be an improvement

**Reference:** [Redux-Saga Recipes](https://redux-saga.js.org/docs/recipes/)

---

### Resilience4j: Decorator Stacking

Resilience4j uses explicit decorator composition with fluent API.

```java
Supplier<String> decoratedSupplier = Decorators.ofSupplier(supplier)
    .withRetry(retry)
    .withCircuitBreaker(circuitBreaker)
    .withBulkhead(bulkhead)
    .decorate();
```

**Key characteristics:**
- Explicit stacking order (innermost to outermost)
- Fluent builder pattern
- Order matters and is configurable

**Recommended order:**
1. **Retry** (outermost) - retries happen within circuit breaker monitoring
2. **CircuitBreaker** (middle) - tracks failures across retries
3. **RateLimiter/Bulkhead** (innermost) - controls throughput

**Applicability to flowdux:**
- Most similar to flowdux's use case (wrapping functions, not data streams)
- Decorator pattern directly applicable
- Order significance is well-documented

**Reference:** [Resilience4j Guide](https://www.baeldung.com/resilience4j)

---

### MobX-State-Tree: Generator-based Flow

MST uses generators with `flow()` for async actions.

```javascript
const fetchData = flow(function* () {
  yield delay(300)           // timing
  const result = yield api.fetch()  // async call
  self.data = result
})
```

**Key characteristics:**
- Sequential `yield` statements define the flow
- Implicit composition through generator syntax
- Framework handles action wrapping automatically

**Applicability to flowdux:**
- Different paradigm (generator vs. strategy wrapping)
- Shows that implicit ordering via sequence is intuitive

**Reference:** [MST Async Actions](https://mobx-state-tree.js.org/concepts/async-actions)

---

## Summary: Lessons from Other Libraries

| Library | Composition Model | Order Control | Type Safety |
|---------|------------------|---------------|-------------|
| **RxJS** | `pipe()` linear chain | Explicit (left→right) | TypeScript overloads |
| **Kotlin Flow** | Extension function chain | Explicit (top→down) | Kotlin generics |
| **Redux-Saga** | Manual in saga function | Implicit (code order) | None |
| **Resilience4j** | Fluent builder | Explicit (method order) | Java generics |
| **MobX-State-Tree** | Generator yields | Implicit (yield order) | None |

**Key Insights:**

1. **Explicit ordering is preferred** - All libraries make execution order clear
2. **Linear chaining is intuitive** - `a.then(b).then(c)` or `pipe(a, b, c)`
3. **Type safety varies** - RxJS/Kotlin achieve it via generics; others use runtime checks
4. **Resilience4j is closest** - Decorator pattern wrapping functions matches flowdux's model

**Recommendation Update:**

Based on this research, **Option 4 (Chaining + Runtime Validation)** remains the best fit because:
- Matches Resilience4j's proven decorator model
- Linear chaining via `then` is intuitive (like RxJS pipe)
- Runtime validation at registration is acceptable (similar to Redux-Saga)
- Avoids complex type overloads needed for full compile-time safety

---

## Next Steps

1. ~~Research how other libraries handle strategy composition~~ ✅
2. Decide on design option
3. Implement chosen design
4. Add new strategies (sequential, retry)
5. Update documentation
