# FlowHolderAction: The Elegant Bridge Between Repository Flows and Redux State

*A deep dive into seamless integration of external data streams in Kotlin Multiplatform state management*

---

## The Problem: When Flows Meet Redux

If you've built applications with Kotlin Multiplatform and adopted a Redux-style architecture, you've likely encountered this friction point: **How do you cleanly integrate existing `Flow` streams into your unidirectional data flow?**

Consider a typical scenario:

```kotlin
// Your repository returns a Flow
class UserRepository {
    fun observeUser(id: String): Flow<User> =
        database.userDao().observeById(id)
            .combine(api.getUserUpdates(id)) { local, remote ->
                remote ?: local
            }
}
```

Now you want this data in your Redux store. The naive approaches all have problems:

### Approach 1: Collect in ViewModel, Dispatch Actions

```kotlin
// ViewModel
init {
    viewModelScope.launch {
        userRepository.observeUser(userId).collect { user ->
            store.dispatch(UserAction.SetUser(user))
        }
    }
}
```

**Problems:**
- Side effect logic scattered outside the store
- No visibility into the data flow from the store's perspective
- Difficult to test and debug
- The ViewModel becomes a coordinator instead of a thin layer

### Approach 2: Middleware Handles Everything

```kotlin
class UserMiddleware(private val repo: UserRepository) : Middleware<State, Action> {
    override fun process(getState: () -> State, action: Action) = flow {
        if (action is ObserveUser) {
            emitAll(repo.observeUser(action.userId).map { SetUser(it) })
        } else {
            emit(action)
        }
    }
}
```

**Problems:**
- Middleware becomes bloated with Flow management logic
- Every new data stream requires middleware modification
- Coupling between middleware and repositories grows

---

## The Solution: FlowHolderAction

**flowdux** introduces `FlowHolderAction`—a specialized action interface that elegantly solves this problem by making Flow integration a first-class citizen of the action pipeline.

```kotlin
interface FlowHolderAction : Action {
    fun toFlowAction(): Flow<Action>
}
```

The design philosophy is simple but powerful:

1. **Actions remain side-effect-free** — no subscription or execution inside the action
2. **Transformation is declarative** — `toFlowAction()` maps the external Flow to Action emissions
3. **The Store handles collection** — automatic subscription and lifecycle management
4. **Concurrent by design** — multiple FlowHolderActions merge seamlessly

---

## Anatomy of a FlowHolderAction

Let's break down a real implementation:

```kotlin
// The external Flow comes from your Repository
object CounterRepository {
    fun getCount(): Flow<Pair<Int, String>> = flow {
        emit(10 to "cache")      // Immediate: cached value
        delay(500)               // Simulate network
        emit(42 to "api")        // Fresh: API response
    }
}

// Actions defined as nested classes in sealed interface
sealed interface CounterAction : Action {
    data class SetCount(val value: Int, val source: String) : CounterAction

    // FlowHolderAction wraps the Flow and defines the transformation
    data class ObserveCount(
        private val countFlow: Flow<Pair<Int, String>>  // Injected, not created
    ) : CounterAction, FlowHolderAction {
        override fun toFlowAction(): Flow<Action> =
            countFlow.map { (value, source) ->
                SetCount(value, source)  // Each emission becomes an Action
            }
    }
}
```

**Key insights:**

1. `ObserveCount` receives the Flow as a constructor parameter—**no side effects in the action**
2. `toFlowAction()` is a pure transformation—**map external data to internal actions**
3. The **subscription point is owned by the Store** — collection and lifecycle are centralized:

```kotlin
// Flow creation (cold flow — no execution yet)
val repositoryFlow = CounterRepository.getCount()  // Just a Flow definition
store.dispatch(CounterAction.ObserveCount(repositoryFlow))  // Store collects it
```

---

## Under the Hood: How flowdux Processes FlowHolderAction

Understanding the internals helps you leverage the full power of this pattern. Here's the relevant section from `Store.kt`:

```kotlin
private fun processAction(a: A): Flow<A> = middlewares
    .fold(flowOf(a)) { flow, middleware ->
        flow.flatMapConcat { currentAction ->
            middleware.process(getState = { currentState }, action = currentAction)
        }
    }
    .flatMapMerge {  // Key operator!
        if (it is FlowHolderAction) {
            (it.toFlowAction() as Flow<A>)
        } else {
            flowOf(it)
        }
    }
    .catch { /* error handling */ }
```

### The Critical Detail: `flatMapMerge`

The use of `flatMapMerge` (not `flatMapConcat`) is deliberate:

| Operator | Behavior | Use Case |
|----------|----------|----------|
| `flatMapConcat` | Sequential — waits for each inner Flow to complete | Middleware chain (order matters) |
| `flatMapMerge` | Concurrent — collects all inner Flows simultaneously | FlowHolderAction (parallelism desired) |

This means:

```kotlin
// These two FlowHolderActions run concurrently!
store.dispatch(ObserveUserProfile(userFlow))
store.dispatch(ObserveNotifications(notificationFlow))
// Both streams are collected in parallel, actions interleave naturally
```

### The Action Pipeline Visualized

```
dispatch(CounterAction.ObserveCount(flow))
         │
         ▼
    ┌─────────────────────────────────────────┐
    │           Middleware Chain              │
    │  (flatMapConcat - sequential)           │
    │                                         │
    │  Logging → Validation → Analytics       │
    └─────────────────────────────────────────┘
         │
         ▼
    ┌─────────────────────────────────────────┐
    │        FlowHolderAction Check           │
    │  (flatMapMerge - concurrent)            │
    │                                         │
    │  if (action is FlowHolderAction)        │
    │      action.toFlowAction()              │
    │  else                                   │
    │      flowOf(action)                     │
    └─────────────────────────────────────────┘
         │
         ▼ (multiple emissions over time)
    ┌─────────────────────────────────────────┐
    │  SetCount(10, "cache")                  │
    │  SetCount(42, "api")                    │
    │  ...                                    │
    └─────────────────────────────────────────┘
         │
         ▼
    ┌─────────────────────────────────────────┐
    │            Reducer                      │
    │  (State, Action) → NewState             │
    └─────────────────────────────────────────┘
         │
         ▼
      StateFlow<State> → UI
```

---

## Real-World Patterns

### Pattern 1: Repository with Cache-then-Network

The classic mobile pattern where you show cached data immediately, then update with fresh data:

```kotlin
class ProductRepository(
    private val cache: ProductCache,
    private val api: ProductApi
) {
    fun observeProduct(id: String): Flow<Product> = flow {
        // Emit cached data immediately
        cache.get(id)?.let { emit(it) }

        // Fetch fresh data
        val fresh = api.getProduct(id)
        cache.put(id, fresh)
        emit(fresh)
    }
}

// FlowHolderAction
data class ObserveProduct(
    private val productFlow: Flow<Product>
) : ProductAction, FlowHolderAction {
    override fun toFlowAction(): Flow<Action> = productFlow
        .map { ProductLoaded(it) }
        .onStart { emit(ProductLoading) }  // Can add loading state!
        .catch { emit(ProductError(it.message)) }  // Handle errors!
}

// Usage
val flow = productRepository.observeProduct(productId)
store.dispatch(ObserveProduct(flow))
```

**Notice:** The `onStart` and `catch` operators let you handle loading and error states **within the FlowHolderAction transformation**, keeping everything cohesive.

### Pattern 2: WebSocket/Real-time Streams

For real-time data like chat messages or live updates:

```kotlin
class ChatRepository(private val socket: WebSocketClient) {
    fun observeMessages(roomId: String): Flow<ChatMessage> =
        socket.messageFlow
            .filter { it.roomId == roomId }
            .map { it.toChatMessage() }
}

data class ObserveChat(
    private val messageFlow: Flow<ChatMessage>
) : ChatAction, FlowHolderAction {
    override fun toFlowAction(): Flow<Action> = messageFlow
        .map { MessageReceived(it) }
}

// The WebSocket connection is managed elsewhere
// FlowHolderAction just bridges it to the store
store.dispatch(ObserveChat(chatRepository.observeMessages(roomId)))
```

### Pattern 3: Multiple Streams Merged

Sometimes you need to observe multiple data sources simultaneously:

```kotlin
data class ObserveDashboard(
    private val userFlow: Flow<User>,
    private val statsFlow: Flow<Stats>,
    private val alertsFlow: Flow<List<Alert>>
) : DashboardAction, FlowHolderAction {

    override fun toFlowAction(): Flow<Action> = merge(
        userFlow.map { UserUpdated(it) },
        statsFlow.map { StatsUpdated(it) },
        alertsFlow.map { AlertsUpdated(it) }
    )
}
```

All three streams are collected concurrently, and their emissions interleave naturally into the action stream.

### Pattern 4: Paginated Data with Flow

For infinite scroll or pagination:

```kotlin
class ArticleRepository(private val api: ArticleApi) {
    fun observeArticles(pageFlow: Flow<Int>): Flow<List<Article>> =
        pageFlow.flatMapLatest { page ->
            flow { emit(api.getArticles(page = page, limit = 20)) }
        }
}

data class ObserveArticles(
    private val articlesFlow: Flow<List<Article>>
) : ArticleAction, FlowHolderAction {
    override fun toFlowAction(): Flow<Action> = articlesFlow
        .map { ArticlesLoaded(it) }
}

// In ViewModel: page changes trigger new data
private val pageFlow = MutableStateFlow(0)

init {
    val articlesFlow = articleRepository.observeArticles(pageFlow)
    store.dispatch(ObserveArticles(articlesFlow))
}

fun loadNextPage() {
    pageFlow.value++
}
```

---

## Testing FlowHolderAction

One of the biggest advantages is testability. Since the Flow is injected, you have full control in tests:

```kotlin
@Test
fun `ObserveCount emits SetCount actions for each flow emission`() = runTest {
    // Create a controlled test flow
    val testFlow = flowOf(
        10 to "cache",
        42 to "api"
    )

    val store = createStore(
        initialState = CounterState(),
        reducer = counterReducer,
        scope = backgroundScope
    )

    store.state.test {
        assertEquals(0, awaitItem().count)  // Initial state

        store.dispatch(CounterAction.ObserveCount(testFlow))

        // Verify each emission updates state
        with(awaitItem()) {
            assertEquals(10, count)
            assertEquals("cache", source)
        }
        with(awaitItem()) {
            assertEquals(42, count)
            assertEquals("api", source)
        }

        cancelAndIgnoreRemainingEvents()
    }
}

@Test
fun `ObserveCount handles errors gracefully`() = runTest {
    val errorFlow = flow<Pair<Int, String>> {
        emit(10 to "cache")
        throw IOException("Network error")
    }

    val errors = mutableListOf<Throwable>()
    val errorProcessor = object : ErrorProcessor<CounterAction> {
        override fun process(throwable: Throwable) = flow {
            errors.add(throwable)
            emit(CounterAction.SetError(throwable.message ?: "Unknown"))
        }
    }

    val store = createStore(
        initialState = CounterState(),
        reducer = counterReducer,
        errorProcessor = errorProcessor,
        scope = backgroundScope
    )

    store.state.test {
        awaitItem()  // Initial
        store.dispatch(CounterAction.ObserveCount(errorFlow))

        awaitItem()  // cache value
        awaitItem()  // error state

        assertEquals(1, errors.size)
        assertTrue(errors.first() is IOException)

        cancelAndIgnoreRemainingEvents()
    }
}
```

---

## Best Practices

### 1. Keep toFlowAction() Pure

```kotlin
// GOOD: Pure transformation
override fun toFlowAction(): Flow<Action> = userFlow.map { UserLoaded(it) }

// BAD: Side effects in transformation
override fun toFlowAction(): Flow<Action> = userFlow.map {
    analytics.track("user_loaded")  // Side effect!
    UserLoaded(it)
}
```

Side effects in mapping belong in middleware or the repository itself.

### 2. Handle All States in the Transformation

```kotlin
override fun toFlowAction(): Flow<Action> = productFlow
    .map<Product, ProductAction> { ProductLoaded(it) }
    .onStart { emit(ProductLoading) }
    .catch { emit(ProductError(it.message ?: "Unknown error")) }
```

This ensures your UI always has a state to display.

### 3. Consider Flow Lifecycle

FlowHolderAction Flows are collected as long as the Store's scope is active. For one-shot operations, ensure your Flow completes:

```kotlin
// Good: Flow completes after emission
fun getUser(id: String): Flow<User> = flow {
    emit(api.getUser(id))  // Single emission, flow completes
}

// Be careful: Infinite Flow
fun observeUser(id: String): Flow<User> =
    database.observeUser(id)  // Never completes - intentional for real-time
```

### 4. Use Descriptive Action Names

```kotlin
// Clear intent
data class ObserveUserProfile(...)
data class ObserveLiveChat(...)
data class ObserveStockPrices(...)

// Vague
data class LoadUser(...)
data class GetChat(...)
```

The `Observe` prefix signals that this is a FlowHolderAction that subscribes to a stream.

---

## Comparison: FlowHolderAction vs Alternatives

| Approach | Separation of Concerns | Testability | Boilerplate | Stream Visibility |
|----------|----------------------|-------------|-------------|-------------------|
| Collect in ViewModel | Poor | Medium | Low | None |
| Side effect in Action | Poor | Poor | Low | Partial |
| All in Middleware | Medium | Good | High | Full |
| **FlowHolderAction** | **Excellent** | **Excellent** | **Low** | **Full** |

---

## Conclusion

`FlowHolderAction` represents a thoughtful solution to a common architectural challenge in Kotlin Multiplatform development. By treating Flow integration as a native part of the action pipeline, flowdux enables:

- **Clean separation** — Side effects stay outside actions
- **Declarative transformations** — Pure mapping from external data to internal actions
- **Automatic lifecycle** — Store manages subscription and cancellation
- **Concurrent streams** — Multiple FlowHolderActions process in parallel
- **Full testability** — Inject test Flows with complete control

The next time you find yourself wrestling with how to connect your repository's Flows to your Redux store, consider reaching for `FlowHolderAction`. Your future self—and your tests—will thank you.

---

*flowdux is a lightweight Redux-style state management library for Kotlin Multiplatform. Find it on [GitHub](https://github.com/chibimoons/flowdux) and start managing state with elegance.*

```kotlin
implementation("com.github.chibimoons:flowdux:1.2.1.1")
```

---

**Tags:** #Kotlin #KotlinMultiplatform #StateManagement #Redux #Flow #Coroutines #Architecture #MobileDevelopment
