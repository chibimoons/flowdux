# Kotlin 상태 관리 라이브러리 종합 비교

*KMP/Android 생태계의 상태 관리 프레임워크 비교 분석*

---

## 라이브러리 분류

### 1. KMP MVI/UDF 프레임워크

| 라이브러리 | 철학 | 동시성 전략 | 디버깅 |
|-----------|------|------------|--------|
| **Orbit MVI** | MVVM+ (Container 패턴) | 없음 | 없음 |
| **MVIKotlin** | 확장 가능 MVI | 없음 | Logging, TimeTravel |
| **Ballast** | Opinionated MVI | InputStrategy (LIFO/FIFO) | Interceptor |
| **FlowMVI** | Plugin 시스템 | 없음 | Plugin 기반 |
| **Fluxo** | Redux + MVVM+ | 미정 (WIP) | Lambda 디버깅 (계획) |
| **FlowRedux** | 상태 머신 DSL | 없음 | 없음 |
| **flowdux** | Redux + 동시성 | ✅ Execution Strategy | Middleware |

### 2. Redux 스타일

| 라이브러리 | 타입 안전 | 비동기 | 플랫폼 | 상태 |
|-----------|----------|--------|--------|------|
| **ReduxKotlin** | `Any` 타입 | Thunk | 전체 KMP | 안정 |
| **ReKotlin** | Sealed class | Thunk | JVM/Android | 안정 |
| **Fluxo** | Sealed class | Handler | 전체 KMP | ⚠️ WIP |
| **flowdux** | Sealed class | Middleware + suspend | 전체 KMP | 안정 |

*Fluxo: WIP(Work-In-Progress) 상태로 첫 릴리즈 전. Redux + MVVM+ Lambda 결합 목표.*

### 3. Android/Compose 전용

| 라이브러리 | 핵심 기능 | KMP 지원 |
|-----------|----------|----------|
| **Mavericks** | Async<T>, execute | ❌ Android 전용 |
| **Molecule** | Compose → StateFlow | ✅ |

### 4. 네비게이션 + 상태

| 라이브러리 | 상태 관리 | 네비게이션 | 라이프사이클 |
|-----------|----------|-----------|-------------|
| **Decompose** | Value/MVIKotlin | ✅ Stack/Slot/Pages | ✅ |
| **PreCompose** | ViewModel | ✅ NavHost | ✅ |

---

## 핵심 기능 비교

### 동시성 제어

```
┌─────────────────────────────────────────────────────────────────────┐
│                      동시성 전략 지원 현황                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   flowdux        ████████████████████ 완전 지원                     │
│                  • takeLatest, takeLeading                           │
│                  • debounce, throttle                                │
│                  • Strategy Group (액션별, 그룹별)                   │
│                                                                      │
│   Ballast        ██████████░░░░░░░░░░ InputStrategy                 │
│                  • LIFO (≈takeLatest, 전역)                         │
│                  • FIFO (순차)                                       │
│                  • Parallel                                          │
│                                                                      │
│   FlowRedux      ██░░░░░░░░░░░░░░░░░░ 상태 머신                     │
│                  • 상태별 액션 허용/무시                             │
│                                                                      │
│   Others         ░░░░░░░░░░░░░░░░░░░░ 수동 구현 필요                │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### flowdux만의 차별점: Strategy Group

```kotlin
// flowdux: 액션 간 조율
group(takeLatest()) {
    on<SearchAction> { ... }    // SearchAction과
    on<RefreshAction> { ... }   // RefreshAction이 같은 취소 스코프 공유
}

// 다른 라이브러리: 불가능하거나 수동 구현 필요
```

---

## 아키텍처 패턴 비교

### MVI vs Redux vs 상태 머신

```
┌─────────────────────────────────────────────────────────────────────┐
│ MVI (Orbit, MVIKotlin, Ballast, FlowMVI)                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   View ──► Intent ──► [Handler/Executor] ──► State                  │
│                              │                                       │
│                              └──► Side Effect                        │
│                                                                      │
│   특징: Intent 내부에서 비동기 처리, 라이브러리마다 구조 다름        │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ Redux (ReduxKotlin, ReKotlin, flowdux)                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   View ──► Action ──► Middleware ──► Reducer ──► State              │
│                          │                                           │
│                          └── 비동기/사이드이펙트                     │
│                                                                      │
│   특징: Store + Middleware 체인, 글로벌/화면별 선택 가능             │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│ 상태 머신 (FlowRedux)                                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   inState<A> { on<Event> { → State B } }                            │
│   inState<B> { on<Event> { → State C } }                            │
│                                                                      │
│   특징: 상태에 따라 허용되는 액션이 다름, FSM                        │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### MVI 라이브러리별 내부 구조

MVI 라이브러리들은 "Middleware"를 사용하지 않고 각자의 방식으로 Intent를 처리합니다:

| 라이브러리 | 핵심 구조 | 비동기 처리 | 상태 변경 |
|-----------|----------|------------|----------|
| **Orbit** | Container + intent { } | intent 블록 내 suspend | reduce { } |
| **MVIKotlin** | Executor + Reducer | Executor에서 dispatch | Message → Reducer |
| **Ballast** | InputHandler | sideJob { } | updateState { } |
| **FlowMVI** | Store + Plugin | reduce 블록 내 suspend | updateState { } |
| **flowdux** | Middleware + Reducer | Middleware에서 emit | Action → Reducer |

```kotlin
// ═══════════════════════════════════════════════════════════════════
// flowdux (Redux) - Middleware가 비동기 처리, Action을 emit
// ═══════════════════════════════════════════════════════════════════
on<LoadUser>(takeLatest()) { state, action ->
    emit(SetLoading(true))           // Action emit
    val user = api.fetchUser(action.id)
    emit(SetUser(user))              // Action emit → Reducer → State
}

// ═══════════════════════════════════════════════════════════════════
// Orbit MVI - Container의 intent 블록에서 직접 reduce
// ═══════════════════════════════════════════════════════════════════
fun loadUser(id: String) = intent {
    reduce { state.copy(isLoading = true) }  // 직접 상태 변경
    val user = api.fetchUser(id)
    reduce { state.copy(user = user) }       // 직접 상태 변경
}

// ═══════════════════════════════════════════════════════════════════
// MVIKotlin - Executor가 Message를 dispatch, Reducer가 상태 변경
// ═══════════════════════════════════════════════════════════════════
// Executor
override fun executeIntent(intent: Intent) {
    when (intent) {
        is LoadUser -> {
            dispatch(Message.SetLoading(true))  // Message dispatch
            scope.launch {
                val user = api.fetchUser(intent.id)
                dispatch(Message.UserLoaded(user))
            }
        }
    }
}
// Reducer
override fun reduce(state: State, msg: Message): State = when (msg) {
    is SetLoading -> state.copy(isLoading = msg.value)
    is UserLoaded -> state.copy(user = msg.user)
}

// ═══════════════════════════════════════════════════════════════════
// Ballast - InputHandler에서 updateState + sideJob
// ═══════════════════════════════════════════════════════════════════
override suspend fun InputHandlerScope.handleInput(input: Input) {
    when (input) {
        is LoadUser -> {
            updateState { it.copy(isLoading = true) }  // 직접 상태 변경
            sideJob("loadUser") {
                val user = api.fetchUser(input.id)
                postInput(Input.UserLoaded(user))      // 새 Input 발행
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// FlowMVI - reduce 블록에서 직접 상태 변경
// ═══════════════════════════════════════════════════════════════════
reduce { intent ->
    when (intent) {
        is LoadUser -> {
            updateState { copy(isLoading = true) }  // 직접 상태 변경
            val user = api.fetchUser(intent.id)
            updateState { copy(user = user) }       // 직접 상태 변경
        }
    }
}
```

### Redux vs MVI 핵심 차이

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Redux (flowdux)                                   │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   비동기 처리: Middleware (외부, 분리됨)                             │
│   상태 변경:   Action → Reducer (간접적)                             │
│   추적성:      모든 Action 로깅 가능                                 │
│                                                                      │
│   LoadUser → [Middleware] → SetLoading Action → Reducer → State     │
│                    ↓                                                 │
│               API 호출                                               │
│                    ↓                                                 │
│              SetUser Action → Reducer → State                        │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                    MVI (Orbit, FlowMVI 등)                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   비동기 처리: Intent/Handler 내부 (통합됨)                          │
│   상태 변경:   reduce/updateState (직접적)                           │
│   추적성:      Intent만 추적, 중간 상태 변경은 암묵적                │
│                                                                      │
│   LoadUser Intent → intent {                                        │
│                        reduce { loading = true }  ← 직접 변경       │
│                        api.fetchUser()                               │
│                        reduce { user = ... }      ← 직접 변경       │
│                     }                                                │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 상세 기능 비교표

> **Note**: Fluxo는 WIP 상태로 상세 비교에서 제외. 출시 후 추가 예정.

### 핵심 기능 - MVI/Redux 프레임워크

| 기능 | flowdux | Orbit | MVIKotlin | Ballast | FlowMVI | FlowRedux |
|------|---------|-------|-----------|---------|---------|-----------|
| **takeLatest** | ✅ | ❌ | ❌ | LIFO* | ❌ | ❌ |
| **debounce** | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **throttle** | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **takeLeading** | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **Strategy Group** | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **외부 Flow 통합** | ✅ FlowHolderAction | 수동 collect | 수동 collect | sideJob | 수동 collect | 수동 collect |
| **TimeTravel** | ✅ (별도 모듈) | ❌ | ✅ | Undo | ❌ | ❌ |
| **Plugin System** | Middleware | ❌ | StoreFactory | Interceptor | ✅ | ❌ |
| **Error Handling** | ErrorProcessor | 수동 | 수동 | Interceptor | recover + Plugin | 수동 |
| **Side Effect** | Action | sideEffect | Label | Event | Action | DSL |

*LIFO: 전역 전략, 액션별 설정 불가

### 핵심 기능 - Redux 스타일 & 기타

| 기능 | flowdux | ReduxKotlin | ReKotlin | Mavericks | Molecule |
|------|---------|-------------|----------|-----------|----------|
| **takeLatest** | ✅ | ❌ | ❌ | ❌ | ❌ |
| **debounce** | ✅ | ❌ | ❌ | ❌ | ❌ |
| **throttle** | ✅ | ❌ | ❌ | ❌ | ❌ |
| **takeLeading** | ✅ | ❌ | ❌ | ❌ | ❌ |
| **외부 Flow 통합** | ✅ FlowHolderAction | Thunk (수동) | Thunk (수동) | 수동 collect | LaunchedEffect |
| **TimeTravel** | ✅ (별도 모듈) | ❌ | ❌ | ❌ | ❌ |
| **Async 타입** | ❌ | ❌ | ❌ | ✅ Async<T> | ❌ |
| **Compose 런타임** | ❌ | ❌ | ❌ | ❌ | ✅ |
| **타입 안전** | Sealed class | Any 타입 | Sealed class | data class | Composable |

### 플랫폼 지원 - 전체

| | flowdux | Orbit | MVIKotlin | Ballast | FlowMVI | FlowRedux |
|---|---------|-------|-----------|---------|---------|-----------|
| **Android** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **iOS** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Desktop** | ✅ (JVM) | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Web (JS)** | ✅ | ❌ | ✅ | ❌ | ✅ | ❌ |
| **WASM** | ✅ | ❌ | ✅ | ✅ | ✅ | ❌ |

| | ReduxKotlin | ReKotlin | Mavericks | Molecule | Decompose | PreCompose |
|---|-------------|----------|-----------|----------|-----------|------------|
| **Android** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **iOS** | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ |
| **Desktop** | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ |
| **Web (JS)** | ✅ | ❌ | ❌ | ✅ | ✅ | ✅ |
| **WASM** | ✅ | ❌ | ❌ | ✅ | ✅ | ❌ |

### 생태계 및 통합

| | flowdux | Orbit | MVIKotlin | Ballast | FlowMVI | Mavericks |
|---|---------|-------|-----------|---------|---------|-----------|
| **ViewModel 통합** | 별도 | ✅ 네이티브 | 별도 | 별도 | 별도 | ✅ 네이티브 |
| **네비게이션** | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ |
| **Compose 지원** | collectAsState | ✅ | stateFlow | ✅ | ✅ | ✅ |
| **SavedState** | 수동 | ❌ | StateKeeper | ✅ | ✅ | ✅ @PersistState |

| | ReduxKotlin | ReKotlin | FlowRedux | Molecule | Decompose | PreCompose |
|---|-------------|----------|-----------|----------|-----------|------------|
| **ViewModel 통합** | 별도 | 별도 | 별도 | 별도 | 별도 | ✅ 네이티브 |
| **네비게이션** | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |
| **Compose 지원** | 별도 | 별도 | ✅ | ✅ 네이티브 | subscribeAsState | ✅ |
| **SavedState** | 수동 | 수동 | 수동 | 수동 | ✅ StateKeeper | ✅ |

### 테스트 지원 - MVI/Redux 프레임워크

| | flowdux | Orbit | MVIKotlin | Ballast | FlowMVI | FlowRedux |
|---|---------|-------|-----------|---------|---------|-----------|
| **테스트 DSL** | 외부 (Turbine) | orbit-test | 내장 TestStore | 내장 | 내장 | 외부 (Turbine) |
| **상태 검증** | StateFlow 구독 | ContainerHost | TestStore | TestEventHandler | TestStore | StateFlow 구독 |
| **Action 검증** | Middleware 로깅 | ❌ | Label 확인 | Event 확인 | Plugin 훅 | Effect 확인 |
| **코루틴 테스트** | runTest | runTest | TestCoroutineScope | runTest | runTest | runTest |

### 테스트 지원 - Redux 스타일 & 기타

| | ReduxKotlin | ReKotlin | Mavericks | Molecule | Decompose | PreCompose |
|---|-------------|----------|-----------|----------|-----------|------------|
| **테스트 DSL** | 없음 | 없음 | MavericksTestRule | 외부 (Turbine) | 없음 | 없음 |
| **상태 검증** | store.state | store.state | withState 블록 | StateFlow 구독 | Value 구독 | StateFlow 구독 |
| **Action 검증** | Middleware | Middleware | ❌ | ❌ | ❌ | ❌ |
| **코루틴 테스트** | 수동 | 수동 | runTest | runTest | runTest | runTest |

```kotlin
// flowdux 테스트 (Turbine 사용)
@Test
fun `increment action updates count`() = runTest {
    val store = createStore()
    store.state.test {
        assertEquals(0, awaitItem().count)
        store.dispatch(Increment)
        assertEquals(1, awaitItem().count)
    }
}

// Orbit 테스트
@Test
fun `increment updates state`() = runTest {
    val viewModel = CounterViewModel().test(this)
    viewModel.testIntent { increment() }
    viewModel.assert(CounterState(count = 1))
}

// MVIKotlin 테스트
@Test
fun `increment test`() {
    val store = TestStore(initialState, reducer)
    store.accept(Increment)
    assertEquals(1, store.state.count)
}
```

### 상태 저장/복원 - MVI/Redux 프레임워크

| | flowdux | Orbit | MVIKotlin | Ballast | FlowMVI | FlowRedux |
|---|---------|-------|-----------|---------|---------|-----------|
| **SavedState** | 수동 | ❌ | StateKeeper | SavedStateAdapter | savedStatePlugin | 수동 |
| **프로세스 복원** | 수동 | ❌ | ✅ (Decompose) | ✅ | ✅ | 수동 |
| **Parcelable** | 수동 | ❌ | ✅ | ✅ | ✅ | 수동 |
| **ViewModel 연동** | 수동 | 네이티브 | 별도 | 별도 | 별도 | 별도 |

### 상태 저장/복원 - Redux 스타일 & 기타

| | ReduxKotlin | ReKotlin | Mavericks | Molecule | Decompose | PreCompose |
|---|-------------|----------|-----------|----------|-----------|------------|
| **SavedState** | 수동 | 수동 | 네이티브 지원 | 수동 | ✅ StateKeeper | ✅ SavedStateHolder |
| **프로세스 복원** | 수동 | 수동 | ✅ | 수동 | ✅ | ✅ |
| **Parcelable** | 수동 | 수동 | ✅ (@PersistState) | 수동 | ✅ | ✅ |
| **ViewModel 연동** | 수동 | 수동 | 네이티브 | 수동 | ComponentContext | 네이티브 |

```kotlin
// Mavericks: 자동 상태 복원
data class UserState(
    @PersistState val userId: String = "",  // 프로세스 복원 시 유지
    val user: Async<User> = Uninitialized   // 복원 안함
) : MavericksState

// FlowMVI: savedStatePlugin
store {
    install(savedStatePlugin(
        saver = { Json.encodeToString(it) },
        restore = { Json.decodeFromString(it) }
    ))
}

// Ballast: SavedStateAdapter
val vm = BasicViewModel(
    config = BallastViewModelConfiguration.Builder()
        .apply { savedStateAdapter = AndroidSavedStateAdapter(savedStateHandle) }
        .build()
)
```

### 스레드 안전성 - MVI/Redux 프레임워크

| | flowdux | Orbit | MVIKotlin | Ballast | FlowMVI | FlowRedux |
|---|---------|-------|-----------|---------|---------|-----------|
| **자동 동기화** | ✅ (Channel) | ✅ | ✅ (Executor) | ✅ (Channel) | ✅ | ✅ (coroutine) |
| **Dispatcher** | 설정 가능 | Main | 설정 가능 | 설정 가능 | 설정 가능 | 설정 가능 |
| **상태 충돌 방지** | 순차 Reducer | 순차 reduce | 순차 Message | 순차 Input | 순차 처리 | 순차 Reducer |
| **동시 Side Effect** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

### 스레드 안전성 - Redux 스타일 & 기타

| | ReduxKotlin | ReKotlin | Mavericks | Molecule | Decompose | PreCompose |
|---|-------------|----------|-----------|----------|-----------|------------|
| **자동 동기화** | 옵션 (ThreadSafe) | ❌ 수동 | ✅ (백그라운드) | ✅ (Compose) | ✅ | ✅ |
| **Dispatcher** | 설정 가능 | 수동 | 내부 관리 | 설정 가능 | 설정 가능 | Main |
| **상태 충돌 방지** | ThreadSafeStore | 수동 동기화 | 순차 setState | Compose 스냅샷 | 순차 | 순차 |
| **동시 Side Effect** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

### 상태 범위 - MVI/Redux 프레임워크

| | flowdux | Orbit | MVIKotlin | Ballast | FlowMVI | FlowRedux |
|---|---------|-------|-----------|---------|---------|-----------|
| **상태 범위** | 자유 선택 | 화면별 권장 | 화면별 권장 | 화면별 권장 | 화면별 권장 | 화면별 권장 |
| **글로벌 Store** | scope로 결정 | DI로 공유 | DI로 공유 | DI로 공유 | DI로 공유 | DI로 공유 |
| **화면별 Store** | scope로 결정 | 네이티브 | 네이티브 | 네이티브 | 네이티브 | 네이티브 |
| **라이프사이클** | scope 주입 | ContainerHost | Lifecycle | ViewModel | Store | scope 주입 |

### 상태 범위 - Redux 스타일 & 기타

| | ReduxKotlin | ReKotlin | Mavericks | Molecule | Decompose | PreCompose |
|---|-------------|----------|-----------|----------|-----------|------------|
| **상태 범위** | 자유 선택 | 자유 선택 | 화면별 권장 | 화면별 권장 | 화면별 권장 | 화면별 권장 |
| **글로벌 Store** | 싱글톤 | 싱글톤 | activityViewModel | DI로 공유 | DI로 공유 | DI로 공유 |
| **화면별 Store** | 수동 | 수동 | 네이티브 | 네이티브 | Component | ViewModel |
| **라이프사이클** | 수동 | 수동 | ViewModel | scope 주입 | ComponentContext | ViewModel |

**모든 라이브러리는 글로벌/화면별 Store 모두 가능**. 차이점은 기본 권장 패턴과 라이프사이클 연결 방식.

```kotlin
// flowdux: scope 파라미터로 라이프사이클 결정

// 1. 화면별 Store (ViewModel 라이프사이클)
class CounterViewModel : ViewModel() {
    private val store = createStore(
        initialState = CounterState(),
        reducer = counterReducer,
        scope = viewModelScope,  // ViewModel과 함께 종료
    )
}

// 2. 글로벌 Store (Application 라이프사이클)
object AppStoreHolder {
    val store = createStore(
        initialState = AppState(),
        reducer = appReducer,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
}

// 3. 화면에서 글로벌 Store 사용
@Composable
fun UserScreen() {
    val state by AppStoreHolder.store.state.collectAsState()
    // ...
}
```

```
┌─────────────────────────────────────────────────────────────────────┐
│                    라이프사이클에 따른 Store 범위                     │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   Application Scope ─────────────────────────────────────────────   │
│   │                                                                  │
│   │  ┌─────────────────────────────────────────────────────────┐   │
│   │  │              Global Store (공유 상태)                    │   │
│   │  │  User, Auth, Settings, Cart ...                         │   │
│   │  └─────────────────────────────────────────────────────────┘   │
│   │                                                                  │
│   └──► viewModelScope A ──► viewModelScope B ──► viewModelScope C   │
│        ┌──────────┐         ┌──────────┐         ┌──────────┐      │
│        │ Screen A │         │ Screen B │         │ Screen C │      │
│        │  Store   │         │  Store   │         │  Store   │      │
│        └──────────┘         └──────────┘         └──────────┘      │
│        (화면 전용 상태)       (화면 전용 상태)       (화면 전용 상태)  │
│                                                                      │
│   flowdux: scope 파라미터로 어느 레벨이든 자유롭게 선택              │
│   다른 MVI: 기본 화면별, 글로벌은 DI로 공유                          │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 구독 및 상태 전달 - MVI/Redux 프레임워크

| | flowdux | Orbit | MVIKotlin | Ballast | FlowMVI | FlowRedux |
|---|---------|-------|-----------|---------|---------|-----------|
| **상태 타입** | StateFlow | StateFlow | Value/StateFlow | StateFlow | StateFlow | StateFlow |
| **Hot/Cold** | Hot | Hot | Hot | Hot | Hot | Hot |
| **초기값** | ✅ 필수 | ✅ 필수 | ✅ 필수 | ✅ 필수 | ✅ 필수 | ✅ 필수 |
| **부분 구독** | Flow.map (수동) | ❌ | ❌ | ❌ | ❌ | ❌ |
| **distinctUntilChanged** | 자동 (StateFlow) | 자동 (StateFlow) | 자동 | 자동 (StateFlow) | 자동 (StateFlow) | 자동 (StateFlow) |

### 구독 및 상태 전달 - Redux 스타일 & 기타

| | ReduxKotlin | ReKotlin | Mavericks | Molecule | Decompose | PreCompose |
|---|-------------|----------|-----------|----------|-----------|------------|
| **상태 타입** | 콜백 (subscribe) | 콜백 (subscribe) | StateFlow | StateFlow | Value | StateFlow |
| **Hot/Cold** | Hot | Hot | Hot | Hot | Hot | Hot |
| **초기값** | ✅ 필수 | ✅ 필수 | ✅ 필수 | ✅ 필수 | ✅ 필수 | ✅ 필수 |
| **부분 구독** | ❌ | ❌ | select { } | ❌ | ❌ | ❌ |
| **distinctUntilChanged** | 수동 | 수동 | 자동 (StateFlow) | 자동 (StateFlow) | 자동 | 자동 (StateFlow) |

### 상태 불변성 - MVI/Redux 프레임워크

| | flowdux | Orbit | MVIKotlin | Ballast | FlowMVI | FlowRedux |
|---|---------|-------|-----------|---------|---------|-----------|
| **불변성 강제** | data class 권장 | data class 권장 | data class 권장 | data class 권장 | data class 권장 | data class 권장 |
| **copy() 사용** | Reducer에서 | reduce에서 | Reducer에서 | updateState에서 | updateState에서 | mutate에서 |
| **Mutable 방지** | 컨벤션 | 컨벤션 | 컨벤션 | 컨벤션 | 컨벤션 | 컨벤션 |

### 상태 불변성 - Redux 스타일 & 기타

| | ReduxKotlin | ReKotlin | Mavericks | Molecule | Decompose | PreCompose |
|---|-------------|----------|-----------|----------|-----------|------------|
| **불변성 강제** | data class 권장 | data class 권장 | data class 필수 | mutableStateOf | data class 권장 | data class 권장 |
| **copy() 사용** | Reducer에서 | Reducer에서 | setState에서 | 직접 할당 | Reducer에서 | copy/emit |
| **Mutable 방지** | 컨벤션 | 컨벤션 | 런타임 체크 | Compose 스냅샷 | 컨벤션 | 컨벤션 |

### 의존성 및 크기 - MVI/Redux 프레임워크

| | flowdux | Orbit | MVIKotlin | Ballast | FlowMVI | FlowRedux |
|---|---------|-------|-----------|---------|---------|-----------|
| **코어 의존성** | Coroutines | Coroutines | 없음 | Coroutines | 없음 (코어) | Coroutines |
| **라이브러리 크기** | 경량 | 경량 | 경량 | 중간 | 모듈화 | 경량 |
| **모듈 구조** | 단일 | Core + Test | Core + Extensions | 단일 | 세분화 | Core + Compose |

### 의존성 및 크기 - Redux 스타일 & 기타

| | ReduxKotlin | ReKotlin | Mavericks | Molecule | Decompose | PreCompose |
|---|-------------|----------|-----------|----------|-----------|------------|
| **코어 의존성** | 없음 | 없음 | AndroidX | Compose Runtime | 없음 (코어) | Compose |
| **라이브러리 크기** | 경량 | 경량 | 중간 | 경량 | 중간 | 중간 |
| **모듈 구조** | Core + Thunk | 단일 | Core + Compose | 단일 | 세분화 | 단일 |

### 학습 곡선 - MVI/Redux 프레임워크

| | flowdux | Orbit | MVIKotlin | Ballast | FlowMVI | FlowRedux |
|---|---------|-------|-----------|---------|---------|-----------|
| **API 복잡도** | 중간 | 낮음 | 높음 | 중간 | 중간 | 중간 |
| **개념 학습** | Redux 필요 | MVI 기본 | MVI 심화 | MVI 기본 | MVI 기본 | 상태 머신 |
| **문서화** | 기본 | ✅ 상세 | ✅ 상세 | ✅ 상세 | ✅ 상세 | ✅ 상세 |
| **예제 코드** | 기본 | ✅ 풍부 | ✅ 풍부 | ✅ 풍부 | ✅ 풍부 | ✅ 풍부 |

### 학습 곡선 - Redux 스타일 & 기타

| | ReduxKotlin | ReKotlin | Mavericks | Molecule | Decompose | PreCompose |
|---|-------------|----------|-----------|----------|-----------|------------|
| **API 복잡도** | 중간 | 중간 | 낮음 | 낮음 | 높음 | 중간 |
| **개념 학습** | Redux 필요 | Redux 필요 | MVVM 기반 | Compose 필요 | 컴포넌트 기반 | MVVM 기반 |
| **문서화** | 기본 | 기본 | ✅ 상세 | ✅ 상세 | ✅ 상세 | ✅ 상세 |
| **예제 코드** | 기본 | 기본 | ✅ 풍부 | ✅ 풍부 | ✅ 풍부 | ✅ 풍부 |

### IDE 지원 - MVI/Redux 프레임워크

| | flowdux | Orbit | MVIKotlin | Ballast | FlowMVI | FlowRedux |
|---|---------|-------|-----------|---------|---------|-----------|
| **IDE 플러그인** | ❌ | ❌ | ❌ | ❌ | ✅ JetBrains | ❌ |
| **디버거 앱** | ❌ | ❌ | ✅ (TimeTravel) | ❌ | ✅ Desktop | ❌ |
| **코드 생성** | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |

### IDE 지원 - Redux 스타일 & 기타

| | ReduxKotlin | ReKotlin | Mavericks | Molecule | Decompose | PreCompose |
|---|-------------|----------|-----------|----------|-----------|------------|
| **IDE 플러그인** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **디버거 앱** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| **코드 생성** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |

### 커뮤니티 및 유지보수 - MVI/Redux 프레임워크

| | flowdux | Orbit | MVIKotlin | Ballast | FlowMVI | FlowRedux |
|---|---------|-------|-----------|---------|---------|-----------|
| **GitHub Stars** | - | 2k+ | 1.5k+ | 500+ | 800+ | 600+ |
| **유지보수** | 활발 | 활발 | 활발 | 활발 | 활발 | 활발 |
| **기업 후원** | - | Babylon | JetBrains (GDE) | Copper Leaf | Respawn | freeletics |
| **프로덕션 사례** | - | 다수 | 다수 | 중간 | 중간 | 다수 |

### 커뮤니티 및 유지보수 - Redux 스타일 & 기타

| | ReduxKotlin | ReKotlin | Mavericks | Molecule | Decompose | PreCompose |
|---|-------------|----------|-----------|----------|-----------|------------|
| **GitHub Stars** | 600+ | 400+ | 5k+ | 2k+ | 3k+ | 1k+ |
| **유지보수** | 활발 | 느림 | 활발 | 활발 | 활발 | 활발 |
| **기업 후원** | - | - | Airbnb | Cash App | JetBrains (GDE) | - |
| **프로덕션 사례** | 중간 | 중간 | Airbnb 전체 | Cash App | 다수 | 중간 |

---

## 상태 업데이트 방식 비교

### 코드 스타일

```kotlin
// flowdux: Reducer + Middleware
val reducer = buildReducer<CounterState, CounterAction> {
    on<Increment> { state, _ -> state.copy(count = state.count + 1) }
}

// 또는 fun interface 직접 사용
val reducer = Reducer<CounterState, CounterAction> { state, action ->
    when (action) {
        is Increment -> state.copy(count = state.count + 1)
        else -> state
    }
}

on<LoadAction>(takeLatest()) { state, action ->
    emit(SetLoading(true))
    val data = api.fetch()
    emit(SetData(data))
}

// Orbit MVI: intent + reduce
fun increment() = intent {
    reduce { state.copy(count = state.count + 1) }
}

// MVIKotlin: Executor + Reducer
override fun executeIntent(intent: Intent) {
    when (intent) {
        Increment -> dispatch(Message.Incremented)
    }
}

// Ballast: InputHandler
override suspend fun handleInput(input: Input) = when (input) {
    Increment -> updateState { it.copy(count = it.count + 1) }
}

// FlowRedux: 상태 머신 DSL
inState<CounterState> {
    on<Increment> { _, state -> state.mutate { copy(count = count + 1) } }
}

// Mavericks: setState
fun increment() {
    setState { copy(count = count + 1) }
}

// Molecule: 직접 수정
@Composable
fun Presenter(): Model {
    var count by remember { mutableStateOf(0) }
    return Model(count = count, onIncrement = { count++ })
}
```

---

## 비동기 처리 비교

```kotlin
// flowdux: Middleware processor (suspend)
on<LoadUser>(takeLatest()) { state, action ->
    emit(SetLoading(true))
    try {
        val user = api.fetchUser(action.id)
        emit(SetUser(user))
    } catch (e: Exception) {
        emit(SetError(e.message))
    }
}

// Orbit: intent 내 suspend
fun loadUser(id: String) = intent {
    reduce { state.copy(isLoading = true) }
    val user = api.fetchUser(id)
    reduce { state.copy(user = user, isLoading = false) }
}

// MVIKotlin: CoroutineExecutor
scope.launch {
    val user = api.fetchUser(id)
    dispatch(Message.UserLoaded(user))
}

// Ballast: SideJob
sideJob("loadUser") {
    val user = api.fetchUser(id)
    postInput(Input.UserLoaded(user))
}

// Mavericks: execute
suspend { api.fetchUser(id) }.execute { copy(user = it) }

// ReduxKotlin: Thunk
fun loadUser(id: String): Thunk<State> = { dispatch, _, _ ->
    dispatch(SetLoading(true))
    val user = api.fetchUser(id)
    dispatch(SetUser(user))
}
```

---

## 외부 Flow 통합 비교

### 문제: Repository Flow를 Store에 연결하기

모든 상태 관리 라이브러리가 직면하는 공통 과제입니다:

```kotlin
// Repository가 Flow를 반환
class UserRepository {
    fun observeUser(id: String): Flow<User> =
        database.userDao().observeById(id)
            .combine(api.getUserUpdates(id)) { local, remote -> remote ?: local }
}
```

이 Flow 스트림을 어떻게 Store/State에 연결할 것인가?

### 다른 라이브러리들의 접근법

```kotlin
// ═══════════════════════════════════════════════════════════════════
// Orbit MVI - repeatOnSubscription 내에서 수동 collect
// ═══════════════════════════════════════════════════════════════════
fun observeUser(id: String) = intent {
    repeatOnSubscription {
        repository.observeUser(id).collect { user ->
            reduce { state.copy(user = user) }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// MVIKotlin - Executor에서 수동 launch + collect
// ═══════════════════════════════════════════════════════════════════
override fun executeIntent(intent: Intent) {
    when (intent) {
        is ObserveUser -> scope.launch {
            repository.observeUser(intent.id).collect { user ->
                dispatch(Message.UserLoaded(user))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Ballast - sideJob 내에서 수동 collect
// ═══════════════════════════════════════════════════════════════════
override suspend fun InputHandlerScope.handleInput(input: Input) {
    when (input) {
        is ObserveUser -> sideJob("observeUser") {
            repository.observeUser(input.id).collect { user ->
                postInput(Input.UserLoaded(user))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// FlowMVI - launchForState 내에서 수동 collect
// ═══════════════════════════════════════════════════════════════════
reduce { intent ->
    when (intent) {
        is ObserveUser -> launchForState {
            repository.observeUser(intent.id).collect { user ->
                updateState { copy(user = user) }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// ReduxKotlin (Thunk) - Thunk 내에서 수동 collect
// ═══════════════════════════════════════════════════════════════════
fun observeUser(id: String): Thunk<State> = { dispatch, _, _ ->
    repository.observeUser(id).collect { user ->
        dispatch(SetUser(user))
    }
}
```

### flowdux FlowHolderAction - 선언적 접근

```kotlin
// ═══════════════════════════════════════════════════════════════════
// flowdux - FlowHolderAction으로 선언적 변환
// ═══════════════════════════════════════════════════════════════════
interface FlowHolderAction : Action {
    fun toFlowAction(): Flow<Action>
}

// Action 정의
data class ObserveUser(
    private val userFlow: Flow<User>  // Flow 주입 (사이드이펙트 없음)
) : UserAction, FlowHolderAction {
    override fun toFlowAction(): Flow<Action> = userFlow
        .map { UserLoaded(it) }
        .onStart { emit(UserLoading) }      // 로딩 상태
        .catch { emit(UserError(it.message)) }  // 에러 처리
}

// 사용
val flow = repository.observeUser(id)
store.dispatch(ObserveUser(flow))  // Store가 자동 구독/관리
```

### 비교표

| 측면 | 다른 라이브러리 | flowdux FlowHolderAction |
|------|----------------|--------------------------|
| **Flow 수집** | 수동 (`.collect { }`) | 자동 (Store가 관리) |
| **생명주기** | 수동 관리 | Store scope에 자동 연결 |
| **변환 정의** | 명령형 (collect 내부) | 선언적 (toFlowAction) |
| **병렬 처리** | 수동 (여러 launch) | 자동 (flatMapMerge) |
| **테스트** | Mock repository 필요 | Flow 직접 주입 가능 |
| **로딩/에러** | 별도 처리 | onStart/catch로 통합 |
| **보일러플레이트** | 높음 (collect 패턴 반복) | 낮음 (선언적 변환) |

### FlowHolderAction의 장점

#### 1. Flow 통합이 일급 시민

다른 라이브러리에서는 Flow 수집이 "부수적인" 작업이지만, flowdux에서는 `FlowHolderAction`이라는 명시적인 개념으로 다룹니다.

```
┌─────────────────────────────────────────────────────────────────────┐
│                    FlowHolderAction 처리 흐름                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   dispatch(ObserveUser(repositoryFlow))                             │
│         │                                                            │
│         ▼                                                            │
│   ┌─────────────────────────────────────────┐                       │
│   │        Middleware Chain                  │                       │
│   │   (flatMapConcat - 순차적)               │                       │
│   └─────────────────────────────────────────┘                       │
│         │                                                            │
│         ▼                                                            │
│   ┌─────────────────────────────────────────┐                       │
│   │     FlowHolderAction 체크               │                       │
│   │   (flatMapMerge - 병렬)                 │                       │
│   │                                         │                       │
│   │   if (action is FlowHolderAction)       │                       │
│   │       action.toFlowAction()  ◄── 자동 구독                      │
│   │   else                                  │                       │
│   │       flowOf(action)                    │                       │
│   └─────────────────────────────────────────┘                       │
│         │                                                            │
│         ▼ (시간에 걸쳐 여러 방출)                                    │
│   ┌─────────────────────────────────────────┐                       │
│   │  UserLoading                            │                       │
│   │  UserLoaded(user1)                      │                       │
│   │  UserLoaded(user2)  ◄── 실시간 업데이트                         │
│   │  ...                                    │                       │
│   └─────────────────────────────────────────┘                       │
│         │                                                            │
│         ▼                                                            │
│      Reducer → State → UI                                           │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

#### 2. 테스트 용이성

```kotlin
// 다른 라이브러리: Repository mock 필요
@Test
fun `user loading test`() = runTest {
    val mockRepo = mockk<UserRepository>()
    every { mockRepo.observeUser(any()) } returns flowOf(testUser)
    // ... ViewModel/Store 생성 ...
}

// flowdux: Flow 직접 주입
@Test
fun `user loading test`() = runTest {
    val testFlow = flowOf(testUser)  // Mock 불필요
    store.dispatch(ObserveUser(testFlow))
    // 상태 검증
}
```

#### 3. 선언적 + 응집력

```kotlin
// 로딩, 성공, 에러 상태를 하나의 Action에서 모두 정의
data class ObserveProduct(
    private val productFlow: Flow<Product>
) : ProductAction, FlowHolderAction {
    override fun toFlowAction(): Flow<Action> = productFlow
        .map<Product, ProductAction> { ProductLoaded(it) }
        .onStart { emit(ProductLoading) }
        .catch { emit(ProductError(it.message ?: "Unknown")) }
}
```

#### 4. 다중 스트림 자동 병렬 처리

```kotlin
// 두 FlowHolderAction이 자동으로 병렬 수집
store.dispatch(ObserveUser(userFlow))
store.dispatch(ObserveNotifications(notificationFlow))
// flatMapMerge로 자연스럽게 인터리브됨 - 수동 launch 불필요

// 또는 하나의 Action에서 여러 Flow 병합
data class ObserveDashboard(
    private val userFlow: Flow<User>,
    private val statsFlow: Flow<Stats>,
    private val alertsFlow: Flow<List<Alert>>
) : DashboardAction, FlowHolderAction {
    override fun toFlowAction(): Flow<Action> = merge(
        userFlow.map { UserUpdated(it) },
        statsFlow.map { StatsUpdated(it) },
        alertsFlow.map { AlertsUpdated(it) }
    )
}
```

#### 5. 관심사 분리

| 책임 | 담당 |
|------|------|
| Flow 제공 | Repository |
| 변환 정의 | FlowHolderAction (순수) |
| 구독/취소 관리 | Store |
| 상태 변경 | Reducer |

### 실전 패턴

```kotlin
// 패턴 1: Cache-then-Network
data class ObserveProduct(
    private val productFlow: Flow<Product>
) : ProductAction, FlowHolderAction {
    override fun toFlowAction(): Flow<Action> = productFlow
        .map { ProductLoaded(it) }
        .onStart { emit(ProductLoading) }
        .catch { emit(ProductError(it.message)) }
}

// 패턴 2: WebSocket 실시간 스트림
data class ObserveChat(
    private val messageFlow: Flow<ChatMessage>
) : ChatAction, FlowHolderAction {
    override fun toFlowAction(): Flow<Action> = messageFlow
        .map { MessageReceived(it) }
}

// 패턴 3: 페이지네이션
data class ObserveArticles(
    private val articlesFlow: Flow<List<Article>>
) : ArticleAction, FlowHolderAction {
    override fun toFlowAction(): Flow<Action> = articlesFlow
        .map { ArticlesLoaded(it) }
}
```

---

## Mavericks Async<T> 패턴

### Async 타입이란?

Mavericks의 `Async<T>`는 비동기 작업의 상태를 타입으로 표현하는 sealed class입니다.

```kotlin
sealed class Async<out T> {
    object Uninitialized : Async<Nothing>()       // 아직 시작 안함
    object Loading : Async<Nothing>()              // 로딩 중
    data class Success<T>(val value: T) : Async<T>() // 성공
    data class Fail<T>(val error: Throwable) : Async<T>() // 실패
}
```

### 상태 전이

```
┌─────────────────┐
│  Uninitialized  │
└────────┬────────┘
         │ execute { } 호출
         ▼
┌─────────────────┐
│     Loading     │
└────────┬────────┘
         │
    ┌────┴────┐
    ▼         ▼
┌───────┐ ┌───────┐
│Success│ │ Fail  │
└───────┘ └───────┘
```

### execute 확장 함수

```kotlin
// Mavericks: 비동기 상태가 자동으로 전환됨
suspend {
    api.fetchUser(id)  // 반환: User
}.execute { asyncResult ->  // asyncResult: Async<User>
    copy(user = asyncResult)
}
// Uninitialized → Loading → Success(User) 또는 Fail(error)
```

### flowdux와 비교

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Mavericks (Async<T>)                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   data class UserState(                                              │
│       val user: Async<User> = Uninitialized                         │
│   ) : MavericksState                                                 │
│                                                                      │
│   fun loadUser(id: String) {                                         │
│       suspend { api.fetchUser(id) }                                  │
│           .execute { copy(user = it) }  // 상태 자동 전환            │
│   }                                                                  │
│                                                                      │
│   // UI                                                              │
│   when (state.user) {                                                │
│       is Loading -> LoadingUI()                                      │
│       is Success -> ContentUI(state.user())                          │
│       is Fail -> ErrorUI(state.user.error)                           │
│   }                                                                  │
│                                                                      │
│   장점: 보일러플레이트 최소, 타입으로 상태 표현                      │
│   단점: 암묵적 전환, Action 추적 불가                                │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                    flowdux (명시적 State)                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   data class UserState(                                              │
│       val user: User? = null,                                        │
│       val isLoading: Boolean = false,                                │
│       val error: String? = null                                      │
│   ) : State                                                          │
│                                                                      │
│   on<LoadUser>(takeLatest()) { state, action ->                      │
│       emit(SetLoading(true))                                         │
│       try {                                                          │
│           val user = api.fetchUser(action.id)                        │
│           emit(SetUser(user))                                        │
│       } catch (e: Exception) {                                       │
│           emit(SetError(e.message))                                  │
│       }                                                              │
│       emit(SetLoading(false))                                        │
│   }                                                                  │
│                                                                      │
│   장점: 명시적 Action 흐름, 로깅/디버깅 용이, 동시성 전략            │
│   단점: 보일러플레이트 (Action 정의 필요)                            │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 비교 요약

| 측면 | Mavericks Async | flowdux |
|------|-----------------|---------|
| **로딩 상태** | `Async<T>` 타입에 내장 | State에 `isLoading` 명시 |
| **상태 변경** | `execute { copy(x = it) }` | Action 명시적 전환 |
| **보일러플레이트** | 최소 (자동) | 명시적 (Action 정의) |
| **추적성** | 암묵적 (디버깅 어려움) | Action 로깅 가능 |
| **동시성** | 수동 | takeLatest 등 전략 |
| **플랫폼** | Android 전용 | KMP |

---

## 에러 처리 비교

### flowdux vs FlowMVI

```
┌─────────────────────────────────────────────────────────────────────┐
│                    flowdux (ErrorProcessor)                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   interface ErrorProcessor<A: Action> {                              │
│       fun process(throwable: Throwable): Flow<A>                     │
│   }                                                                  │
│                                                                      │
│   // 사용                                                            │
│   class MyErrorProcessor : ErrorProcessor<AppAction> {               │
│       override fun process(throwable: Throwable) = flow {           │
│           emit(ShowError(throwable.message ?: "Unknown error"))      │
│           analytics.logError(throwable)                              │
│       }                                                              │
│   }                                                                  │
│                                                                      │
│   val store = Store(                                                 │
│       initialState = initialState,                                   │
│       reducer = reducer,                                             │
│       middlewares = middlewares,                                     │
│       errorProcessor = MyErrorProcessor()                            │
│   )                                                                  │
│                                                                      │
│   특징:                                                              │
│   • Redux 원칙 준수: Error → Action → Reducer → State               │
│   • 전역 에러 처리 (Store 레벨)                                      │
│   • Action을 통한 상태 변경                                          │
│   • 에러도 Action으로 변환하여 추적 가능                             │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                    FlowMVI (recover + Plugin)                        │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   // 1. Intent별 recover 블록                                        │
│   recover { e: Exception ->                                          │
│       updateState { copy(error = e.message) }  // 직접 상태 변경     │
│       null  // 에러 무시                                             │
│   }                                                                  │
│                                                                      │
│   // 2. Plugin의 onException 훅                                      │
│   val errorLoggingPlugin = object : StorePlugin<State, Intent, A> {  │
│       override suspend fun onException(e: Exception): Exception? {   │
│           logger.error("Error occurred", e)                          │
│           analytics.logError(e)                                      │
│           return null  // 에러 무시                                  │
│       }                                                              │
│   }                                                                  │
│                                                                      │
│   특징:                                                              │
│   • Intent별 세밀한 에러 처리 (recover)                              │
│   • Plugin으로 전역 에러 처리 (onException)                          │
│   • 직접 상태 변경 가능 (updateState)                                │
│   • 에러 전파/무시 선택 (null 반환 시 무시)                          │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 비교 요약

| 측면 | flowdux | FlowMVI |
|------|---------|---------|
| **에러 처리 위치** | Store 레벨 (전역) | Intent별 + Plugin (전역) |
| **상태 변경 방식** | Action 반환 → Reducer | 직접 updateState |
| **Redux 원칙** | ✅ 준수 | ❌ 우회 |
| **에러 추적** | Action으로 로깅 가능 | Plugin 훅으로 로깅 |
| **유연성** | 에러별 다른 Action | 에러별 다른 recover |

### flowdux의 ErrorProcessor 장점

```kotlin
// 1. 모든 에러가 Action으로 변환되어 Reducer에서 상태 반영
val reducer = buildReducer<AppState, AppAction> {
    on<ShowError> { state, action ->
        state.copy(error = action.message, isLoading = false)
    }
}

// 2. StoreLogger로 에러 Action 로깅 가능
val logger = object : StoreLogger<AppState, AppAction> {
    override fun onActionDispatched(action: AppAction) {
        if (action is ShowError) {
            Log.e("Store", "Error occurred: ${action.message}")
        }
    }
}

// 3. TimeTravel 디버깅에서 에러 상태도 추적 가능
// Error → ShowError Action → Reducer → State(error = "...")

// 4. 에러 복구 로직도 Action으로 표현
class RetryErrorProcessor : ErrorProcessor<AppAction> {
    override fun process(throwable: Throwable) = flow {
        if (throwable is NetworkException) {
            delay(1000)
            emit(RetryLastAction)  // 재시도 Action
        } else {
            emit(ShowError(throwable.message))
        }
    }
}
```

---

## 라이브러리 선택 가이드

### flowdux가 최적인 경우

```
✅ 동시성 전략이 핵심 요구사항
   • 검색 자동완성 (takeLatest + debounce)
   • 폼 자동저장 (debounce)
   • 중복 제출 방지 (takeLeading)
   • Rate limiting (throttle)

✅ 액션 간 조율 필요
   • SearchAction과 RefreshAction이 같은 취소 스코프
   • Strategy Group으로 선언적 정의

✅ Redux 패턴 선호
   • 글로벌 상태, Middleware 체인
   • 액션 로깅, 디버깅
```

### 다른 라이브러리가 적합한 경우

```
Orbit MVI
   • Android ViewModel 중심 아키텍처
   • 화면별 독립 상태
   • 간단한 MVI 도입

MVIKotlin
   • 타임트래블 디버깅 필수
   • Decompose와 함께 사용
   • 의존성 최소화

Ballast
   • Compose Desktop 프로젝트
   • 전역 LIFO/FIFO 전략 충분
   • 내장 네비게이션 필요

FlowRedux
   • 명확한 상태 머신 (결제, 온보딩)
   • 상태별 다른 UI
   • onEnter 핸들러 활용

Mavericks
   • Android 전용
   • Async<T> 타입 활용
   • Airbnb 패턴 채택

Decompose/PreCompose
   • 네비게이션 + 라이프사이클 필요
   • flowdux와 조합 가능
```

---

## flowdux와의 조합 가능성

### Decompose + flowdux

```kotlin
class CounterComponent(
    componentContext: ComponentContext,
    private val store: Store<AppState, AppAction>
) : ComponentContext by componentContext {

    val state: StateFlow<AppState> = store.state

    fun increment() {
        store.dispatch(IncrementAction)
    }
}
```

### PreCompose + flowdux

```kotlin
class CounterViewModel(
    private val store: Store<AppState, AppAction>
) : ViewModel() {

    val state: StateFlow<AppState> = store.state

    fun dispatch(action: AppAction) {
        store.dispatch(action)
    }
}

@Composable
fun CounterScreen() {
    val viewModel = viewModel { CounterViewModel(appStore) }
    val state by viewModel.state.collectAsState()
    // ...
}
```

---

## 결론

### flowdux의 고유 가치

1. **선언적 동시성**: `takeLatest()`, `debounce()`, `throttle()`, `takeLeading()`
2. **Strategy Group**: 액션 간 조율을 위한 유일한 일급 DSL
3. **FlowHolderAction**: Repository Flow를 선언적으로 Store에 통합 (자동 구독/병렬 처리)
4. **타입 안전**: Sealed class 기반 Action
5. **전체 KMP 지원**: Android, iOS, Desktop (JVM), JS, WASM
6. **ErrorProcessor**: Redux 원칙을 준수하는 전역 에러 처리 (Error → Action → State)

### 다른 라이브러리 대비

| vs | flowdux 장점 | 상대 장점 |
|----|-------------|----------|
| Orbit | 동시성 전략, FlowHolderAction | ViewModel 통합, 간결 |
| MVIKotlin | 간결한 API, 선언적 Flow 통합 | 타임트래블, Decompose |
| Ballast | 액션별 전략, Strategy Group | InputStrategy 간단 |
| FlowMVI | Redux 원칙 에러 처리, FlowHolderAction | Plugin 시스템, Intent별 에러 |
| FlowRedux | 동시성 전략, FlowHolderAction | 상태 머신 DSL |
| ReduxKotlin | 타입 안전, FlowHolderAction | JS Redux 호환 |
| Mavericks | KMP, 동시성, FlowHolderAction | Async<T>, Android 최적화 |

---

## 참고 자료

### 개별 라이브러리 문서

- [Orbit MVI](./libraries/orbit-mvi.md)
- [MVIKotlin](./libraries/mvikotlin.md)
- [Ballast](./libraries/ballast.md)
- [FlowMVI](./libraries/flowmvi.md)
- [Fluxo](./libraries/fluxo.md)
- [FlowRedux](./libraries/flowredux.md)
- [ReduxKotlin](./libraries/reduxkotlin.md)
- [ReKotlin](./libraries/rekotlin.md)
- [Mavericks](./libraries/mavericks.md)
- [Decompose](./libraries/decompose.md)
- [PreCompose](./libraries/precompose.md)
- [Molecule](./libraries/molecule.md)

### 관련 분석 문서

- [redux-saga 아키텍처](./redux-saga-architecture.md)
- [다중 전략 UseCase 분석](./multi-strategy-usecase-analysis.md)
