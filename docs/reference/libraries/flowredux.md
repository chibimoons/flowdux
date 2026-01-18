# FlowRedux

*Freeletics의 KMP 상태 머신 라이브러리 (DSL 기반)*

---

## 개요

| 항목 | 내용 |
|------|------|
| GitHub | [freeletics/FlowRedux](https://github.com/freeletics/FlowRedux) |
| 문서 | [freeletics.github.io/FlowRedux](https://freeletics.github.io/FlowRedux/) |
| 저자 | Freeletics |
| 철학 | 상태 머신 DSL, 상태 기반 액션 처리 |
| 플랫폼 | Android, iOS, JVM, JS (KMP) |
| 의존성 | Kotlin Coroutines, Flow |
| 최신 버전 | 1.2.2 (Kotlin 2.2.20, Compose 1.9.0) |

FlowRedux는 Kotlin Flow 기반의 상태 머신 라이브러리로,
상태별로 다른 액션 처리를 DSL로 정의할 수 있습니다.

---

## 핵심 개념: 상태 머신

FlowRedux의 핵심 차별점은 **상태에 따라 액션 처리가 달라지는** 상태 머신 패턴입니다:

```
일반 Redux:
  Action → Reducer → State (항상 같은 방식으로 처리)

FlowRedux (상태 머신):
  현재 State + Action → 해당 State에서 정의된 방식으로 처리
```

### 예시: 로그인 플로우

```
┌─────────────┐     Login     ┌─────────────┐
│   LoggedOut │──────────────►│   Loading   │
└─────────────┘               └──────┬──────┘
      ▲                              │
      │                    ┌─────────┴─────────┐
      │                    ▼                   ▼
      │             ┌───────────┐       ┌───────────┐
      └─────────────│   Error   │       │  LoggedIn │
         Retry      └───────────┘       └───────────┘
                                              │
                                              │ Logout
                                              ▼
                                        ┌───────────┐
                                        │ LoggedOut │
                                        └───────────┘
```

---

## 기본 사용법

### 1. State와 Action 정의

```kotlin
// State (sealed class로 상태 머신 표현)
sealed interface LoginState {
    object LoggedOut : LoginState
    object Loading : LoginState
    data class LoggedIn(val user: User) : LoginState
    data class Error(val message: String) : LoginState
}

// Action
sealed interface LoginAction {
    data class Login(val email: String, val password: String) : LoginAction
    object Logout : LoginAction
    object Retry : LoginAction
}
```

### 2. StateMachine 정의 (DSL)

```kotlin
class LoginStateMachine(
    private val api: AuthApi
) : FlowReduxStateMachine<LoginState, LoginAction>(
    initialState = LoginState.LoggedOut
) {
    init {
        spec {
            // LoggedOut 상태에서의 동작
            inState<LoginState.LoggedOut> {
                on<LoginAction.Login> { action, state ->
                    state.override { LoginState.Loading }
                }
            }

            // Loading 상태에서의 동작
            inState<LoginState.Loading> {
                onEnter { state ->
                    // Loading 진입 시 API 호출
                    try {
                        val user = api.login(/* ... */)
                        state.override { LoginState.LoggedIn(user) }
                    } catch (e: Exception) {
                        state.override { LoginState.Error(e.message ?: "Error") }
                    }
                }
            }

            // Error 상태에서의 동작
            inState<LoginState.Error> {
                on<LoginAction.Retry> { _, state ->
                    state.override { LoginState.LoggedOut }
                }
            }

            // LoggedIn 상태에서의 동작
            inState<LoginState.LoggedIn> {
                on<LoginAction.Logout> { _, state ->
                    state.override { LoginState.LoggedOut }
                }
            }
        }
    }
}
```

### 3. 사용

```kotlin
val stateMachine = LoginStateMachine(api)

// 상태 관찰
stateMachine.state.collect { state ->
    // UI 업데이트
}

// 액션 디스패치
stateMachine.dispatch(LoginAction.Login("email", "password"))
```

---

## DSL 상세

### inState

특정 상태에서의 동작을 정의:

```kotlin
spec {
    inState<SomeState> {
        // 이 상태일 때만 아래 핸들러들이 활성화됨
    }
}
```

### on

특정 액션 처리:

```kotlin
inState<LoggedOut> {
    on<LoginAction> { action, state ->
        // action 처리
        state.override { Loading }
    }
}
```

### onEnter

상태 진입 시 실행:

```kotlin
inState<Loading> {
    onEnter { state ->
        // Loading 상태에 진입하면 자동 실행
        val result = api.loadData()
        state.override { Loaded(result) }
    }
}
```

### onEnterLoadSmoothly (확장)

로딩 표시 최적화:

```kotlin
inState<Loading> {
    onEnterLoadSmoothly(
        minLoadingDuration = 500.milliseconds,
        loadingIndicatorDelay = 200.milliseconds
    ) { state ->
        val result = api.loadData()
        state.override { Loaded(result) }
    }
}
```

- 빠른 로딩: 로딩 인디케이터 표시 안 함
- 느린 로딩: 최소 시간 동안 인디케이터 표시 (깜빡임 방지)

### collectWhileInState

상태 유지 중 Flow 수집:

```kotlin
inState<Monitoring> {
    collectWhileInState(dataSource.updates) { update, state ->
        state.mutate { copy(data = update) }
    }
}
```

---

## 상태 전이 방식

### override

상태를 완전히 교체:

```kotlin
state.override { NewState() }
```

### mutate

현재 상태의 프로퍼티만 변경:

```kotlin
state.mutate { copy(isLoading = true) }
```

### noChange

상태 유지:

```kotlin
state.noChange()
```

---

## Compose 통합

```kotlin
@Composable
fun LoginScreen(stateMachine: LoginStateMachine) {
    val state by stateMachine.state.collectAsState()

    when (val currentState = state) {
        LoginState.LoggedOut -> {
            LoginForm(
                onLogin = { email, password ->
                    stateMachine.dispatch(LoginAction.Login(email, password))
                }
            )
        }
        LoginState.Loading -> LoadingIndicator()
        is LoginState.Error -> ErrorMessage(currentState.message)
        is LoginState.LoggedIn -> UserProfile(currentState.user)
    }
}
```

---

## flowdux와 비교

| 측면 | FlowRedux | flowdux |
|------|-----------|---------|
| **패러다임** | 상태 머신 | Redux |
| **상태 정의** | Sealed class (FSM) | Data class |
| **액션 처리** | 상태별로 다름 | 전역 동일 |
| **DSL** | `inState { on { } }` | `on<Action>(strategy) { }` |
| **동시성** | 수동 | Execution Strategy |
| **진입 핸들러** | `onEnter` | Middleware에서 처리 |
| **Strategy Group** | 없음 | 있음 |

### 패러다임 비교

```
┌─────────────────────────────────────────────────────────────────┐
│                    FlowRedux (상태 머신)                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   spec {                                                         │
│       inState<LoggedOut> {                                       │
│           on<Login> { ... }      // 이 상태에서만 Login 처리     │
│       }                                                          │
│       inState<LoggedIn> {                                        │
│           on<Logout> { ... }     // 이 상태에서만 Logout 처리    │
│           // Login 액션은 여기서 무시됨                          │
│       }                                                          │
│   }                                                              │
│                                                                  │
│   특징: 상태에 따라 허용되는 액션이 다름 (FSM)                    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                         flowdux (Redux)                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   buildProcessors {                                              │
│       on<Login>(takeLatest()) { state, action ->                 │
│           // 어떤 상태에서든 Login 액션 처리 가능                 │
│           // 상태 체크는 내부 로직으로                            │
│           if (state is LoggedOut) { ... }                        │
│       }                                                          │
│       on<Logout>(takeLatest()) { ... }                           │
│   }                                                              │
│                                                                  │
│   특징: 모든 액션이 항상 처리 가능, 동시성 전략 적용             │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 장단점

### 장점

1. **명시적 상태 머신**: 유효한 상태 전이만 허용
2. **onEnter 핸들러**: 상태 진입 시 자동 실행
3. **타입 안전**: Sealed class로 상태 완전성 보장
4. **로딩 최적화**: `onEnterLoadSmoothly` 확장
5. **Compose 친화적**: 상태 기반 UI 렌더링 자연스러움

### 단점

1. **동시성 전략 없음**: takeLatest, debounce 내장 없음
2. **Strategy Group 없음**: 액션 간 조율 어려움
3. **상태 폭발**: 복잡한 앱에서 상태 수 증가
4. **글로벌 상태**: 별도 조합 필요

---

## 상태 머신 vs Redux

### 상태 머신이 적합한 경우

- 명확한 상태 전이 (로그인 플로우, 결제 프로세스)
- 특정 상태에서만 허용되는 액션
- UI가 상태와 1:1 매핑

### Redux가 적합한 경우

- 복잡한 동시성 제어
- 여러 액션이 독립적으로 처리
- 상태가 다양한 프로퍼티의 조합

---

## 언제 사용하면 좋은가?

### FlowRedux가 적합한 경우

- 명확한 상태 머신 (결제, 온보딩, 인증 플로우)
- 상태별 다른 UI 렌더링
- `onEnter`로 상태 진입 시 자동 작업

### flowdux가 적합한 경우

- 동시성 전략이 핵심 요구사항
- 검색 자동완성 등 takeLatest 필요
- Strategy Group으로 액션 간 조율
- 상태가 프로퍼티 조합인 경우

---

## 참고 자료

- [FlowRedux 공식 문서](https://freeletics.github.io/FlowRedux/)
- [GitHub Repository](https://github.com/freeletics/FlowRedux)
- [State Machine on Jetpack Compose - ProAndroidDev](https://proandroiddev.com/state-machine-on-jetpack-compose-by-using-flowredux-e425af2fa5d4)
