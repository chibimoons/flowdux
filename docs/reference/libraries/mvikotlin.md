# MVIKotlin

*Arkadii Ivanov의 KMP MVI 프레임워크 (로그/타임트래블 디버깅 강조)*

---

## 개요

| 항목 | 내용 |
|------|------|
| GitHub | [arkivanov/MVIKotlin](https://github.com/arkivanov/MVIKotlin) |
| 문서 | [arkivanov.github.io/MVIKotlin](https://arkivanov.github.io/MVIKotlin/) |
| 저자 | Arkadii Ivanov (Bumble, Google Developer Expert) |
| 철학 | 확장 가능한 MVI + 강력한 디버깅 도구 |
| 플랫폼 | Android, JVM, iOS, watchOS, tvOS, macOS, Linux, JS, WASM |
| 의존성 | 없음 (Reaktive 또는 Coroutines 확장은 선택) |

MVIKotlin은 강력한 디버깅 도구(로깅, 타임트래블)를 갖춘 KMP용 확장 가능 MVI 프레임워크입니다.

---

## 핵심 아키텍처

### Store 구조

```
┌─────────────────────────────────────────────────────────────────────┐
│                              Store                                   │
│                                                                      │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐              │
│  │ Bootstrapper│    │  Executor   │    │   Reducer   │              │
│  │  (초기화)    │    │ (비즈니스)  │    │ (상태변환)   │              │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘              │
│         │                  │                  │                      │
│         │    Action        │    Message       │                      │
│         └─────────────────►│─────────────────►│                      │
│                            │                  │                      │
│                            │                  ▼                      │
│                            │           ┌───────────┐                 │
│                            │           │   State   │                 │
│                            │           └───────────┘                 │
│                            │                                         │
│                            ▼                                         │
│                     ┌───────────┐                                    │
│                     │   Label   │ (일회성 이벤트)                     │
│                     └───────────┘                                    │
│                                                                      │
│  외부 입력: Intent ─────────────────► Executor                       │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 구성 요소 설명

| 구성 요소 | 역할 | 가시성 |
|-----------|------|--------|
| **State** | 기능/화면의 전체 상태 (불변) | 외부 공개 |
| **Intent** | View가 보내는 사용자 액션 | 외부 공개 |
| **Label** | 일회성 이벤트 (네비게이션, Toast 등) | 외부 공개 |
| **Message** | Executor → Reducer 내부 결과 | 내부 전용 |
| **Action** | Bootstrapper/Executor 내부 액션 | 내부 전용 |
| **Bootstrapper** | Store 초기화 (선택) | 내부 전용 |
| **Executor** | 비즈니스 로직, 비동기 처리 | 내부 전용 |
| **Reducer** | Message → State 변환 | 내부 전용 |

---

## 기본 사용법

### 1. 타입 정의

```kotlin
// State
data class CounterState(
    val count: Int = 0,
    val isLoading: Boolean = false
)

// Intent (외부 입력)
sealed interface CounterIntent {
    object Increment : CounterIntent
    object Decrement : CounterIntent
    data class Load(val id: String) : CounterIntent
}

// Label (일회성 이벤트)
sealed interface CounterLabel {
    data class Error(val message: String) : CounterLabel
}

// Message (내부용)
internal sealed interface CounterMessage {
    object Incremented : CounterMessage
    object Decremented : CounterMessage
    data class Loaded(val value: Int) : CounterMessage
}
```

### 2. Executor 구현 (Coroutines)

```kotlin
internal class CounterExecutor : CoroutineExecutor<
    CounterIntent,    // Intent
    Nothing,          // Action (Bootstrapper 미사용)
    CounterState,     // State
    CounterMessage,   // Message
    CounterLabel      // Label
>() {
    override fun executeIntent(intent: CounterIntent) {
        when (intent) {
            CounterIntent.Increment -> dispatch(CounterMessage.Incremented)
            CounterIntent.Decrement -> dispatch(CounterMessage.Decremented)
            is CounterIntent.Load -> load(intent.id)
        }
    }

    private fun load(id: String) {
        scope.launch {
            try {
                val result = api.load(id)
                dispatch(CounterMessage.Loaded(result))
            } catch (e: Exception) {
                publish(CounterLabel.Error(e.message ?: "Unknown error"))
            }
        }
    }
}
```

### 3. Reducer 구현

```kotlin
internal object CounterReducer : Reducer<CounterState, CounterMessage> {
    override fun CounterState.reduce(msg: CounterMessage): CounterState =
        when (msg) {
            CounterMessage.Incremented -> copy(count = count + 1)
            CounterMessage.Decremented -> copy(count = count - 1)
            is CounterMessage.Loaded -> copy(count = msg.value, isLoading = false)
        }
}
```

### 4. Store 생성

```kotlin
internal class CounterStoreFactory(
    private val storeFactory: StoreFactory
) {
    fun create(): CounterStore =
        object : CounterStore, Store<CounterIntent, CounterState, CounterLabel> by storeFactory.create(
            name = "CounterStore",
            initialState = CounterState(),
            executorFactory = ::CounterExecutor,
            reducer = CounterReducer
        ) {}
}

// Store 인터페이스 (외부 공개)
interface CounterStore : Store<CounterIntent, CounterState, CounterLabel>
```

---

## 데이터 흐름

```
┌─────────────────────────────────────────────────────────────────────┐
│                           데이터 흐름                                 │
└─────────────────────────────────────────────────────────────────────┘

1. View에서 Intent 전송
   │
   ▼
2. Store.accept(intent)
   │
   ▼
3. Executor.executeIntent(intent)
   │
   ├──► dispatch(Message) ───► Reducer ───► 새 State
   │                                            │
   │                                            ▼
   │                                      State 구독자에게 전달
   │
   └──► publish(Label) ───► Label 구독자에게 전달 (일회성)

┌─────────────────────────────────────────────────────────────────────┐
│ Bootstrapper 흐름 (선택적)                                           │
│                                                                      │
│ Store 생성 시:                                                       │
│   Bootstrapper.invoke() ───► dispatch(Action) ───► Executor         │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 디버깅 도구

### 1. LoggingStoreFactory

```kotlin
val storeFactory = LoggingStoreFactory(
    delegate = DefaultStoreFactory(),
    logger = object : Logger {
        override fun log(text: String) {
            println("MVIKotlin: $text")
        }
    }
)
```

모든 Intent, Message, State 변경을 로깅합니다.

### 2. TimeTravelStoreFactory

```kotlin
val storeFactory = TimeTravelStoreFactory()

// 타임트래블 컨트롤러
val controller = storeFactory.timeTravelController

// 상태 이력 탐색
controller.stepBackward()
controller.stepForward()
controller.moveToStart()
controller.moveToEnd()

// 녹화
controller.startRecording()
controller.stopRecording()

// 이벤트 내보내기
val events = controller.export()
```

### 3. Android 타임트래블 UI

```kotlin
// TimeTravelView 추가
TimeTravelView(context).apply {
    connect(storeFactory.timeTravelController)
}
```

---

## StoreFactory 종류

| Factory | 설명 | 모듈 |
|---------|------|------|
| `DefaultStoreFactory` | 기본 구현 | mvikotlin-main |
| `LoggingStoreFactory` | 로깅 래퍼 | mvikotlin-logging |
| `TimeTravelStoreFactory` | 타임트래블 지원 | mvikotlin-timetravel |

### 조합 사용

```kotlin
val storeFactory = LoggingStoreFactory(
    delegate = TimeTravelStoreFactory()
)
```

---

## Executor 종류

| Executor | 기반 | 모듈 |
|----------|------|------|
| `CoroutineExecutor` | Kotlin Coroutines | mvikotlin-extensions-coroutines |
| `ReaktiveExecutor` | Reaktive 라이브러리 | mvikotlin-extensions-reaktive |

### CoroutineExecutor 사용

```kotlin
class MyExecutor : CoroutineExecutor<Intent, Action, State, Message, Label>() {

    override fun executeIntent(intent: Intent) {
        when (intent) {
            is Intent.Load -> {
                scope.launch {
                    // 비동기 작업
                    val result = api.fetch()
                    dispatch(Message.Loaded(result))
                }
            }
        }
    }

    // state() 함수로 현재 상태 접근 가능
    private fun checkState() {
        val currentState = state()
    }
}
```

---

## flowdux와 비교

| 측면 | MVIKotlin | flowdux |
|------|-----------|---------|
| **아키텍처** | Store (Executor + Reducer) | Store (Middleware + Reducer) |
| **비동기 처리** | Executor 내부 | Middleware processor |
| **내부/외부 분리** | Message(내부) / Intent, Label(외부) | Action 통합 |
| **일회성 이벤트** | Label | Action으로 처리 |
| **동시성 제어** | 수동 (scope.launch) | Execution Strategy |
| **디버깅** | LoggingStoreFactory, TimeTravelStoreFactory | 커스텀 미들웨어 |
| **의존성** | 없음 (코어) | Coroutines |
| **글로벌 상태** | Store 조합 필요 | 단일 Store |

### 아키텍처 비교

```
┌─────────────────────────────────────────────────────────────────┐
│                         MVIKotlin                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   View ──► Intent ──► Executor ──► Message ──► Reducer ──► State│
│                           │                                      │
│                           └──► Label (일회성)                    │
│                                                                  │
│   특징: Intent/Message 분리, 강력한 디버깅 도구                   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                           flowdux                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   View ──► Action ──► Middleware ──► Action ──► Reducer ──► State│
│                           │                                      │
│                           └── Execution Strategy                 │
│                                                                  │
│   특징: 단일 Action 타입, Middleware 체인, 선언적 동시성         │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 장단점

### 장점

1. **강력한 디버깅**: 로깅, 타임트래블 내장
2. **명확한 분리**: Intent/Message/Label로 역할 명확
3. **플랫폼 독립**: 코어 모듈 의존성 없음
4. **Decompose 통합**: 같은 저자의 라이브러리와 완벽 호환
5. **WASM 지원**: 최신 플랫폼 지원

### 단점

1. **보일러플레이트**: Store 정의에 많은 코드 필요
2. **학습 곡선**: Intent/Action/Message/Label 구분 필요
3. **동시성 수동 관리**: takeLatest 같은 전략 없음
4. **글로벌 상태**: 별도 조합 필요

---

## Decompose와의 조합

MVIKotlin은 같은 저자의 Decompose와 함께 사용되는 경우가 많습니다:

```kotlin
class CounterComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory
) : ComponentContext by componentContext {

    private val store = CounterStoreFactory(storeFactory).create()

    val state: StateFlow<CounterState> = store.stateFlow

    fun onIntent(intent: CounterIntent) {
        store.accept(intent)
    }

    init {
        // 라이프사이클 연동
        lifecycle.doOnDestroy {
            store.dispose()
        }
    }
}
```

---

## 언제 사용하면 좋은가?

### MVIKotlin이 적합한 경우

- 타임트래블 디버깅이 필요한 경우
- Decompose와 함께 사용
- Intent/Message 분리로 명확한 경계 필요
- 의존성 최소화 원하는 경우

### flowdux가 적합한 경우

- 선언적 동시성 제어 (takeLatest, debounce)
- Middleware 패턴으로 횡단 관심사
- 간결한 API 선호
- 글로벌 상태 관리

---

## 참고 자료

- [MVIKotlin 공식 문서](https://arkivanov.github.io/MVIKotlin/)
- [GitHub Repository](https://github.com/arkivanov/MVIKotlin)
- [Store 문서](https://arkivanov.github.io/MVIKotlin/store.html)
- [MVIKotlin in Practice - Medium](https://medium.com/@mikhaltchenkov/mvikotlin-in-practice-a-modern-architecture-framework-for-android-and-kmp-ca68e58be94b)
- [Talking Kotlin Podcast - MVIKotlin and Decompose](https://talkingkotlin.com/mvikotlin-and-decompose-with-arkadii-ivanov/)
