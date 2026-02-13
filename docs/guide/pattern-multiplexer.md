# Multiplexer Pattern (단일 WebSocket, 다중 방)

Multiplexer 패턴은 **단일 WebSocket 연결**에서 여러 방(room)에 동시 참여하는 구조입니다. 각 방은 독립된 가상 연결(virtual connection)을 통해 메시지를 주고받으며, 물리적 연결은 하나만 유지됩니다.

## Room 패턴과의 차이

| 항목 | Room 패턴 | Multiplexer 패턴 |
|------|----------|-----------------|
| WebSocket 수 | 방마다 1개 (`/room/{roomId}`) | 전체 1개 (`/ws`) |
| 방 참여 | URL 경로로 라우팅 | `RoutedAction`으로 라우팅 |
| 동시 다중 방 | 별도 연결 필요 | 단일 연결로 가능 |
| 적합한 시나리오 | 한 번에 1개 방 | 동시에 여러 방 |

```
Room 패턴:                        Multiplexer 패턴:
  /room/general ─── WS1 ──► Room A     /ws ─── 단일 WS ──► Room A
  /room/random  ─── WS2 ──► Room B                       ├──► Room B
  /room/kotlin  ─── WS3 ──► Room C                       └──► Room C
```

## 언제 사용하나요?

| Use Case | 설명 |
|----------|------|
| **Slack/Discord** | 여러 채널에 동시 참여 |
| **게임 로비 + 매치** | 로비와 게임방을 동시 사용 |
| **대시보드** | 여러 데이터 소스를 하나의 연결로 수신 |
| **협업 도구** | 여러 문서/보드를 동시 편집 |

단일 방만 사용한다면 [Room 패턴](./pattern-room.md)이 더 간단합니다.

## 개요

```
┌───────────── 단일 WebSocket ─────────────┐
│                                           │
│   ┌─────────┐  ┌─────────┐  ┌─────────┐  │
│   │ Room A  │  │ Room B  │  │ Room C  │  │
│   │ (Store) │  │ (Store) │  │ (Store) │  │
│   └────┬────┘  └────┬────┘  └────┬────┘  │
│        │            │            │        │
│   ┌────┴────────────┴────────────┴────┐   │
│   │     ConnectionMultiplexer          │   │
│   │     (RoutedAction 라우팅)          │   │
│   └────────────────┬──────────────────┘   │
│                    │                      │
└────────────────────┼──────────────────────┘
                     │
                   Client
```

- **1 WebSocket = N Rooms** — 물리적 연결 1개, 가상 연결 N개
- **`RoutedAction`** — `{ "roomId": "...", "action": { ... } }` 형식으로 방 라우팅
- **방별 독립 Store** — 각 Room의 상태와 Reducer는 완전히 독립

## RoutedAction 프로토콜

모든 메시지는 `RoutedAction`으로 래핑되어 전송됩니다:

```json
{"roomId": "general", "action": {"type": "SendMessage", "user": "Alice", "text": "hello"}}
```

```kotlin
@Serializable
data class RoutedAction<A : Action>(
    val roomId: String,
    val action: A,
) : Action
```

## 작성해야 할 파일

```
shared/                           # 공유 모듈
├── State.kt                      # 방 상태 (RoomState, ChatMessage 등)
└── Actions.kt                    # SharedChatAction (ServerSharedAction, ClientSharedAction)

server/                           # 서버 모듈
├── Main.kt                       # ServerConnectionMultiplexer + 라우팅
├── Reducer.kt                    # 방 리듀서
└── Processors.kt                 # 액션 변환

client/                           # 클라이언트 모듈
├── Main.kt                       # ClientConnectionMultiplexer + UI
├── RoomManager.kt                # 방별 Store 관리
└── RoomMiddleware.kt             # SyncMiddleware 상속
```

## 서버 구성

### 1. 공유 액션 정의

```kotlin
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
    data class SyncState(val state: RoomState) : SharedChatAction, ClientSharedAction
}
```

### 2. WebSocket 엔드포인트

`typedRoutedJson<A>()` 확장 함수로 `RoutedAction` 직렬화를 설정합니다:

```kotlin
import io.flowdux.remote.ktor.KtorWebSocketServerConnection
import io.flowdux.remote.multiplexer.ServerConnectionMultiplexer
import io.flowdux.remote.multiplexer.typedRoutedJson

webSocket("/ws") {
    val physicalConnection = KtorWebSocketServerConnection(this)
        .typedRoutedJson<SharedChatAction>()

    val multiplexer = ServerConnectionMultiplexer(
        physicalConnection,
        this,
        onUnknownRoom = { roomId, action ->
            // 알 수 없는 방에 대한 액션 처리 (JoinRoom 등)
            handleNewRoom(roomId, action)
        },
    )

    try {
        // 연결 유지 (incoming flow가 끝날 때까지)
        handleConnection()
    } finally {
        multiplexer.close()
    }
}
```

### 3. Room Server와 함께 사용

```kotlin
val roomServer = createSharedStateRoomServer(
    initialStateFactory = { roomId -> ServerRoomState(roomId = roomId) },
    reducer = serverRoomReducer,
    processors = roomProcessors(),
    stateMapper = { state -> SharedChatAction.SyncState(state.toRoomState()) },
    scope = applicationScope,
)

// Multiplexer의 onUnknownRoom 콜백에서 방 생성
lateinit var multiplexer: ServerConnectionMultiplexer<SharedChatAction>
multiplexer = ServerConnectionMultiplexer(
    physicalConnection,
    this,
    onUnknownRoom = { roomId, action ->
        if (action is SharedChatAction.JoinRoom) {
            val room = roomServer.getOrCreateRoom(roomId)
            val virtualConn = multiplexer.getOrCreateRoom(roomId)
            room.handleClient(sessionId, virtualConn)
        }
    },
)
```

## 클라이언트 구성

### 1. Multiplexer 생성 및 연결

```kotlin
import io.flowdux.remote.ktor.KtorWebSocketClientConnection
import io.flowdux.remote.multiplexer.ClientConnectionMultiplexer
import io.flowdux.remote.multiplexer.typedRoutedJson

val physicalConnection = KtorWebSocketClientConnection.create(
    host = "localhost",
    port = 8080,
    path = "/ws",
).typedRoutedJson<SharedChatAction>()

val multiplexer = ClientConnectionMultiplexer(physicalConnection, scope)
multiplexer.connect()
```

### 2. 방별 Store 생성

```kotlin
// 방에 참여할 때마다 가상 연결을 받아 Store 생성
suspend fun joinRoom(roomId: String): Store<ClientRoomState, ChatAction> {
    val virtualConnection = multiplexer.getOrCreateRoom(roomId)

    return createStore(
        initialState = ClientRoomState(),
        reducer = clientRoomReducer,
        middlewares = listOf(RoomRemoteMiddleware(virtualConnection)),
    )
}
```

### 3. 방 관리

```kotlin
// 방 나가기
suspend fun leaveRoom(roomId: String) {
    stores.remove(roomId)?.let { store ->
        store.dispatch(SharedChatAction.LeaveRoom(username))
    }
    multiplexer.removeRoom(roomId)
}

// 전체 종료
suspend fun shutdown() {
    leaveAllRooms()
    multiplexer.close()
}
```

## 직렬화 설정

### Extension Functions 사용 (권장)

```kotlin
// 클라이언트
val physical = clientConnection.typedRoutedJson<SharedChatAction>()

// 서버
val physical = serverConnection.typedRoutedJson<SharedChatAction>()
```

### 직접 Codec 생성

```kotlin
import io.flowdux.remote.multiplexer.RoutedAction
import io.flowdux.remote.serialization.JsonMessageCodec
import io.flowdux.remote.serialization.actionCodecOf

val routedCodec = actionCodecOf<RoutedAction<SharedChatAction>>()
val physical = rawConnection.typed(routedCodec, JsonMessageCodec())
```

## 생명주기

```
Client                           Server
  │                                │
  ├── multiplexer.connect() ────►  │ ServerConnectionMultiplexer 생성
  │                                │   (routing 자동 시작)
  │                                │
  ├── JoinRoom("general") ──────►  │ onUnknownRoom callback
  │   (RoutedAction)               │   → getOrCreateRoom("general")
  │                                │   → room.handleClient(...)
  │                                │
  ├── SendMessage(...) ──────────► │ room Store에 dispatch
  │   (RoutedAction: general)      │
  │                                │
  ├── JoinRoom("random") ───────►  │ 동일 WS, 새 room
  │   (RoutedAction)               │
  │                                │
  ├── multiplexer.close() ──────►  │ multiplexer.close()
  │                                │   모든 가상 연결 정리
```

## Sample App

```bash
# 서버 실행
./gradlew :kotlin:sample-remote-multiplexer:server:run

# 클라이언트 실행 (별도 터미널에서)
./gradlew :kotlin:sample-remote-multiplexer:client:run --args="Alice"
./gradlew :kotlin:sample-remote-multiplexer:client:run --args="Bob"
```

### 클라이언트 명령어

| 명령어 | 설명 |
|--------|------|
| `/join <room>` | 방 참여 |
| `/leave <room>` | 방 나가기 |
| `/rooms` | 참여 중인 방 목록 |
| `/switch <room>` | 활성 방 전환 |
| `/quit` | 종료 |
| `<메시지>` | 활성 방에 메시지 전송 |

### 데모 시나리오

1. 터미널 3개 열기
2. 서버 시작
3. Client 1: Alice로 접속 → 자동으로 `general` 방 입장
4. Client 2: Bob으로 접속 → 자동으로 `general` 방 입장
5. Alice: `/join random` → 동일 WebSocket에서 `random` 방 추가 참여
6. Alice: `/switch random` → 활성 방을 `random`으로 변경
7. Alice: `Hello!` → `random` 방에 메시지 전송
8. Alice: `/switch general` → `general` 방으로 돌아와서 Bob과 대화

## 다른 패턴으로 전환

| 신호 | 전환 대상 |
|------|----------|
| "동시에 여러 방은 필요 없어요" | [Room Pattern](./pattern-room.md) |
| "방 하나만 있으면 돼요" | [Shared State](./pattern-shared-state.md) |
| "방 내 비공개 정보가 필요해요" | [Per-Client](./pattern-per-client.md) |

## Related

- [Server Patterns Overview](./server-patterns.md) — 패턴 선택 가이드
- [Room Pattern](./pattern-room.md) — 방별 WebSocket 패턴
- [Shared State Pattern](./pattern-shared-state.md) — 단일 방 패턴
- [Scaling Guide](./scaling.md) — 대규모 연결 처리
