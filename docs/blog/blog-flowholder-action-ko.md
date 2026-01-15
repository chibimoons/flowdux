# FlowHolderAction: Repository Flow와 Redux State를 연결하는 우아한 다리

*Kotlin Multiplatform 상태 관리에서 외부 데이터 스트림을 자연스럽게 통합하는 방법*

---

## 문제: Flow와 Redux가 만났을 때

Kotlin Multiplatform으로 앱을 만들면서 Redux 스타일 아키텍처를 채택했다면, 아마 이런 고민을 해봤을 겁니다: **기존 `Flow` 스트림을 단방향 데이터 흐름에 어떻게 깔끔하게 통합할 것인가?**

전형적인 시나리오를 생각해 봅시다:

```kotlin
// Repository가 Flow를 반환합니다
class UserRepository {
    fun observeUser(id: String): Flow<User> =
        database.userDao().observeById(id)
            .combine(api.getUserUpdates(id)) { local, remote ->
                remote ?: local
            }
}
```

이 데이터를 Redux 스토어에 넣고 싶습니다. 하지만 단순한 접근법들은 모두 문제가 있습니다:

### 접근법 1: ViewModel에서 collect하고 Action dispatch

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

**문제점:**
- 사이드 이펙트 로직이 스토어 외부에 흩어짐
- 스토어 관점에서 데이터 흐름을 파악하기 어려움
- 테스트와 디버깅이 힘듦
- ViewModel이 얇은 레이어가 아닌 중재자 역할을 하게 됨

### 접근법 2: Middleware가 모든 것을 처리

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

**문제점:**
- Flow 관리 로직으로 Middleware가 비대해짐
- 새로운 데이터 스트림마다 Middleware 수정 필요
- Middleware와 Repository 간 결합도가 점점 높아짐

---

## 해결책: FlowHolderAction

**flowdux**는 `FlowHolderAction`을 도입합니다. Flow 통합을 액션 파이프라인의 일급 시민으로 만들어 이 문제를 우아하게 해결하는 특수한 액션 인터페이스입니다.

```kotlin
interface FlowHolderAction : Action {
    fun toFlowAction(): Flow<Action>
}
```

설계 철학은 단순하지만 강력합니다:

1. **Action은 사이드 이펙트가 없음** — 구독이나 실행이 Action 내부에서 발생하지 않음
2. **변환은 선언적** — `toFlowAction()`이 외부 Flow를 Action 방출로 매핑
3. **Store가 수집을 담당** — 자동 구독 및 생명주기 관리
4. **동시성 내장** — 여러 FlowHolderAction이 자연스럽게 병합

---

## FlowHolderAction 해부학

실제 구현을 분석해 봅시다:

```kotlin
// 외부 Flow는 Repository에서 옵니다
object CounterRepository {
    fun getCount(): Flow<Pair<Int, String>> = flow {
        emit(10 to "cache")      // 즉시: 캐시된 값
        delay(500)               // 네트워크 시뮬레이션
        emit(42 to "api")        // 최신: API 응답
    }
}

// sealed interface 안에 nested class로 Action 정의
sealed interface CounterAction : Action {
    data class SetCount(val value: Int, val source: String) : CounterAction

    // FlowHolderAction이 Flow를 감싸고 변환을 정의합니다
    data class ObserveCount(
        private val countFlow: Flow<Pair<Int, String>>  // 주입됨, 생성되지 않음
    ) : CounterAction, FlowHolderAction {
        override fun toFlowAction(): Flow<Action> =
            countFlow.map { (value, source) ->
                SetCount(value, source)  // 각 방출이 Action이 됨
            }
    }
}
```

**핵심 포인트:**

1. `ObserveCount`는 Flow를 생성자 파라미터로 받음 — **Action에 사이드 이펙트 없음**
2. `toFlowAction()`은 순수한 변환 — **외부 데이터를 내부 Action으로 매핑**
3. **구독 지점을 Store가 소유** — 수집과 생명주기가 중앙에서 관리됨:

```kotlin
// Flow 생성 (cold flow — 아직 실행되지 않음)
val repositoryFlow = CounterRepository.getCount()  // Flow 정의일 뿐
store.dispatch(CounterAction.ObserveCount(repositoryFlow))  // Store가 수집함
```

---

## 내부 동작: flowdux가 FlowHolderAction을 처리하는 방식

내부 구조를 이해하면 이 패턴의 모든 힘을 활용할 수 있습니다. `Store.kt`의 관련 부분입니다:

```kotlin
private fun processAction(a: A): Flow<A> = middlewares
    .fold(flowOf(a)) { flow, middleware ->
        flow.flatMapConcat { currentAction ->
            middleware.process(getState = { currentState }, action = currentAction)
        }
    }
    .flatMapMerge {  // 핵심 연산자!
        if (it is FlowHolderAction) {
            (it.toFlowAction() as Flow<A>)
        } else {
            flowOf(it)
        }
    }
    .catch { /* 에러 처리 */ }
```

### 중요한 디테일: `flatMapMerge`

`flatMapMerge`를 사용한 것은 의도적입니다 (`flatMapConcat`이 아니라):

| 연산자 | 동작 | 사용 케이스 |
|--------|------|-------------|
| `flatMapConcat` | 순차적 — 각 내부 Flow 완료를 기다림 | Middleware 체인 (순서가 중요) |
| `flatMapMerge` | 동시 — 모든 내부 Flow를 동시에 수집 | FlowHolderAction (병렬 처리가 필요) |

이것이 의미하는 바:

```kotlin
// 이 두 FlowHolderAction은 동시에 실행됩니다!
store.dispatch(ObserveUserProfile(userFlow))
store.dispatch(ObserveNotifications(notificationFlow))
// 두 스트림이 병렬로 수집되고, Action들이 자연스럽게 인터리브됨
```

### Action 파이프라인 시각화

```
dispatch(CounterAction.ObserveCount(flow))
         │
         ▼
    ┌─────────────────────────────────────────┐
    │           Middleware Chain              │
    │  (flatMapConcat - 순차적)               │
    │                                         │
    │  Logging → Validation → Analytics       │
    └─────────────────────────────────────────┘
         │
         ▼
    ┌─────────────────────────────────────────┐
    │        FlowHolderAction 체크            │
    │  (flatMapMerge - 동시)                  │
    │                                         │
    │  if (action is FlowHolderAction)        │
    │      action.toFlowAction()              │
    │  else                                   │
    │      flowOf(action)                     │
    └─────────────────────────────────────────┘
         │
         ▼ (시간에 걸쳐 여러 방출)
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

## 실전 패턴

### 패턴 1: Cache-then-Network Repository

캐시된 데이터를 먼저 보여주고, 그 다음 최신 데이터로 업데이트하는 전형적인 모바일 패턴:

```kotlin
class ProductRepository(
    private val cache: ProductCache,
    private val api: ProductApi
) {
    fun observeProduct(id: String): Flow<Product> = flow {
        // 캐시된 데이터를 즉시 방출
        cache.get(id)?.let { emit(it) }

        // 최신 데이터 가져오기
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
        .onStart { emit(ProductLoading) }  // 로딩 상태도 추가 가능!
        .catch { emit(ProductError(it.message)) }  // 에러 처리!
}

// 사용
val flow = productRepository.observeProduct(productId)
store.dispatch(ObserveProduct(flow))
```

**주목:** `onStart`와 `catch` 연산자로 로딩과 에러 상태를 **FlowHolderAction 변환 내에서** 처리할 수 있어, 모든 것이 응집력 있게 유지됩니다.

### 패턴 2: WebSocket/실시간 스트림

채팅 메시지나 실시간 업데이트 같은 실시간 데이터:

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

// WebSocket 연결은 다른 곳에서 관리
// FlowHolderAction은 그것을 Store로 연결만 함
store.dispatch(ObserveChat(chatRepository.observeMessages(roomId)))
```

### 패턴 3: 여러 스트림 병합

여러 데이터 소스를 동시에 관찰해야 할 때:

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

세 스트림 모두 동시에 수집되고, 방출들이 자연스럽게 Action 스트림으로 인터리브됩니다.

### 패턴 4: Flow를 활용한 페이지네이션

무한 스크롤이나 페이지네이션:

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

// ViewModel에서: 페이지 변경이 새 데이터를 트리거
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

## FlowHolderAction 테스트하기

가장 큰 장점 중 하나는 테스트 용이성입니다. Flow가 주입되므로, 테스트에서 완전한 제어권을 가집니다:

```kotlin
@Test
fun `ObserveCount는 각 flow 방출에 대해 SetCount action을 emit한다`() = runTest {
    // 제어된 테스트 flow 생성
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
        assertEquals(0, awaitItem().count)  // 초기 상태

        store.dispatch(CounterAction.ObserveCount(testFlow))

        // 각 방출이 상태를 업데이트하는지 검증
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
fun `ObserveCount는 에러를 우아하게 처리한다`() = runTest {
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
        awaitItem()  // 초기
        store.dispatch(CounterAction.ObserveCount(errorFlow))

        awaitItem()  // cache 값
        awaitItem()  // error 상태

        assertEquals(1, errors.size)
        assertTrue(errors.first() is IOException)

        cancelAndIgnoreRemainingEvents()
    }
}
```

---

## 베스트 프랙티스

### 1. toFlowAction()을 순수하게 유지

```kotlin
// 좋음: 순수한 변환
override fun toFlowAction(): Flow<Action> = userFlow.map { UserLoaded(it) }

// 나쁨: 변환에 사이드 이펙트
override fun toFlowAction(): Flow<Action> = userFlow.map {
    analytics.track("user_loaded")  // 사이드 이펙트!
    UserLoaded(it)
}
```

매핑에서의 사이드 이펙트는 Middleware나 Repository 자체에 있어야 합니다.

### 2. 변환에서 모든 상태 처리

```kotlin
override fun toFlowAction(): Flow<Action> = productFlow
    .map<Product, ProductAction> { ProductLoaded(it) }
    .onStart { emit(ProductLoading) }
    .catch { emit(ProductError(it.message ?: "Unknown error")) }
```

이렇게 하면 UI가 항상 표시할 상태를 가지게 됩니다.

### 3. Flow 생명주기 고려

FlowHolderAction의 Flow는 Store의 scope가 활성화된 동안 수집됩니다. 일회성 작업의 경우, Flow가 완료되도록 해야 합니다:

```kotlin
// 좋음: 방출 후 Flow 완료
fun getUser(id: String): Flow<User> = flow {
    emit(api.getUser(id))  // 단일 방출, flow 완료
}

// 주의: 무한 Flow
fun observeUser(id: String): Flow<User> =
    database.observeUser(id)  // 절대 완료되지 않음 - 실시간용으로 의도된 것
```

### 4. 설명적인 Action 이름 사용

```kotlin
// 명확한 의도
data class ObserveUserProfile(...)
data class ObserveLiveChat(...)
data class ObserveStockPrices(...)

// 모호함
data class LoadUser(...)
data class GetChat(...)
```

`Observe` 접두사는 이것이 스트림을 구독하는 FlowHolderAction임을 나타냅니다.

---

## 비교: FlowHolderAction vs 대안들

| 접근법 | 관심사 분리 | 테스트 용이성 | 보일러플레이트 | 스트림 가시성 |
|--------|------------|--------------|---------------|--------------|
| ViewModel에서 collect | 나쁨 | 중간 | 낮음 | 없음 |
| Action에서 사이드 이펙트 | 나쁨 | 나쁨 | 낮음 | 부분적 |
| Middleware에서 전부 처리 | 중간 | 좋음 | 높음 | 전체 |
| **FlowHolderAction** | **우수** | **우수** | **낮음** | **전체** |

---

## 결론

`FlowHolderAction`은 Kotlin Multiplatform 개발에서 흔한 아키텍처 과제에 대한 사려 깊은 해결책입니다. Flow 통합을 Action 파이프라인의 핵심 기능으로 다룸으로써, flowdux는 다음을 가능하게 합니다:

- **깔끔한 분리** — 사이드 이펙트가 Action 외부에 유지
- **선언적 변환** — 외부 데이터에서 내부 Action으로 순수 매핑
- **자동 생명주기** — Store가 구독과 취소를 관리
- **동시 스트림** — 여러 FlowHolderAction이 병렬로 처리
- **완전한 테스트 용이성** — 테스트 Flow를 완전한 제어와 함께 주입

다음번에 Repository의 Flow를 Redux Store에 어떻게 연결할지 고민하게 된다면, `FlowHolderAction`을 고려해 보세요. 미래의 당신과 당신의 테스트가 감사할 것입니다.

---

*flowdux는 Kotlin Multiplatform을 위한 경량 Redux 스타일 상태 관리 라이브러리입니다. [GitHub](https://github.com/chibimoons/flowdux)에서 찾아보시고 우아하게 상태를 관리해 보세요.*

```kotlin
implementation("com.github.chibimoons:flowdux:1.2.1.1")
```

---

**태그:** #Kotlin #KotlinMultiplatform #상태관리 #Redux #Flow #Coroutines #아키텍처 #모바일개발
