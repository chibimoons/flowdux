# Shared State Pattern (1:1:N)

Shared State 패턴은 여러 클라이언트가 하나의 Store를 공유하는 구조입니다. 모든 클라이언트가 동일한 상태를 실시간으로 봅니다.

## 작성해야 할 파일

```
shared/                           # 공유 모듈
├── State.kt                      # 서버 상태 + 클라이언트 상태 + 변환 함수
└── Actions.kt                    # SharedAction, ServerAction 정의

server/                           # 서버 모듈
├── Main.kt                       # createSharedStateServer() + WebSocket 라우팅
├── Reducer.kt                    # 서버 리듀서
└── Processors.kt                 # 클라이언트 → 서버 액션 변환

client/                           # 클라이언트 모듈
├── Main.kt                       # 연결, Store 생성, 상태 관찰
├── Reducer.kt                    # 클라이언트 리듀서
└── RemoteMiddleware.kt           # SyncMiddleware 상속
```

### 각 파일의 역할

| 파일 | 필수 | 설명 |
|------|:----:|------|
| `shared/State.kt` | ✅ | `ChatState` (서버), `ClientChatState` (클라이언트), `toClientState()` 변환 |
| `shared/Actions.kt` | ✅ | `SharedChatAction` + `ServerChatAction` |
| `server/Main.kt` | ✅ | `createSharedStateServer()`, WebSocket 라우팅 |
| `server/Reducer.kt` | ✅ | 서버 액션 → 상태 변경 |
| `server/Processors.kt` | ✅ | `ServerSharedAction` → `ServerChatAction` 변환 |
| `client/Main.kt` | ✅ | 연결, Store 생성 |
| `client/Reducer.kt` | ✅ | `SyncState` 처리 |
| `client/RemoteMiddleware.kt` | ✅ | Connect/Disconnect 처리 |

## 개요

```
┌─────────────────────────────────────────────────────────────┐
│                      Server                                  │
│                                                              │
│                    ┌─────────┐                              │
│                    │  Store  │                              │
│                    └────┬────┘                              │
│                         │                                   │
│            ┌────────────┼────────────┐                     │
│            │            │            │                     │
└────────────┼────────────┼────────────┼─────────────────────┘
             │            │            │
             ▼            ▼            ▼
        ┌────────┐   ┌────────┐   ┌────────┐
        │Client 1│   │Client 2│   │Client 3│
        └────────┘   └────────┘   └────────┘

        모든 클라이언트가 동일한 상태를 공유
```

**특징:**
- 1 Server : 1 Store : N Clients
- 모든 클라이언트가 동일한 상태 공유
- 상태 변경 시 모든 클라이언트에 자동 브로드캐스트
- `SharedStateServer` 사용

## 언제 사용하나요?

| Use Case | 설명 |
|----------|------|
| **채팅방** | 모든 참가자가 같은 메시지 히스토리 |
| **실시간 대시보드** | 팀 전체가 같은 메트릭 |
| **협업 문서** | 동시 편집, 실시간 동기화 |
| **라이브 투표** | 실시간 집계 결과 공유 |
| **실시간 시세** | 모든 사용자에게 동일 시세 |

## 기본 구현

### Server

```kotlin
import io.flowdux.remote.server.pattern.SharedStateServer
import io.flowdux.remote.server.pattern.createSharedStateServer
import io.flowdux.remote.ktor.KtorWebSocketServerConnection
import io.flowdux.remote.serialization.typedJsonAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun main() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 단일 서버 (모든 클라이언트 공유)
    val server = createSharedStateServer(
        initialState = ChatState(),
        reducer = chatReducer,
        processors = chatProcessors(),
        stateMapper = { state ->
            SharedChatAction.SyncState(state.toClientState())
        },
        scope = applicationScope,
    )

    embeddedServer(CIO, port = 8080) {
        install(WebSockets)

        routing {
            webSocket("/chat") {
                val sessionId = UUID.randomUUID().toString()
                println("Client connected: $sessionId")

                val connection = KtorWebSocketServerConnection(this)
                    .typedJsonAs<SharedChatAction, ChatAction>()

                try {
                    server.handleClient(sessionId, connection)
                } finally {
                    println("Client disconnected: $sessionId")
                }
            }
        }
    }.start(wait = true)

    server.close()
}
```

### State & Actions

```kotlin
import io.flowdux.Action
import io.flowdux.State
import io.flowdux.remote.ClientSharedAction
import io.flowdux.remote.ServerSharedAction
import kotlinx.serialization.Serializable

// Server State (전체 정보)
data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val users: Set<String> = emptySet(),
    val totalMessageCount: Int = 0,  // 서버 전용 통계
) : State

// Client State (클라이언트에 전송할 정보)
@Serializable
data class ClientChatState(
    val messages: List<ChatMessage> = emptyList(),
    val users: Set<String> = emptySet(),
)

fun ChatState.toClientState() = ClientChatState(
    messages = messages.takeLast(100),  // 최근 100개만
    users = users,
)

@Serializable
data class ChatMessage(
    val id: String,
    val user: String,
    val text: String,
    val timestamp: Long,
)

// Actions
sealed interface ChatAction : Action

@Serializable
sealed interface SharedChatAction : ChatAction {
    // Client → Server
    @Serializable
    data class SendMessage(val user: String, val text: String) : SharedChatAction, ServerSharedAction

    @Serializable
    data class JoinRoom(val user: String) : SharedChatAction, ServerSharedAction

    @Serializable
    data class LeaveRoom(val user: String) : SharedChatAction, ServerSharedAction

    // Server → Client
    @Serializable
    data class SyncState(val state: ClientChatState) : SharedChatAction, ClientSharedAction

    @Serializable
    data class SystemAnnouncement(val message: String) : SharedChatAction, ClientSharedAction
}

// Server-only actions
sealed interface ServerChatAction : ChatAction {
    data class MessageReceived(val user: String, val text: String) : ServerChatAction
    data class UserJoined(val user: String) : ServerChatAction
    data class UserLeft(val user: String) : ServerChatAction
}
```

### Reducer & Processors

```kotlin
import io.flowdux.Middleware
import io.flowdux.buildReducer
import java.util.UUID

val chatReducer = buildReducer<ChatState, ChatAction> {
    on<ServerChatAction.MessageReceived> { state, action ->
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            user = action.user,
            text = action.text,
            timestamp = System.currentTimeMillis(),
        )
        state.copy(
            messages = state.messages + message,
            totalMessageCount = state.totalMessageCount + 1,
        )
    }

    on<ServerChatAction.UserJoined> { state, action ->
        state.copy(users = state.users + action.user)
    }

    on<ServerChatAction.UserLeft> { state, action ->
        state.copy(users = state.users - action.user)
    }
}

fun chatProcessors() = Middleware.ActionProcessorBuilder<ChatState, ChatAction>().apply {
    on<SharedChatAction.SendMessage> { _, action ->
        emit(ServerChatAction.MessageReceived(action.user, action.text))
    }

    on<SharedChatAction.JoinRoom> { _, action ->
        emit(ServerChatAction.UserJoined(action.user))
    }

    on<SharedChatAction.LeaveRoom> { _, action ->
        emit(ServerChatAction.UserLeft(action.user))
    }
}.build()
```

### Client Middleware

```kotlin
import io.flowdux.ActionProcessorMap
import io.flowdux.remote.SyncMiddleware
import io.flowdux.remote.TypedClientConnection

// 로컬 전용 액션
sealed interface LocalChatAction : ChatAction {
    data object Connect : LocalChatAction
    data object Disconnect : LocalChatAction
}

class ChatRemoteMiddleware(
    connection: TypedClientConnection<ChatAction>,
) : SyncMiddleware<ClientChatState, ChatAction>(
    connection = connection,
) {
    override val name: String = "ChatRemoteMiddleware"

    override val processors: ActionProcessorMap<ClientChatState, ChatAction> = buildProcessors {
        on<LocalChatAction.Connect> { _, _ ->
            startConnection()  // 내장: 연결 및 메시지 리스닝
        }
        on<LocalChatAction.Disconnect> { _, _ ->
            stopConnection()   // 내장: 정상 종료
        }
    }
}
```

### Client Usage

```kotlin
import io.flowdux.createStore
import io.flowdux.remote.ktor.KtorWebSocketClientConnection

suspend fun main() {
    val connection = KtorWebSocketClientConnection.create(
        host = "localhost",
        port = 8080,
        path = "/chat",
    ).typedJsonAs<SharedChatAction, ChatAction>()

    val store = createStore(
        initialState = ClientChatState(),
        reducer = clientChatReducer,
        middlewares = listOf(ChatRemoteMiddleware(connection)),
    )

    // 연결
    store.dispatch(LocalChatAction.Connect)

    // 방 입장
    store.dispatch(SharedChatAction.JoinRoom("Alice"))

    // 메시지 전송
    store.dispatch(SharedChatAction.SendMessage("Alice", "Hello everyone!"))

    // 상태 관찰
    store.state.collect { state ->
        println("Messages: ${state.messages.size}, Users: ${state.users}")
    }
}

val clientChatReducer = buildReducer<ClientChatState, ChatAction> {
    on<SharedChatAction.SyncState> { _, action ->
        action.state
    }
}
```

## Use Cases

### 1. 실시간 채팅방

기본적인 채팅방 구현입니다.

```kotlin
// 이미 위에서 구현한 예제와 동일
val chatServer = createSharedStateServer(
    initialState = ChatState(),
    reducer = chatReducer,
    processors = chatProcessors(),
    stateMapper = { SharedChatAction.SyncState(it.toClientState()) },
)
```

### 2. 실시간 대시보드

팀 전체가 같은 메트릭을 보는 대시보드입니다.

```kotlin
@Serializable
data class DashboardState(
    val activeUsers: Int = 0,
    val requestsPerSecond: Double = 0.0,
    val errorRate: Double = 0.0,
    val cpuUsage: Double = 0.0,
    val memoryUsage: Double = 0.0,
    val recentEvents: List<SystemEvent> = emptyList(),
) : State

// 서버: 주기적으로 메트릭 업데이트
val dashboardServer = createSharedStateServer(
    initialState = DashboardState(),
    reducer = dashboardReducer,
    stateMapper = { SharedAction.SyncDashboard(it) },
    scope = applicationScope,
)

// 메트릭 수집 루프
applicationScope.launch {
    while (isActive) {
        val metrics = metricsCollector.collect()
        dashboardServer.store.dispatch(ServerAction.UpdateMetrics(metrics))
        delay(1.seconds)
    }
}
```

### 3. 협업 문서 편집

여러 사용자가 동시에 문서를 편집합니다.

```kotlin
@Serializable
data class DocumentState(
    val content: String = "",
    val cursors: Map<String, CursorPosition> = emptyMap(),
    val version: Int = 0,
    val lastEditor: String? = null,
) : State

@Serializable
sealed interface SharedDocAction : DocAction {
    // Client → Server
    @Serializable
    data class Edit(
        val userId: String,
        val position: Int,
        val deleteCount: Int,
        val insertText: String,
    ) : SharedDocAction, ServerSharedAction

    @Serializable
    data class MoveCursor(val userId: String, val position: CursorPosition) : SharedDocAction, ServerSharedAction

    // Server → Client
    @Serializable
    data class SyncDocument(val state: DocumentState) : SharedDocAction, ClientSharedAction
}

// Processor: OT (Operational Transform) 적용
fun docProcessors() = Middleware.ActionProcessorBuilder<DocumentState, DocAction>().apply {
    on<SharedDocAction.Edit> { state, action ->
        // OT 변환 적용
        val transformedEdit = operationalTransform(action, state.pendingOps)
        emit(ServerDocAction.ApplyEdit(transformedEdit))
    }
}.build()
```

### 4. 라이브 투표/설문

실시간으로 투표 결과를 집계합니다.

```kotlin
@Serializable
data class PollState(
    val question: String = "",
    val options: List<PollOption> = emptyList(),
    val votes: Map<String, String> = emptyMap(),  // userId -> optionId
    val totalVotes: Int = 0,
    val status: PollStatus = PollStatus.OPEN,
) : State

@Serializable
data class PollOption(
    val id: String,
    val text: String,
    val voteCount: Int = 0,
)

@Serializable
sealed interface SharedPollAction : PollAction {
    @Serializable
    data class Vote(val userId: String, val optionId: String) : SharedPollAction, ServerSharedAction

    @Serializable
    data class SyncPoll(val state: ClientPollState) : SharedPollAction, ClientSharedAction
}

val pollReducer = buildReducer<PollState, PollAction> {
    on<ServerPollAction.RecordVote> { state, action ->
        // 이미 투표한 경우 변경
        val previousVote = state.votes[action.userId]
        val updatedOptions = state.options.map { option ->
            when (option.id) {
                previousVote -> option.copy(voteCount = option.voteCount - 1)
                action.optionId -> option.copy(voteCount = option.voteCount + 1)
                else -> option
            }
        }

        state.copy(
            options = updatedOptions,
            votes = state.votes + (action.userId to action.optionId),
            totalVotes = if (previousVote == null) state.totalVotes + 1 else state.totalVotes,
        )
    }
}
```

### 5. 실시간 시세/주식

모든 사용자에게 동일한 시세를 전달합니다.

```kotlin
@Serializable
data class MarketState(
    val prices: Map<String, StockPrice> = emptyMap(),
    val lastUpdate: Long = 0,
    val marketStatus: MarketStatus = MarketStatus.CLOSED,
) : State

@Serializable
data class StockPrice(
    val symbol: String,
    val price: Double,
    val change: Double,
    val changePercent: Double,
    val volume: Long,
)

// 서버: 외부 시세 피드 연동
val marketServer = createSharedStateServer(
    initialState = MarketState(),
    reducer = marketReducer,
    stateMapper = { SharedAction.SyncMarket(it) },
)

// 시세 피드 구독
applicationScope.launch {
    marketFeed.subscribe().collect { priceUpdate ->
        marketServer.store.dispatch(ServerAction.PriceUpdate(priceUpdate))
    }
}
```

### 6. 화이트보드/드로잉

여러 사용자가 동시에 그림을 그립니다.

```kotlin
@Serializable
data class WhiteboardState(
    val strokes: List<Stroke> = emptyList(),
    val activeDrawers: Map<String, DrawingCursor> = emptyMap(),
) : State

@Serializable
data class Stroke(
    val id: String,
    val userId: String,
    val points: List<Point>,
    val color: String,
    val width: Float,
)

@Serializable
sealed interface SharedWhiteboardAction : WhiteboardAction {
    @Serializable
    data class StartStroke(val userId: String, val point: Point, val color: String, val width: Float)
        : SharedWhiteboardAction, ServerSharedAction

    @Serializable
    data class AddPoint(val userId: String, val point: Point)
        : SharedWhiteboardAction, ServerSharedAction

    @Serializable
    data class EndStroke(val userId: String)
        : SharedWhiteboardAction, ServerSharedAction

    @Serializable
    data class SyncWhiteboard(val state: WhiteboardState)
        : SharedWhiteboardAction, ClientSharedAction
}
```

## 시스템 공지 (Server-Initiated Broadcast)

서버에서 모든 클라이언트에게 메시지를 보내는 기능입니다.

```kotlin
// Admin API 엔드포인트
post("/announce") {
    val message = call.receiveText()
    server.broadcast(SharedChatAction.SystemAnnouncement(message))
    call.respond(HttpStatusCode.OK, "Announcement sent")
}

// 점검 모드
post("/maintenance/{enabled}") {
    val enabled = call.parameters["enabled"]?.toBoolean() ?: false
    val message = if (enabled) "Server entering maintenance mode" else "Maintenance complete"
    server.broadcast(SharedChatAction.SystemAnnouncement(message))
}
```

## 스케일링

대규모 클라이언트 처리를 위한 설정입니다.

```kotlin
import io.flowdux.remote.server.session.BroadcastConfig
import io.flowdux.remote.server.session.InMemorySessionRegistry

val server = createSharedStateServer(
    initialState = ChatState(),
    reducer = chatReducer,
    stateMapper = { SharedChatAction.SyncState(it.toClientState()) },
    // 병렬 브로드캐스트 (대규모 클라이언트용)
    broadcastConfig = BroadcastConfig(concurrency = 32),
    // 커스텀 세션 레지스트리 (분산 환경용)
    sessionRegistry = InMemorySessionRegistry(),
    scope = applicationScope,
)
```

자세한 내용은 [Scaling Guide](./scaling.md)를 참조하세요.

## 다른 패턴으로 전환

Shared State 패턴에서 다른 패턴으로 전환해야 하는 신호:

| 신호 | 전환 대상 |
|------|----------|
| "그룹별로 데이터를 분리해야 해요" | [Room](./pattern-room.md) |
| "일부 정보는 본인만 봐야 해요" | [Per-Client](./pattern-per-client.md) |
| "클라이언트별 독립 세션이 필요해요" | [Single Client](./pattern-single-client.md) |

## Sample App

```bash
# 서버 실행
./gradlew :kotlin:sample-remote-multi:server:run

# 클라이언트 실행 (여러 터미널에서)
./gradlew :kotlin:sample-remote-multi:client:run --args="Alice"
./gradlew :kotlin:sample-remote-multi:client:run --args="Bob"
```

## Related

- [Server Patterns Overview](./server-patterns.md) — 패턴 선택 가이드
- [Room Pattern](./pattern-room.md) — 다중 방 관리
- [Scaling Guide](./scaling.md) — 대규모 연결 처리
