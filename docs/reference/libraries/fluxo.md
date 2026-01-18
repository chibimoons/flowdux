# Fluxo

*BLoC/MVI/Redux/MVU 등을 폭넓게 커버하는 KMP 상태 관리 프레임워크*

---

## 개요

| 항목 | 내용 |
|------|------|
| GitHub | [fluxo-kt/fluxo](https://github.com/fluxo-kt/fluxo) |
| 철학 | Redux/MVI의 엄격함 + MVVM+의 유연함 결합 |
| 플랫폼 | Android, iOS, JVM, JS, Linux, Windows, macOS, watchOS, tvOS |
| 상태 | WIP (Work-In-Progress), 첫 릴리즈 준비 중 |
| 의존성 | Kotlin Coroutines |

Fluxo는 BLoC, MVI, Redux, TEA/Elm/MVU, SAM 등 다양한 패턴을 지원하며,
엄격한 Redux/MVI와 유연한 MVVM+의 장점을 결합하려는 실험적 프레임워크입니다.

---

## 핵심 철학

### Redux/MVI + MVVM+ 결합

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Fluxo의 목표                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   Redux/MVI의 장점:                                                  │
│   • 예측 가능한 상태 변화                                            │
│   • 타임트래블 디버깅 가능                                           │
│   • 트랜지션 그래프 분석                                             │
│                                                                      │
│   MVVM+의 장점:                                                      │
│   • 직관적이고 읽기 쉬운 코드                                        │
│   • Lambda intent로 유연한 처리                                      │
│   • 유지보수 용이                                                    │
│                                                                      │
│   Fluxo = 두 가지 장점 모두 제공                                     │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 지원하는 패턴들

| 패턴 | 설명 |
|------|------|
| **BLoC** | Business Logic Component (Flutter 유래) |
| **MVI** | Model-View-Intent |
| **Redux** | 단방향 데이터 흐름, Store/Reducer |
| **TEA/Elm/MVU** | The Elm Architecture / Model-View-Update |
| **SAM** | State-Action-Model |
| **FSM** | Finite State Machine |

---

## 주요 특징

### 1. 원라이너 Store 생성

```kotlin
val store = store(initialState = CounterState()) {
    // 로직 정의
}
```

### 2. 두 가지 Intent 스타일

```kotlin
// Discrete MVI Intents (타입 안전, 분석 가능)
sealed interface CounterIntent {
    object Increment : CounterIntent
}
store.send(CounterIntent.Increment)

// Lambda MVVM+ Intents (유연, 간결)
store.send {
    updateState { copy(count = count + 1) }
}
```

### 3. Lambda Intent의 고급 디버깅

Fluxo의 핵심 혁신: Lambda intent도 discrete intent 수준의 디버깅 가능

```kotlin
// 일반 라이브러리: Lambda intent는 로깅/디버깅 어려움
store.send { updateState { copy(count = count + 1) } }
// 로그: "Lambda@123abc" (의미 없음)

// Fluxo: Lambda intent도 예쁜 로깅
store.send { updateState { copy(count = count + 1) } }
// 로그: "updateState { copy(count = 2) }" (의미 있음)
```

### 4. 타임트래블 + MVVM+

Lambda intent에서도 타임트래블과 트랜지션 그래프 분석 가능:

```
State History:
  [0] CounterState(count=0)
  [1] CounterState(count=1)  <- updateState { copy(count = count + 1) }
  [2] CounterState(count=2)  <- updateState { copy(count = count + 1) }
  [3] CounterState(count=1)  <- updateState { copy(count = count - 1) }
```

---

## 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│                           Fluxo Store                                │
│                                                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                    Intent (MVI 또는 Lambda)                  │   │
│   └─────────────────────────────┬───────────────────────────────┘   │
│                                 │                                    │
│                                 ▼                                    │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                       Reducer/Handler                        │   │
│   │                                                              │   │
│   │   • updateState { } : 상태 변경                              │   │
│   │   • sideEffect { } : 부수 효과                               │   │
│   │   • postIntent() : 추가 Intent 발행                          │   │
│   └─────────────────────────────┬───────────────────────────────┘   │
│                                 │                                    │
│                                 ▼                                    │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                         State                                │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                   Debugging & Analysis                       │   │
│   │   • Logging (Lambda도 예쁘게)                                │   │
│   │   • Time Travel                                              │   │
│   │   • Transition Graph                                         │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 사용 예시 (예상)

> 주의: Fluxo는 아직 WIP 상태로, API가 변경될 수 있습니다.

```kotlin
// State 정의
data class CounterState(
    val count: Int = 0,
    val isLoading: Boolean = false
)

// Store 생성
val store = store(
    initialState = CounterState(),
    settings = StoreSettings(
        logging = true,
        timeTravel = true
    )
) {
    // MVI 스타일 Intent 처리
    on<Increment> {
        updateState { copy(count = count + 1) }
    }

    on<Decrement> {
        updateState { copy(count = count - 1) }
    }

    on<LoadData> { intent ->
        updateState { copy(isLoading = true) }
        sideEffect {
            val data = api.load(intent.id)
            postIntent(DataLoaded(data))
        }
    }
}

// 사용
store.send(Increment)
store.send { updateState { copy(count = count + 10) } }  // Lambda 스타일
```

---

## flowdux와 비교

| 측면 | Fluxo | flowdux |
|------|-------|---------|
| **상태** | WIP (미출시) | 안정 (1.5.0) |
| **Intent 스타일** | MVI + MVVM+ Lambda | Action 기반 |
| **Lambda 디버깅** | 고급 지원 | N/A (Action 기반) |
| **동시성 전략** | 미정 | Execution Strategy |
| **타임트래블** | 계획됨 (Lambda 포함) | 커스텀 미들웨어 |
| **패턴 지원** | 다중 (BLoC, MVI, Redux 등) | Redux 중심 |

### 접근 방식 비교

```
┌─────────────────────────────────────────────────────────────────┐
│                          Fluxo                                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   목표: 모든 패턴을 하나의 프레임워크로                           │
│                                                                  │
│   store {                                                        │
│       // MVI 스타일                                              │
│       on<IncrementIntent> { updateState { ... } }                │
│                                                                  │
│       // MVVM+ 스타일                                            │
│       // store.send { updateState { ... } }                      │
│   }                                                              │
│                                                                  │
│   특징: Lambda intent도 완전한 디버깅/분석 지원                   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                         flowdux                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   목표: Redux 패턴 + 동시성 제어                                 │
│                                                                  │
│   buildProcessors {                                              │
│       on<SearchAction>(takeLatest()) { ... }                     │
│       on<SaveAction>(debounce(300.ms)) { ... }                   │
│                                                                  │
│       group(takeLatest()) {                                      │
│           on<ActionA> { ... }                                    │
│           on<ActionB> { ... }                                    │
│       }                                                          │
│   }                                                              │
│                                                                  │
│   특징: 액션별 동시성 전략, Strategy Group                       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 장단점

### 장점 (예상)

1. **패턴 유연성**: MVI와 MVVM+ 자유롭게 혼용
2. **Lambda 디버깅**: Lambda intent도 의미 있는 로깅
3. **광범위한 플랫폼**: 거의 모든 KMP 타겟 지원
4. **타임트래블**: Lambda에서도 작동 (혁신적)

### 단점

1. **미출시**: WIP 상태, 프로덕션 사용 어려움
2. **불안정 API**: 변경 가능성
3. **문서 부족**: 아직 상세 문서 없음
4. **커뮤니티**: 아직 형성되지 않음

---

## 언제 사용하면 좋은가?

### Fluxo가 적합한 경우 (출시 후)

- Lambda intent + 완전한 디버깅 필요
- MVI와 MVVM+ 혼용하고 싶은 경우
- 다양한 아키텍처 패턴 실험

### 현재 시점에서 flowdux가 적합한 경우

- 안정적인 프로덕션 사용 필요
- 동시성 전략이 핵심 요구사항
- 명확한 API와 문서 필요

---

## 참고 자료

- [GitHub Repository](https://github.com/fluxo-kt/fluxo)
- [KMP State Management 비교 - Medium](https://medium.com/@hiren6997/state-management-in-kotlin-multiplatform-my-complete-survival-guide-c03b32c08038)

---

> **Note**: Fluxo는 현재 WIP 상태입니다. 프로덕션 사용 전 안정화 상태를 확인하세요.
