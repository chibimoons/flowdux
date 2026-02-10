# Room Pattern (1:N:M)

Room 패턴은 그룹(방) 단위로 상태를 관리하고 메시지를 라우팅하는 구조입니다. 각 방은 독립된 Store를 가지며, 방 간 상태는 완전히 격리됩니다.

## 작성해야 할 파일

```
shared/                           # 공유 모듈
├── State.kt                      # 방 상태 (ChatRoomState, ChatState, 변환 함수)
└── Actions.kt                    # SharedChatAction, ServerChatAction

server/                           # 서버 모듈
├── Main.kt                       # createSharedStateRoomServer() + WebSocket 라우팅
├── Reducer.kt                    # 방 리듀서
└── Processors.kt                 # 액션 변환 로직

client/                           # 클라이언트 모듈
├── Main.kt                       # connectToRoom() 함수, Store 생성
├── Reducer.kt                    # 클라이언트 리듀서
└── RemoteMiddleware.kt           # SyncMiddleware 상속
```

### 각 파일의 역할

| 파일 | 필수 | 설명 |
|------|:----:|------|
| `shared/State.kt` | ✅ | `ChatRoomState` (서버), `ChatState` (클라이언트), `toChatState()` |
| `shared/Actions.kt` | ✅ | `SharedChatAction` + `ServerChatAction` |
| `server/Main.kt` | ✅ | `createSharedStateRoomServer()`, `/room/{roomId}` 라우팅 |
| `server/Reducer.kt` | ✅ | 방 상태 변경 로직 |
| `server/Processors.kt` | ✅ | `ServerSharedAction` → 내부 액션 변환 |
| `client/Main.kt` | ✅ | `connectToRoom(roomId)` 함수, 방별 Store 생성 |
| `client/Reducer.kt` | ✅ | `SyncState` 처리 |
| `client/RemoteMiddleware.kt` | ✅ | Connect/Disconnect 처리 |

### Single Client 패턴과의 차이점

| 항목 | Single Client | Room |
|------|---------------|------|
| 서버 팩토리 | `createSingleClientServer()` | `createSharedStateRoomServer()` |
| Store 수 | 클라이언트당 1개 | 방당 1개 (N 클라이언트 공유) |
| 라우팅 | `/ws` | `/room/{roomId}` |

## 개요

```
┌──────┐ ┌──────┐ ┌──────┐
│Room 1│ │Room 2│ │Room 3│   방마다 독립된 Store
└──┬───┘ └──┬───┘ └──┬───┘
   │        │        │
┌──┼──┐  ┌──┼──┐  ┌──┼──┐
▼  ▼  ▼  ▼  ▼  ▼  ▼  ▼  ▼
C1 C2 C3 C4 C5 C6 C7 C8 C9
```

- **1 Room = 1 SharedStateServer** — 방마다 독립된 Store와 상태
- **메시지 라우팅 O(방 인원)** — 전체 유저가 아닌 방 구성원에게만 전송
- **방 간 상태 격리** — 각 방은 서로 영향 없음

## 기본 사용법 (단일 방)

`SharedStateServer`가 Room Store 패턴의 핵심입니다.

```kotlin
import io.flowdux.remote.server.pattern.createSharedStateServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

val roomScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

// 1개 Room 생성
val chatRoom = createSharedStateServer(
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

### createRoomServer 사용 (권장)

`RoomServer`는 다중 방 관리를 위한 래퍼입니다. `SharedStateServer` 또는 `PerClientServer` 방을 지원합니다.

#### SharedStateServer 방 (방 내 상태 공유)

```kotlin
import io.flowdux.remote.server.pattern.createSharedStateRoomServer

val roomServer = createSharedStateRoomServer(
    initialStateFactory = { roomId -> ChatRoomState(roomId = roomId) },
    reducer = chatRoomReducer,
    stateMapper = { state -> SharedChatAction.SyncState(state.toChatState()) },
    processors = chatProcessors(),
    scope = applicationScope,
)

// 방 접근
val room = roomServer.getOrCreateRoom("general")
room.handleClient(sessionId, connection)

// 빈 방 정리
roomServer.cleanupEmptyRooms()

// 종료
roomServer.close()
```

#### PerClientServer 방 (방 내 클라이언트별 비공개 상태)

```kotlin
import io.flowdux.remote.server.pattern.createPerClientRoomServer

val pokerLobby = createPerClientRoomServer(
    initialStateFactory = { tableId, playerId -> PlayerState(tableId, playerId) },
    reducer = playerReducer,
    stateMapper = { SyncHand(it.hand) },
    scope = applicationScope,
)

webSocket("/table/{tableId}/{playerId}") {
    val tableId = call.parameters["tableId"]!!
    val playerId = call.parameters["playerId"]!!
    val table = pokerLobby.getOrCreateRoom(tableId)
    table.handleClient(playerId, connection)
}
```

#### 커스텀 팩토리

```kotlin
import io.flowdux.remote.server.pattern.createRoomServer

// 어떤 ClientHandler든 사용 가능
val roomServer = createRoomServer { roomId ->
    createSharedStateServer(
        initialState = ChatRoomState(roomId = roomId),
        reducer = chatRoomReducer,
        stateMapper = { state -> SharedChatAction.SyncState(state.toChatState()) },
        scope = applicationScope,
    )
}
```

### 커스텀 RoomManager (세부 제어 필요 시)

`createRoomServer`가 제공하는 것 이상의 제어가 필요한 경우 직접 구현할 수 있습니다.

```kotlin
import io.flowdux.remote.server.pattern.SharedStateServer
import io.flowdux.remote.server.pattern.createSharedStateServer
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.ConcurrentHashMap

class RoomManager(
    private val applicationScope: CoroutineScope,
) {
    private val rooms = ConcurrentHashMap<String, SharedStateServer<ChatRoomState, ChatAction>>()

    /** 방 생성 */
    fun createRoom(roomId: String): SharedStateServer<ChatRoomState, ChatAction> {
        return rooms.computeIfAbsent(roomId) {
            createSharedStateServer(
                initialState = ChatRoomState(roomId = roomId),
                reducer = chatRoomReducer,
                processors = chatProcessors(),
                stateMapper = { state -> SharedChatAction.SyncState(state.toChatState()) },
                scope = applicationScope,
            )
        }
    }

    /** 방 조회 */
    fun getRoom(roomId: String): SharedStateServer<ChatRoomState, ChatAction>? = rooms[roomId]

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
val roomServer = createSharedStateRoomServer(
    initialStateFactory = { roomId -> ChatRoomState(roomId = roomId) },
    reducer = chatRoomReducer,
    stateMapper = { state -> SharedChatAction.SyncState(state.toChatState()) },
    scope = applicationScope,
)

routing {
    // 방 목록 API
    get("/rooms") {
        call.respond(roomServer.roomIds())
    }

    // 방별 WebSocket 연결
    webSocket("/room/{roomId}") {
        val roomId = call.parameters["roomId"] ?: return@webSocket close(
            CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Room ID required")
        )

        val room = roomServer.getOrCreateRoom(roomId)
        val sessionId = UUID.randomUUID().toString()

        val connection = KtorWebSocketServerConnection(this)
            .typedJson<SharedChatAction>() as TypedServerConnection<ChatAction>

        println("[$roomId] Client $sessionId joined")

        try {
            room.handleClient(sessionId, connection)
        } finally {
            println("[$roomId] Client $sessionId left")

            // 선택: 빈 방 자동 정리
            roomServer.destroyRoomIfEmpty(roomId)
        }
    }
}
```

## 클라이언트 구현

### Client Middleware

```kotlin
import io.flowdux.ActionProcessorMap
import io.flowdux.remote.SyncMiddleware
import io.flowdux.remote.TypedClientConnection

// 로컬 전용 액션
sealed interface ClientChatAction : ChatAction {
    data object Connect : ClientChatAction
    data object Disconnect : ClientChatAction
}

class ChatRemoteMiddleware(
    connection: TypedClientConnection<ChatAction>,
) : SyncMiddleware<ClientChatState, ChatAction>(
    connection = connection,
) {
    override val name: String = "ChatRemoteMiddleware"

    override val processors: ActionProcessorMap<ClientChatState, ChatAction> = buildProcessors {
        on<ClientChatAction.Connect> { _, _ ->
            startConnection()
        }
        on<ClientChatAction.Disconnect> { _, _ ->
            stopConnection()
        }
    }
}
```

### Client Usage

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
val room = createSessionAwareSharedStateServer(
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
├── Room "general" (SharedStateServer)
├── Room "random" (SharedStateServer)
└── Room "game-123" (SharedStateServer)

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

자세한 확장 전략은 [Scaling Guide](./scaling.md)를 참조하세요.

## Use Cases

### 1. 다중 채팅방 (Slack, Discord)

채널/서버별 독립된 메시지 공간입니다.

```kotlin
class ChatRoomManager(private val scope: CoroutineScope) {
    private val rooms = ConcurrentHashMap<String, SharedStateServer<...>>()

    fun getOrCreateRoom(channelId: String) = rooms.computeIfAbsent(channelId) {
        createSharedStateServer(
            initialState = ChannelState(channelId = channelId),
            reducer = channelReducer,
            stateMapper = { SharedAction.SyncChannel(it) },
            scope = scope,
        )
    }
}
```

### 2. 게임 로비/매치

매치별 독립된 게임 상태를 관리합니다.

```kotlin
class GameLobby(private val scope: CoroutineScope) {
    private val matches = ConcurrentHashMap<String, SharedStateServer<...>>()

    fun createMatch(matchId: String, settings: MatchSettings): SharedStateServer<...> {
        return matches.computeIfAbsent(matchId) {
            createSharedStateServer(
                initialState = MatchState(matchId = matchId, settings = settings),
                reducer = matchReducer,
                stateMapper = { SharedAction.SyncMatch(it) },
                scope = scope,
            )
        }
    }

    suspend fun cleanupFinishedMatches() {
        matches.entries.removeIf { (_, match) ->
            match.currentState.status == MatchStatus.FINISHED
        }
    }
}
```

### 3. 온라인 강의실

수업별 독립된 콘텐츠 공간입니다.

```kotlin
class ClassroomManager(private val scope: CoroutineScope) {
    private val classrooms = ConcurrentHashMap<String, SharedStateServer<...>>()

    fun getClassroom(classId: String) = classrooms.computeIfAbsent(classId) {
        createSharedStateServer(
            initialState = ClassroomState(classId = classId),
            reducer = classroomReducer,
            stateMapper = { SharedAction.SyncClassroom(it) },
            scope = scope,
        )
    }
}

// 강사용 API
post("/classroom/{classId}/slide") {
    val classId = call.parameters["classId"]!!
    val slideIndex = call.receive<Int>()
    classroomManager.getClassroom(classId).store.dispatch(
        ServerAction.ChangeSlide(slideIndex)
    )
}
```

### 4. 협업 프로젝트 보드

프로젝트별 독립된 보드입니다.

```kotlin
class ProjectBoardManager(private val scope: CoroutineScope) {
    private val boards = ConcurrentHashMap<String, SharedStateServer<...>>()

    fun getBoard(projectId: String) = boards.computeIfAbsent(projectId) {
        createSharedStateServer(
            initialState = BoardState(projectId = projectId),
            reducer = boardReducer,
            stateMapper = { SharedAction.SyncBoard(it) },
            scope = scope,
        )
    }
}
```

### 5. 다중 문서 편집

문서별 독립된 편집 세션입니다.

```kotlin
class DocumentManager(private val scope: CoroutineScope) {
    private val documents = ConcurrentHashMap<String, SharedStateServer<...>>()

    fun getDocument(docId: String) = documents.computeIfAbsent(docId) {
        createSharedStateServer(
            initialState = DocumentState(docId = docId),
            reducer = documentReducer,
            processors = otProcessors(),  // OT 변환
            stateMapper = { SharedAction.SyncDocument(it) },
            scope = scope,
        )
    }
}
```

## 언제 사용하나요?

| Use Case | 설명 |
|----------|------|
| **다중 채팅방** | Slack, Discord 스타일 채널 |
| **게임 매치** | 매치별 독립 상태 |
| **온라인 강의** | 수업별 독립 콘텐츠 |
| **협업 도구** | 프로젝트별 독립 보드 |
| **다중 문서** | 문서별 독립 편집 세션 |

## 다른 패턴으로 전환

Room 패턴에서 다른 패턴으로 전환해야 하는 신호:

| 신호 | 전환 대상 |
|------|----------|
| "방 하나만 있으면 돼요" | [Shared State](./pattern-shared-state.md) |
| "방 내에서도 비공개 정보가 필요해요" | [Per-Client](./pattern-per-client.md) (Hybrid) |
| "각 클라이언트가 완전 독립이에요" | [Single Client](./pattern-single-client.md) |

## Sample App

```bash
# 서버 실행
./gradlew :kotlin:sample-remote-multiroom:server:run

# 클라이언트 실행 (다른 방으로)
./gradlew :kotlin:sample-remote-multiroom:client:run --args="Alice general"
./gradlew :kotlin:sample-remote-multiroom:client:run --args="Bob random"
```

## Related

- [Server Patterns Overview](./server-patterns.md) — 패턴 선택 가이드
- [Shared State Pattern](./pattern-shared-state.md) — 단일 방 패턴
- [Per-Client Pattern](./pattern-per-client.md) — 비공개 상태 추가
- [Scaling Guide](./scaling.md) — 대규모 연결 처리
