# Multi-Device Session Sync Pattern

한 유저가 여러 디바이스(폰, 태블릿, PC)에서 동시에 접속할 때 동일한 상태를 공유하는 패턴이다.
기존 **Room 패턴**(`createSharedStateRoomServer`)에서 **roomId = userId**로 설정하면 자연스럽게 구현된다.

```
User "alice"
├── Phone    → ws://server/sync/alice  ─┐
├── Tablet   → ws://server/sync/alice  ─┼─► Room "alice" (SharedStateServer) ──► 상태 공유
└── Desktop  → ws://server/sync/alice  ─┘

User "bob"
└── Phone    → ws://server/sync/bob    ─── Room "bob"  (별도 상태)
```

---

## 기존 패턴과의 관계

| 패턴 | roomId 의미 | 적용 예시 |
|------|-------------|----------|
| [Room](./pattern-room.md) | 방 이름 (general, random) | 다중 채팅방 |
| **Multi-Device** | **userId** (alice, bob) | 디바이스 간 동기화 |
| [Per-Client](./pattern-per-client.md) | 클라이언트 ID | 비공개 상태 |

Multi-Device는 Room 패턴의 **응용**이다. 코드 구조가 동일하고, roomId의 **의미**만 다르다:

```
Room 패턴:     roomId = "general"  → 같은 방의 유저들이 상태 공유
Multi-Device:  roomId = "alice"    → 같은 유저의 디바이스들이 상태 공유
```

---

## 아키텍처

### 서버 구조

```
┌───────────────────────────────────────────────────────────┐
│                         Server                             │
│  ┌─────────────────────────────────────────────────────┐  │
│  │          RoomServer (roomId = userId)                │  │
│  │  ┌───────────────┐  ┌───────────────┐               │  │
│  │  │ Room "alice"  │  │ Room "bob"    │               │  │
│  │  │ (Store)       │  │ (Store)       │               │  │
│  │  │ ┌───────────┐ │  │ ┌───────────┐ │               │  │
│  │  │ │ Notes: 5  │ │  │ │ Notes: 3  │ │               │  │
│  │  │ │ Devices:  │ │  │ │ Devices:  │ │               │  │
│  │  │ │  phone    │ │  │ │  phone    │ │               │  │
│  │  │ │  desktop  │ │  │ └───────────┘ │               │  │
│  │  │ └───────────┘ │  └───────┬───────┘               │  │
│  │  └───────┬───────┘          │                       │  │
│  └──────────┼──────────────────┼───────────────────────┘  │
└─────────────┼──────────────────┼──────────────────────────┘
              │                  │
      ┌───────┼───────┐     ┌───┴───┐
      │       │       │     │       │
   Phone  Tablet  Desktop  Phone  (bob의 디바이스)
   (alice)                 (bob)
```

### 데이터 흐름

```
alice/phone                    Server (Room "alice")           alice/desktop
     │                              │                              │
     │── AddNote("Buy milk") ─────►│                              │
     │                              │ Processor: AddNote           │
     │                              │ → ServerNoteAction.NoteAdded │
     │                              │ Reducer: notes += newNote    │
     │                              │                              │
     │◄── SyncState(notes=[...]) ──│── SyncState(notes=[...]) ──►│
     │    (즉시 동기화)             │    (즉시 동기화)             │
```

---

## 구현

### Server

```kotlin
val roomServer = createSharedStateRoomServer(
    // roomId = userId — 핵심 아이디어
    initialStateFactory = { userId ->
        ServerNoteState(userId = userId)
    },
    reducer = serverNoteReducer,
    processors = noteProcessors(),
    stateMapper = { state ->
        SharedNoteAction.SyncState(
            NoteState(
                notes = state.notes,
                connectedDevices = state.connectedDevices,
            )
        )
    },
    scope = applicationScope,
)

// WebSocket 엔드포인트: /sync/{userId}
webSocket("/sync/{userId}") {
    val userId = call.parameters["userId"]!!
    val room = roomServer.getOrCreateRoom(userId)  // userId가 roomId
    val sessionId = UUID.randomUUID().toString()
    val connection = KtorWebSocketServerConnection(this)
        .typedJson<SharedNoteAction>() as TypedServerConnection<NoteAction>

    try {
        room.handleClient(sessionId, connection)
    } finally {
        roomServer.destroyRoomIfEmpty(userId)
    }
}
```

### Client

```kotlin
// userId로 서버에 연결 — 같은 userId의 모든 디바이스가 같은 Room에 접속
val connection = KtorWebSocketClientConnection.create(
    host = "localhost",
    port = 8080,
    path = "/sync/$userId",  // userId가 경로에 포함
).typedJson<SharedNoteAction>() as TypedClientConnection<NoteAction>

val store = createClientStore(
    initialState = ClientNoteState(userId = userId),
    syncMiddleware = NoteRemoteMiddleware(connection),
    reducer = clientNoteReducer,
)
```

---

## Use Cases

| Use Case | 공유 상태 | roomId |
|----------|----------|--------|
| 메모 동기화 | 메모 목록 | userId |
| 설정 동기화 | 앱 설정 (테마, 알림 등) | userId |
| 장바구니 | 장바구니 항목 | userId |
| 읽기 진행률 | 책/문서의 마지막 위치 | userId |
| 알림 센터 | 읽음/안읽음 상태 | userId |

---

## 인증 연동

실제 서비스에서는 WebSocket 경로에 userId를 노출하는 대신, [인밴드 인증](./remote-authentication.md)으로 userId를 추출한다:

```kotlin
// 서버: AuthVerifier에서 userId 추출 → roomId로 사용
webSocket("/sync") {
    val authenticated = authServer.authenticate(session)
    val userId = authenticated.getOrElse { return@webSocket }
        .principal.userId  // 인증된 userId

    val room = roomServer.getOrCreateRoom(userId)  // userId = roomId
    room.handleClient(sessionId, authenticated.connection)
}
```

```kotlin
// 클라이언트: 토큰으로 인증 후 연결
val connection = KtorWebSocketClientConnection.create(
    host = "server.example.com",
    port = 443,
    path = "/sync",  // userId가 경로에 없음
).authenticated(tokenProvider = { authToken })
 .typedJson<SharedNoteAction>()
```

---

## 디바이스별 차별화

`DeviceConnected` 액션에 디바이스 정보를 포함하면, 서버 상태에서 디바이스를 식별할 수 있다:

```kotlin
// 연결 시 디바이스 정보 전송
store.dispatch(SharedNoteAction.DeviceConnected(deviceName = "phone"))

// 서버 상태에서 연결된 디바이스 확인
data class ServerNoteState(
    val userId: String,
    val connectedDevices: Set<String>,  // {"phone", "desktop"}
    // ...
)
```

활용 예시:
- 연결된 디바이스 목록 표시 ("Phone, Desktop에서 접속 중")
- 디바이스별 다른 UI 힌트 전송 (stateMapper에서 분기)
- 특정 디바이스에만 알림 전송

---

## Room 패턴과의 비교

| 항목 | Room (채팅방) | Multi-Device (유저 동기화) |
|------|--------------|--------------------------|
| roomId | 방 이름 | userId |
| 참가자 | 서로 다른 유저들 | 한 유저의 여러 디바이스 |
| 방 생성 | 명시적 (유저가 방 선택) | 암묵적 (로그인 시 자동) |
| 방 삭제 | 빈 방 정리 | 모든 디바이스 오프라인 시 정리 |
| 상태 의미 | 공유 컨텐츠 (메시지) | 개인 데이터 (메모, 설정) |

---

## 샘플 앱

[Multi-Device Notes Sample](./samples.md#remote-multi-device-sample)에서 실행 가능한 예제를 확인할 수 있다.

```bash
# 서버
./gradlew :kotlin:sample-remote-multidevice:server:run

# alice의 phone
./gradlew :kotlin:sample-remote-multidevice:client:run --args="alice phone"

# alice의 desktop (같은 userId → 상태 공유!)
./gradlew :kotlin:sample-remote-multidevice:client:run --args="alice desktop"

# bob의 phone (다른 userId → 별도 상태)
./gradlew :kotlin:sample-remote-multidevice:client:run --args="bob phone"
```

---

## 스케일 아웃

싱글 서버에서는 모든 디바이스가 같은 프로세스의 Room에 접속하므로 자연스럽게 동기화된다.
서버를 여러 대로 스케일 아웃하면, 같은 유저의 디바이스가 서로 다른 서버에 접속할 수 있다:

```
❌ 스케일 아웃 시 문제
alice/phone   → Server-1 (Room "alice" — 메모 3개)
alice/desktop → Server-2 (Room "alice" — 비어있음!)  ← 동기화 안 됨
```

### 방법 1: Sticky Session (Affinity Routing)

로드밸런서에서 userId 기반 consistent hashing으로 같은 유저의 모든 디바이스를 항상 같은 서버로 라우팅한다.

```
                  LB (hash(userId) → Server)
                         │
            ┌────────────┴────────────┐
            │                         │
         Server-1                  Server-2
         Room "alice"              Room "bob"
         ├── phone                 └── phone
         ├── tablet
         └── desktop
```

```nginx
# nginx 예시: WebSocket sticky session (consistent hashing)
upstream backend {
    hash $arg_userId consistent;
    server server-1:8080;
    server server-2:8080;
}
```

- **장점**: 코드 변경 없음, 현재 샘플 그대로 동작
- **단점**: 서버 추가/제거 시 세션 재분배, 특정 서버에 핫유저 집중 가능, 서버 장애 시 해당 유저 세션 유실

### 방법 2: Node Mediator 패턴

FlowDux의 [Node Mediator 패턴](./pattern-node-mediator.md)을 사용하면, 각 서버 인스턴스가 Node가 되고
Central 서버가 cross-node 릴레이를 담당한다. 디바이스가 어느 Node에 붙든 상관없이 동기화된다.

```
alice/phone → Node-1 ─┐                   ┌─ Node-2 ← alice/desktop
                       ├─► Central ◄──────┘
bob/phone   → Node-1 ─┘    (relay)    ┌─ Node-3 ← bob/tablet
                                       └─ Node-3 ← charlie/phone
```

동작 흐름:

```
alice/phone (Node-1)        Central             alice/desktop (Node-2)
     │                         │                        │
     │── AddNote ────────────►│                        │
     │                         │ relay (Node-1 제외)    │
     │                         │── AddNote ───────────►│
     │                         │                        │
     │◄── SyncState ──────── │                        │
     │                         │ ──── SyncState ──────►│
     │                         │                (즉시 동기화)
```

- **장점**: 디바이스가 어느 서버에 붙든 동기화, 서버 장애 시 다른 Node에서 계속 동작
- **단점**: Central 서버 추가 구성 필요, 네트워크 홉 증가

### 비교

| | Sticky Session | Node Mediator |
|---|---|---|
| 코드 변경 | 없음 (LB 설정만) | Node + Central 구성 |
| 디바이스 라우팅 | 같은 서버 강제 | 아무 서버나 가능 |
| 서버 장애 시 | 해당 유저 세션 유실 | 다른 Node에서 계속 동작 |
| 확장성 | 중간 (~10K users) | 높음 (~100K+ users) |
| 복잡도 | 낮음 | 중간 |
| 지연시간 | 최소 (직접 통신) | Central 경유 (1홉 추가) |

**권장**: 초기에는 Sticky Session으로 시작하고, 규모가 커지면 Node Mediator로 전환.
Node Mediator 구현 예시는 [Node Mediator 샘플](./samples.md#remote-node-mediator-sample)을 참조.

---

## Related

- [Room Pattern](./pattern-room.md) — Multi-Device의 기반 패턴
- [Node Mediator Pattern](./pattern-node-mediator.md) — 스케일 아웃 시 cross-node 릴레이
- [Scaling Architecture](./scaling.md) — 대규모 연결 처리
- [Server Patterns](./server-patterns.md) — 전체 서버 패턴 비교
- [Remote Authentication](./remote-authentication.md) — 인증 연동
- [Samples](./samples.md) — 샘플 앱 실행 가이드
