# Molecule

*Cash App의 Compose 런타임 기반 StateFlow 생성 도구*

---

## 개요

| 항목 | 내용 |
|------|------|
| GitHub | [cashapp/molecule](https://github.com/cashapp/molecule) |
| 저자 | Cash App (Square) |
| 철학 | Compose를 "헤드리스"로 사용하여 상태 관리 |
| 플랫폼 | Android, JVM, JS, Native (KMP) |
| 버전 | 2.2.0 |
| 의존성 | Compose Runtime (UI 불필요) |

Molecule은 Compose의 상태 추적 능력을 활용하여
@Composable 함수에서 StateFlow를 생성하는 라이브러리입니다.

---

## 핵심 아이디어

### Compose는 상태 관리 도구이다

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Compose의 본질                                    │
│                                                                      │
│   Compose = 상태 추적 컴파일러 + 트리 조작 런타임                    │
│                                                                      │
│   일반적 사용:                                                       │
│   @Composable fun → Compose Runtime → UI Tree                       │
│                                                                      │
│   Molecule 사용:                                                     │
│   @Composable fun → Compose Runtime → StateFlow                     │
│                      (헤드리스)                                      │
│                                                                      │
│   Compose UI 없이 Compose의 상태 추적 기능만 활용!                   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 기본 사용법

### 1. Presenter 정의

```kotlin
@Composable
fun CounterPresenter(
    events: Flow<CounterEvent>
): CounterModel {
    var count by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        events.collect { event ->
            when (event) {
                CounterEvent.Increment -> count++
                CounterEvent.Decrement -> count--
            }
        }
    }

    return CounterModel(
        count = count,
        onIncrement = { count++ },
        onDecrement = { count-- }
    )
}

data class CounterModel(
    val count: Int,
    val onIncrement: () -> Unit,
    val onDecrement: () -> Unit
)

sealed interface CounterEvent {
    object Increment : CounterEvent
    object Decrement : CounterEvent
}
```

### 2. StateFlow 생성

```kotlin
val events = MutableSharedFlow<CounterEvent>()

val stateFlow: StateFlow<CounterModel> = moleculeFlow(RecompositionMode.Immediate) {
    CounterPresenter(events)
}.stateIn(
    scope = coroutineScope,
    started = SharingStarted.WhileSubscribed(),
    initialValue = CounterModel(0, {}, {})
)
```

### 3. UI에서 사용

```kotlin
@Composable
fun CounterScreen() {
    val model by stateFlow.collectAsState()

    Column {
        Text("Count: ${model.count}")
        Button(onClick = model.onIncrement) { Text("+") }
        Button(onClick = model.onDecrement) { Text("-") }
    }
}
```

---

## Recomposition Mode

### Immediate 모드

```kotlin
moleculeFlow(RecompositionMode.Immediate) {
    // 상태 변경 시 즉시 recomposition
}
```

- 상태 변경 즉시 새 값 emit
- Frame clock 불필요
- 대부분의 경우 권장

### ContextClock 모드

```kotlin
moleculeFlow(RecompositionMode.ContextClock) {
    // CoroutineContext의 MonotonicFrameClock 사용
}
```

- 프레임 단위 업데이트
- 애니메이션 등 프레임 동기화 필요 시

---

## Presenter 패턴

### Cash App의 Presenter

```kotlin
@Composable
fun UserProfilePresenter(
    userId: String,
    userRepository: UserRepository
): UserProfileModel {
    // 로딩 상태
    var isLoading by remember { mutableStateOf(true) }
    var user by remember { mutableStateOf<User?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // 데이터 로딩
    LaunchedEffect(userId) {
        isLoading = true
        try {
            user = userRepository.getUser(userId)
        } catch (e: Exception) {
            error = e.message
        } finally {
            isLoading = false
        }
    }

    return UserProfileModel(
        isLoading = isLoading,
        user = user,
        error = error,
        onRetry = {
            // 재시도 로직
        }
    )
}
```

### 장점

1. **명령형 코드**: RxJava 연산자 없이 if/when/for 사용
2. **읽기 쉬움**: 위에서 아래로 순차적 로직
3. **테스트 용이**: Presenter 함수 단위 테스트

---

## 고급 패턴

### 계층적 상태 관리

```kotlin
@Composable
fun AppPresenter(): AppModel {
    // 자식 Presenter 호출
    val userModel = UserPresenter()
    val settingsModel = SettingsPresenter()

    return AppModel(
        user = userModel,
        settings = settingsModel
    )
}

@Composable
fun UserPresenter(): UserModel {
    var name by remember { mutableStateOf("") }
    // ...
    return UserModel(name = name)
}
```

### 이벤트 처리

```kotlin
@Composable
fun SearchPresenter(
    events: Flow<SearchEvent>,
    searchRepository: SearchRepository
): SearchModel {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Result>>(emptyList()) }

    LaunchedEffect(Unit) {
        events.collect { event ->
            when (event) {
                is SearchEvent.QueryChanged -> {
                    query = event.query
                    // Debounce는 Flow 연산자로
                }
                is SearchEvent.Search -> {
                    results = searchRepository.search(query)
                }
            }
        }
    }

    return SearchModel(
        query = query,
        results = results
    )
}
```

---

## flowdux와 비교

| 측면 | Molecule | flowdux |
|------|----------|---------|
| **패러다임** | Compose Presenter | Redux |
| **상태 정의** | remember + mutableStateOf | State data class |
| **상태 변경** | 직접 변수 수정 | Reducer |
| **비동기** | LaunchedEffect | Middleware |
| **동시성** | 수동 (Flow 연산자) | Execution Strategy |
| **이벤트** | Flow 수신 | Action dispatch |
| **출력** | StateFlow | StateFlow |

### 상태 변경 비교

```
┌─────────────────────────────────────────────────────────────────┐
│                    Molecule (Compose Presenter)                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   @Composable                                                    │
│   fun CounterPresenter(): CounterModel {                         │
│       var count by remember { mutableStateOf(0) }                │
│                                                                  │
│       return CounterModel(                                       │
│           count = count,                                         │
│           onIncrement = { count++ }  // 직접 수정                │
│       )                                                          │
│   }                                                              │
│                                                                  │
│   특징: 명령형, 직관적, Compose 스냅샷 시스템 활용               │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    flowdux (Redux)                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   // Reducer (buildReducer DSL)                                  │
│   val reducer = buildReducer<State, Action> {                    │
│       on<Increment> { state, _ -> state.copy(count = count + 1) }│
│   }                                                              │
│                                                                  │
│   // 사용                                                        │
│   store.dispatch(Increment)                                      │
│                                                                  │
│   특징: 선언적, Action 추적 가능, 동시성 전략                    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 왜 StateFlow인가?

```kotlin
// State (Compose): 동기 값만 제공
val state: State<Int> = mutableStateOf(0)
state.value  // 현재 값

// StateFlow: 동기 값 + 구독 가능
val stateFlow: StateFlow<Int> = MutableStateFlow(0)
stateFlow.value  // 현재 값
stateFlow.collect { }  // 구독

// Compose State는 KMP에서 공유 불가
// StateFlow는 KMP에서 완전 지원
```

---

## 장단점

### 장점

1. **명령형 코드**: RxJava/Flow 연산자 학습 불필요
2. **Compose 재사용**: 기존 Compose 지식 활용
3. **테스트 용이**: Presenter 함수 단위 테스트
4. **KMP 지원**: Compose UI 없이 Compose Runtime 사용
5. **동기 초기값**: StateFlow.value로 즉시 접근

### 단점

1. **동시성 수동**: takeLatest, debounce 직접 구현
2. **액션 추적 없음**: Redux처럼 액션 로깅 어려움
3. **타임트래블 없음**: 상태 이력 관리 어려움
4. **글로벌 상태**: Presenter 간 상태 공유 복잡

---

## 언제 사용하면 좋은가?

### Molecule이 적합한 경우

- Compose에 익숙한 팀
- 명령형 프로그래밍 선호
- 화면별 독립 상태
- RxJava/Flow 연산자 회피

### flowdux가 적합한 경우

- Redux 패턴 선호
- 액션 로깅/디버깅 중요
- 동시성 전략 (takeLatest, debounce)
- 글로벌 상태 관리

### 조합 가능성

```kotlin
// Molecule Presenter에서 flowdux Store 사용
@Composable
fun MyPresenter(store: Store<AppState, AppAction>): MyModel {
    val state by store.state.collectAsState()

    return MyModel(
        count = state.count,
        onIncrement = { store.dispatch(Increment) }
    )
}
```

---

## 참고 자료

- [Molecule GitHub](https://github.com/cashapp/molecule)
- [Molecule 1.0 발표 - Cash App Blog](https://code.cash.app/molecule-1-0)
- [The State of Managing State - Cash App Blog](https://code.cash.app/the-state-of-managing-state-with-compose)
- [Bridge Between Your Code and Compose](https://code.cash.app/bridge-between-your-code-and-compose)
- [Hierarchical State Management - Medium](https://medium.com/@cgaisl/beyond-mvvm-hierarchical-state-management-with-molecule-and-compose-660648eeb88e)
