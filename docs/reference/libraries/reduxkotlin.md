# ReduxKotlin

*멀티플랫폼 우선 Redux 구현*

---

## 개요

| 항목 | 내용 |
|------|------|
| GitHub | [reduxkotlin/redux-kotlin](https://github.com/reduxkotlin/redux-kotlin) |
| 문서 | [reduxkotlin.org](https://reduxkotlin.org/) |
| 철학 | JavaScript Redux의 Kotlin 포팅 |
| 플랫폼 | JVM, Native (iOS, macOS, Linux, Windows), JS, WASM |
| 의존성 | 없음 (코어) |

ReduxKotlin은 JavaScript Redux를 Kotlin Multiplatform으로 포팅한 라이브러리로,
원본 Redux의 API와 개념을 최대한 유지합니다.

---

## 핵심 개념

### Redux 3원칙

1. **단일 진실 공급원 (Single Source of Truth)**
   - 앱의 모든 상태가 하나의 Store에 저장

2. **상태는 읽기 전용 (State is Read-Only)**
   - 상태 변경은 오직 Action dispatch로만 가능

3. **순수 함수로 변경 (Changes with Pure Functions)**
   - Reducer는 순수 함수

### 데이터 흐름

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Redux 단방향 데이터 흐름                          │
│                                                                      │
│   1. View에서 Action dispatch                                        │
│         │                                                            │
│         ▼                                                            │
│   2. Middleware 체인 통과 (선택적)                                   │
│         │                                                            │
│         ▼                                                            │
│   3. Reducer가 새 State 계산                                        │
│         │                                                            │
│         ▼                                                            │
│   4. Store가 새 State 저장                                          │
│         │                                                            │
│         ▼                                                            │
│   5. 구독자에게 알림                                                 │
│         │                                                            │
│         ▼                                                            │
│   6. View가 새 State로 업데이트                                     │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 기본 사용법

### 1. State 정의

```kotlin
data class AppState(
    val count: Int = 0,
    val user: User? = null,
    val isLoading: Boolean = false
)
```

### 2. Action 정의

```kotlin
sealed interface AppAction {
    object Increment : AppAction
    object Decrement : AppAction
    data class SetUser(val user: User) : AppAction
    data class SetLoading(val isLoading: Boolean) : AppAction
}
```

### 3. Reducer 정의

```kotlin
val appReducer: Reducer<AppState> = { state, action ->
    when (action) {
        is AppAction.Increment -> state.copy(count = state.count + 1)
        is AppAction.Decrement -> state.copy(count = state.count - 1)
        is AppAction.SetUser -> state.copy(user = action.user)
        is AppAction.SetLoading -> state.copy(isLoading = action.isLoading)
        else -> state
    }
}
```

### 4. Store 생성

```kotlin
val store = createStore(
    reducer = appReducer,
    preloadedState = AppState()
)

// 스레드 안전 Store
val store = createThreadSafeStore(
    reducer = appReducer,
    preloadedState = AppState()
)
```

### 5. 사용

```kotlin
// 상태 조회
val currentState = store.state

// 구독
val unsubscribe = store.subscribe {
    println("State changed: ${store.state}")
}

// Action dispatch
store.dispatch(AppAction.Increment)
store.dispatch(AppAction.Increment)
store.dispatch(AppAction.SetUser(User("John")))

// 구독 해제
unsubscribe()
```

---

## Middleware

### Middleware 구조

```kotlin
typealias Middleware<S> = (Store<S>) -> (next: Dispatcher) -> (action: Any) -> Any
```

### 로깅 Middleware

```kotlin
val loggingMiddleware: Middleware<AppState> = { store ->
    { next ->
        { action ->
            println("Before: ${store.state}")
            println("Action: $action")
            val result = next(action)
            println("After: ${store.state}")
            result
        }
    }
}
```

### Middleware 적용

```kotlin
val store = createStore(
    reducer = appReducer,
    preloadedState = AppState(),
    enhancer = applyMiddleware(loggingMiddleware, thunkMiddleware)
)
```

### Middleware 체인

```
Action
   │
   ▼
┌─────────────────┐
│ Middleware 1    │ ──► 다음 호출 전 로직
│ (Logging)       │
└────────┬────────┘
         │ next(action)
         ▼
┌─────────────────┐
│ Middleware 2    │ ──► 비동기 처리
│ (Thunk)         │
└────────┬────────┘
         │ next(action)
         ▼
┌─────────────────┐
│    Reducer      │
└────────┬────────┘
         │
         ▼
   새 State
```

---

## Thunk Middleware

비동기 액션을 위한 Thunk 패턴:

### 설정

```kotlin
dependencies {
    implementation("org.reduxkotlin:redux-kotlin-thunk:0.6.0")
}
```

### Thunk 정의

```kotlin
fun loadUser(userId: String): Thunk<AppState> = { dispatch, getState, extraArg ->
    dispatch(AppAction.SetLoading(true))

    try {
        val user = api.fetchUser(userId)  // suspend 또는 callback
        dispatch(AppAction.SetUser(user))
    } catch (e: Exception) {
        dispatch(AppAction.SetError(e.message))
    } finally {
        dispatch(AppAction.SetLoading(false))
    }
}
```

### Thunk 사용

```kotlin
val store = createStore(
    reducer = appReducer,
    preloadedState = AppState(),
    enhancer = applyMiddleware(createThunkMiddleware())
)

// Thunk dispatch
store.dispatch(loadUser("user123"))
```

---

## Store Enhancer

### Thread-Safe Enhancer

```kotlin
val store = createStore(
    reducer = appReducer,
    preloadedState = AppState(),
    enhancer = compose(
        applyMiddleware(loggingMiddleware, thunkMiddleware),
        createSynchronizedStoreEnhancer()
    )
)
```

### Custom Enhancer

```kotlin
val myEnhancer: StoreEnhancer<AppState> = { createStore ->
    { reducer, preloadedState, enhancer ->
        val store = createStore(reducer, preloadedState, enhancer)
        // Store 기능 확장
        store
    }
}
```

---

## flowdux와 비교

| 측면 | ReduxKotlin | flowdux |
|------|-------------|---------|
| **철학** | JS Redux 포팅 | Kotlin 네이티브 Redux |
| **API 스타일** | `store.dispatch()`, `store.subscribe()` | `store.dispatch()`, `store.state` (Flow) |
| **비동기 처리** | Thunk Middleware | Middleware processor (suspend) |
| **동시성 전략** | 수동 | Execution Strategy |
| **상태 관찰** | Callback 기반 (`subscribe`) | Flow 기반 (`StateFlow`) |
| **타입 안전성** | 런타임 (`Any` 타입 Action) | 컴파일타임 (sealed class) |

### 비동기 처리 비교

```
┌─────────────────────────────────────────────────────────────────┐
│                    ReduxKotlin (Thunk)                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   fun loadData(): Thunk<AppState> = { dispatch, getState, _ ->  │
│       dispatch(SetLoading(true))                                │
│       try {                                                      │
│           val data = api.fetch()                                 │
│           dispatch(SetData(data))                                │
│       } finally {                                                │
│           dispatch(SetLoading(false))                            │
│       }                                                          │
│   }                                                              │
│                                                                  │
│   store.dispatch(loadData())                                     │
│                                                                  │
│   특징: Thunk 함수로 비동기 로직 캡슐화                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    flowdux (Middleware)                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   on<LoadDataAction>(takeLatest()) { state, action ->           │
│       emit(SetLoading(true))                                     │
│       try {                                                      │
│           val data = api.fetch()                                 │
│           emit(SetData(data))                                    │
│       } finally {                                                │
│           emit(SetLoading(false))                                │
│       }                                                          │
│   }                                                              │
│                                                                  │
│   store.dispatch(LoadDataAction)                                 │
│                                                                  │
│   특징: Middleware에서 직접 처리, Execution Strategy 적용       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 타입 안전성 비교

```kotlin
// ReduxKotlin: Action 타입이 Any
val reducer: Reducer<AppState> = { state, action ->
    when (action) {
        is Increment -> ...  // 런타임 타입 체크
        else -> state        // 반드시 else 필요
    }
}

// flowdux: sealed class로 컴파일타임 체크
val reducer = buildReducer<AppState, AppAction> {
    on<Increment> { state, _ -> ... }
    on<Decrement> { state, _ -> ... }
    // 명시적 핸들러 등록, 타입 안전
}
```

---

## 장단점

### 장점

1. **Redux 친숙도**: JS Redux 경험자에게 익숙한 API
2. **최소 의존성**: 코어 모듈 의존성 없음
3. **광범위한 플랫폼**: WASM 포함 모든 KMP 타겟
4. **확장성**: Middleware, Enhancer 패턴

### 단점

1. **타입 안전성 부족**: Action이 `Any` 타입
2. **Callback 기반**: Flow 대신 `subscribe` 패턴
3. **동시성 전략 없음**: takeLatest 등 내장 없음
4. **보일러플레이트**: Thunk 패턴 장황함

---

## 언제 사용하면 좋은가?

### ReduxKotlin이 적합한 경우

- JS Redux 경험자 팀
- 원본 Redux API 유지 필요
- Thunk 패턴 선호
- 의존성 최소화 필요

### flowdux가 적합한 경우

- Kotlin 네이티브 API 선호
- Flow 기반 상태 관찰
- 동시성 전략 (takeLatest, debounce)
- 타입 안전한 Action

---

## 참고 자료

- [ReduxKotlin 공식 문서](https://reduxkotlin.org/)
- [GitHub Repository](https://github.com/reduxkotlin/redux-kotlin)
- [Middleware 문서](https://reduxkotlin.org/advanced/middleware)
- [Thunk Middleware](https://github.com/reduxkotlin/redux-kotlin-thunk)
