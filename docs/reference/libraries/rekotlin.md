# ReKotlin

*Kotlin용 Redux-like UDF 구현*

---

## 개요

| 항목 | 내용 |
|------|------|
| GitHub | [ReKotlin/ReKotlin](https://github.com/ReKotlin/ReKotlin) |
| 대안 | [rakutentech/ReKotlin](https://github.com/rakutentech/ReKotlin) |
| 기원 | ReSwift (Swift) 포팅 |
| 철학 | 단방향 데이터 흐름, Redux 단순화 |
| 플랫폼 | JVM, Android |
| 의존성 | 없음 |

ReKotlin은 ReSwift의 Kotlin 포팅으로, Redux의 단방향 데이터 흐름을
간소화된 API로 제공합니다.

---

## 핵심 개념

### 3가지 핵심 관심사

```
┌─────────────────────────────────────────────────────────────────────┐
│                     ReKotlin 핵심 구조                               │
│                                                                      │
│   1. State (상태)                                                   │
│      • 앱 전체 상태를 명시적으로 저장                                │
│      • 불변 데이터 구조                                              │
│                                                                      │
│   2. Views (뷰)                                                     │
│      • 현재 상태의 시각화                                            │
│      • 상태 변경 시 자동 업데이트                                    │
│                                                                      │
│   3. State Changes (상태 변경)                                      │
│      • Action을 통해서만 변경 가능                                   │
│      • Reducer가 새 상태 계산                                       │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 데이터 흐름

```
User Interaction
        │
        ▼
   ┌─────────┐
   │  Action │  (상태 변경 요청)
   └────┬────┘
        │
        ▼
   ┌─────────┐
   │  Store  │  (액션을 Reducer에 전달)
   └────┬────┘
        │
        ▼
   ┌─────────┐
   │ Reducer │  (새 상태 계산)
   └────┬────┘
        │
        ▼
   ┌─────────┐
   │  State  │  (새 상태 저장)
   └────┬────┘
        │
        ▼
   ┌─────────┐
   │Subscriber│ (상태 변경 알림)
   └────┬────┘
        │
        ▼
   ┌─────────┐
   │  View   │  (UI 업데이트)
   └─────────┘
```

---

## 기본 사용법

### 1. State 정의

```kotlin
data class AppState(
    val counter: CounterState = CounterState(),
    val user: UserState = UserState()
) : StateType

data class CounterState(
    val count: Int = 0
) : StateType

data class UserState(
    val name: String = "",
    val isLoggedIn: Boolean = false
) : StateType
```

### 2. Action 정의

```kotlin
// Marker interface
interface Action

// Counter Actions
sealed class CounterAction : Action {
    object Increment : CounterAction()
    object Decrement : CounterAction()
    data class SetValue(val value: Int) : CounterAction()
}

// User Actions
sealed class UserAction : Action {
    data class Login(val name: String) : UserAction()
    object Logout : UserAction()
}
```

### 3. Reducer 정의

```kotlin
// 메인 Reducer
val appReducer: Reducer<AppState> = { action, state ->
    AppState(
        counter = counterReducer(action, state?.counter),
        user = userReducer(action, state?.user)
    )
}

// Counter Reducer
val counterReducer: Reducer<CounterState> = { action, state ->
    val currentState = state ?: CounterState()
    when (action) {
        is CounterAction.Increment -> currentState.copy(count = currentState.count + 1)
        is CounterAction.Decrement -> currentState.copy(count = currentState.count - 1)
        is CounterAction.SetValue -> currentState.copy(count = action.value)
        else -> currentState
    }
}

// User Reducer
val userReducer: Reducer<UserState> = { action, state ->
    val currentState = state ?: UserState()
    when (action) {
        is UserAction.Login -> currentState.copy(name = action.name, isLoggedIn = true)
        is UserAction.Logout -> currentState.copy(name = "", isLoggedIn = false)
        else -> currentState
    }
}
```

### 4. Store 생성

```kotlin
val store = Store(
    reducer = appReducer,
    state = null  // 또는 초기 상태
)
```

### 5. 구독 및 사용

```kotlin
class MainActivity : AppCompatActivity(), StoreSubscriber<AppState> {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store.subscribe(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        store.unsubscribe(this)
    }

    // 상태 변경 콜백
    override fun newState(state: AppState) {
        updateUI(state)
    }

    // 액션 디스패치
    fun onIncrementClick() {
        store.dispatch(CounterAction.Increment)
    }
}
```

---

## Subscriber 패턴

### 전체 상태 구독

```kotlin
class MySubscriber : StoreSubscriber<AppState> {
    override fun newState(state: AppState) {
        // 전체 상태 수신
    }
}

store.subscribe(mySubscriber)
```

### 부분 상태 구독

```kotlin
// 특정 서브상태만 구독
store.subscribe(this) { subscription ->
    subscription.select { state ->
        state.counter  // CounterState만 선택
    }
}
```

### 변경된 경우만 알림

```kotlin
store.subscribe(this) { subscription ->
    subscription
        .select { it.counter }
        .skipRepeats { old, new -> old == new }  // 중복 스킵
}
```

---

## Middleware

ReKotlin도 Middleware 패턴을 지원합니다:

```kotlin
val loggingMiddleware: Middleware<AppState> = { dispatch, getState ->
    { next ->
        { action ->
            println("Action: $action")
            println("Before: ${getState()}")
            next(action)
            println("After: ${getState()}")
        }
    }
}

val store = Store(
    reducer = appReducer,
    state = null,
    middleware = listOf(loggingMiddleware)
)
```

---

## Rakuten의 ReKotlin

Rakuten에서 유지보수하는 버전은 추가 기능을 제공합니다:

### SideEffect

```kotlin
sealed class MySideEffect : SideEffect {
    data class ShowToast(val message: String) : MySideEffect()
    object NavigateBack : MySideEffect()
}

// Reducer에서 SideEffect 반환
val reducer: ReducerWithSideEffects<AppState, MySideEffect> = { action, state ->
    when (action) {
        is SaveAction -> {
            val newState = state.copy(isSaved = true)
            ReducerResult(newState, listOf(MySideEffect.ShowToast("Saved!")))
        }
        else -> ReducerResult(state, emptyList())
    }
}
```

### Thunk 지원

```kotlin
fun loadData(): Thunk<AppState> = { dispatch, getState ->
    dispatch(LoadingAction)
    try {
        val data = api.fetch()
        dispatch(DataLoadedAction(data))
    } catch (e: Exception) {
        dispatch(ErrorAction(e.message))
    }
}
```

---

## flowdux와 비교

| 측면 | ReKotlin | flowdux |
|------|----------|---------|
| **기원** | ReSwift 포팅 | Kotlin 네이티브 |
| **상태 관찰** | Callback (`StoreSubscriber`) | Flow (`StateFlow`) |
| **부분 구독** | `select { }` | Flow 연산자 |
| **비동기** | Thunk (Rakuten) | Middleware processor |
| **동시성** | 없음 | Execution Strategy |
| **SideEffect** | 별도 개념 (Rakuten) | Action으로 통합 |
| **플랫폼** | JVM/Android 중심 | KMP |

### 구독 패턴 비교

```
┌─────────────────────────────────────────────────────────────────┐
│                    ReKotlin (Callback)                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   class MyActivity : StoreSubscriber<AppState> {                │
│       override fun newState(state: AppState) {                  │
│           // UI 업데이트                                         │
│       }                                                          │
│   }                                                              │
│                                                                  │
│   store.subscribe(this) { subscription ->                        │
│       subscription.select { it.counter }.skipRepeats()          │
│   }                                                              │
│                                                                  │
│   특징: Callback 기반, select로 부분 구독                        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    flowdux (Flow)                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   @Composable                                                    │
│   fun MyScreen() {                                               │
│       val state by store.state.collectAsState()                  │
│       // UI 렌더링                                               │
│   }                                                              │
│                                                                  │
│   // 또는 Flow 연산자 사용                                       │
│   store.state                                                    │
│       .map { it.counter }                                        │
│       .distinctUntilChanged()                                    │
│       .collect { ... }                                           │
│                                                                  │
│   특징: Flow 기반, 표준 연산자 활용                              │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 장단점

### 장점

1. **간결함**: Redux 핵심만 추출
2. **의존성 없음**: 순수 Kotlin
3. **ReSwift 호환**: Swift 팀과 패턴 공유 가능
4. **부분 구독**: 효율적인 UI 업데이트

### 단점

1. **KMP 제한**: 주로 JVM/Android 중심
2. **Callback 패턴**: Flow 대신 subscribe 콜백
3. **동시성 없음**: takeLatest 등 미지원
4. **개발 정체**: 활발한 유지보수 부족

---

## 언제 사용하면 좋은가?

### ReKotlin이 적합한 경우

- ReSwift 사용하는 iOS 팀과 패턴 공유
- 간단한 Redux 구현 필요
- Android 전용 프로젝트
- 의존성 최소화

### flowdux가 적합한 경우

- KMP 프로젝트
- Flow 기반 반응형 프로그래밍
- 동시성 전략 필요
- Compose 통합

---

## 참고 자료

- [ReKotlin GitHub](https://github.com/ReKotlin/ReKotlin)
- [Rakuten ReKotlin](https://github.com/rakutentech/ReKotlin)
- [Rakuten ReKotlin 문서](https://rakutentech.github.io/ReKotlin/docs/)
