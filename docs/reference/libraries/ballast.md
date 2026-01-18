# Ballast

*KMP용 "Opinionated" 상태 관리 프레임워크*

---

## 개요

| 항목 | 내용 |
|------|------|
| GitHub | [copper-leaf/ballast](https://github.com/copper-leaf/ballast) |
| 문서 | [copper-leaf.github.io/ballast](https://copper-leaf.github.io/ballast/) |
| 철학 | Compose Desktop 우선 설계, 강한 의견(opinionated) |
| 플랫폼 | 모든 KMP 타겟 (Coroutines/Flow 지원 필수) |
| 의존성 | Kotlin Coroutines |

Ballast는 Compose Desktop에서 시작된 상태 관리 프레임워크로,
Android에 종속되지 않은 설계가 특징입니다.

---

## 핵심 아키텍처

### MVI 루프

```
┌─────────────────────────────────────────────────────────────────────┐
│                           Ballast MVI Loop                           │
│                                                                      │
│   ┌──────────┐    ┌────────────────┐    ┌──────────────────┐        │
│   │   View   │───►│   ViewModel    │───►│  InputHandler    │        │
│   └──────────┘    └────────────────┘    └────────┬─────────┘        │
│        ▲               │                         │                   │
│        │               │                         ▼                   │
│        │          ┌────┴────┐           ┌────────────────┐          │
│        │          │  State  │◄──────────│ updateState {} │          │
│        │          └─────────┘           └────────────────┘          │
│        │                                         │                   │
│        │                                         ▼                   │
│        │                                ┌────────────────┐          │
│        └────────────────────────────────│ EventHandler   │          │
│                                         │  (postEvent)   │          │
│                                         └────────────────┘          │
│                                                  │                   │
│                                                  ▼                   │
│                                         ┌────────────────┐          │
│                                         │   SideJob      │          │
│                                         │  (백그라운드)   │          │
│                                         └────────────────┘          │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 핵심 개념

| 구성 요소 | 역할 |
|-----------|------|
| **Input** | UI → ViewModel로 전달되는 사용자 액션 |
| **State** | 불변 데이터 클래스, UI 렌더링 소스 |
| **Event** | ViewModel → UI로 전달되는 일회성 이벤트 |
| **InputHandler** | Input 처리 로직 (유일하게 코드 실행 가능) |
| **EventHandler** | Event 처리 (Toast, 네비게이션 등) |
| **SideJob** | 장기 실행 백그라운드 작업 |

---

## 기본 사용법

### 1. Contract 정의

```kotlin
object CounterContract {
    data class State(
        val count: Int = 0,
        val isLoading: Boolean = false
    )

    sealed interface Input {
        object Increment : Input
        object Decrement : Input
        data class LoadValue(val id: String) : Input
    }

    sealed interface Event {
        data class ShowError(val message: String) : Event
        object NavigateBack : Event
    }
}
```

### 2. InputHandler 구현

```kotlin
class CounterInputHandler : InputHandler<
    CounterContract.Input,
    CounterContract.Event,
    CounterContract.State
> {
    override suspend fun InputHandlerScope<
        CounterContract.Input,
        CounterContract.Event,
        CounterContract.State
    >.handleInput(input: CounterContract.Input) = when (input) {

        CounterContract.Input.Increment -> {
            updateState { it.copy(count = it.count + 1) }
        }

        CounterContract.Input.Decrement -> {
            updateState { it.copy(count = it.count - 1) }
        }

        is CounterContract.Input.LoadValue -> {
            updateState { it.copy(isLoading = true) }

            // SideJob으로 백그라운드 작업
            sideJob("loadValue") {
                try {
                    val value = api.load(input.id)
                    postInput(CounterContract.Input.SetValue(value))
                } catch (e: Exception) {
                    postEvent(CounterContract.Event.ShowError(e.message ?: "Error"))
                }
            }
        }
    }
}
```

### 3. ViewModel 생성

```kotlin
class CounterViewModel(
    coroutineScope: CoroutineScope
) : BasicViewModel<
    CounterContract.Input,
    CounterContract.Event,
    CounterContract.State
>(
    coroutineScope = coroutineScope,
    config = BallastViewModelConfiguration.Builder()
        .apply {
            initialState = CounterContract.State()
            inputHandler = CounterInputHandler()
        }
        .build(),
    eventHandler = CounterEventHandler()
)
```

### 4. EventHandler 구현

```kotlin
class CounterEventHandler : EventHandler<
    CounterContract.Input,
    CounterContract.Event,
    CounterContract.State
> {
    override suspend fun EventHandlerScope<
        CounterContract.Input,
        CounterContract.Event,
        CounterContract.State
    >.handleEvent(event: CounterContract.Event) = when (event) {
        is CounterContract.Event.ShowError -> {
            // Toast 표시 등
        }
        CounterContract.Event.NavigateBack -> {
            // 네비게이션
        }
    }
}
```

---

## Input Strategy

Ballast는 3가지 Input 처리 전략을 제공합니다:

### 1. LifoInputStrategy (기본값)

```kotlin
BallastViewModelConfiguration.Builder()
    .withInputStrategy(LifoInputStrategy.typed())
```

- Last-In-First-Out
- 새 Input 도착 시 이전 처리 취소
- **flowdux의 `takeLatest`와 유사**

### 2. FifoInputStrategy (권장)

```kotlin
BallastViewModelConfiguration.Builder()
    .withInputStrategy(FifoInputStrategy.typed())
```

- First-In-First-Out
- 모든 Input 순차 처리
- **flowdux의 기본 동작과 유사**

### 3. ParallelInputStrategy

```kotlin
BallastViewModelConfiguration.Builder()
    .withInputStrategy(ParallelInputStrategy.typed())
```

- 모든 Input 병렬 처리
- 상태 경합 주의 필요

### 전략 비교

```
LifoInputStrategy (LIFO):
Input A ──────────────────────> (취소됨)
     Input B ─────────────────> (취소됨)
          Input C ────────────> 완료 ✓

FifoInputStrategy (FIFO):
Input A ────────> 완료 ✓
     Input B ────────> (대기) ────────> 완료 ✓
          Input C ────────> (대기) ────────> 완료 ✓

ParallelInputStrategy:
Input A ────────────────────> 완료 ✓
     Input B ───────────────> 완료 ✓
          Input C ──────────> 완료 ✓
```

---

## SideJob

장기 실행 백그라운드 작업을 위한 메커니즘:

```kotlin
sideJob("uniqueKey") {
    // 이 블록은 InputHandler와 병렬로 실행

    // 상태에 직접 접근 불가 (병렬 실행이므로)
    // 대신 Input이나 Event를 통해 통신

    observeDataSource().collect { data ->
        postInput(Input.DataReceived(data))
    }
}
```

### SideJob 특징

- **Key 기반**: 같은 key로 재시작 시 이전 job 취소
- **병렬 실행**: InputHandler와 독립적으로 실행
- **간접 통신**: `postInput()`, `postEvent()`로만 통신

```
┌─────────────────────────────────────────────────────────────────┐
│                    SideJob 동작 방식                              │
│                                                                  │
│   InputHandler ──► sideJob("key") ──► 백그라운드 실행            │
│        │                                    │                    │
│        │                                    ├── postInput()      │
│        │◄───────────────────────────────────┘                    │
│        │                                    │                    │
│        │                                    └── postEvent()      │
│        │                                           │             │
│        ▼                                           ▼             │
│   updateState()                               EventHandler       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Interceptor

Ballast는 Interceptor로 기능을 확장합니다:

```kotlin
BallastViewModelConfiguration.Builder()
    .apply {
        this += LoggingInterceptor()
        this += BallastDebuggerInterceptor(debuggerConnection)
    }
```

### 내장 Interceptor

| Interceptor | 역할 |
|-------------|------|
| `LoggingInterceptor` | Input, State 변경 로깅 |
| `BallastDebuggerInterceptor` | 원격 디버거 연결 |
| `BallastSavedStateInterceptor` | 상태 저장/복원 |
| `BallastUndoInterceptor` | Undo/Redo 기능 |
| `BallastAnalyticsInterceptor` | 분석 이벤트 추적 |

---

## flowdux와 비교

| 측면 | Ballast | flowdux |
|------|---------|---------|
| **패러다임** | MVI (InputHandler 중심) | Redux (Middleware + Reducer) |
| **동시성 전략** | InputStrategy (LIFO/FIFO/Parallel) | ExecutionStrategy (takeLatest 등) |
| **비동기 처리** | InputHandler + SideJob | Middleware processor |
| **확장 메커니즘** | Interceptor | Middleware 체인 |
| **일회성 이벤트** | Event + EventHandler | Action |
| **상태 업데이트** | `updateState { }` | Reducer |
| **글로벌 상태** | 별도 모듈 (ballast-repository) | 단일 Store |

### Input Strategy vs Execution Strategy 비교

```
┌─────────────────────────────────────────────────────────────────┐
│                    Ballast Input Strategy                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  LifoInputStrategy ≈ takeLatest() (전역)                        │
│  FifoInputStrategy ≈ 기본 순차 처리                              │
│  ParallelInputStrategy ≈ 병렬 처리                               │
│                                                                  │
│  특징: ViewModel 전체에 하나의 전략 적용                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                   flowdux Execution Strategy                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  on<SearchAction>(takeLatest()) { ... }                         │
│  on<SaveAction>(debounce(300.ms)) { ... }                       │
│  on<SubmitAction>(takeLeading()) { ... }                        │
│                                                                  │
│  특징: 액션별로 다른 전략 적용 가능                               │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 모듈 구조

| 모듈 | 설명 |
|------|------|
| `ballast-core` | 핵심 MVI 프레임워크 |
| `ballast-repository` | 글로벌 상태/캐싱 |
| `ballast-navigation` | 라우팅 |
| `ballast-saved-state` | 상태 저장/복원 |
| `ballast-debugger` | 원격 디버거 |
| `ballast-undo` | Undo/Redo |
| `ballast-analytics` | 분석 통합 |

---

## 장단점

### 장점

1. **Android 독립적**: Compose Desktop 우선 설계
2. **InputStrategy**: 동시성 전략 전환 용이
3. **SideJob**: 장기 실행 작업 관리
4. **풍부한 모듈**: 네비게이션, 상태 저장, 디버거 등
5. **상세한 문서**: 비교 페이지 포함

### 단점

1. **액션별 전략 불가**: 전체 ViewModel에 하나의 InputStrategy
2. **보일러플레이트**: Contract, InputHandler, EventHandler 분리
3. **SideJob 제약**: 상태 직접 접근 불가

---

## 언제 사용하면 좋은가?

### Ballast가 적합한 경우

- Compose Desktop/Multiplatform 프로젝트
- Android 종속성 피하고 싶은 경우
- SideJob으로 장기 실행 작업 관리
- 내장 네비게이션, 상태 저장 필요

### flowdux가 적합한 경우

- 액션별 세밀한 동시성 제어
- Strategy Group으로 액션 간 조율
- Middleware로 횡단 관심사
- 글로벌 단일 Store

---

## 참고 자료

- [Ballast 공식 문서](https://copper-leaf.github.io/ballast/)
- [GitHub Repository](https://github.com/copper-leaf/ballast)
- [Feature Overview](https://copper-leaf.github.io/ballast/wiki/feature-overview/)
- [Ballast Core](https://copper-leaf.github.io/ballast/wiki/modules/ballast-core/)
