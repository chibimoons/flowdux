# FlowMVI

*플러그인 시스템을 내세운 KMP 아키텍처 프레임워크*

---

## 개요

| 항목 | 내용 |
|------|------|
| GitHub | [respawn-app/FlowMVI](https://github.com/respawn-app/FlowMVI) |
| 문서 | [opensource.respawn.pro/FlowMVI](https://opensource.respawn.pro/FlowMVI/) |
| 철학 | 플러그인 시스템, 자동 에러 핸들링, 제로 보일러플레이트 |
| 플랫폼 | Android, iOS, Desktop, Web (KMP) |
| 의존성 | Kotlin Coroutines (코어는 의존성 없음) |

FlowMVI는 강력한 플러그인 시스템을 갖춘 KMP 아키텍처 프레임워크로,
50개 이상의 기능과 자동 에러 핸들링을 제공합니다.

---

## 핵심 특징

### 주요 장점

- **자동 에러 핸들링**: 모든 에러 자동 처리
- **스레드 안전성**: 모든 코드 자동 동기화
- **플러그인 시스템**: 기능 확장이 함수 호출 수준으로 간단
- **MVVM+/MVI 지원**: 두 가지 스타일 모두 지원
- **IDE 플러그인**: 디버깅 및 코드 생성 도구

---

## 아키텍처

### Store 구조

```
┌─────────────────────────────────────────────────────────────────────┐
│                              Store                                   │
│                                                                      │
│   ┌─────────────┐    ┌─────────────┐    ┌─────────────────────┐    │
│   │   Intent    │───►│   Reduce    │───►│       State         │    │
│   └─────────────┘    └──────┬──────┘    └─────────────────────┘    │
│                             │                                        │
│                             ▼                                        │
│                      ┌─────────────┐                                │
│                      │   Action    │ (Side Effects)                 │
│                      └─────────────┘                                │
│                                                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                      Plugin Chain                            │   │
│   │  [Logging] → [Analytics] → [Debugging] → [SavedState] → ... │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### MVI vs MVVM+ 스타일

```kotlin
// MVI 스타일 (Intent 기반)
sealed interface CounterIntent : MVIIntent {
    data object Increment : CounterIntent
    data object Decrement : CounterIntent
}

store.intent(CounterIntent.Increment)

// MVVM+ 스타일 (Lambda 기반)
store.intent {
    updateState { copy(count = count + 1) }
}
```

---

## 기본 사용법

### 1. Contract 정의

```kotlin
data class CounterState(
    val count: Int = 0,
    val isLoading: Boolean = false
) : MVIState

sealed interface CounterIntent : MVIIntent {
    data object Increment : CounterIntent
    data object Decrement : CounterIntent
    data class Load(val id: String) : CounterIntent
}

sealed interface CounterAction : MVIAction {
    data class ShowError(val message: String) : CounterAction
}
```

### 2. Store 생성

```kotlin
val store = store<CounterState, CounterIntent, CounterAction>(
    initial = CounterState()
) {
    // 플러그인 추가
    install(loggingPlugin())
    install(analyticsPlugin())

    // Intent 처리
    reduce { intent ->
        when (intent) {
            CounterIntent.Increment -> updateState { copy(count = count + 1) }
            CounterIntent.Decrement -> updateState { copy(count = count - 1) }
            is CounterIntent.Load -> load(intent.id)
        }
    }
}

private fun StoreScope<CounterState, CounterIntent, CounterAction>.load(id: String) {
    launchForState {
        updateState { copy(isLoading = true) }
        try {
            val result = api.load(id)
            updateState { copy(count = result, isLoading = false) }
        } catch (e: Exception) {
            action(CounterAction.ShowError(e.message ?: "Error"))
            updateState { copy(isLoading = false) }
        }
    }
}
```

### 3. Compose에서 사용

```kotlin
@Composable
fun CounterScreen() {
    val store = remember { createCounterStore() }

    val state by store.subscribe { action ->
        when (action) {
            is CounterAction.ShowError -> showToast(action.message)
        }
    }

    CounterContent(
        count = state.count,
        onIncrement = { store.intent(CounterIntent.Increment) }
    )
}
```

---

## 플러그인 시스템

FlowMVI의 핵심 차별점은 플러그인 시스템입니다:

### 내장 플러그인

```kotlin
store<State, Intent, Action>(initial) {
    // 로깅
    install(loggingPlugin())

    // 분석
    install(analyticsPlugin { event ->
        analytics.log(event)
    })

    // 상태 저장/복원
    install(savedStatePlugin(
        saver = { state -> json.encodeToString(state) },
        restore = { json.decodeFromString(it) }
    ))

    // 에러 핸들링
    install(errorHandlerPlugin { e ->
        action(ShowError(e.message))
    })

    // 성능 메트릭
    install(metricsPlugin())
}
```

### 커스텀 플러그인 작성

```kotlin
fun <S : MVIState, I : MVIIntent, A : MVIAction> customPlugin() = plugin<S, I, A> {
    onState { old, new ->
        // 상태 변경 시
        println("State: $old -> $new")
        new // 상태 변환 가능
    }

    onIntent { intent ->
        // Intent 처리 전
        println("Intent: $intent")
        intent // Intent 변환 가능
    }

    onAction { action ->
        // Action 발생 시
        println("Action: $action")
        action
    }

    onStart {
        // Store 시작 시
    }

    onStop {
        // Store 종료 시
    }
}
```

---

## 모듈 구조

| 모듈 | 설명 |
|------|------|
| `flowmvi-core` | 핵심 프레임워크 (의존성 없음) |
| `flowmvi-compose` | Compose Multiplatform 지원 |
| `flowmvi-test` | 테스트 DSL |
| `flowmvi-savedstate` | 상태 저장/복원 |
| `flowmvi-metrics` | 성능 메트릭 |
| `flowmvi-debugger-plugin` | 원격 디버깅 |
| `flowmvi-essenty` | Decompose/Essenty 통합 |

---

## IDE 플러그인

FlowMVI는 전용 IDE 플러그인을 제공합니다:

- **코드 생성**: Store 보일러플레이트 자동 생성
- **디버깅**: Store 상태 실시간 확인
- **데스크톱 앱**: Windows, Linux, macOS용 디버거

---

## flowdux와 비교

| 측면 | FlowMVI | flowdux |
|------|---------|---------|
| **확장 메커니즘** | Plugin 시스템 | Middleware 체인 |
| **스타일** | MVI + MVVM+ 지원 | Redux 스타일 |
| **에러 핸들링** | recover + onException | ErrorProcessor |
| **동시성 제어** | 내장 없음 | Execution Strategy |
| **IDE 지원** | 전용 플러그인 | 없음 |
| **보일러플레이트** | 최소 | 중간 |
| **글로벌 상태** | Store 조합 | 단일 Store |

### 확장 메커니즘 비교

```
┌─────────────────────────────────────────────────────────────────┐
│                    FlowMVI Plugin System                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   store {                                                        │
│       install(loggingPlugin())     // 플러그인 설치              │
│       install(analyticsPlugin())                                 │
│       install(customPlugin())                                    │
│                                                                  │
│       reduce { intent -> ... }     // 핵심 로직                  │
│   }                                                              │
│                                                                  │
│   특징: 선언적 플러그인 체인, 상태/인텐트/액션 가로채기           │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    flowdux Middleware                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   createStore(                                                   │
│       middlewares = listOf(                                      │
│           LoggingMiddleware(),                                   │
│           AnalyticsMiddleware(),                                 │
│           FeatureMiddleware()                                    │
│       )                                                          │
│   )                                                              │
│                                                                  │
│   특징: 액션 기반 체인, Execution Strategy 적용                  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 에러 핸들링 비교

```
┌─────────────────────────────────────────────────────────────────┐
│                    FlowMVI 에러 핸들링                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   // 1. Intent별 recover 블록                                    │
│   reduce { intent ->                                             │
│       recover { e ->                                             │
│           updateState { copy(error = e.message) }                │
│           null  // 에러 무시                                     │
│       }                                                          │
│       // 에러 발생 가능 코드                                     │
│   }                                                              │
│                                                                  │
│   // 2. Plugin의 onException 훅                                  │
│   plugin {                                                       │
│       onException { e ->                                         │
│           logger.error("Error", e)                               │
│           null  // 에러 무시                                     │
│       }                                                          │
│   }                                                              │
│                                                                  │
│   특징: Intent별 세밀한 처리, 직접 상태 변경 가능               │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                    flowdux ErrorProcessor                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   class MyErrorProcessor : ErrorProcessor<AppAction> {           │
│       override fun process(throwable: Throwable) = flow {       │
│           emit(ShowError(throwable.message ?: "Error"))          │
│       }                                                          │
│   }                                                              │
│                                                                  │
│   val store = Store(                                             │
│       errorProcessor = MyErrorProcessor()                        │
│   )                                                              │
│                                                                  │
│   특징: Error → Action → Reducer → State (Redux 원칙 준수)      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

| 측면 | FlowMVI | flowdux |
|------|---------|---------|
| **처리 위치** | Intent별 + Plugin | Store 레벨 (전역) |
| **상태 변경** | 직접 updateState | Action → Reducer |
| **Redux 원칙** | ❌ 우회 | ✅ 준수 |
| **에러 추적** | Plugin 훅 | Action으로 로깅 |

---

## 장단점

### 장점

1. **강력한 플러그인**: 3줄로 분석, 로깅, 디버깅 추가
2. **자동 에러 핸들링**: 수동 try-catch 불필요
3. **스레드 안전성**: 자동 동기화
4. **유연한 스타일**: MVI와 MVVM+ 선택 가능
5. **풍부한 생태계**: IDE 플러그인, 디버거 앱

### 단점

1. **동시성 전략 없음**: takeLatest, debounce 내장 없음
2. **Strategy Group 없음**: 액션 간 조율 기능 없음
3. **글로벌 상태**: 별도 조합 필요

---

## 언제 사용하면 좋은가?

### FlowMVI가 적합한 경우

- 플러그인으로 빠른 기능 확장
- 자동 에러 핸들링 원하는 경우
- MVVM+와 MVI 둘 다 사용하고 싶은 경우
- IDE 플러그인 활용

### flowdux가 적합한 경우

- 세밀한 동시성 제어 필요
- Middleware로 횡단 관심사 처리
- Strategy Group으로 액션 간 조율
- Redux 패턴 익숙한 경우

---

## 참고 자료

- [FlowMVI 공식 문서](https://opensource.respawn.pro/FlowMVI/)
- [GitHub Repository](https://github.com/respawn-app/FlowMVI)
- [IDE Plugin](https://plugins.jetbrains.com/plugin/25766-flowmvi)
