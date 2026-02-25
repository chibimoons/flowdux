# Getting Started

FlowDux 시작 가이드입니다. 기본 Store 생성부터 Middleware, Remote 동기화까지 단계별로 안내합니다.

## 1. 설치

### Kotlin Multiplatform (Maven Central)

`build.gradle.kts`에 의존성을 추가합니다:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.chibimoons:flowdux:1.17.0")
        }
    }
}
```

Gradle이 각 타겟(JVM, iOS, JS, WASM)에 맞는 아티팩트를 자동으로 해석합니다.

### 단일 플랫폼 (JVM / Android)

KMP가 아닌 프로젝트에서는 직접 추가합니다:

```kotlin
dependencies {
    implementation("io.github.chibimoons:flowdux:1.17.0")
}
```

## 2. 첫 번째 Store 만들기

FlowDux의 핵심 개념 세 가지: **State**, **Action**, **Reducer**.

### State 정의

State는 앱의 현재 상태를 나타내는 불변 데이터 클래스입니다:

```kotlin
import io.flowdux.State

data class CounterState(val count: Int = 0) : State
```

### Action 정의

Action은 상태 변경 요청입니다. `sealed interface`로 정의하면 타입 안전성을 보장합니다:

```kotlin
import io.flowdux.Action

sealed interface CounterAction : Action {
    object Increment : CounterAction
    object Decrement : CounterAction
    data class Add(val value: Int) : CounterAction
}
```

### Reducer 정의

Reducer는 현재 State와 Action을 받아 새로운 State를 반환하는 순수 함수입니다:

```kotlin
import io.flowdux.buildReducer

val counterReducer = buildReducer<CounterState, CounterAction> {
    on<CounterAction.Increment> { state, _ ->
        state.copy(count = state.count + 1)
    }
    on<CounterAction.Decrement> { state, _ ->
        state.copy(count = state.count - 1)
    }
    on<CounterAction.Add> { state, action ->
        state.copy(count = state.count + action.value)
    }
}
```

> 등록되지 않은 Action은 자동으로 현재 State를 그대로 반환합니다.

### Store 생성 및 사용

```kotlin
import io.flowdux.createStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val store = createStore(
        initialState = CounterState(),
        reducer = counterReducer,
    )

    // 상태 관찰
    println("초기: ${store.currentState}")  // CounterState(count=0)

    // 액션 디스패치
    store.dispatch(CounterAction.Increment)
    store.state.first { it.count == 1 }
    println("증가: ${store.currentState}")  // CounterState(count=1)

    store.dispatch(CounterAction.Add(10))
    store.state.first { it.count == 11 }
    println("추가: ${store.currentState}")  // CounterState(count=11)

    store.close()
}
```

**핵심 API:**
- `store.currentState` — 현재 상태 동기적 접근
- `store.state` — `StateFlow<State>`로 반응형 관찰
- `store.dispatch(action)` — 액션 전송
- `store.close()` — 리소스 해제

## 3. Middleware 추가

Middleware는 API 호출, 로깅 같은 사이드 이펙트를 처리합니다. Action이 Reducer에 도달하기 전에 가로채서 변환, 필터, 또는 새 Action을 발행할 수 있습니다.

```kotlin
import io.flowdux.Middleware

class LoggingMiddleware : Middleware<CounterState, CounterAction> {
    override val processors = buildProcessors {
        on<CounterAction.Increment> { state, action ->
            println("  [LOG] Increment 수신 (현재: ${state.count})")
            emit(action)  // 다음 단계로 전달
        }
    }
}
```

**Middleware 동작 규칙:**
- `emit(action)` — 다음 단계로 액션을 전달
- `emit(otherAction)` — 다른 액션으로 변환
- 아무것도 emit하지 않으면 — 액션을 차단
- 여러 번 emit하면 — 하나의 액션을 여러 개로 분리

등록되지 않은 Action 타입은 자동으로 통과(pass-through)합니다.

### Middleware를 Store에 등록

```kotlin
val store = createStore(
    initialState = CounterState(),
    reducer = counterReducer,
    middlewares = listOf(LoggingMiddleware()),
)
```

### 비동기 API 호출 예제

```kotlin
sealed interface TodoAction : Action {
    object LoadTodos : TodoAction
    data class TodosLoaded(val items: List<String>) : TodoAction
    data class LoadFailed(val error: String) : TodoAction
}

class TodoMiddleware : Middleware<TodoState, TodoAction> {
    override val processors = buildProcessors {
        on<TodoAction.LoadTodos> { _, _ ->
            try {
                val todos = api.fetchTodos()  // suspend 함수 직접 호출 가능
                emit(TodoAction.TodosLoaded(todos))
            } catch (e: Exception) {
                emit(TodoAction.LoadFailed(e.message ?: "Unknown error"))
            }
        }
    }
}
```

### Execution Strategy

동시성 제어가 필요한 경우 전략을 지정합니다:

```kotlin
override val processors = buildProcessors {
    // 이전 검색을 취소하고 최신 검색만 실행
    on<SearchAction>(takeLatest()) { _, action ->
        val results = api.search(action.query)
        emit(SearchResult(results))
    }

    // 200ms 동안 입력이 없을 때만 실행
    on<TypeAhead>(debounce(200.milliseconds)) { _, action ->
        emit(SuggestionsLoaded(api.suggest(action.text)))
    }

    // 처리 중에는 추가 요청 무시
    on<SubmitForm>(takeLeading()) { _, _ ->
        api.submit()
        emit(SubmitSuccess)
    }
}
```

사용 가능한 전략: `takeLatest()`, `takeLeading()`, `sequential()`, `debounce(duration)`, `throttle(duration)`, `retry(n)`, `retryWithBackoff(...)`

전략은 체이닝도 가능합니다: `debounce(300.ms) then takeLatest() then retry(3)`

> 자세한 내용은 [Execution Strategies](execution-strategies.md) 가이드를 참고하세요.

## 4. Remote 동기화 (기초)

FlowDux Remote를 사용하면 클라이언트와 서버 간 실시간 상태 동기화를 구현할 수 있습니다.

### 추가 의존성

```kotlin
commonMain.dependencies {
    implementation("io.github.chibimoons:flowdux:1.17.0")
    implementation("io.github.chibimoons:flowdux-remote-core:1.17.0")
    implementation("io.github.chibimoons:flowdux-remote-client:1.17.0")
    implementation("io.github.chibimoons:flowdux-remote-server:1.17.0")
    implementation("io.github.chibimoons:flowdux-remote-serialization:1.17.0")
    implementation("io.github.chibimoons:flowdux-remote-ktor:1.17.0")
}
```

### Shared Action 정의

`ServerSharedAction`은 클라이언트에서 서버로, `ClientSharedAction`은 서버에서 클라이언트로 전송됩니다:

```kotlin
import io.flowdux.remote.ServerSharedAction
import io.flowdux.remote.ClientSharedAction
import kotlinx.serialization.Serializable

@Serializable
sealed interface ChatAction : Action {
    // Client → Server
    @Serializable
    data class SendMessage(val text: String) : ChatAction, ServerSharedAction

    // Server → Client
    @Serializable
    data class MessageReceived(val text: String, val sender: String) : ChatAction, ClientSharedAction

    // Local only (전송되지 않음)
    data class SetInput(val text: String) : ChatAction
}
```

### 동작 흐름

```
Client                          Server
  │                               │
  ├─ dispatch(SendMessage) ──────►│ SyncMiddleware가 ServerSharedAction을 감지하여 전송
  │                               ├─ Reducer가 상태 업데이트
  │                               ├─ dispatch(MessageReceived)
  │◄── ClientSharedAction ────────┤ SingleClientSyncMiddleware가 클라이언트에 전송
  ├─ Reducer가 상태 업데이트       │
```

> 전체 설정 및 서버 패턴은 [Remote State Sync](remote.md) 가이드를 참고하세요.

## 5. 다음 단계

### 핵심 가이드

| 가이드 | 설명 |
|--------|------|
| [Architecture](architecture.md) | Store 파이프라인, 액션 흐름, 컴포넌트 역할 |
| [Execution Strategies](execution-strategies.md) | 동시성 전략 (takeLatest, debounce, retry 등) |
| [Time Travel](timetravel.md) | Undo/Redo, 상태 히스토리 디버깅 |

### Remote 가이드

| 가이드 | 설명 |
|--------|------|
| [Remote State Sync](remote.md) | WebSocket 기반 클라이언트-서버 설정 |
| [Server Patterns](server-patterns.md) | 4가지 서버 패턴 비교 및 선택 가이드 |
| [Authentication](remote-authentication.md) | WebSocket 인증 연동 |
| [Scaling](scaling.md) | 대규모 동시 연결 처리 |

### 샘플 앱

| 샘플 | 설명 |
|------|------|
| [JVM Console](samples.md#jvm-console-sample) | 핵심 기능 데모 (Store, Middleware, Strategy) |
| [Remote Simple](samples.md#remote-simple-sample) | 1:1 WebSocket 클라이언트-서버 |
| [Remote Multi-Client](samples.md#remote-multi-client-sample) | N 클라이언트가 하나의 Store 공유 |

전체 샘플 목록은 [Sample Apps](samples.md)를 참고하세요.
