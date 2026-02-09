# REST 없이 실시간 채팅 만들기: Action/State 실전 편

*개념에서 동작하는 코드로 — 엔드포인트 대신 액션과 상태로 채팅 앱 설계하기*

*3부작 시리즈의 Part 2입니다. [← Part 1: REST API를 설계하지 않아도 된다면?](./part1-concept-ko.md) | [Part 3: Action/State 스케일링 →](./part3-scaling-ko.md)*

> **안내:** 이 시리즈는 *개념 제안*입니다. REST의 보편적 대체가 아닌, 대안 통신 패턴으로서 Action/State 모델을 탐구합니다. 아이디어를 공유하고 토론을 이끌어내는 것이 목적입니다.

---

[Part 1](./part1-concept-ko.md)에서 급진적인 단순화를 제안했습니다: REST 엔드포인트를 설계하는 대신, **Action**(사용자가 하는 것)과 **State**(결과로 바뀌는 것)만 설계하자. 실시간 동기화는 공짜로 따라오고, API 계약 전체가 단일 `sealed interface`에 들어가며, URL·HTTP 메서드·상태 코드·DTO라는 설계 결정 카테고리 전체를 건너뛸 수 있다고 했습니다.

대담한 주장이었습니다. 실제로 만들어서 증명해 봅시다.

이 글에서는 **다중 사용자 실시간 채팅 앱**을 만듭니다 — 먼저 REST로 어떻게 하는지 스케치하고, 이어서 Action/State 모델로 전부 구현합니다. 둘을 나란히 비교합니다.

---

## 요구사항

기본 채팅 앱에 필요한 것:

- 사용자가 채팅방에 참여할 수 있다
- 사용자가 메시지를 보낼 수 있다
- 사용자가 방을 나갈 수 있다
- 연결된 모든 사용자가 새 메시지를 실시간으로 본다
- 연결된 모든 사용자가 입장/퇴장 이벤트를 실시간으로 본다

간단합니다. 하지만 마지막 두 요구사항 — "실시간으로" — 이 부분에서 REST가 복잡해지기 시작합니다.

---

## REST로 만든다면

### 엔드포인트 설계

```
POST   /api/v1/chat/join         { "user": "Alice" }
POST   /api/v1/chat/messages     { "user": "Alice", "text": "Hello" }
DELETE /api/v1/chat/leave         { "user": "Alice" }
GET    /api/v1/chat/messages     → 페이지네이션된 메시지 목록
GET    /api/v1/chat/users        → 현재 사용자 목록
```

5개의 엔드포인트입니다. 각각에 필요한 것:
- URL 경로
- HTTP 메서드
- 요청/응답 DTO
- 상태 코드 (200, 201, 400, 404...)
- 에러 응답 포맷

### 실시간 문제

이 엔드포인트들은 요청-응답을 처리합니다. 하지만 요구사항은 사용자가 메시지를 *도착하는 즉시* 봐야 한다고 합니다. 다음 중 하나가 추가로 필요합니다:

1. **폴링** — 매초 `GET /messages`. 낭비이고 지연됨.
2. **SSE** — 실시간을 위한 Server-Sent Events. 단방향; 전송은 여전히 REST.
3. **WebSocket** — REST 옆에 두 번째 통신 채널.

어떤 것이든 두 번째 프로토콜을 설계해야 합니다. WebSocket이라면 `"new_message"`, `"user_joined"`, `"user_left"` 같은 이벤트 타입을 정의하게 됩니다 — REST DTO와는 별개의 스키마. 유지해야 할 계약이 둘.

### 전체 설계 표면

"간단한" 채팅 앱을 REST + 실시간으로:

- REST 엔드포인트 5개
- 요청/응답 DTO 쌍 5개
- 자체 메시지 포맷을 가진 WebSocket 채널 1개
- WebSocket의 이벤트 타입 최소 3개
- REST와 WebSocket 양쪽의 인증
- 양쪽 채널의 에러 처리
- REST 응답과 WebSocket 이벤트 간 상태 조정

채팅 앱 치고 표면적이 상당합니다.

---

## Action/State로 만들기

### 1단계: 계약 정의

모든 것이 여기서 시작됩니다. 사용자가 뭘 할 수 있나? 상태는 어떻게 생겼나?

```kotlin
// ─── 공유 모듈 (클라이언트 + 서버 모두 참조) ───

// Action — 사용자가 무엇을 할 수 있는가
@Serializable
sealed interface SharedChatAction : ChatAction {
    // Client → Server
    @Serializable
    data class JoinRoom(val user: String) : SharedChatAction, ServerSharedAction

    @Serializable
    data class LeaveRoom(val user: String) : SharedChatAction, ServerSharedAction

    @Serializable
    data class SendMessage(val user: String, val text: String) : SharedChatAction, ServerSharedAction

    // Server → Client
    @Serializable
    data class SyncState(val state: ChatState) : SharedChatAction, ClientSharedAction
}

// State — 세상이 어떻게 생겼는가
@Serializable
data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val users: Set<String> = emptySet(),
    val lastEvent: ChatEvent? = null,
)

@Serializable
data class ChatMessage(val user: String, val text: String)

@Serializable
sealed interface ChatEvent {
    @Serializable data class UserJoined(val user: String) : ChatEvent
    @Serializable data class UserLeft(val user: String) : ChatEvent
    @Serializable data class MessageReceived(val user: String, val text: String) : ChatEvent
}
```

API 전체입니다. 클라이언트가 보낼 수 있는 액션 3개(`JoinRoom`, `LeaveRoom`, `SendMessage`), 서버가 돌려보내는 액션 1개(`SyncState`), 그리고 상태 구조. `ServerSharedAction`과 `ClientSharedAction` 마커가 방향을 타입 시스템에 인코딩합니다 — 클라이언트에서 `SyncState`를 보내려 하면 컴파일러가 막습니다.

*없는 것*을 주목하세요: URL, HTTP 메서드, 상태 코드, 페이지네이션 파라미터, 별도의 요청/응답 타입. 설계 공간이 축소되었습니다.

### 2단계: 서버 구현

서버는 클라이언트에 노출하는 것보다 더 많은 내부 상태를 가질 수 있습니다:

```kotlin
// ─── 서버 모듈 ───

// 서버 내부 액션 (와이어를 건너지 않음)
sealed interface ServerChatAction : ChatAction {
    data class MessageReceived(val user: String, val text: String) : ServerChatAction
    data class UserJoined(val user: String) : ServerChatAction
    data class UserLeft(val user: String) : ServerChatAction
}

// 서버 상태 — 클라이언트가 못 보는 추가 필드
data class ServerChatState(
    val messages: List<ChatMessage> = emptyList(),
    val users: Set<String> = emptySet(),
    val lastEvent: ChatEvent? = null,
    val totalMessagesProcessed: Int = 0,  // 서버 전용 메트릭
) : State
```

리듀서가 서버 내부 액션을 처리합니다:

```kotlin
val serverChatReducer: Reducer<ServerChatState, ChatAction> = buildReducer {
    on<ServerChatAction.MessageReceived> { state, action ->
        state.copy(
            messages = state.messages + ChatMessage(action.user, action.text),
            lastEvent = ChatEvent.MessageReceived(action.user, action.text),
            totalMessagesProcessed = state.totalMessagesProcessed + 1,
        )
    }
    on<ServerChatAction.UserJoined> { state, action ->
        state.copy(
            users = state.users + action.user,
            lastEvent = ChatEvent.UserJoined(action.user),
        )
    }
    on<ServerChatAction.UserLeft> { state, action ->
        state.copy(
            users = state.users - action.user,
            lastEvent = ChatEvent.UserLeft(action.user),
        )
    }
}
```

미들웨어가 들어오는 공유 액션을 서버 내부 액션으로 변환합니다:

```kotlin
class ChatSingleClientSyncMiddleware(
    connection: TypedServerConnection<ChatAction>,
) : SingleClientSyncMiddleware<ServerChatState, ChatAction>(connection) {

    override val processors = buildProcessors {
        on<SharedChatAction.SendMessage> { _, action ->
            emit(ServerChatAction.MessageReceived(action.user, action.text))
        }
        on<SharedChatAction.JoinRoom> { _, action ->
            emit(ServerChatAction.UserJoined(action.user))
        }
        on<SharedChatAction.LeaveRoom> { _, action ->
            emit(ServerChatAction.UserLeft(action.user))
        }
    }
}
```

서버 진입점 — 모든 것이 여기서 합쳐집니다:

```kotlin
fun main() {
    embeddedServer(CIO, port = 8080) {
        install(WebSockets)

        routing {
            webSocket("/chat") {
                println("[Server] Client connected")

                createChatStore(this).serve { serverState ->
                    SharedChatAction.SyncState(
                        ChatState(
                            messages = serverState.messages,
                            users = serverState.users,
                            lastEvent = serverState.lastEvent,
                        )
                    )
                }
            }
        }
    }.start(wait = true)
}
```

`.serve { }` 블록이 핵심입니다. 두 가지를 합니다:
1. 클라이언트로부터 들어오는 액션을 수신해서 스토어에 디스패치
2. 상태 변경을 관찰하고 매핑 함수를 통해 클라이언트에 푸시

매핑 함수는 서버 상태를 선택적으로 노출합니다 — `totalMessagesProcessed`는 서버 내부이며 절대 클라이언트로 전송되지 않습니다. 클라이언트는 `SyncState` 안의 `ChatState`가 담고 있는 것만 봅니다.

### 3단계: 클라이언트 구현

클라이언트는 클라이언트 로컬 필드가 포함된 자체 상태를 가집니다:

```kotlin
// ─── 클라이언트 모듈 ───

// 클라이언트 전용 액션 (와이어를 건너지 않음)
sealed interface ClientChatAction : ChatAction {
    data object Connect : ClientChatAction
    data object Disconnect : ClientChatAction
    data class SetCurrentUser(val user: String) : ClientChatAction
}

// 클라이언트 상태 — 동기화 필드 + 로컬 필드
data class ClientChatState(
    // 서버에서 동기화
    val messages: List<ChatMessage> = emptyList(),
    val users: Set<String> = emptySet(),
    val lastEvent: ChatEvent? = null,
    // 클라이언트 로컬
    val currentUser: String = "",
) : State
```

클라이언트 리듀서가 동기화된 상태와 로컬 액션을 적용합니다:

```kotlin
val clientChatReducer: Reducer<ClientChatState, ChatAction> = buildReducer {
    on<SharedChatAction.SyncState> { state, action ->
        state.copy(
            messages = action.state.messages,
            users = action.state.users,
            lastEvent = action.state.lastEvent,
        )
    }
    on<ClientChatAction.SetCurrentUser> { state, action ->
        state.copy(currentUser = action.user)
    }
}
```

클라이언트 미들웨어가 연결 라이프사이클을 처리합니다:

```kotlin
class ChatRemoteMiddleware(
    connection: TypedClientConnection<ChatAction>,
) : SyncMiddleware<ClientChatState, ChatAction>(connection) {

    override val processors = buildProcessors {
        on<ClientChatAction.Connect> { _, _ ->
            startConnection()
        }
        on<ClientChatAction.Disconnect> { _, _ ->
            stopConnection()
        }
    }
}
```

사용하기:

```kotlin
fun main() = runBlocking {
    val store = createChatStore()

    // 상태 변경 관찰
    launch {
        store.state.collect { state ->
            when (val event = state.lastEvent) {
                is ChatEvent.UserJoined -> println("[시스템] ${event.user} 입장")
                is ChatEvent.UserLeft -> println("[시스템] ${event.user} 퇴장")
                is ChatEvent.MessageReceived -> println("[${event.user}] ${event.text}")
                null -> {}
            }
        }
    }

    // 연결하고 상호작용
    store.dispatch(ClientChatAction.SetCurrentUser("Alice"))
    store.dispatch(ClientChatAction.Connect)
    store.dispatch(SharedChatAction.JoinRoom("Alice"))
    store.dispatch(SharedChatAction.SendMessage("Alice", "안녕하세요!"))
}
```

클라이언트 코드를 보세요. `SharedChatAction.JoinRoom`을 디스패치합니다 — 공유 액션입니다. `SyncMiddleware`가 가로채서(`ServerSharedAction`이므로) 직렬화하고 WebSocket으로 전송합니다. 서버 쪽에서 `SingleClientSyncMiddleware`가 수신하고 서버 스토어에 디스패치하고, 미들웨어가 처리하고, 리듀서가 상태를 업데이트하고, `.serve {}`가 `SyncState`를 모든 클라이언트에 푸시합니다.

이 모든 것이 단일 `store.dispatch()` 호출에서 일어납니다. HTTP 요청 없음. 엔드포인트 URL 없음. 상태 코드 처리 없음.

---

## 흐름, 단계별로

하나의 액션의 전체 라이프사이클을 추적해 봅시다 — Alice가 메시지를 보냅니다:

```
  Alice 클라이언트                  서버                    Bob 클라이언트
       │                              │                            │
  1.   │  dispatch(SendMessage(       │                            │
       │    "Alice", "Hello"))        │                            │
       │                              │                            │
  2.   │  SyncMiddleware      │                            │
       │  ServerSharedAction 인터셉트  │                            │
       │  → 직렬화 → WebSocket        │                            │
       │                              │                            │
  3.   │ ──── SendMessage ──────────→ │                            │
       │                              │                            │
  4.   │                  SingleClientSyncMiddleware                    │
       │                  수신 & 디스패치                            │
       │                              │                            │
  5.   │                  ChatSingleClientSyncMiddleware                │
       │                  MessageReceived emit                     │
       │                              │                            │
  6.   │                  Reducer 상태 업데이트:                     │
       │                  messages += ChatMessage("Alice","Hello") │
       │                              │                            │
  7.   │                  serve {} 상태 변경 감지                    │
       │                  ServerChatState → SyncState 매핑          │
       │                              │                            │
  8.   │ ←── SyncState(newState) ──── │ ── SyncState(newState) ──→ │
       │                              │                            │
  9.   │  SyncMiddleware      │    SyncMiddleware  │
       │  ClientSharedAction 수신     │    같은 액션 수신            │
       │  → 클라이언트 스토어 디스패치  │    → 스토어 디스패치          │
       │                              │                            │
  10.  │  Reducer가 SyncState 적용    │    같은 내용 적용            │
       │  → UI 업데이트               │    → UI 업데이트            │
```

두 클라이언트가 동일한 상태를 가지게 됩니다. Alice는 자신의 메시지를 봅니다. Bob도 봅니다. 폴링 없음. 별도 구독 없음. 같은 파이프라인이 모든 것을 처리합니다.

---

## 나란히 비교

### 설계 산출물

| 항목 | REST + WebSocket | Action/State |
|------|-----------------|-------------|
| 엔드포인트 정의 | REST 5개 | 0개 |
| DTO 타입 | 10개+ (요청 + 응답 쌍) | 공유 sealed interface 1개 |
| WebSocket 이벤트 타입 | 3개+ (REST와 별개) | 0개 (같은 채널) |
| 인증 지점 | 2곳 (REST + WS) | 1곳 (WS 연결) |
| 상태 조정 | 수동 (REST + WS 데이터 병합) | 자동 (단일 상태 소스) |
| 실시간 설정 | 별도 채널, 별도 프로토콜 | 내장 (같은 디스패치 메커니즘) |

### 코드량

REST 방식에 필요한 것:
- 각 엔드포인트별 라우트 정의
- 요청/응답 직렬화 클래스
- 엔드포인트별 컨트롤러/핸들러
- WebSocket 이벤트 핸들러 (별도)
- 클라이언트 측 HTTP 클라이언트 설정
- 클라이언트 측 WebSocket 클라이언트 설정
- 상태 병합 로직 (REST 응답 + WS 이벤트)

Action/State 방식에 필요한 것:
- 공유 액션 sealed interface
- 서버 리듀서 + 미들웨어
- 클라이언트 리듀서 + 미들웨어
- 서버 `serve {}` 호출
- 클라이언트 `dispatch()` 호출

핵심 차이: REST는 *두 개의 병렬 통신 채널*(HTTP + WebSocket)을 *두 개의 별도 계약*으로 만들고 유지해야 합니다. Action/State는 *하나의 채널*에 *하나의 계약*을 사용합니다.

### 작성하지 않는 것

Action/State에서는 다음을 작성하지 않습니다:
- URL 라우팅 설정
- HTTP 메서드 핸들러
- 상태 코드 매핑
- 엔드포인트별 요청 검증 미들웨어
- 엔드포인트별 응답 직렬화
- WebSocket 이벤트 타입 정의
- 이벤트-상태 조정 로직
- 폴링 로직이나 SSE 설정

---

## 멀티 클라이언트: 하나의 Store, 다수의 연결

위의 간단한 예제는 연결마다 새 Store를 생성합니다. 실제 채팅 앱에서는 모든 클라이언트가 같은 상태에 기여하는 *공유* Store가 필요합니다. 서버가 이렇게 바뀝니다:

```kotlin
fun main() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // 모든 클라이언트를 위한 하나의 서버 — 공유 상태
    val server = createSharedStateServer(
        initialState = ServerChatState(),
        reducer = serverChatReducer,
        processors = chatProcessors(),
        stateMapper = { serverState ->
            SharedChatAction.SyncState(
                ChatState(
                    messages = serverState.messages,
                    users = serverState.users,
                    lastEvent = serverState.lastEvent,
                )
            )
        },
        scope = applicationScope,
    )

    embeddedServer(CIO, port = 8080) {
        install(WebSockets)
        routing {
            webSocket("/chat") {
                val sessionId = UUID.randomUUID().toString()
                val connection = KtorWebSocketServerConnection(this)
                    .typedJson<SharedChatAction>()

                server.handleClient(sessionId, connection)
            }
        }
    }.start(wait = true)
}
```

`createSharedStateServer`가 단일 Store, 세션 레지스트리, 상태 브로드캐스트를 결합한 `RemoteServer` 파사드를 생성합니다. 각 WebSocket 연결이 `handleClient`를 호출하면 액션 수신과 상태 업데이트 브로드캐스트 모두에 등록됩니다. 어떤 클라이언트가 액션을 디스패치하면 Store가 처리하고 새 상태를 *모든* 연결된 클라이언트에 브로드캐스트합니다.

클라이언트 코드는 전혀 바뀌지 않습니다. 여전히 액션을 디스패치하고 상태 업데이트를 받습니다. 서버에 클라이언트가 하나인지 천 개인지 알지도, 신경 쓰지도 않습니다.

---

## 에러 처리는?

현실적인 채팅 앱에는 에러 처리가 필요합니다. Action/State 모델에서 에러는 그냥 상태입니다:

```kotlin
// 공유 계약에 추가
@Serializable
data class ActionError(
    val message: String,
    val code: String,
) : SharedChatAction, ClientSharedAction

// 서버 미들웨어가 검증하고 필요시 에러 emit
on<SharedChatAction.SendMessage> { state, action ->
    if (action.text.isBlank()) {
        emit(ActionError("메시지가 비어있습니다", "EMPTY_MESSAGE"))
        return@on
    }
    if (action.user !in state.users) {
        emit(ActionError("먼저 방에 참여해야 합니다", "NOT_IN_ROOM"))
        return@on
    }
    emit(ServerChatAction.MessageReceived(action.user, action.text))
}
```

클라이언트는 다른 서버 액션과 같은 방식으로 에러를 처리합니다 — 리듀서를 통해:

```kotlin
on<ActionError> { state, action ->
    state.copy(error = action)
}
```

외울 상태 코드가 없습니다. 별도로 설계할 에러 응답 포맷이 없습니다. 에러는 같은 타입 계약의 일부입니다.

---

## flowdux-remote에 대하여

이 글의 모든 코드는 Action/State 패턴의 참조 구현으로 만든 Kotlin 라이브러리 [flowdux-remote](https://github.com/chibimoons/flowdux)를 사용합니다. 제공하는 것:

- `SingleClientSyncMiddleware` / `SyncMiddleware` — 직렬화와 WebSocket 전송 처리
- `serve {}` — 상태 변경 관찰 및 클라이언트에 푸시
- `createSharedStateServer` — 공유 Store에 다중 클라이언트 연결을 관리하는 `RemoteServer` 파사드 생성
- `TypedConnection` — 와이어 프로토콜에 대한 타입 안전 추상화
- `ActionCodec` — 플러거블 직렬화 (`kotlinx.serialization` JSON 포함)

Kotlin Multiplatform과 Ktor WebSocket 전송 위에 구축되었습니다. 하지만 패턴 자체는 언어와 프레임워크에 종속되지 않습니다. TypeScript, Swift, 또는 대수적 타입과 WebSocket 지원이 있는 어떤 언어든 같은 아키텍처를 구현할 수 있습니다.

---

## 다음 편에서

REST 엔드포인트 하나 없이 동작하는 채팅 앱을 만들었습니다. 하나의 공유 계약. 하나의 통신 채널. 기본이 실시간.

하지만 이건 단일 방에 단일 공유 상태였습니다. 실제 애플리케이션에는 다음이 필요합니다:
- 독립적 상태를 가진 다중 Room
- 플레이어별 상태 뷰 (예: 게임에서 상대방의 숨긴 카드를 보면 안 됨)
- 효율적으로 배치된 고빈도 상태 업데이트 (60fps 게임 서버)
- 다중 서버 인스턴스 간 수평 확장

**Part 3**에서 이 모든 것을 다룹니다. Action/State 모델을 채팅방에서 멀티플레이어 게임 서버로 확장하며, Room 관리, StateView 필터링, 틱 기반 배칭, Redis 기반 수평 확장을 소개합니다.

---

*3부작 시리즈의 Part 2입니다. [← Part 1: REST API를 설계하지 않아도 된다면?](./part1-concept-ko.md) | [Part 3: Action/State 스케일링 →](./part3-scaling-ko.md)*

