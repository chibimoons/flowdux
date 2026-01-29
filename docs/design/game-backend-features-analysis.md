# 게임 백엔드 솔루션 분석 및 FlowDux 적용 검토

## 1. 개요

본 문서는 주요 게임 백엔드 솔루션(Colyseus, Photon, Nakama)의 기능을 분석하고,
FlowDux에 적용할 수 있는 기능들을 검토한다.

### 분석 대상

| 솔루션 | 언어 | 특징 |
|--------|------|------|
| [Colyseus](https://colyseus.io/) | TypeScript/Node.js | Schema 기반 상태 동기화, Delta Encoding |
| [Photon PUN](https://www.photonengine.com/pun) | C# (Unity) | RPC, Custom Properties, 대규모 인프라 |
| [Nakama](https://heroiclabs.com/) | Go/TypeScript/Lua | Authoritative Server, Tick 기반 |

---

## 2. 핵심 기능 분석

### 2.1 Delta Encoding (Colyseus)

**개념:** 전체 상태 대신 변경된 속성만 전송하여 대역폭 절약.

**Colyseus 구현:**
```typescript
// @colyseus/schema - 속성 레벨 변경 추적
@schema class GameState extends Schema {
    @type("number") score: number = 0;
    @type("number") time: number = 300;
    @type([Player]) players = new ArraySchema<Player>();
}

// 내부적으로 property-level 변경만 추적
// patchRate 간격마다 변경분만 binary encode하여 전송
```

**전송 데이터 비교:**
```
Full State:  { players: [...100명...], score: 100, time: 299, items: [...] }  → ~10KB
Delta:       { score: 101 }                                                    → ~10B
```

**FlowDux 현재 방식:**
```kotlin
// Action 기반 - 전체 상태 아닌 액션만 전송
SharedAction: AddScore(1)  →  서버에서 처리  →  결과 액션 브로드캐스트
```

**검토 의견:**
- FlowDux는 이미 Action 기반이라 전체 상태를 전송하지 않음
- 단, 초기 접속 시 전체 상태 동기화에는 Delta Encoding이 유용할 수 있음
- **우선순위: Medium** - 대규모 상태에서만 의미 있음

---

### 2.2 StateView - 클라이언트별 상태 필터링 (Colyseus)

**개념:** 같은 Room에 있어도 클라이언트마다 다른 상태를 전송.

**사용 사례:**
```
포커 게임:
- Player A: 자신의 패 [A♠, K♠] 보임
- Player B: Player A의 패 갯수만 보임 (2장)

FPS 게임:
- Player A: 자신의 위치 + 시야 내 적만 보임
- 벽 뒤의 적 위치는 전송하지 않음 (월핵 방지)
```

**Colyseus 구현:**
```typescript
class GameState extends Schema {
    @type([Card])
    @view() // StateView로 필터링
    cards = new ArraySchema<Card>();
}

// 룸에서 플레이어별 StateView 설정
this.state.cards.setView(client.sessionId, (card) => {
    return card.ownerId === client.sessionId;
});
```

**FlowDux 적용 방안:**
```kotlin
// StateFilter 인터페이스
interface StateFilter<S : State> {
    fun filter(state: S, clientId: ClientId): S
}

// 서버 미들웨어에서 적용
class FilteredBroadcastMiddleware<S, A>(
    private val stateFilter: StateFilter<S>
) : Middleware<S, A> {

    fun broadcastState(state: S, clients: List<Client>) {
        clients.forEach { client ->
            val filteredState = stateFilter.filter(state, client.id)
            client.send(filteredState)
        }
    }
}

// 포커 게임 예시
class PokerStateFilter : StateFilter<PokerState> {
    override fun filter(state: PokerState, clientId: ClientId): PokerState {
        return state.copy(
            players = state.players.map { player ->
                if (player.id == clientId) {
                    player // 자신의 정보는 전부 보임
                } else {
                    player.copy(cards = emptyList()) // 다른 플레이어 패는 숨김
                }
            }
        )
    }
}
```

**검토 의견:**
- 숨겨진 정보가 있는 게임에서 **필수** 기능
- 보안상으로도 중요 (클라이언트에 민감 정보 전송 방지)
- **우선순위: High**

---

### 2.3 Tick-based Batching (Nakama, Colyseus)

**개념:** 매 이벤트마다 전송하지 않고, 고정 간격(tick)으로 묶어서 전송.

**Nakama 구현:**
```go
// Match Handler - 초당 10회 tick
func (m *Match) MatchLoop(ctx context.Context, ...) {
    // 매 tick마다 호출 (100ms 간격)
    // 누적된 상태 변경을 한 번에 브로드캐스트
}
```

**Colyseus 구현:**
```typescript
// patchRate: 상태 동기화 간격 (ms)
this.setPatchRate(50); // 초당 20회

// 50ms 동안 발생한 모든 변경을 모아서 한 번에 전송
```

**적용 시나리오:**

| 게임 유형 | Tick Rate | 설명 |
|----------|-----------|------|
| FPS/액션 | 20-60/sec | 부드러운 움직임 필요 |
| MOBA | 10-30/sec | 적당한 반응성 |
| 턴제 게임 | 1-5/sec | 높은 빈도 불필요 |
| 보드게임 | 이벤트 기반 | Tick 불필요 |

**FlowDux 적용 방안:**
```kotlin
class BatchingMiddleware<S, A>(
    private val tickRateMs: Long = 50,
    private val scope: CoroutineScope,
) : Middleware<S, A> {

    private val pendingActions = mutableListOf<A>()

    init {
        scope.launch {
            while (isActive) {
                delay(tickRateMs)
                if (pendingActions.isNotEmpty()) {
                    broadcastBatch(pendingActions.toList())
                    pendingActions.clear()
                }
            }
        }
    }

    override fun process(getState: () -> S, action: A): Flow<A> = flow {
        pendingActions.add(action)
        // tick에서 일괄 전송
    }
}
```

**검토 의견:**
- 고빈도 업데이트 게임(액션, FPS)에서 필수
- 턴제/이벤트 기반 게임에서는 오히려 지연만 추가
- **우선순위: Medium** - 사용 사례에 따라 선택적 적용

---

### 2.4 Reconnection & State Recovery

**개념:** 네트워크 끊김 후 재접속 시 상태 복구.

**Photon 구현:**
```csharp
// 재접속 시 자동으로 Room 상태 복구
PhotonNetwork.ReconnectAndRejoin();

// Custom Properties는 서버에 저장되어 있어 자동 복구
```

**Nakama 구현:**
```go
// Match는 플레이어가 없어도 계속 실행
// 재접속 시 현재 상태 전송
func (m *Match) MatchJoin(...) {
    // 전체 상태 전송
    dispatcher.BroadcastMessage(FullStateOpCode, state, []runtime.Presence{presence})
}
```

**FlowDux 현재 상태:**
- ❌ 재접속 핸들링 없음
- ❌ 전체 상태 동기화 메커니즘 없음

**FlowDux 적용 방안:**
```kotlin
// RemoteConnection 확장
interface RemoteConnection {
    // 기존
    val connectionState: StateFlow<ConnectionState>
    val incoming: Flow<String>
    suspend fun send(message: String)
    suspend fun connect()
    suspend fun disconnect()

    // 추가
    suspend fun reconnect()  // 재접속
}

// 메시지 프로토콜 확장
sealed interface ServerMessage {
    data class ActionMessage(val actions: List<String>) : ServerMessage
    data class FullStateSync(val state: String) : ServerMessage  // 추가
    data class Reconnected(val state: String) : ServerMessage    // 추가
}

// RemoteFlowMiddleware 확장
class RemoteFlowMiddleware<S, A>(...) {

    private fun handleReconnection() {
        scope.launch {
            connection.connectionState.collect { state ->
                if (state == ConnectionState.RECONNECTING) {
                    // 서버에 전체 상태 요청
                    connection.send(messageCodec.encodeStateRequest())
                }
            }
        }
    }
}
```

**검토 의견:**
- 모바일 환경에서 **필수** (네트워크 불안정)
- 긴 세션 게임에서 중요 (경매, 보드게임)
- **우선순위: High**

---

### 2.5 Presence System

**개념:** 접속자 목록 및 상태 관리.

**Nakama 구현:**
```go
// Presence: 누가 온라인인지 추적
type Presence struct {
    UserID    string
    SessionID string
    Username  string
    Status    string // "online", "away", "busy"
}
```

**사용 사례:**
- 로비에서 온라인 친구 목록
- 게임 내 접속자 표시
- 타이핑 인디케이터

**FlowDux 적용 방안:**
```kotlin
// Presence를 State의 일부로 관리
data class RoomState(
    val gameState: GameState,
    val presence: Map<UserId, PresenceInfo>,
)

data class PresenceInfo(
    val oderId: UserId,
    val status: PresenceStatus,
    val lastSeen: Instant,
)

// 시스템 액션
sealed interface PresenceAction : Action {
    data class UserJoined(val userId: UserId) : PresenceAction
    data class UserLeft(val userId: UserId) : PresenceAction
    data class StatusChanged(val userId: UserId, val status: PresenceStatus) : PresenceAction
}
```

**검토 의견:**
- State/Reducer로 이미 구현 가능
- 별도 시스템보다 패턴/가이드 문서화가 적절
- **우선순위: Low** - 문서화만 필요

---

### 2.6 Action Validation (Authoritative Server)

**개념:** 서버에서 클라이언트 액션 검증 후 처리.

**Nakama 구현:**
```go
func (m *Match) MatchLoop(ctx context.Context, ..., messages []runtime.MatchData) {
    for _, msg := range messages {
        // 클라이언트 입력 검증
        if !validateMove(msg) {
            // 잘못된 입력 거부
            continue
        }
        // 유효한 입력만 상태에 반영
        applyMove(msg)
    }
}
```

**FlowDux 현재 지원:**
```kotlin
// Middleware에서 검증 가능
class ValidationMiddleware<S, A> : Middleware<S, A> {
    override fun process(getState: () -> S, action: A): Flow<A> = flow {
        if (action is SharedAction) {
            if (!isValid(getState(), action)) {
                // 유효하지 않으면 무시 또는 에러 액션 emit
                emit(ValidationError(action, "Invalid move"))
                return@flow
            }
        }
        emit(action)
    }
}
```

**검토 의견:**
- FlowDux Middleware로 이미 구현 가능
- 패턴 문서화 및 예제 코드 제공 필요
- **우선순위: Low** - 문서화만 필요

---

## 3. 구현 우선순위

### Phase 1: Core Features (High Priority)

| 기능 | 설명 | 예상 작업량 |
|------|------|------------|
| **StateView (상태 필터링)** | 클라이언트별 다른 상태 전송 | Medium |
| **Reconnection Handler** | 재접속 시 전체 상태 동기화 | Medium |

### Phase 2: Optimization (Medium Priority)

| 기능 | 설명 | 예상 작업량 |
|------|------|------------|
| **Tick-based Batching** | 고빈도 업데이트 묶어서 전송 | Low |
| **Delta Encoding** | 초기 상태 동기화 최적화 | High |

### Phase 3: Documentation (Low Priority)

| 기능 | 설명 | 예상 작업량 |
|------|------|------------|
| **Presence Pattern** | 접속자 관리 패턴 가이드 | Low |
| **Validation Pattern** | 서버 검증 패턴 가이드 | Low |

---

## 4. 제안 API 설계

### 4.1 StateView

```kotlin
/**
 * 클라이언트별 상태 필터링을 위한 인터페이스.
 * 포커 게임에서 자기 패만 보이게 하거나,
 * FPS에서 시야 내 적만 보이게 할 때 사용.
 */
interface StateView<S : State> {
    /**
     * 특정 클라이언트에게 보낼 상태를 필터링.
     * @param state 전체 서버 상태
     * @param clientId 대상 클라이언트 ID
     * @return 필터링된 상태
     */
    fun filter(state: S, clientId: String): S
}

// 사용 예시
class PokerStateView : StateView<PokerState> {
    override fun filter(state: PokerState, clientId: String): PokerState {
        return state.copy(
            players = state.players.map { player ->
                if (player.id == clientId) player
                else player.copy(hand = emptyList(), handSize = player.hand.size)
            }
        )
    }
}
```

### 4.2 Reconnection Protocol

```kotlin
/**
 * 재접속 처리를 위한 메시지 타입.
 */
sealed interface SyncMessage {
    /** 클라이언트 → 서버: 상태 요청 */
    data class RequestSync(val lastKnownVersion: Long) : SyncMessage

    /** 서버 → 클라이언트: 전체 상태 전송 */
    data class FullSync(val state: String, val version: Long) : SyncMessage

    /** 서버 → 클라이언트: 증분 업데이트 */
    data class DeltaSync(val actions: List<String>, val fromVersion: Long, val toVersion: Long) : SyncMessage
}

/**
 * 재접속을 지원하는 RemoteConnection 확장.
 */
interface ReconnectableConnection : RemoteConnection {
    /** 재접속 시도 */
    suspend fun reconnect()

    /** 마지막으로 받은 상태 버전 */
    val lastSyncVersion: Long
}
```

### 4.3 Tick-based Batching

```kotlin
/**
 * 틱 기반 배칭을 위한 설정.
 */
data class BatchingConfig(
    /** 틱 간격 (밀리초) */
    val tickRateMs: Long = 50,

    /** 최대 배치 크기 */
    val maxBatchSize: Int = 100,

    /** 빈 틱에도 전송할지 여부 */
    val sendEmptyTicks: Boolean = false,
)

/**
 * 배칭 미들웨어.
 */
class BatchingMiddleware<S : State, A : Action>(
    private val config: BatchingConfig,
    private val scope: CoroutineScope,
) : Middleware<S, A> {
    // 구현...
}
```

---

## 5. 참고 자료

- [Colyseus Documentation](https://docs.colyseus.io/)
- [Colyseus Schema (Delta Encoding)](https://github.com/colyseus/schema)
- [Photon PUN Synchronization](https://doc.photonengine.com/pun/current/gameplay/synchronization-and-state)
- [Nakama Authoritative Multiplayer](https://heroiclabs.com/docs/nakama/concepts/multiplayer/authoritative/)
- [Game Networking Resources](https://github.com/MFatihMAR/Game-Networking-Resources)

---

## 6. 결론

게임 백엔드 솔루션들은 실시간 멀티플레이어에 최적화된 기능들을 제공한다.
FlowDux는 범용 상태 관리 라이브러리이지만, 다음 기능들을 추가하면
게임 및 실시간 협업 도구에서의 활용도를 크게 높일 수 있다:

1. **StateView**: 숨겨진 정보가 있는 게임의 핵심 요구사항
2. **Reconnection**: 모바일/불안정 네트워크 환경의 필수 기능
3. **Batching**: 고빈도 업데이트 최적화 (선택적)

이 기능들은 기존 FlowDux 아키텍처를 크게 변경하지 않고도
Middleware 또는 별도 모듈로 추가할 수 있다.
