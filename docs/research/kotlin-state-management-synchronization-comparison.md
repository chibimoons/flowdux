# Kotlin 기반 상태관리 라이브러리 동기화 전략 비교

## 1. 개요

Kotlin 기반의 주요 상태관리 라이브러리들이 동시성과 상태 동기화를 어떻게 처리하는지 조사하고 FlowDux와 비교 분석한다.

### 조사 대상 라이브러리
- Orbit MVI
- MVIKotlin
- Redux-Kotlin
- Ballast
- Uniflow-kt

---

## 2. 라이브러리별 동기화 전략

### 2.1 Orbit MVI

**GitHub:** https://github.com/orbit-mvi/orbit-mvi

**동기화 방식:** Channel 기반 순차 처리 + Atomic 연산

#### 핵심 구현 코드

```kotlin
// RealContainer.kt
class RealContainer<STATE : Any, SIDE_EFFECT : Any>(
    initialState: STATE,
    parentScope: CoroutineScope,
    settings: Container.Settings,
    onCreate: (Container<STATE, SIDE_EFFECT>) -> Unit
) : Container<STATE, SIDE_EFFECT> {

    // 1. Intent 큐잉을 위한 Channel (UNLIMITED = 무제한 버퍼)
    private val dispatchChannel = Channel<Pair<CompletableJob,
        suspend ContainerContext<STATE, SIDE_EFFECT>.() -> Unit>>(Channel.UNLIMITED)

    // 2. 상태 저장을 위한 MutableStateFlow
    private val internalStateFlow = MutableStateFlow(initialState)
    override val stateFlow: StateFlow<STATE> = internalStateFlow.asStateFlow()

    // 3. Side Effect 처리를 위한 Channel
    private val sideEffectChannel = Channel<SIDE_EFFECT>(settings.sideEffectBufferSize)

    // 4. Lock-free 동기화를 위한 Atomic 변수
    private val initialised = AtomicBoolean(false)
    private val intentCounter = AtomicInt(0)

    // 5. Job 기반 코루틴 조율
    private val intentJob = Job(scope.coroutineContext[Job])
}
```

#### 동기화 메커니즘

| 구성요소 | 역할 |
|---------|------|
| `Channel.UNLIMITED` | Intent를 순차적으로 큐잉하여 처리 순서 보장 |
| `MutableStateFlow` | Thread-safe 상태 저장소 |
| `AtomicBoolean/Int` | 초기화 상태, Intent 카운터 등 Lock-free 동기화 |
| `Job` hierarchy | 코루틴 취소 및 완료 추적 |

#### 특징
- 명시적 Mutex 사용 안함
- Channel의 FIFO 특성을 활용한 순차 처리
- Kotlin Coroutines의 구조적 동시성에 의존

---

### 2.2 MVIKotlin

**GitHub:** https://github.com/arkivanov/MVIKotlin

**동기화 방식:** Main Thread Confinement (메인 스레드 강제)

#### 핵심 구현 코드

```kotlin
// DefaultStore.kt
internal class DefaultStore<in Intent : Any, in Action : Any, in Message : Any, out State : Any, Label : Any>(
    initialState: State,
    private val bootstrapper: Bootstrapper<Action>?,
    executorFactory: () -> Executor<Intent, Action, State, Message, Label>,
    private val reducer: Reducer<State, Message>
) : Store<Intent, State, Label> {

    // 1. BehaviorSubject로 상태 관리
    private val stateSubject = BehaviorSubject(initialState)
    override val state: State get() = stateSubject.value

    // 2. 메인 스레드 강제 검증
    @MainThread
    override fun init() {
        assertOnMainThread()  // 메인 스레드 아니면 예외 발생
        // 초기화 로직...
    }

    @MainThread
    override fun accept(intent: Intent) {
        assertOnMainThread()  // 메인 스레드 아니면 예외 발생
        doIfNotDisposed {
            executor.handleIntent(intent)
        }
    }

    // 3. Reducer를 통한 순차적 상태 업데이트
    private fun onMessage(message: Message) {
        assertOnMainThread()
        doIfNotDisposed {
            val oldState = stateSubject.value
            val newState = oldState.reduce(message)  // 순차적 reduce
            stateSubject.onNext(newState)
        }
    }

    // 4. Dispose 상태 체크
    private inline fun doIfNotDisposed(block: () -> Unit) {
        if (!isDisposed) {
            block()
        }
    }
}
```

#### 동기화 메커니즘

| 구성요소 | 역할 |
|---------|------|
| `@MainThread` | 메인 스레드 실행 강제 어노테이션 |
| `assertOnMainThread()` | 런타임 스레드 검증 |
| `BehaviorSubject` | 현재 상태 유지 및 순차 업데이트 |

#### 특징
- **동기화를 하지 않음** - 단일 스레드 강제로 문제 자체를 회피
- 멀티스레드 접근은 프로그래밍 오류로 간주
- Android의 UI 스레드 모델과 자연스럽게 통합

---

### 2.3 Redux-Kotlin

**GitHub:** https://github.com/reduxkotlin/redux-kotlin

**동기화 방식:** 명시적 Synchronized Store (선택적)

#### 핵심 구현 코드

```kotlin
// Store.kt - 기본 Store (Thread-safe 아님)
typealias Store<State> = (action: Any) -> Any

fun <State> createStore(
    reducer: Reducer<State>,
    preloadedState: State,
    enhancer: StoreEnhancer<State>? = null
): Store<State> {
    // 기본 구현 - 동기화 없음
    var currentState = preloadedState

    return { action ->
        currentState = reducer(currentState, action)
        currentState
    }
}

// ThreadSafeStore.kt - Thread-safe 버전
fun <State> createThreadSafeStore(
    reducer: Reducer<State>,
    preloadedState: State,
    enhancer: StoreEnhancer<State>? = null
): Store<State> {
    val store = createStore(reducer, preloadedState, enhancer)

    // synchronized 블록으로 동기화
    val lock = Any()

    return { action ->
        synchronized(lock) {
            store(action)
        }
    }
}

// SynchronizedStoreEnhancer.kt - 미들웨어용
fun <State> createSynchronizedStoreEnhancer(): StoreEnhancer<State> = { createStore ->
    { reducer, preloadedState, enhancer ->
        val store = createStore(reducer, preloadedState, enhancer)
        val lock = Any()

        // dispatch와 getState 모두 동기화
        object : Store<State> {
            override fun dispatch(action: Any) = synchronized(lock) {
                store.dispatch(action)
            }
            override fun getState() = synchronized(lock) {
                store.getState()
            }
        }
    }
}
```

#### 동기화 메커니즘

| 구성요소 | 역할 |
|---------|------|
| `synchronized(lock)` | JVM의 명시적 동기화 |
| `createThreadSafeStore()` | Thread-safe Store 팩토리 |
| `SynchronizedStoreEnhancer` | 미들웨어 체인용 동기화 래퍼 |

#### 특징
- **유일하게 명시적 Lock 사용**
- Thread-safe / Non-thread-safe 버전 분리 제공
- 별도 아티팩트: `redux-kotlin-threadsafe`

---

### 2.4 Ballast

**GitHub:** https://github.com/copper-leaf/ballast

**동기화 방식:** Input Strategy 패턴 (FIFO/LIFO)

#### 핵심 구현 코드

```kotlin
// BallastViewModelImpl.kt (개념적 구조)
class BallastViewModelImpl<Inputs : Any, Events : Any, State : Any>(
    config: BallastViewModelConfiguration<Inputs, Events, State>
) : BallastViewModel<Inputs, Events, State> {

    // 1. Input Channel - 모든 입력이 이 채널을 통과
    private val inputChannel = Channel<Inputs>(Channel.UNLIMITED)

    // 2. State는 MutableStateFlow로 관리
    private val _state = MutableStateFlow(config.initialState)
    override val state: StateFlow<State> = _state.asStateFlow()

    init {
        // 3. Input Strategy에 따른 처리
        viewModelScope.launch {
            inputChannel
                .receiveAsFlow()
                .collect { input ->
                    processInput(input)  // 순차 처리
                }
        }
    }

    // 4. 상태 업데이트 - 단일 파이프라인
    suspend fun updateState(block: (State) -> State) {
        _state.update(block)  // MutableStateFlow.update 사용
    }
}

// InputStrategy.kt
sealed class InputStrategy {
    // FIFO: 입력 순서대로 순차 처리
    object Fifo : InputStrategy() {
        override fun <Inputs> createQueue(): InputQueue<Inputs> {
            return FifoInputQueue()
        }
    }

    // LIFO: 새 입력 시 이전 작업 취소
    object Lifo : InputStrategy() {
        override fun <Inputs> createQueue(): InputQueue<Inputs> {
            return LifoInputQueue()
        }
    }
}
```

#### 동기화 메커니즘

| 구성요소 | 역할 |
|---------|------|
| `Channel.UNLIMITED` | Input 큐잉 |
| `InputStrategy.FIFO` | 순차 처리 보장 (기본값) |
| `InputStrategy.LIFO` | 최신 입력 우선, 이전 취소 |
| `MutableStateFlow.update()` | 원자적 상태 업데이트 |

#### 특징
- 명시적 Mutex 없음
- **전략 패턴**으로 동시성 처리 방식 선택 가능
- Side-jobs로 비동기 작업 분리

---

### 2.5 Uniflow-kt

**GitHub:** https://github.com/uniflow-kt/uniflow-kt

**동기화 방식:** Action Dispatcher 패턴

#### 핵심 구현 코드

```kotlin
// DataFlow.kt
interface DataFlow {
    val actionDispatcher: ActionDispatcher
    val coroutineScope: CoroutineScope
    val dataPublisher: DataPublisher
}

// ActionDispatcher.kt (개념적 구조)
class ActionDispatcher(
    private val scope: CoroutineScope
) {
    // 1. Action Channel
    private val actionChannel = Channel<Action>(Channel.UNLIMITED)

    init {
        // 2. 순차적 Action 처리
        scope.launch {
            actionChannel.receiveAsFlow().collect { action ->
                processAction(action)
            }
        }
    }

    // 3. Action 디스패치
    fun dispatch(action: Action) {
        scope.launch {
            actionChannel.send(action)
        }
    }
}

// UIState management
class AndroidDataFlow : DataFlow, ViewModel() {
    private val _states = MutableStateFlow<UIState>(UIState.Empty)
    val states: StateFlow<UIState> = _states.asStateFlow()

    // 상태 업데이트
    suspend fun setState(state: UIState) {
        _states.value = state
    }
}
```

#### 동기화 메커니즘

| 구성요소 | 역할 |
|---------|------|
| `Channel.UNLIMITED` | Action 큐잉 |
| `ActionDispatcher` | 순차적 Action 처리 |
| `MutableStateFlow` | Thread-safe 상태 저장 |

#### 특징
- 단방향 데이터 플로우로 암묵적 순차 처리
- Coroutine 기반 동시성 관리
- 명시적 동기화 프리미티브 미사용

---

## 3. FlowDux 현재 구현

### 핵심 코드

```kotlin
// Store.kt
@OptIn(ExperimentalCoroutinesApi::class)
class Store<S : State, A : Action>(
    initialState: S,
    private val reducer: Reducer<S, A>,
    private val middlewares: List<Middleware<S, A>>,
    private val errorProcessor: ErrorProcessor<A>,
    private val logger: StoreLogger<S, A>,
    private val scope: CoroutineScope,
) {
    // 1. Action Channel
    private val actionFlow = Channel<A>()

    // 2. Flow 파이프라인으로 상태 관리
    private val stateFlow = actionFlow
        .receiveAsFlow()
        .flatMapMerge { processAction(it) }
        .map { reduceAction(state.value, it) }
        .stateIn(scope, SharingStarted.Eagerly, initialState)

    // 3. Mutex (현재 존재하지만 불필요)
    private val mutex = Mutex()

    // 4. Reduce with Mutex
    private suspend fun reduceAction(currentState: S, action: A): S {
        return mutex.withLock {
            val newState = reducer.reduce(currentState, action)
            logger.onStateReduced(action, currentState, newState)
            newState
        }
    }

    // 5. Action dispatch
    fun dispatch(action: A) {
        logger.onActionDispatched(action)
        scope.launch {
            actionFlow.send(action)
        }
    }
}
```

### 동기화 메커니즘

| 구성요소 | 역할 |
|---------|------|
| `Channel<A>()` | Action 큐잉 |
| `flatMapMerge` | Middleware 동시 처리 |
| `map` | 순차적 Reduce 실행 |
| `stateIn` | StateFlow 변환 |
| `Mutex` | (불필요하지만 존재) |

---

## 4. 비교 분석

### 4.1 동기화 방식 비교

| 라이브러리 | 주요 동기화 방식 | Mutex/Lock | Channel | StateFlow | 스레드 제한 |
|-----------|-----------------|------------|---------|-----------|------------|
| **Orbit MVI** | Channel + Atomic | ❌ | ✅ | ✅ | ❌ |
| **MVIKotlin** | Main Thread Only | ❌ | ❌ | ❌ | ✅ (Main) |
| **Redux-Kotlin** | synchronized | ✅ (선택) | ❌ | ❌ | ❌ |
| **Ballast** | Input Strategy | ❌ | ✅ | ✅ | ❌ |
| **Uniflow-kt** | Action Dispatcher | ❌ | ✅ | ✅ | ❌ |
| **FlowDux** | Flow 순차 실행 | ✅ (불필요) | ✅ | ✅ | ❌ |

### 4.2 설계 철학 비교

| 라이브러리 | 설계 철학 |
|-----------|----------|
| **Orbit MVI** | 구조적 동시성으로 암묵적 순차 처리 |
| **MVIKotlin** | 단일 스레드 강제로 동기화 문제 회피 |
| **Redux-Kotlin** | 명시적 동기화 옵션 제공 (개발자 선택) |
| **Ballast** | 전략 패턴으로 동시성 처리 방식 선택 |
| **Uniflow-kt** | 단방향 플로우로 자연스러운 순차 처리 |
| **FlowDux** | Flow의 Context Preservation 활용 |

### 4.3 공통점

1. **대부분 명시적 Mutex를 사용하지 않음**
   - Redux-Kotlin만 선택적으로 synchronized 제공

2. **Channel 기반 Action 큐잉이 주류**
   - Orbit, Ballast, Uniflow, FlowDux 모두 Channel 사용

3. **StateFlow/MutableStateFlow 활용**
   - Thread-safe한 상태 저장소로 널리 사용

4. **단방향 데이터 플로우**
   - 모든 라이브러리가 UDF 패턴 채택

---

## 5. FlowDux 개선 권장사항

### 5.1 현재 상태

FlowDux는 Flow의 순차 실행 특성에 의해 이미 thread-safe하므로 현재 Mutex는 불필요하다.

### 5.2 권장 옵션

#### Option A: Mutex 제거 (권장)

```kotlin
private val stateFlow = actionFlow
    .receiveAsFlow()
    .flatMapMerge { processAction(it) }
    .map { action ->
        val newState = reducer.reduce(state.value, action)
        logger.onStateReduced(action, state.value, newState)
        newState
    }
    .stateIn(scope, SharingStarted.Eagerly, initialState)
```

- 다른 라이브러리들과 동일한 패턴
- 불필요한 오버헤드 제거

#### Option B: scan 연산자 사용

```kotlin
private val stateFlow = actionFlow
    .receiveAsFlow()
    .flatMapMerge { processAction(it) }
    .scan(initialState) { currentState, action ->
        val newState = reducer.reduce(currentState, action)
        logger.onStateReduced(action, currentState, newState)
        newState
    }
    .stateIn(scope, SharingStarted.Eagerly, initialState)
```

- `state.value` 읽기 없이 이전 상태를 명시적으로 전달
- 의도가 더 명확함

#### Option C: 현재 유지 (방어적)

```kotlin
// 현재 코드 유지
private suspend fun reduceAction(currentState: S, action: A): S {
    return mutex.withLock {
        // ...
    }
}
```

- 향후 `flowOn` 추가 시 안전
- 방어적 프로그래밍 관점

---

## 6. 결론

| 항목 | 결론 |
|------|------|
| 업계 표준 | Channel + StateFlow 조합이 주류 |
| Mutex 사용 | 대부분 사용 안함 (Redux-Kotlin만 선택적 제공) |
| FlowDux 현황 | 업계 표준과 유사한 패턴, Mutex만 제거하면 동일 |
| 권장사항 | Mutex 제거 또는 scan 연산자 사용 |

---

## 7. 참고 자료

- [Orbit MVI GitHub](https://github.com/orbit-mvi/orbit-mvi)
- [MVIKotlin GitHub](https://github.com/arkivanov/MVIKotlin)
- [Redux-Kotlin GitHub](https://github.com/reduxkotlin/redux-kotlin)
- [Ballast GitHub](https://github.com/copper-leaf/ballast)
- [Ballast Mental Model](https://copper-leaf.github.io/ballast/wiki/usage/mental-model/)
- [Uniflow-kt GitHub](https://github.com/uniflow-kt/uniflow-kt)
- [Kotlin Flow Documentation](https://kotlinlang.org/docs/flow.html)

---

*문서 작성일: 2026-01-14*
