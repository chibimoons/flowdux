# Action/State 스케일링: 단일 세션에서 멀티플레이어 게임 서버까지

*Room, 상태 뷰, 틱 배칭, 수평 확장 — Action/State 모델의 확장*

*3부작 시리즈의 Part 3입니다. [← Part 1: REST API를 설계하지 않아도 된다면?](./part1-concept-ko.md) | [← Part 2: REST 없이 실시간 채팅 만들기](./part2-practice-ko.md)*

> **안내:** 이 시리즈는 *개념 제안*입니다. REST의 보편적 대체가 아닌, 대안 통신 패턴으로서 Action/State 모델을 탐구합니다. 아이디어를 공유하고 토론을 이끌어내는 것이 목적입니다.

---

[Part 1](./part1-concept-ko.md)에서 REST 엔드포인트를 **Action**과 **State**로 대체하자고 제안했습니다. [Part 2](./part2-practice-ko.md)에서는 실시간 채팅 앱을 만들어 실제로 동작함을 증명했습니다. 하나의 공유 계약, 하나의 통신 채널, 기본이 실시간.

하지만 그건 모든 사용자가 같은 상태를 공유하는 단일 채팅방이었습니다. 현실의 애플리케이션은 더 복잡합니다:

- 게임에는 각각 독립적 상태를 가진 다수의 Room이 있다
- 같은 게임의 플레이어가 *다른 것*을 볼 수 있다 (전장의 안개, 감춰진 패)
- 60fps로 돌아가는 게임 서버가 상태 변경마다 WebSocket 메시지를 보낼 수는 없다
- 성공한 앱은 서버 하나로 돌아가지 않는다

이 글에서는 Action/State 모델을 한계까지 밀어봅니다 — 채팅방에서 멀티플레이어 게임 서버까지 어디까지 확장되는지.

---

## Room 패턴: 1 Room = 1 Store

Part 2의 단일 Store 채팅은 하나의 방에서 잘 동작합니다. 하지만 각각 자체 상태를 가진 수백 개의 동시 Room은?

답은 간단합니다: **1 Room = 1 Store**. 각 Room은 자체 미들웨어 파이프라인을 가진 격리된 상태 컨테이너입니다.

```
                        ┌──────────────────────────┐
                        │       Room Manager        │
                        │    (Room 생성/삭제 관리)    │
                        └─────┬──────┬──────┬───────┘
                              │      │      │
                    ┌─────────┴──┐ ┌─┴──────┴─────┐
                    │   Room A   │ │    Room B     │
                    │            │ │               │
                    │  Store     │ │  Store        │
                    │  (State A) │ │  (State B)    │
                    │            │ │               │
                    │  Middleware │ │  Middleware   │
                    │  Pipeline  │ │  Pipeline     │
                    │            │ │               │
                    │  플레이어:  │ │  플레이어:     │
                    │  Alice,Bob │ │  Carol,Dave   │
                    └────────────┘ └───────────────┘
```

Alice와 Bob의 액션은 Room A의 상태에만 영향을 줍니다. Carol과 Dave는 Room B에만. 상태는 완전히 격리됩니다. 각 Store는 자체 미들웨어 파이프라인을 독립적으로 실행합니다.

### Room Manager

Room Manager는 FlowDux의 일부가 *아닙니다* — Room 생성, 삭제, 플레이어-Room 매핑을 처리하는 순수 서버 코드입니다:

```kotlin
class Room(
    val id: String,
    val maxPlayers: Int = 4,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val players = ConcurrentHashMap<String, WebSocketSession>()

    // FlowDux 영역 ──────────────────────────────────
    private lateinit var store: Store<GameState, GameAction>

    fun initialize(connection: TypedServerConnection<GameAction>) {
        store = createStore(
            initialState = GameState(),
            reducer = gameReducer,
            middlewares = listOf(
                ValidationMiddleware(),
                GameLogicMiddleware(),
                GameSingleClientSyncMiddleware(connection),
            ),
            scope = scope,
        )
    }
    // ────────────────────────────────────────────────

    val playerCount: Int get() = players.size
    val isFull: Boolean get() = players.size >= maxPlayers

    fun addPlayer(playerId: String, session: WebSocketSession) {
        players[playerId] = session
    }

    fun removePlayer(playerId: String) {
        players.remove(playerId)
    }

    fun dispatch(action: GameAction) = store.dispatch(action)

    fun close() {
        store.close()
        players.clear()
    }
}

class RoomManager {
    private val rooms = ConcurrentHashMap<String, Room>()
    private val playerRoomMap = ConcurrentHashMap<String, String>()

    fun findOrCreateRoom(playerId: String): Room {
        playerRoomMap[playerId]?.let { roomId ->
            rooms[roomId]?.let { return it }
        }

        val available = rooms.values.firstOrNull { !it.isFull }
        if (available != null) {
            playerRoomMap[playerId] = available.id
            return available
        }

        val room = Room(id = "room-${rooms.size + 1}")
        rooms[room.id] = room
        playerRoomMap[playerId] = room.id
        return room
    }

    fun removePlayer(playerId: String) {
        val roomId = playerRoomMap.remove(playerId) ?: return
        val room = rooms[roomId] ?: return
        room.removePlayer(playerId)
        if (room.playerCount == 0) {
            room.close()
            rooms.remove(roomId)
        }
    }
}
```

FlowDux와 서버 코드의 경계가 명확합니다:

| 구성요소 | FlowDux? | 설명 |
|---------|----------|------|
| Room Manager | 아니오 | Room 생성/삭제, 플레이어 매칭 |
| Auth Middleware | 아니오 | Ktor JWT 검증 |
| WebSocket 라우팅 | 아니오 | Ktor 라우팅 |
| **Store (Room당)** | **예** | 상태 관리 |
| **미들웨어 파이프라인** | **예** | 검증, 게임 로직 |
| **SingleClientSyncMiddleware** | **예** | 클라이언트 ↔ Store 브릿지 |

FlowDux는 각 Room *내부*의 상태와 비즈니스 로직을 관리합니다. 그 바깥의 모든 것 — 인증, 매치메이킹, 연결 관리 — 은 서버 코드입니다.

---

## 미들웨어 파이프라인: 조합 가능한 서버 로직

Action/State 모델의 강점 중 하나는 서버 로직이 각각 하나의 관심사를 처리하는 미들웨어의 파이프라인이라는 점입니다. 게임 서버라면 파이프라인이 이렇게 보일 수 있습니다:

```
들어오는 Action
    │
    ▼
┌───────────────────┐
│ 1. Validation     │  액션이 구조적으로 유효한가?
│    (범위 검증)     │  이동이 맵 안인가?
└───────┬───────────┘
        │
        ▼
┌───────────────────┐
│ 2. Auth Check     │  이 플레이어가 이 액션을
│    (권한 확인)     │  수행할 권한이 있는가?
└───────┬───────────┘
        │
        ▼
┌───────────────────┐
│ 3. Game Logic     │  액션 처리: 충돌 판정,
│    (핵심 규칙)     │  점수 계산, 물리 시뮬레이션
└───────┬───────────┘
        │
        ▼
┌───────────────────┐
│ 4. Remote         │  연결된 클라이언트에 상태 동기화
│    (브로드캐스트)   │
└───────────────────┘
```

먼저 게임의 Action과 State 계약을 정의합니다 — Part 1, 2와 같은 패턴을 이제 게임에 적용합니다:

```kotlin
// ─── 공유 모듈 ───

// State — 게임 세계가 어떻게 생겼는가
data class GameState(
    val players: Map<String, Player> = emptyMap(),
    val projectiles: List<Projectile> = emptyList(),
    val tick: Long = 0,
) : State

data class Player(val id: String, val x: Float, val y: Float, val hp: Int = 100)
data class Projectile(val ownerId: String, val x: Float, val y: Float, val dx: Float, val dy: Float)

// Action — 플레이어가 무엇을 할 수 있는가
sealed interface GameAction : Action {
    // Client → Server
    data class Move(val playerId: String, val x: Float, val y: Float) : GameAction, ServerSharedAction
    data class Shoot(val playerId: String, val dx: Float, val dy: Float) : GameAction, ServerSharedAction
    data class JoinGame(val playerId: String) : GameAction, ServerSharedAction

    // Server → Client
    data class PlayerMoved(val playerId: String, val x: Float, val y: Float) : GameAction, ClientSharedAction
    data class ProjectileFired(val projectile: Projectile) : GameAction, ClientSharedAction
    data class PlayerJoined(val player: Player) : GameAction, ClientSharedAction
    data class PlayerHit(val playerId: String, val damage: Int) : GameAction, ClientSharedAction
}
```

Action은 플레이어가 할 수 있는 것(`Move`, `Shoot`, `JoinGame`)을, State는 게임 세계(`GameState` — 플레이어, 투사체, 틱 카운트)를 정의합니다. 서버가 클라이언트 액션을 처리하고, 상태를 업데이트하는 결과 액션을 emit해서 클라이언트에 푸시합니다.

각 미들웨어는 분리된, 테스트 가능한 단위입니다:

```kotlin
// 검증: 좌표를 유효 범위로 클램프
class ValidationMiddleware : Middleware<GameState, GameAction> {
    override val name = "Validation"

    override val processors = buildProcessors {
        on<GameAction.Move> { _, action ->
            val clampedX = action.x.coerceIn(0f, 1000f)
            val clampedY = action.y.coerceIn(0f, 1000f)
            emit(GameAction.PlayerMoved(action.playerId, clampedX, clampedY))
        }
    }
}

// 게임 로직: 발사하면 플레이어 위치에서 투사체 생성
class GameLogicMiddleware : Middleware<GameState, GameAction> {
    override val name = "GameLogic"

    override val processors = buildProcessors {
        on<GameAction.Shoot> { state, action ->
            val player = state.players[action.playerId] ?: return@on
            val projectile = Projectile(
                ownerId = action.playerId,
                x = player.x, y = player.y,
                dx = action.dx, dy = action.dy,
            )
            emit(GameAction.ProjectileFired(projectile))
        }
        on<GameAction.JoinGame> { _, action ->
            val player = Player(id = action.playerId, x = 500f, y = 500f)
            emit(GameAction.PlayerJoined(player))
        }
    }
}
```

새로운 관심사를 추가하려면 — 예를 들어 로깅이나 분석 — 파이프라인에 미들웨어 하나를 더 넣으면 됩니다. 기존 미들웨어는 변경하지 않습니다.

---

## StateView: 플레이어별 상태 필터링

채팅 앱에서는 모든 사용자가 같은 상태를 봤습니다. 많은 게임에서는 그게 틀립니다:

- **전장의 안개**: 시야 범위 내의 적만 보인다
- **카드 게임**: 자기 패는 보이지만 상대 패는 안 보인다
- **비대칭 역할**: 던전 마스터는 전부 보고, 플레이어는 자기 시점만 본다

이것에는 **StateView**가 필요합니다 — 전체 서버 상태와 플레이어 ID를 받아서, 그 플레이어가 볼 수 있는 상태를 반환하는 함수입니다.

```kotlin
// 카드 게임을 위한 StateView 개념
fun stateViewFor(playerId: String, fullState: GameState): PlayerGameView {
    return PlayerGameView(
        myHand = fullState.hands[playerId] ?: emptyList(),
        opponentCardCount = fullState.hands
            .filterKeys { it != playerId }
            .mapValues { it.value.size },
        discardPile = fullState.discardPile,
        currentTurn = fullState.currentTurn,
        myScore = fullState.scores[playerId] ?: 0,
    )
}
```

모든 사람에게 같은 `SyncState`를 브로드캐스트하는 대신, 서버가 플레이어별 뷰를 생성합니다:

```
전체 서버 상태
    │
    ├── stateViewFor("alice") → Alice는 자기 패 + 상대 카드 수를 본다
    ├── stateViewFor("bob")   → Bob은 자기 패 + 상대 카드 수를 본다
    └── stateViewFor("carol") → Carol은 자기 패 + 상대 카드 수를 본다
```

각 플레이어는 볼 수 있는 정보만 담긴 `SyncState`를 받습니다. 전체 상태는 서버를 절대 떠나지 않습니다.

flowdux-remote에서는 `createSessionAwareSharedStateServer`가 이를 직접 지원합니다 — 하나의 글로벌 `stateMapper` 대신, 상태와 세션 ID를 모두 받는 `sessionStateMapper`를 제공합니다:

```kotlin
val server = createSessionAwareSharedStateServer(
    initialState = GameState(),
    reducer = gameReducer,
    processors = gameProcessors(),
    sessionStateMapper = { state, sessionId ->
        SharedGameAction.SyncState(stateViewFor(sessionId, state))
    },
    scope = applicationScope,
)
```

패턴은 동일합니다: 상태 변경이 푸시를 트리거합니다. 차이점은 `sessionStateMapper`가 연결된 클라이언트마다 한 번씩 호출되어, 각각 필터링된 뷰를 받는다는 것입니다. `null`을 반환하면 해당 세션에는 전송을 건너뜁니다.

---

## 틱 배칭: 게임 서버 성능

고빈도로 플레이어 액션을 처리하는 게임 서버가 모든 상태 변경마다 WebSocket 메시지를 보낼 수는 없습니다. 10명의 플레이어가 각각 초당 30번 이동하면, 플레이어당 초당 300개의 상태 변경 — 전부 보내면 연결이 포화됩니다.

해결책은 **틱 배칭**입니다: 고정 간격("틱") 동안 상태 변경을 누적하고, 틱마다 통합된 업데이트 하나를 보냅니다.

```
시간 ──────────────────────────────────────────────→

액션:     Move Move Shoot Move Move Shoot Move
          ─────────┬──────────────────┬────────────
                   │                  │
          틱 1 (16.67ms)       틱 2 (16.67ms)
                   │                  │
                   ▼                  ▼
          통합된 상태             통합된 상태
          스냅샷 전송             스냅샷 전송
```

초당 60틱(60fps)이면, 처리된 액션 수에 관계없이 서버가 플레이어당 초당 최대 60개의 상태 업데이트를 보냅니다. 클라이언트는 해당 틱 동안 처리된 모든 액션이 반영된 상태 스냅샷을 받습니다.

```kotlin
// 틱 루프 개념
class TickBatcher(
    private val intervalMs: Long = 16L,  // ~60fps
    private val onTick: (GameState) -> Unit,
) {
    private var lastState: GameState? = null

    fun start(stateFlow: Flow<GameState>) = scope.launch {
        while (isActive) {
            delay(intervalMs)
            val currentState = stateFlow.value
            if (currentState != lastState) {
                onTick(currentState)
                lastState = currentState
            }
        }
    }
}
```

틱 루프는 서버 쪽 인프라이지, FlowDux 자체가 아닙니다. Store의 상태 출력과 WebSocket 브로드캐스트 사이에 위치합니다. Store는 액션을 즉시 처리하고(게임 로직의 반응성 유지), 네트워크 출력만 배칭합니다.

StateView와 결합하면 흐름은 이렇습니다:

```
Store 상태 변경
    │
    ▼
틱 배처 (16.67ms 동안 누적)
    │
    ▼
StateView 필터 (플레이어별)
    │
    ├── 플레이어 A 필터된 상태 → WebSocket → 클라이언트 A
    ├── 플레이어 B 필터된 상태 → WebSocket → 클라이언트 B
    └── 플레이어 C 필터된 상태 → WebSocket → 클라이언트 C
```

---

## 수평 확장: 다중 서버 인스턴스

단일 서버가 많은 Room을 실행할 수 있지만, 결국 다중 서버가 필요합니다. 과제: 서버 1의 플레이어가 서버 2의 플레이어와 같은 Room에 있을 수 있습니다.

표준 접근법은 **메시지 백플레인** — 서버 간 액션과 상태 변경을 중계하는 공유 버스(보통 Redis Pub/Sub)입니다.

```
                    ┌───────────────┐
                    │ 로드 밸런서    │
                    │ (Sticky WS)   │
                    └───┬───────┬───┘
                        │       │
              ┌─────────┴──┐ ┌──┴──────────┐
              │  서버 1    │ │  서버 2     │
              │            │ │             │
              │  Room A    │ │  Room C     │
              │  Room B    │ │  Room D     │
              └─────┬──────┘ └──────┬──────┘
                    │               │
                    └───────┬───────┘
                            │
                    ┌───────┴───────┐
                    │ Redis Pub/Sub  │
                    │  (백플레인)     │
                    └───────────────┘
```

### 스티키 세션

WebSocket 연결은 본질적으로 스티키합니다 — 한번 맺어지면 해당 연결의 모든 메시지가 같은 서버로 갑니다. 로드밸런서는 새 연결을 라우팅하지만 기존 연결을 방해하지 않습니다. ALB, Nginx, Envoy 모두 네이티브로 처리합니다.

### 서버 간 통신

Room이 다른 서버의 플레이어와 통신해야 하거나, 글로벌 이벤트가 모든 서버에 도달해야 할 때, Redis Pub/Sub가 중계 역할을 합니다:

```kotlin
// 서버 간 액션 중계 의사코드
class RedisActionRelay(private val redis: RedisClient) {
    // 이 Room을 호스팅하는 모든 서버에 액션 발행
    suspend fun broadcastToRoom(roomId: String, action: GameAction) {
        redis.publish("room:$roomId:actions", serialize(action))
    }

    // 이 서버의 Room들에 대한 액션 구독
    fun subscribeToRoom(roomId: String, onAction: (GameAction) -> Unit) {
        redis.subscribe("room:$roomId:actions") { message ->
            onAction(deserialize(message))
        }
    }
}
```

Room 자체는 Redis를 모릅니다. Room 관점에서는 액션이 도착하고 상태 업데이트가 나갑니다 — 플레이어가 이 서버에 직접 연결되어 있든 Redis를 통해 중계되든 인프라 관심사입니다.

### Redis가 담당하는 것

| 관심사 | Redis 기능 | 설명 |
|--------|-----------|------|
| Room 간 메시징 | Pub/Sub | 서버 간 액션 중계 |
| 세션 저장 | Key-Value | 재연결을 위한 플레이어 → 서버 매핑 |
| 접속 추적 | Sets + TTL | 서버 간 온라인 플레이어 추적 |
| 매치메이킹 큐 | Sorted Sets | 서버 간 매치메이킹 |
| 리더보드 | Sorted Sets | 글로벌 랭킹 |

### FlowDux가 담당하는 것 (변경 없음)

| 관심사 | FlowDux 기능 | 설명 |
|--------|-------------|------|
| 상태 관리 | Store | Room별 상태 |
| 비즈니스 로직 | Middleware | 검증, 게임 규칙 |
| 클라이언트 동기화 | SingleClientSyncMiddleware | 액션 수신 + 상태 푸시 |
| 상태 매핑 | `serve {}` / StateView | 클라이언트별 상태 필터 |

스케일링 레이어가 FlowDux를 변경하지 않고 감쌉니다. Store, 미들웨어, 동기화 메커니즘은 서버가 하나든 오십 개든 동일하게 동작합니다.

---

## 실전 유즈케이스 카탈로그

Action/State 모델은 채팅과 게임에 한정되지 않습니다. 패턴이 자연스럽게 맞는 애플리케이션 카탈로그를 도메인별로 정리합니다:

### 게임
| 유즈케이스 | Action | State | 왜 Action/State인가 |
|-----------|--------|-------|-------------------|
| 멀티플레이어 액션 게임 | Move, Shoot, UseItem | 플레이어 위치, 체력, 인벤토리 | 실시간 상태 동기화가 핵심 요구사항 |
| 턴제 게임 | PlayCard, EndTurn, DrawCard | 보드 상태, 패, 점수 | StateView가 상대 패를 숨김; 턴이 자연스럽게 액션 기반 |
| 라이브 퀴즈/트리비아 | SubmitAnswer, NextQuestion | 점수, 현재 질문, 타이머 | 모든 참가자가 같은 질문을 봄; 답은 액션 |
| 인터랙티브 라이브 스트리밍 | SendReaction, Vote, Bid | 반응 수, 투표 결과, 입찰 상태 | 수천 시청자가 공유 상태에 기여 |

### 협업
| 유즈케이스 | Action | State | 왜 Action/State인가 |
|-----------|--------|-------|-------------------|
| 문서 에디터 | InsertText, DeleteRange, FormatText | 문서 내용, 커서 위치 | OT가 자연스럽게 액션으로 매핑 |
| 디자인 도구 | MoveElement, Resize, ChangeColor | 캔버스 상태, 선택 요소 | 다중 사용자가 공유 캔버스 편집 |
| 화이트보드 | Draw, Erase, AddSticky | 보드 내용, 참가자 | 자유로운 협업 + 실시간 동기화 |

### 비즈니스
| 유즈케이스 | Action | State | 왜 Action/State인가 |
|-----------|--------|-------|-------------------|
| 라이브 경매 | PlaceBid, CloseLot | 현재 입찰, 입찰자, 타이머, 로트 정보 | 시간에 민감한 공유 상태 + 다수의 관찰자 |
| 트레이딩 대시보드 | PlaceOrder, CancelOrder | 포트폴리오, 주문장, 가격 | 실시간 가격 업데이트 + 사용자 액션 |
| 주문 관리 | UpdateStatus, AssignDriver | 주문 상태, 드라이버 위치 | 배차 + 추적이 단일 상태 모델에 |
| 티켓 예매 | SelectSeat, ConfirmBooking | 좌석도, 가용성 | 이중 예약 방지를 위해 가용성이 실시간이어야 함 |

### IoT & 모니터링
| 유즈케이스 | Action | State | 왜 Action/State인가 |
|-----------|--------|-------|-------------------|
| 스마트 홈 | SetTemperature, TurnOnLight | 디바이스 상태, 센서 수치 | 디바이스 명령이 액션; 센서 데이터가 상태 |
| 산업 모니터링 | AcknowledgeAlarm, SetThreshold | 센서 데이터, 알람 상태 | 운영자가 공유 대시보드를 봄; 명령이 액션 |

### 워크플로우
| 유즈케이스 | Action | State | 왜 Action/State인가 |
|-----------|--------|-------|-------------------|
| 결재 워크플로우 | Submit, Approve, Reject, Escalate | 문서 상태, 결재 체인 | 상태 전이가 액션으로 직접 매핑 |
| 칸반 보드 | MoveCard, CreateCard, AssignMember | 보드 레인, 카드, 할당 | 다중 사용자 보드 + 실시간 업데이트 |

### 이들의 공통점

이 모든 것이 하나의 패턴을 공유합니다:
1. **다수의 사용자**가 **공유 상태**에 영향을 준다
2. 변경이 **실시간으로 보여야** 한다
3. 상호작용 모델이 **액션 기반**이다 (사용자가 행동하고; 세상이 반응한다)

여러분의 애플리케이션이 이 설명에 맞는다면, Action/State 모델이 REST + 실시간 덧붙이기보다 설계 복잡성을 줄여줄 가능성이 높습니다.

---

## 결정 매트릭스: 언제 무엇을 쓸까

| 요인 | REST | Action/State |
|------|------|-------------|
| 연결 모델 | 무상태, 요청별 | 지속 WebSocket |
| 실시간 필요성 | 추가 필요 (WS/SSE/폴링) | 내장 |
| API 소비자 | 다양한 클라이언트, 서드파티 | 알려진 클라이언트 앱 |
| 상태 공유 | 각 요청이 독립 | 공유 변경 가능 상태 |
| 상호작용 모델 | 자원 지향 (CRUD) | 행위 지향 (액션) |
| 도구 생태계 | 성숙 (Swagger, Postman 등) | 발전 중 |
| 발견성 | URL이 자기 문서화 | 공유 타입 정의 필요 |
| 캐싱 | HTTP 캐싱, CDN | 변경 시에만 서버 푸시 |
| 오프라인 지원 | 응답 캐시 | 재연결 전략 필요 |

솔직한 답: 서드파티 개발자가 소비하는 공개 API라면 REST를 쓰세요. 도구, 문서 생태계, 보편적 이해는 비할 데가 없습니다.

클라이언트와 서버를 모두 통제하는 제품이고, 실시간 공유 상태를 포함한다면, Action/State 모델이 복잡성 한 레이어를 통째로 제거합니다.

현실의 많은 애플리케이션은 둘 다 필요합니다: 공개 API와 서드파티 연동에는 REST, 실시간 인터랙티브 핵심에는 Action/State.

---

## 풀 스케일 아키텍처

전부 합치면 — 프로덕션 스케일의 Action/State 모델을 사용한 멀티플레이어 게임 서버:

```
클라이언트 (Android, iOS, Web, Desktop)
    │
    │ WSS + JWT
    ▼
┌──────────────────────────┐
│       로드 밸런서          │
│  (Nginx, Sticky Session)  │
└──────────┬───────────────┘
           │
    ┌──────┴──────┐
    ▼             ▼
┌────────┐  ┌────────┐
│서버 1  │  │서버 2  │
│        │  │        │
│ Auth   │  │ Auth   │  ← Ktor JWT (서버 코드)
│  │     │  │  │     │
│  ▼     │  │  ▼     │
│ Room   │  │ Room   │  ← Room Manager (서버 코드)
│ Manager│  │ Manager│
│  │     │  │  │     │
│  ├─Room A │  ├─Room C
│  │  │     │  │  │
│  │  Store │  │  Store    ← FlowDux
│  │  │     │  │  │
│  │  미들웨어 파이프라인    ← FlowDux
│  │  │ Validation
│  │  │ Auth Check
│  │  │ Game Logic
│  │  │ Remote Middleware   ← FlowDux
│  │  │     │  │  │
│  │  Tick  │  │  Tick     ← 서버 코드
│  │  Batch │  │  Batch
│  │  │     │  │  │
│  │  State │  │  State    ← 플레이어별 필터링
│  │  View  │  │  View
│  │        │  │        │
│  ├─Room B │  ├─Room D │
│  │  ...   │  │  ...   │
│        │  │        │
└────┬───┘  └────┬───┘
     │           │
     └─────┬─────┘
           │
    ┌──────┴──────┐
    │ Redis Cluster│
    │  Pub/Sub     │  ← 서버 간 메시징
    │  Sessions    │  ← 재연결 라우팅
    │  Presence    │  ← 접속 추적
    │  Matchmaking │  ← 큐 관리
    └──────────────┘
           │
    ┌──────┴──────┐
    │  Database    │
    │  프로필       │
    │  히스토리     │
    │  랭킹        │
    └─────────────┘
```

FlowDux 경계가 명확합니다: Store, 미들웨어 파이프라인, Remote Middleware. 나머지 — 인증, Room 관리, 틱 배칭, 상태 뷰 필터링, Redis, 데이터베이스 — 는 FlowDux 코어를 감싸는 서버 코드입니다.

이 분리는 의도적입니다. FlowDux는 잘하는 것(상태 관리, 액션 처리, 클라이언트 동기화)을 담당합니다. 코드는 도메인 고유의 것(매치메이킹, 게임 규칙, 영속화, 스케일링 인프라)을 담당합니다.

---

## 결론: 자원 지향 vs 행위 지향

REST는 세상을 **자원**으로 모델링합니다: users, messages, rooms. 명사(URL)에 동사(GET, POST, DELETE)를 적용합니다. 20년간 잘 기능해 온 강력한 추상화입니다.

Action/State 모델은 세상을 **행위**로 바라봅니다: 사용자가 무엇을 하는가, 그 결과 무엇이 바뀌는가. 자원을 모델링하지 않습니다 — 상호작용을 모델링합니다.

```
REST:     "어떤 자원이 있나?"    → URL과 메서드 설계
Action:   "사용자가 뭘 할 수 있나?"  → 액션 설계
State:    "그 결과 무엇이 되나?"    → 상태 설계

REST:     GET /api/v1/game/42/players
Action:   dispatch(JoinGame("player-7"))
State:    GameState(players = [..., player7], ...)
```

어느 패러다임도 보편적으로 우월하지 않습니다. 하지만 **인터랙티브하고, 실시간이며, 다중 사용자인 애플리케이션** — 게임, 협업 도구, 라이브 커머스, 대시보드 — 에서 Action/State 모델은 다음을 제공합니다:

1. **API 설계 오버헤드 없음** — 액션과 상태가 스펙
2. **실시간이 기본** — 추가 채널 불필요
3. **타입 안전한 계약** — 컴파일러가 프로토콜 강제
4. **조합 가능한 서버 로직** — 라우트 핸들러 대신 미들웨어 파이프라인
5. **자연스러운 스케일링 패턴** — Room = Store, 클라이언트별 필터링의 StateView, 성능을 위한 틱 배칭

우리는 Kotlin으로 참조 구현인 [flowdux-remote](https://github.com/user/flowdux)를 만들었습니다. 하지만 패턴은 특정 라이브러리를 초월합니다. 인터랙티브 앱을 만들면서 REST 엔드포인트를 설계한 다음 실시간을 위해 WebSocket을 덧붙이고 있다면, Action과 State만으로 충분하지 않은지 한번 생각해 보세요.

---

*3부작 시리즈의 Part 3입니다.*
*[← Part 1: REST API를 설계하지 않아도 된다면?](#)*
*[← Part 2: REST 없이 실시간 채팅 만들기](#)*

*이 시리즈가 아이디어나 반론을 촉발했다면 듣고 싶습니다. 최고의 아키텍처는 독단이 아니라 토론에서 나옵니다.*
