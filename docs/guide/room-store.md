# Room Store Pattern

Room Store 패턴은 그룹(방) 단위로 상태를 관리하고 메시지를 라우팅하는 구조입니다.

## 언제 사용하나요?

- 채팅방 (메시지는 방 구성원에게만)
- 게임 매치 (매치 상태는 참가자에게만)
- 수업/강의실 (자료는 수강생에게만)
- 팀 단위 협업 공간

## 핵심 개념

```
┌──────┐ ┌──────┐ ┌──────┐
│Room 1│ │Room 2│ │Room 3│   방마다 독립된 Store
└──┬───┘ └──┬───┘ └──┬───┘
   │        │        │
┌──┼──┐  ┌──┼──┐  ┌──┼──┐
▼  ▼  ▼  ▼  ▼  ▼  ▼  ▼  ▼
C1 C2 C3 C4 C5 C6 C7 C8 C9
```

- **1 Room = 1 RemoteServer** — 방마다 독립된 Store와 상태
- **메시지 라우팅 O(방 인원)** — 전체 유저가 아닌 방 구성원에게만 전송
- **방 간 상태 격리** — 각 방은 서로 영향 없음

## 기본 사용법 (단일 방)

`RemoteServer`가 Room Store 패턴의 핵심입니다.

```kotlin
import io.flowdux.remote.server.createRemoteServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

val roomScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

// 1개 Room 생성
val chatRoom = createRemoteServer(
    initialState = ChatRoomState(),
    reducer = chatRoomReducer,
    processors = chatProcessors(),
    stateMapper = { state -> SharedChatAction.SyncState(state.toChatState()) },
    scope = roomScope,
)

// WebSocket 핸들러에서 클라이언트 연결
webSocket("/chat") {
    val sessionId = UUID.randomUUID().toString()
    val connection = KtorWebSocketServerConnection(this)
        .typedJson<SharedChatAction>() as TypedServerConnection<ChatAction>

    chatRoom.handleClient(sessionId, connection)
}

// 종료 시
chatRoom.close()
```

## 다중 방 관리

실제 서비스에서는 여러 방을 동시에 운영합니다.

```kotlin
import java.util.concurrent.ConcurrentHashMap

class RoomManager(
    private val applicationScope: CoroutineScope,
) {
    private val rooms = ConcurrentHashMap<String, RemoteServer<ChatRoomState, ChatAction>>()

    /** 방 생성 */
    fun createRoom(roomId: String): RemoteServer<ChatRoomState, ChatAction> {
        return rooms.computeIfAbsent(roomId) {
            createRemoteServer(
                initialState = ChatRoomState(roomId = roomId),
                reducer = chatRoomReducer,
                processors = chatProcessors(),
                stateMapper = { state -> SharedChatAction.SyncState(state.toChatState()) },
                scope = applicationScope,
            )
        }
    }

    /** 방 조회 */
    fun getRoom(roomId: String): RemoteServer<ChatRoomState, ChatAction>? = rooms[roomId]

    /** 방 삭제 */
    fun destroyRoom(roomId: String) {
        rooms.remove(roomId)?.close()
    }

    /** 빈 방 정리 */
    suspend fun cleanupEmptyRooms() {
        rooms.entries.removeIf { (roomId, room) ->
            val isEmpty = room.sessionCount() == 0
            if (isEmpty) {
                room.close()
                println("Cleaned up empty room: $roomId")
            }
            isEmpty
        }
    }

    /** 전체 종료 */
    fun shutdown() {
        rooms.values.forEach { it.close() }
        rooms.clear()
    }
}
```

### WebSocket 라우팅

```kotlin
val roomManager = RoomManager(applicationScope)

routing {
    // 방 목록 API
    get("/rooms") {
        // ... 방 목록 반환
    }

    // 방별 WebSocket 연결
    webSocket("/room/{roomId}") {
        val roomId = call.parameters["roomId"] ?: return@webSocket close(
            CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Room ID required")
        )

        val room = roomManager.getRoom(roomId) ?: roomManager.createRoom(roomId)
        val sessionId = UUID.randomUUID().toString()

        val connection = KtorWebSocketServerConnection(this)
            .typedJson<SharedChatAction>() as TypedServerConnection<ChatAction>

        println("[$roomId] Client $sessionId joined")

        try {
            room.handleClient(sessionId, connection)
        } finally {
            println("[$roomId] Client $sessionId left")

            // 선택: 빈 방 자동 정리
            if (room.sessionCount() == 0) {
                roomManager.destroyRoom(roomId)
            }
        }
    }
}
```

## 클라이언트 구현

```kotlin
// 특정 방에 연결
fun connectToRoom(roomId: String): Store<ClientChatState, ChatAction> {
    val connection = KtorWebSocketClientConnection.create(
        host = "localhost",
        port = 8080,
        path = "/room/$roomId",  // 방 ID를 경로에 포함
    ).typedJson<SharedChatAction>() as TypedClientConnection<ChatAction>

    return createStore(
        initialState = ClientChatState(),
        reducer = clientChatReducer,
        middlewares = listOf(ChatRemoteMiddleware(connection)),
    )
}

// 사용
val room1Store = connectToRoom("general")
val room2Store = connectToRoom("random")

room1Store.dispatch(ClientChatAction.Connect)
room1Store.dispatch(SharedChatAction.JoinRoom("Alice"))
room1Store.dispatch(SharedChatAction.SendMessage("Alice", "Hello in general!"))
```

## 세션별 차별화 전송 (SessionAware)

방장에게만 관리 정보를 보내거나, 유저별로 다른 데이터를 전송할 때 사용합니다.

```kotlin
val room = createSessionAwareRemoteServer(
    initialState = GameRoomState(),
    reducer = gameRoomReducer,
    processors = gameProcessors(),
    sessionStateMapper = { state, sessionId ->
        // 세션별로 다른 액션 반환
        when {
            sessionId == state.hostSessionId -> {
                // 방장에게는 전체 정보
                SharedGameAction.SyncHostState(
                    players = state.players,
                    settings = state.settings,
                    adminControls = true,
                )
            }
            else -> {
                // 일반 참가자에게는 제한된 정보
                SharedGameAction.SyncPlayerState(
                    players = state.players.map { it.copy(hand = emptyList()) }, // 다른 사람 패 숨김
                    myHand = state.getPlayerHand(sessionId),
                )
            }
        }
    },
    scope = roomScope,
)
```

### 활용 예시

| 시나리오 | 방장/특정 유저 | 일반 유저 |
|----------|---------------|----------|
| 게임 로비 | 시작 버튼, 설정 변경 | 대기 화면만 |
| 포커 | 딜러 컨트롤 | 본인 패만 |
| 퀴즈 | 정답 + 점수 관리 | 문제만 |
| 경매 | 최고 입찰자 상세 | 현재가만 |

## State 설계 팁

```kotlin
// 서버 상태 (풀 정보)
data class ChatRoomState(
    val roomId: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val users: Map<String, UserInfo> = emptyMap(),  // sessionId -> UserInfo
    val hostSessionId: String? = null,
    val createdAt: Instant = Clock.System.now(),

    // 서버 전용 (클라이언트에 전송 안 함)
    val totalMessagesProcessed: Int = 0,
) : State

// 클라이언트에 전송할 상태 (선별)
@Serializable
data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val users: Set<String> = emptySet(),
    val lastEvent: ChatEvent? = null,
) : State

// 변환 함수
fun ChatRoomState.toChatState() = ChatState(
    messages = messages.takeLast(100),  // 최근 100개만
    users = users.keys,
    lastEvent = lastEvent,
)
```

## 확장 고려사항

### 단일 서버 (1만 클라이언트 이하)

```
RoomManager
├── Room "general" (RemoteServer)
├── Room "random" (RemoteServer)
└── Room "game-123" (RemoteServer)

모두 같은 프로세스에서 실행
```

### 대규모 (10만+ 클라이언트)

```
Load Balancer
├── Node A: Room 1~100
├── Node B: Room 101~200
└── Node C: Room 201~300

노드 간 통신이 필요하면 Central Store 패턴과 조합
```

자세한 확장 전략은 [Server Architecture Patterns](../design/server-architecture-patterns.md)를 참조하세요.

## 다음 단계

- [Server Architecture Patterns](../design/server-architecture-patterns.md) — Central Store, Per-Client Store 패턴
- [샘플 앱 실행](./samples.md) — multi-client 샘플 실행 방법
