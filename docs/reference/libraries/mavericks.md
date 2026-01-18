# Mavericks

*Airbnb의 Android 상태 관리 프레임워크*

---

## 개요

| 항목 | 내용 |
|------|------|
| GitHub | [airbnb/mavericks](https://github.com/airbnb/mavericks) |
| 문서 | [GitHub Wiki](https://github.com/airbnb/mavericks/wiki) |
| 저자 | Airbnb |
| 철학 | "Android on Autopilot", ViewModel 기반 MVI |
| 플랫폼 | Android 전용 |
| 의존성 | Kotlin Coroutines, AndroidX |

Mavericks(구 MvRx)는 Airbnb가 개발한 Android 상태 관리 프레임워크로,
수백 개의 화면에서 검증된 프로덕션 레벨 라이브러리입니다.

---

## 핵심 철학

### UI = f(State)

```
┌─────────────────────────────────────────────────────────────────────┐
│                     Mavericks 핵심 원칙                              │
│                                                                      │
│   화면 UI는 상태의 함수이다                                          │
│                                                                      │
│   Screen = render(State)                                            │
│                                                                      │
│   장점:                                                              │
│   • 스레드 안전 (어떤 순서로 이벤트가 와도 동일한 결과)               │
│   • 예측 가능 (상태만 보면 UI를 알 수 있음)                          │
│   • 테스트 용이 (상태 설정 → UI 검증)                                │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 아키텍처

### MavericksState

모든 상태는 단일 data class에 저장:

```kotlin
data class UserState(
    val user: Async<User> = Uninitialized,
    val posts: Async<List<Post>> = Uninitialized,
    val isRefreshing: Boolean = false
) : MavericksState
```

### MavericksViewModel

상태 변경 로직을 담당:

```kotlin
class UserViewModel(
    initialState: UserState,
    private val userRepository: UserRepository
) : MavericksViewModel<UserState>(initialState) {

    init {
        loadUser()
    }

    fun loadUser() {
        suspend {
            userRepository.getUser()
        }.execute { copy(user = it) }
    }

    fun refresh() {
        setState { copy(isRefreshing = true) }
        viewModelScope.launch {
            userRepository.refresh()
            setState { copy(isRefreshing = false) }
        }
    }
}
```

---

## Async 타입

Mavericks의 핵심 기능: 비동기 작업 상태 표현

### 4가지 상태

```kotlin
sealed class Async<out T> {
    object Uninitialized : Async<Nothing>()
    object Loading : Async<Nothing>()
    data class Success<T>(val value: T) : Async<T>()
    data class Fail<T>(val error: Throwable) : Async<T>()
}
```

### 상태 전이

```
┌───────────────┐
│ Uninitialized │
└───────┬───────┘
        │ execute { } 호출
        ▼
┌───────────────┐
│    Loading    │
└───────┬───────┘
        │
   ┌────┴────┐
   ▼         ▼
┌───────┐ ┌───────┐
│Success│ │ Fail  │
└───────┘ └───────┘
```

### execute 확장 함수

```kotlin
suspend {
    api.fetchUser(userId)
}.execute { asyncResult ->
    // asyncResult: Async<User>
    // Loading → Success 또는 Fail로 자동 전환
    copy(user = asyncResult)
}
```

### UI에서 사용

```kotlin
@Composable
fun UserScreen(viewModel: UserViewModel) {
    val state by viewModel.collectAsState()

    when (val user = state.user) {
        is Uninitialized -> { /* 초기 상태 */ }
        is Loading -> LoadingIndicator()
        is Success -> UserProfile(user.value)
        is Fail -> ErrorMessage(user.error.message)
    }
}
```

---

## setState와 Reducer

### setState

상태 업데이트는 `setState` 블록 내에서:

```kotlin
fun increment() {
    setState { copy(count = count + 1) }
}
```

### 특징

1. **Reducer 람다**: `setState`는 현재 상태를 받아 새 상태를 반환
2. **백그라운드 실행**: 모든 `setState`는 백그라운드 스레드에서 순차 실행
3. **원자성**: 여러 `setState`가 순서대로 적용

### 동작 방식

```kotlin
// 이 두 호출은...
setState { copy(count = count + 1) }
setState { copy(name = "John") }

// 순차적으로 적용됨
// State(count=0, name="") → State(count=1, name="") → State(count=1, name="John")
```

---

## withState

현재 상태 읽기:

```kotlin
fun doSomethingWithState() {
    withState { state ->
        if (state.user is Success) {
            // 현재 상태 기반 로직
        }
    }
}
```

### setState vs withState

| | setState | withState |
|---|----------|-----------|
| 목적 | 상태 변경 | 상태 읽기 |
| 반환 | 새 상태 | Unit |
| 실행 | 백그라운드, 순차 | 백그라운드 |

---

## Compose 통합

### Trio 아키텍처 (2024)

Airbnb의 최신 Compose 아키텍처:

```kotlin
@Composable
fun UserScreen(
    viewModel: UserViewModel = mavericksViewModel()
) {
    val state by viewModel.collectAsState()

    // 100% Compose UI
    UserScreenContent(
        user = state.user,
        onRefresh = viewModel::refresh
    )
}
```

### collectAsState

```kotlin
// 전체 상태
val state by viewModel.collectAsState()

// 부분 상태 (recomposition 최적화)
val count by viewModel.collectAsState { it.count }
```

---

## flowdux와 비교

| 측면 | Mavericks | flowdux |
|------|-----------|---------|
| **플랫폼** | Android 전용 | KMP |
| **ViewModel** | 네이티브 통합 | 별도 통합 필요 |
| **비동기 표현** | `Async<T>` 타입 | Action + State |
| **상태 업데이트** | `setState { }` | Reducer |
| **동시성 전략** | 수동 | Execution Strategy |
| **Middleware** | 없음 | 있음 |
| **테스트** | `MavericksTestRule` | Turbine |

### 비동기 처리 비교

```
┌─────────────────────────────────────────────────────────────────┐
│                    Mavericks (Async<T>)                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   data class State(                                              │
│       val user: Async<User> = Uninitialized                     │
│   )                                                              │
│                                                                  │
│   fun loadUser() {                                               │
│       suspend { api.fetchUser() }                                │
│           .execute { copy(user = it) }                           │
│   }                                                              │
│                                                                  │
│   // UI에서                                                      │
│   when (state.user) {                                            │
│       is Loading -> LoadingUI()                                  │
│       is Success -> ContentUI(state.user.value)                  │
│       is Fail -> ErrorUI()                                       │
│   }                                                              │
│                                                                  │
│   특징: Async 타입으로 로딩/성공/실패 상태 표현                   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    flowdux (Action + State)                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   data class State(                                              │
│       val user: User? = null,                                    │
│       val isLoading: Boolean = false,                            │
│       val error: String? = null                                  │
│   )                                                              │
│                                                                  │
│   on<LoadUser>(takeLatest()) { state, action ->                  │
│       emit(SetLoading(true))                                     │
│       try {                                                      │
│           val user = api.fetchUser()                             │
│           emit(SetUser(user))                                    │
│       } catch (e: Exception) {                                   │
│           emit(SetError(e.message))                              │
│       } finally {                                                │
│           emit(SetLoading(false))                                │
│       }                                                          │
│   }                                                              │
│                                                                  │
│   특징: 명시적 Action으로 상태 전환, takeLatest 적용             │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 장단점

### 장점

1. **Airbnb 검증**: 수백 개 화면에서 프로덕션 사용
2. **Async 타입**: 비동기 상태 표현 간결함
3. **execute 함수**: 보일러플레이트 최소화
4. **ViewModel 통합**: Android 아키텍처 컴포넌트 네이티브
5. **Compose 지원**: Trio 아키텍처로 진화

### 단점

1. **Android 전용**: KMP 불가
2. **동시성 전략 없음**: takeLatest 등 수동 구현
3. **Middleware 없음**: 횡단 관심사 처리 어려움
4. **글로벌 상태**: ViewModel 간 상태 공유 복잡

---

## 언제 사용하면 좋은가?

### Mavericks가 적합한 경우

- Android 전용 프로젝트
- ViewModel 중심 아키텍처
- `Async<T>` 타입으로 간결한 비동기 표현
- Airbnb 패턴 채택

### flowdux가 적합한 경우

- KMP (Android + iOS) 프로젝트
- 동시성 전략 (takeLatest, debounce)
- Middleware로 로깅, 분석 등 횡단 관심사
- 글로벌 상태 관리

---

## 참고 자료

- [Mavericks GitHub](https://github.com/airbnb/mavericks)
- [Mavericks Wiki](https://github.com/airbnb/mavericks/wiki)
- [MvRx at Airbnb](https://github.com/airbnb/mavericks/wiki/MvRx-at-Airbnb)
- [Introducing Trio - Airbnb Tech Blog](https://medium.com/airbnb-engineering/introducing-trio-part-i-7f5017a1a903)
