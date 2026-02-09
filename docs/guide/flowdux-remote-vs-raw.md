# FlowDux Remote vs Raw WebSocket 비교

이 문서는 FlowDux Remote와 Raw WebSocket 구현 방식을 다양한 Use Case별로 비교합니다.

## 핵심 차이점

### 개념적 차이

```
Raw WebSocket:
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│  Message Types  │ +  │  State Updates  │ +  │  Connection Mgmt│
│  (Protocol)     │    │  (Business)     │    │  (Infrastructure)│
└─────────────────┘    └─────────────────┘    └─────────────────┘
        ↓                      ↓                      ↓
   별도 정의 필요          when 분기 작성         try/catch/finally

FlowDux Remote:
┌─────────────────────────────────────────────────────────────────┐
│                    SharedAction (Protocol = Action)              │
│                    Reducer (Business Logic)                      │
│                    Middleware (Connection - 제공됨)               │
└─────────────────────────────────────────────────────────────────┘
```

### 코드 구조 비교

| 영역 | Raw WebSocket | FlowDux Remote |
|------|---------------|----------------|
| **프로토콜 정의** | Message sealed class | SharedAction |
| **상태 관리** | MutableStateFlow + Mutex | Store (thread-safe) |
| **비즈니스 로직** | when 분기 | Reducer |
| **연결 관리** | 직접 구현 | Middleware 제공 |
| **직렬화** | Json.encode/decode 반복 | `.typedJson<T>()` 체이닝 |
| **Broadcast** | clients.forEach { send() } | stateMapper 자동 |
| **에러 처리** | try/catch 곳곳에 | 프레임워크 처리 |

---

## Use Case별 비교

### 1. Multi-Client Chat (1:N Broadcast)

**시나리오**: 채팅방에 여러 클라이언트가 접속, 메시지를 모든 참가자에게 broadcast

#### Raw WebSocket 구현

```kotlin
// === 서버 ===
class ChatServer {
    private val clients = ConcurrentHashMap<String, WebSocketSession>()
    private val state = AtomicReference(ChatState())
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handleClient(session: WebSocketSession) {
        val clientId = UUID.randomUUID().toString()
        clients[clientId] = session

        try {
            // 초기 상태 전송
            session.send(json.encodeToString(SyncMessage(state.get())))

            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    val msg = json.decodeFromString<ChatMessage>(frame.readText())
                    when (msg) {
                        is ChatMessage.Send -> {
                            mutex.withLock {
                                val newState = state.get().copy(
                                    messages = state.get().messages + Message(msg.user, msg.text)
                                )
                                state.set(newState)
                                broadcastState(newState)
                            }
                        }
                        is ChatMessage.Join -> { /* 비슷한 패턴 반복 */ }
                        is ChatMessage.Leave -> { /* 비슷한 패턴 반복 */ }
                    }
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            // 정상 종료
        } catch (e: Exception) {
            println("Error: ${e.message}")
        } finally {
            clients.remove(clientId)
        }
    }

    private suspend fun broadcastState(state: ChatState) {
        val message = json.encodeToString(SyncMessage(state))
        clients.values.forEach { client ->
            try {
                client.send(message)
            } catch (e: Exception) {
                // 실패한 클라이언트 처리
            }
        }
    }
}

// 라우팅
webSocket("/chat") {
    chatServer.handleClient(this)
}
```

**예상 코드량**: 서버 ~150줄, 클라이언트 ~100줄

#### FlowDux Remote 구현

```kotlin
// === 서버 ===
val chatRoom = createSharedStateServer(
    initialState = ServerChatState(),
    reducer = serverChatReducer,
    stateMapper = { SharedChatAction.SyncState(it.toPublic()) },
    scope = applicationScope,
)

webSocket("/chat") {
    val connection = KtorWebSocketServerConnection(this)
        .typedJson<SharedChatAction>()
        .upcast<SharedChatAction, ChatAction>()
    chatRoom.handleClient(sessionId, connection)
}

// Reducer
val serverChatReducer = buildReducer<ServerChatState, ChatAction> {
    on<SharedChatAction.SendMessage> { state, action ->
        state.copy(messages = state.messages + ChatMessage(action.user, action.text))
    }
    on<SharedChatAction.JoinRoom> { state, action ->
        state.copy(users = state.users + action.user)
    }
}
```

**실제 코드량**: 서버 ~60줄, 클라이언트 ~50줄

#### 비교 분석

| 항목 | Raw | FlowDux Remote |
|------|-----|----------------|
| Broadcast 구현 | 수동 loop | stateMapper 자동 |
| 동시성 처리 | Mutex 직접 | Store 내장 |
| 클라이언트 관리 | ConcurrentHashMap | 자동 |
| 코드량 | ~250줄 | ~110줄 |

---

### 2. Per-Client Private State (Poker Game)

**시나리오**: 포커 게임에서 각 플레이어는 자신의 패만 볼 수 있음. 공개 정보(베팅, 턴)는 모두에게 broadcast.

#### Raw WebSocket 구현

```kotlin
// === 서버 ===
class PokerServer {
    private val clients = ConcurrentHashMap<String, WebSocketSession>()
    private val tableState = AtomicReference(TableState())
    private val playerHands = ConcurrentHashMap<String, List<Card>>()
    private val mutex = Mutex()

    suspend fun handlePlayer(playerId: String, session: WebSocketSession) {
        clients[playerId] = session

        try {
            // 공개 상태 전송
            session.send(json.encodeToString(SyncTable(tableState.get().toPublic())))

            // 비공개 패 전송 (있으면)
            playerHands[playerId]?.let { hand ->
                session.send(json.encodeToString(SyncHand(hand)))
            }

            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    val action = json.decodeFromString<PokerAction>(frame.readText())

                    mutex.withLock {
                        // 턴 검증
                        if (action.playerId != tableState.get().currentTurn) {
                            session.send(json.encodeToString(Error("Not your turn")))
                            continue
                        }

                        when (action) {
                            is PokerAction.Bet -> {
                                val newState = processBet(tableState.get(), action)
                                tableState.set(newState)
                                broadcastTableState(newState)
                            }
                            is PokerAction.Fold -> { /* ... */ }
                            // 각 액션마다 비슷한 패턴
                        }
                    }
                }
            }
        } finally {
            handlePlayerDisconnect(playerId)
        }
    }

    private suspend fun dealCards() {
        mutex.withLock {
            val deck = createShuffledDeck()
            var deckIndex = 0

            for (playerId in clients.keys) {
                val hand = listOf(deck[deckIndex++], deck[deckIndex++])
                playerHands[playerId] = hand

                // 각 플레이어에게 개별 전송
                clients[playerId]?.send(json.encodeToString(SyncHand(hand)))
            }
        }
    }

    private suspend fun broadcastTableState(state: TableState) {
        val public = state.toPublic()
        clients.values.forEach { session ->
            try {
                session.send(json.encodeToString(SyncTable(public)))
            } catch (e: Exception) { }
        }
    }
}
```

**예상 코드량**: 서버 ~400줄, 클라이언트 ~150줄

#### FlowDux Remote 구현

```kotlin
// === 서버 ===
class PokerTable(private val scope: CoroutineScope) {
    private val players = ConcurrentHashMap<String, PlayerSession>()

    val roomStore = createSharedStateServer(
        initialState = ServerTableState(),
        reducer = serverTableReducer,
        processors = tableProcessors(),
        stateMapper = { SharedPokerAction.SyncTableState(it.toPublic()) },
        scope = scope,
    )

    init {
        // Room → Per-Client 자동 전파
        scope.launch {
            roomStore.state.collect { state ->
                state.hands.forEach { (playerId, hand) ->
                    players[playerId]?.updateHand(hand)
                }
            }
        }
    }

    fun addPlayer(playerId: String, session: PlayerSession) {
        players[playerId] = session
    }
}

// Per-Client Store
class PlayerSession(playerId: String, connection: TypedServerConnection<PokerAction>) {
    val store = createStore(
        initialState = PlayerState(playerId),
        reducer = playerReducer,
        middlewares = listOf(SingleClientSyncMiddleware(connection)),
    )

    fun updateHand(cards: List<Card>) {
        store.dispatch(InternalAction.SetHand(cards))
    }

    suspend fun serve() = store.serve { SharedPokerAction.SyncHand(it.hand) }
}
```

**실제 코드량**: 서버 ~200줄, 클라이언트 ~80줄

#### 비교 분석

| 항목 | Raw | FlowDux Remote |
|------|-----|----------------|
| 공개/비공개 분리 | 수동 관리 | Room Store + Per-Client Store |
| 상태 전파 | 직접 loop | collect + dispatch |
| 턴 검증 | when 내 if 체크 | processors에서 분리 |
| 코드량 | ~550줄 | ~280줄 |

---

### 3. Real-time Dashboard (Server Push Only)

**시나리오**: 서버가 주기적으로 메트릭을 push, 클라이언트는 수신만 함

#### Raw WebSocket 구현

```kotlin
// 서버
class MetricsServer {
    private val clients = ConcurrentHashMap<String, WebSocketSession>()

    init {
        scope.launch {
            while (isActive) {
                val metrics = collectMetrics()
                broadcast(json.encodeToString(metrics))
                delay(1000)
            }
        }
    }

    private suspend fun broadcast(message: String) {
        clients.values.forEach { try { it.send(message) } catch (e: Exception) {} }
    }

    suspend fun handleClient(session: WebSocketSession) {
        val id = UUID.randomUUID().toString()
        clients[id] = session
        try {
            for (frame in session.incoming) { /* keep-alive만 처리 */ }
        } finally {
            clients.remove(id)
        }
    }
}
```

**예상 코드량**: ~60줄

#### FlowDux Remote 구현

```kotlin
val dashboard = createSharedStateServer(
    initialState = MetricsState(),
    reducer = metricsReducer,
    stateMapper = { SharedAction.SyncMetrics(it) },
    scope = scope,
)

// 메트릭 수집
scope.launch {
    while (isActive) {
        dashboard.store.dispatch(InternalAction.UpdateMetrics(collectMetrics()))
        delay(1000)
    }
}

webSocket("/metrics") {
    dashboard.handleClient(sessionId, connection)
}
```

**실제 코드량**: ~40줄

#### 비교 분석

| 항목 | Raw | FlowDux Remote |
|------|-----|----------------|
| 적합성 | 단순해서 충분 | 약간 오버스펙 |
| 확장성 | 필터링 추가 시 복잡 | Action 추가로 확장 용이 |
| 코드량 | ~60줄 | ~40줄 |

**결론**: 단순 push-only는 Raw도 괜찮음. 하지만 "특정 메트릭만 구독" 같은 기능 추가 시 FlowDux가 유리.

---

### 4. Collaborative Document Editing

**시나리오**: Google Docs처럼 여러 사용자가 동시에 문서 편집

#### Raw WebSocket 구현

```kotlin
class DocumentServer {
    private val clients = ConcurrentHashMap<String, WebSocketSession>()
    private val document = AtomicReference(Document())
    private val operationLog = ConcurrentLinkedQueue<Operation>()
    private val mutex = Mutex()

    suspend fun handleClient(session: WebSocketSession) {
        val clientId = UUID.randomUUID().toString()
        clients[clientId] = session

        try {
            // 현재 문서 + 버전 전송
            session.send(json.encodeToString(SyncDoc(document.get(), operationLog.size)))

            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    val op = json.decodeFromString<Operation>(frame.readText())

                    mutex.withLock {
                        // OT (Operational Transform) 적용
                        val transformed = transformOperation(op, operationLog)
                        operationLog.add(transformed)
                        document.set(applyOperation(document.get(), transformed))

                        // 다른 클라이언트에게 전파 (발신자 제외)
                        clients.filter { it.key != clientId }.forEach { (_, s) ->
                            try { s.send(json.encodeToString(transformed)) } catch (e: Exception) {}
                        }
                    }
                }
            }
        } finally {
            clients.remove(clientId)
            broadcastPresence()
        }
    }
}
```

**예상 코드량**: ~300줄 (OT 로직 제외)

#### FlowDux Remote 구현

```kotlin
val docServer = createSharedStateServer(
    initialState = DocumentState(),
    reducer = documentReducer,
    processors = documentProcessors(),  // OT 변환 처리
    stateMapper = { SharedAction.SyncDocument(it.content, it.version) },
    scope = scope,
)

// Processors에서 OT 변환
private fun documentProcessors() = buildProcessors<DocumentState, DocAction> {
    on<SharedAction.ApplyEdit> { state, action ->
        val transformed = transformOperation(action.operation, state.operationLog)
        emit(InternalAction.ApplyTransformed(transformed))
    }
}

// Reducer
val documentReducer = buildReducer<DocumentState, DocAction> {
    on<InternalAction.ApplyTransformed> { state, action ->
        state.copy(
            content = applyOperation(state.content, action.operation),
            operationLog = state.operationLog + action.operation,
            version = state.version + 1
        )
    }
}
```

**실제 코드량**: ~150줄 (OT 로직 제외)

#### 비교 분석

| 항목 | Raw | FlowDux Remote |
|------|-----|----------------|
| OT 로직 분리 | when 내 혼재 | Processors로 분리 |
| 버전 관리 | 수동 | State로 자동 추적 |
| 발신자 제외 broadcast | 직접 필터링 | 커스텀 필요 (동일) |
| 테스트 용이성 | 어려움 | Reducer 단위 테스트 |

---

### 5. IoT Device Control

**시나리오**: 스마트홈 앱에서 여러 디바이스 제어, 상태 모니터링

#### Raw WebSocket 구현

```kotlin
class IoTGateway {
    private val devices = ConcurrentHashMap<String, DeviceState>()
    private val clients = ConcurrentHashMap<String, WebSocketSession>()

    // MQTT 등에서 디바이스 상태 수신
    fun onDeviceStateChange(deviceId: String, state: DeviceState) {
        devices[deviceId] = state
        scope.launch {
            broadcast(json.encodeToString(DeviceUpdate(deviceId, state)))
        }
    }

    suspend fun handleClient(session: WebSocketSession) {
        val clientId = UUID.randomUUID().toString()
        clients[clientId] = session

        try {
            // 전체 디바이스 상태 전송
            session.send(json.encodeToString(SyncAllDevices(devices.toMap())))

            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    val cmd = json.decodeFromString<DeviceCommand>(frame.readText())
                    // 디바이스에 명령 전송 (MQTT 등)
                    sendToDevice(cmd.deviceId, cmd.command)
                }
            }
        } finally {
            clients.remove(clientId)
        }
    }
}
```

**예상 코드량**: ~120줄

#### FlowDux Remote 구현

```kotlin
val iotHub = createSharedStateServer(
    initialState = IoTState(),
    reducer = iotReducer,
    processors = iotProcessors(),
    stateMapper = { SharedAction.SyncDevices(it.devices) },
    scope = scope,
)

// MQTT → Store
mqttClient.onMessage { deviceId, state ->
    iotHub.store.dispatch(InternalAction.DeviceStateChanged(deviceId, state))
}

// Processors: 클라이언트 명령 → 디바이스
private fun iotProcessors() = buildProcessors<IoTState, IoTAction> {
    on<SharedAction.ControlDevice> { _, action ->
        sendToDevice(action.deviceId, action.command)
        // 응답 대기 후 상태 업데이트는 MQTT 콜백에서
    }
}
```

**실제 코드량**: ~80줄

#### 비교 분석

| 항목 | Raw | FlowDux Remote |
|------|-----|----------------|
| 외부 시스템 연동 | 콜백 + broadcast | dispatch로 통합 |
| 상태 일관성 | 수동 동기화 | Store가 보장 |
| 확장성 | 디바이스 추가 시 수정 다수 | Action 추가만 |

---

### 6. Online Game Lobby (Room-based)

**시나리오**: 게임 로비에서 방 생성/참가, 각 방은 독립된 게임 상태

#### Raw WebSocket 구현

```kotlin
class GameLobby {
    private val rooms = ConcurrentHashMap<String, GameRoom>()
    private val lobbyClients = ConcurrentHashMap<String, WebSocketSession>()

    inner class GameRoom(val id: String) {
        val players = ConcurrentHashMap<String, WebSocketSession>()
        val state = AtomicReference(GameState())
        val mutex = Mutex()

        suspend fun broadcast(message: String) {
            players.values.forEach { try { it.send(message) } catch (e: Exception) {} }
        }

        suspend fun handlePlayer(playerId: String, session: WebSocketSession) {
            players[playerId] = session
            lobbyClients.remove(playerId)  // 로비에서 제거

            try {
                session.send(json.encodeToString(SyncGame(state.get())))
                for (frame in session.incoming) {
                    // 게임 로직...
                }
            } finally {
                players.remove(playerId)
                if (players.isEmpty()) {
                    rooms.remove(id)
                }
                // 로비로 복귀 처리...
            }
        }
    }

    suspend fun handleLobbyClient(session: WebSocketSession) {
        val clientId = UUID.randomUUID().toString()
        lobbyClients[clientId] = session

        try {
            session.send(json.encodeToString(RoomList(rooms.keys.toList())))

            for (frame in session.incoming) {
                if (frame is Frame.Text) {
                    val msg = json.decodeFromString<LobbyMessage>(frame.readText())
                    when (msg) {
                        is LobbyMessage.CreateRoom -> {
                            val room = GameRoom(UUID.randomUUID().toString())
                            rooms[room.id] = room
                            broadcastRoomList()
                            room.handlePlayer(clientId, session)
                            return  // 연결을 방으로 이전
                        }
                        is LobbyMessage.JoinRoom -> {
                            rooms[msg.roomId]?.handlePlayer(clientId, session)
                            return
                        }
                    }
                }
            }
        } finally {
            lobbyClients.remove(clientId)
        }
    }
}
```

**예상 코드량**: ~400줄

#### FlowDux Remote 구현

```kotlin
class GameLobby(private val scope: CoroutineScope) {
    private val rooms = ConcurrentHashMap<String, GameRoom>()

    val lobbyStore = createSharedStateServer(
        initialState = LobbyState(),
        reducer = lobbyReducer,
        stateMapper = { SharedAction.SyncLobby(it.rooms.map { r -> r.toInfo() }) },
        scope = scope,
    )

    fun createRoom(): GameRoom {
        val room = GameRoom(UUID.randomUUID().toString(), scope)
        rooms[room.id] = room
        lobbyStore.store.dispatch(InternalAction.RoomCreated(room.id))
        return room
    }
}

class GameRoom(val id: String, scope: CoroutineScope) {
    val roomStore = createSharedStateServer(
        initialState = GameState(),
        reducer = gameReducer,
        stateMapper = { SharedAction.SyncGame(it) },
        scope = scope,
    )
}

// 라우팅
webSocket("/lobby") { lobbyStore.handleClient(id, connection) }
webSocket("/room/{roomId}") { rooms[roomId]?.roomStore?.handleClient(id, connection) }
```

**실제 코드량**: ~200줄

#### 비교 분석

| 항목 | Raw | FlowDux Remote |
|------|-----|----------------|
| 방 생명주기 관리 | 수동 정리 | Store 단위 관리 |
| 로비↔방 전환 | 세션 이전 복잡 | 별도 WebSocket 연결 |
| 독립된 게임 상태 | Room별 state | Room별 Store |
| 코드량 | ~400줄 | ~200줄 |

---

## Use Case 적합성 매트릭스

| Use Case | Raw WebSocket | FlowDux Remote | 권장 |
|----------|---------------|----------------|------|
| **Simple 1:1 통신** | ★★★★★ | ★★★☆☆ | Raw |
| **Multi-Client Broadcast** | ★★★☆☆ | ★★★★★ | FlowDux |
| **Per-Client Private State** | ★★☆☆☆ | ★★★★★ | FlowDux |
| **Server Push Only** | ★★★★☆ | ★★★★☆ | 동등 |
| **Collaborative Editing** | ★★☆☆☆ | ★★★★☆ | FlowDux |
| **IoT Gateway** | ★★★☆☆ | ★★★★☆ | FlowDux |
| **Room-based Games** | ★★☆☆☆ | ★★★★★ | FlowDux |
| **Binary Protocol** | ★★★★★ | ★★☆☆☆ | Raw |
| **Minimal Dependencies** | ★★★★★ | ★★☆☆☆ | Raw |

---

## FlowDux Remote 선택 기준

### 사용을 권장하는 경우

1. **다중 클라이언트 상태 동기화**
   - 채팅, 협업 도구, 실시간 대시보드
   - `stateMapper` 하나로 자동 broadcast

2. **클라이언트별 권한/상태 분리**
   - 포커, 온라인 시험, 개인화된 데이터
   - Room Store + Per-Client Store 패턴

3. **복잡한 비즈니스 로직**
   - 게임, 금융, 워크플로우
   - Reducer로 순수 함수 분리, 테스트 용이

4. **확장 가능성이 필요한 경우**
   - 초기엔 단순하지만 기능 추가 예상
   - Action 추가로 점진적 확장

5. **이미 FlowDux를 사용 중인 경우**
   - 클라이언트가 FlowDux Store 사용
   - 자연스러운 확장

### Raw WebSocket이 나은 경우

1. **극도로 단순한 1:1 통신**
   - 복잡한 상태 관리 불필요
   - 프레임워크 오버헤드 회피

2. **바이너리 프로토콜 필요**
   - 고성능 게임, 미디어 스트리밍
   - JSON 직렬화 부적합

3. **학습/프로토타이핑**
   - WebSocket 동작 이해 목적
   - 빠른 PoC 제작

4. **의존성 최소화 필수**
   - 임베디드, 경량 환경
   - 라이브러리 크기 제약

---

## Sample App 요약

### Chat Sample (multi-client)

```
목적: 다중 클라이언트 채팅방
패턴: Room Store
코드량: 서버 60줄, 클라이언트 50줄
핵심: createSharedStateServer + stateMapper로 자동 broadcast
```

### Poker Sample (poker)

```
목적: 비공개 패를 가진 포커 게임
패턴: Room Store + Per-Client Store
코드량: 서버 200줄, 클라이언트 80줄
핵심: 공개 정보는 Room에서 broadcast, 비공개 패는 PlayerSession에서 개별 전송
```

---

## 결론

| 상황 | 선택 |
|------|------|
| "빠르게 PoC 만들어야 해" | Raw (단순) 또는 FlowDux (확장성) |
| "여러 클라이언트에 상태 동기화" | **FlowDux Remote** |
| "클라이언트마다 다른 데이터" | **FlowDux Remote** (Per-Client Store) |
| "바이너리 고성능 필요" | Raw WebSocket |
| "테스트 가능한 구조 필요" | **FlowDux Remote** (Reducer 분리) |
| "복잡한 게임/협업 도구" | **FlowDux Remote** |

FlowDux Remote는 **프로토콜 정의와 상태 관리를 통합**하여 boilerplate를 줄이고, **확장 가능한 구조**를 제공합니다. Raw WebSocket 대비 코드량은 평균 **40-50% 감소**하며, 특히 복잡한 시나리오에서 그 차이가 커집니다.
