# Redis + Kafka로 Central/NodeMediator 대체 설계

## Context

FlowDux node-mediator 모듈이 제공하는 수평 확장 기능(Central relay, Room routing, Node discovery)이
Redis + Kafka만으로 **더 간단하고 견고하게** 구현 가능한지 설계 검증.

결론: **가능하며, 오히려 더 낫다.** Central 서버 프로세스가 완전히 제거되고,
기존 `NodeTransport<A>` 인터페이스를 그대로 구현하므로 앱 코드 변경이 0.

---

## Architecture

### Before (Central 방식)

```
Client A ──WS──▶ Node 1 ──WS──▶ Central (relay) ──WS──▶ Node 2 ◀──WS── Client B
                                  │
                          InMemoryRoomRegistry
```

- N+1 프로세스 (Central이 SPOF)
- Central 장애 시 노드 간 통신 불가
- 상태 복구 불가 (in-memory)

### After (Redis + Kafka 방식)

```
                    ┌─────────────────────────────┐
                    │           Redis              │
                    │  Pub/Sub: room:{id}:actions  │
                    │  Set:     room:{id}:nodes    │
                    │  Set:     active:nodes       │
                    └──────┬──────────┬────────────┘
                           │          │
              subscribe/publish    subscribe/publish
                           │          │
Client A ──WS──▶ Node 1 ──┘          └── Node 2 ◀──WS── Client B
                    │                        │
                    └──produce──▶ Kafka ◀──produce──┘
                              (optional, 상태복구용)
```

- N 프로세스만 (Central 제거)
- Redis Sentinel/Cluster로 HA
- Kafka replay로 상태 복구 가능

---

## Redis 데이터 구조

| Key Pattern | Type | 용도 |
|---|---|---|
| `room:{roomId}:actions` | Pub/Sub channel | 크로스 노드 액션 릴레이 |
| `room:{roomId}:nodes` | Set | 해당 룸을 호스팅하는 노드 목록 |
| `node:{nodeId}:rooms` | Set | 해당 노드가 호스팅하는 룸 목록 |
| `active:nodes` | Set | 활성 노드 목록 |
| `node:{nodeId}` | Hash (lastSeen, host, port) | 노드 메타데이터 + 헬스체크 |

## Kafka 토픽 설계

| Topic | Key | 용도 |
|---|---|---|
| `flowdux.actions` | roomId | 룸별 액션 영구 로그 (파티션 내 순서 보장) |

- Kafka는 **실시간 릴레이에 사용하지 않음** (Redis Pub/Sub가 담당)
- 용도: 장애 복구 시 액션 리플레이, 감사 로그, 이벤트 소싱
- **선택 사항** — Kafka 없이도 기본 동작 가능

---

## 새로 구현할 컴포넌트 (3개)

### 1. `RedisPubSubTransport<A>` — `NodeTransport<A>` 구현

기존 인터페이스 `NodeTransport<A>`를 그대로 구현. Central + WebSocket 전체를 대체.

```kotlin
class RedisPubSubTransport<A : Action>(
    private val nodeId: String,
    private val redis: RedisClient,
    private val codec: ActionCodec<A>,
    private val scope: CoroutineScope,
) : NodeTransport<A> {

    private val _incoming = MutableSharedFlow<NodeAction<A>>(extraBufferCapacity = 256)
    override val incoming: Flow<NodeAction<A>> = _incoming

    override suspend fun send(action: NodeAction<A>) {
        val envelope = RelayEnvelope(sourceNodeId = nodeId, action = codec.encode(action.action))
        redis.publish("room:${action.roomId}:actions", Json.encodeToString(envelope))
    }

    override suspend fun subscribeRoom(roomId: String) {
        // Redis channel 구독 → incoming flow로 emit (자기 메시지 필터)
    }

    override suspend fun unsubscribeRoom(roomId: String) {
        // Redis channel 구독 해제
    }

    override suspend fun connect() { /* Redis client는 풀링 — no-op */ }
    override suspend fun disconnect() { /* 모든 구독 취소 */ }
}
```

**핵심**: `NodeTransport.subscribeRoom()` KDoc에 이미 "topic-based transports (e.g. Kafka)용"이라고 명시되어 있음.
WebSocket 구현에서는 no-op이었지만, Redis 구현에서는 실제 채널 구독을 수행.

### 2. `RedisRoomRegistry` — `RoomRegistry` 구현

기존 인터페이스 `RoomRegistry`를 Redis로 구현. `InMemoryRoomRegistry` 대체.

```kotlin
class RedisRoomRegistry(private val redis: RedisClient) : RoomRegistry {
    override suspend fun assignRoom(roomId: String, nodeId: String) {
        redis.sadd("room:$roomId:nodes", nodeId)
        redis.sadd("node:$nodeId:rooms", roomId)
    }
    override suspend fun unassignRoom(roomId: String) { /* SREM + DEL */ }
    override suspend fun getNodeForRoom(roomId: String): String? = redis.srandmember("room:$roomId:nodes")
    override suspend fun getRoomsForNode(nodeId: String): Set<String> = redis.smembers("node:$nodeId:rooms")
    // ...
}
```

### 3. `KafkaActionLog<A>` — 선택적 영구 로그

```kotlin
class KafkaActionLog<A : Action>(
    private val producer: KafkaProducer<String, String>,
    private val codec: ActionCodec<A>,
) {
    suspend fun log(nodeId: String, roomId: String, action: A) { /* produce to flowdux.actions */ }
    fun replay(roomId: String): Flow<A> { /* consume & emit */ }
}
```

---

## 메시지 플로우

### Client A가 "hello" 전송 (lobby 룸, Node 1)

```
  Client A        Node 1              Redis              Node 2          Client B
     │               │                  │                   │                │
  1. │─SendMessage──▶│                  │                   │                │
     │               │                  │                   │                │
  2. │         ┌─────┴──────┐           │                   │                │
     │         │ Reducer:    │           │                   │                │
     │         │ +message    │           │                   │                │
     │         └─────┬──────┘           │                   │                │
     │               │                  │                   │                │
  3. │               │─ PUBLISH ───────▶│                   │                │
     │               │  room:lobby      │                   │                │
     │               │  {node-1,        │                   │                │
     │               │   SendMessage}   │                   │                │
     │               │                  │                   │                │
  4. │               │                  │── deliver ───────▶│                │
     │               │                  │  (node-2 구독중)  │                │
     │               │                  │  sourceNodeId     │                │
     │               │                  │  != node-2 ✓      │                │
     │               │                  │                   │                │
  5. │               │                  │             ┌─────┴──────┐         │
     │               │                  │             │ Reducer:    │         │
     │               │                  │             │ +message    │         │
     │               │                  │             └─────┬──────┘         │
     │               │                  │                   │                │
     │◀─SyncState───│                  │                   │─SyncState────▶│
     │               │                  │                   │                │
  6. │               │─ produce ───────▶ Kafka (optional)   │                │
     │               │  flowdux.actions │                   │                │
```

### 룸 생성

```
  Client          Node 1              Redis
     │               │                  │
  1. │─ WS connect ─▶│                  │
     │  /ws/lobby     │                  │
     │               │                  │
  2. │         RoomServer               │
     │         .getOrCreateRoom         │
     │         ("lobby")                │
     │               │                  │
     │         SharedStateServer        │
     │         created                  │
     │               │                  │
  3. │               │─ SUBSCRIBE ─────▶│  room:lobby:actions
     │               │                  │
  4. │               │─ SADD ──────────▶│  room:lobby:nodes + "node-1"
     │               │─ SADD ──────────▶│  node:node-1:rooms + "lobby"
     │               │                  │
  5. │◀─SyncState───│                  │  (초기 상태 전송)
```

### 노드 장애 복구 (Kafka 있을 때)

```
  Node 1 (crash)     Redis              Node 2         Client A (재접속)
     │                  │                  │                │
  1. │ ✕ CRASH          │                  │                │
     │                  │                  │                │
  2. │            TTL 만료:                │                │
     │            node:node-1:alive        │                │
     │                  │                  │                │
  3. │            Cleanup:                 │                │
     │            SREM room:lobby:nodes    │                │
     │              "node-1"               │                │
     │            DEL node:node-1:rooms    │                │
     │                  │                  │                │
  4. │                  │                  │◀── WS connect ─│
     │                  │                  │   /ws/lobby     │
     │                  │                  │                │
  5. │                  │            ┌─────┴──────┐         │
     │                  │            │ Kafka       │         │
     │                  │            │ .replay     │         │
     │                  │            │ ("lobby")   │         │
     │                  │            │             │         │
     │                  │            │ action 1 ──▶│ dispatch│
     │                  │            │ action 2 ──▶│ dispatch│
     │                  │            │ action N ──▶│ dispatch│
     │                  │            │ (상태 복구) │         │
     │                  │            └─────┬──────┘         │
     │                  │                  │                │
     │                  │                  │─SyncState────▶│
     │                  │                  │  (복구된 상태)  │
```

---

## 개발자 코드 변경량

### Before (NodeMediator + Central)

```kotlin
// Node 서버 (node/Main.kt)
val transport = KtorWebSocketClientConnection.create(centralHost, centralPort, "/node/$nodeId")
    .webSocketNodeTransport<SharedChatAction>()

val nodeRoomServer = NodeRoomServer(nodeId, transport, roomServer, scope)
nodeRoomServer.connect()

// + Central 서버 별도 프로세스 (central/Main.kt, 120+ lines)
```

### After (Redis + Kafka)

```kotlin
// Node 서버만 (Central 프로세스 불필요)
val transport = RedisPubSubTransport<SharedChatAction>(
    nodeId, redisClient, actionCodecOf(), scope
)

val nodeRoomServer = NodeRoomServer(nodeId, transport, roomServer, scope)  // 동일
nodeRoomServer.connect()  // 동일
```

**변경되지 않는 것**: `RoomServer`, `SharedStateServer`, `SyncMiddleware`, `Reducer`, `SharedAction`, 클라이언트 코드 전부.

---

## 비교 요약

| | Central (현재) | Redis + Kafka |
|---|---|---|
| 프로세스 수 | N+1 (Central 필요) | N (Central 제거) |
| SPOF | Central (HA 없음) | Redis (Sentinel/Cluster HA) |
| 릴레이 레이턴시 | Node→WS→Central→WS→Node (2홉) | Node→Redis PubSub→Node (1홉, sub-ms) |
| 상태 복구 | 불가 (in-memory) | Kafka replay 가능 |
| 유지보수 코드 | ~389줄 (Central + WebSocket transport) | ~170줄 (Redis transport + registry) |
| 인프라 요구사항 | 없음 (순수 Kotlin) | Redis (필수), Kafka (선택) |
| 스케일링 한계 | Central 처리량 병목 | Redis Pub/Sub ~100K 채널 |
| Cross-DC | 미지원 | Redis Cluster + Kafka mirroring |
| 모니터링 | 커스텀 구현 필요 | Redis INFO, Kafka consumer lag (표준 도구) |

---

## 비즈니스 로직 배치 지도

서버 사이드 비즈니스 로직이 어디에 위치하는지, Redis 전환 시 영향이 있는지 정리.

### Room Store Reducer — 룸 내 상태 전이 (로직의 ~70%)

순수 함수. Redis 전환 영향 **없음**.

```
  Client A          Node 1 (Room Store)             Node 2 (Room Store)          Client B
     │                    │                               │                        │
     │─ SendMessage ────▶ │                               │                        │
     │                    │                               │                        │
     │              ┌─────┴──────┐                        │                        │
     │              │  Reducer   │                        │                        │
     │              │  state' =  │                        │                        │
     │              │  reducer(  │                        │                        │
     │              │   state,   │                        │                        │
     │              │   action)  │                        │                        │
     │              └─────┬──────┘                        │                        │
     │                    │                               │                        │
     │                    │── PUBLISH room:lobby ────▶ Redis ────▶ │               │
     │                    │                               │                        │
     │                    │                         ┌─────┴──────┐                 │
     │                    │                         │  Reducer   │                 │
     │                    │                         │  (동일한   │                 │
     │                    │                         │   순수함수) │                 │
     │                    │                         └─────┴──────┘                 │
     │                    │                               │                        │
     │◀── SyncState ─────│                               │── SyncState ──────────▶│
```

| 예시 | 로직 |
|---|---|
| 채팅 | `SendMessage` → messages 리스트에 추가 |
| 포커 | `PlaceBet(amount)` → pot 증가, 칩 감소, 턴 이동 |
| 화이트보드 | `DrawStroke(path)` → strokes 리스트에 추가 |
| 투표 | `CastVote(option)` → 투표 집계 업데이트 |

### Room Middleware Processor — 액션 변환/검증 (로직의 ~20%)

`MultiClientSyncMiddleware` processor. Redis 전환 영향 **없음**.

```
  Client               MultiClientSyncMiddleware (Node 로컬)
     │                          │
     │─ SendMessage("스팸") ──▶ │
     │                    ┌─────┴───────────────┐
     │                    │ Processor:           │
     │                    │ if (isSpam) return   │  ← 검증: 드랍 (emit 안 함)
     │                    └─────────────────────┘
     │                          ✕ (릴레이 안 됨)
     │
     │─ SendMessage("hi") ───▶ │
     │                    ┌─────┴───────────────┐
     │                    │ Processor:           │
     │                    │ emit(MessageReceived)│  ← 변환: 서버 내부 액션으로
     │                    └─────┬───────────────┘
     │                          │
     │                          ├──▶ Reducer (상태 업데이트)
     │                          └──▶ Redis PUBLISH (다른 노드로 전파)
```

서버 권위 패턴 (포커 카드 딜):

```
  Client               Processor (Node 로컬)           다른 Clients
     │                          │                          │
     │─ StartRound ───────────▶ │                          │
     │                    ┌─────┴───────────────┐          │
     │                    │ cards = shuffle()    │          │
     │                    │ emit(DealCards(cards))│  ← 서버에서 카드 생성
     │                    └─────┬───────────────┘          │
     │                          │                          │
     │                    Reducer: state + cards            │
     │                          │                          │
     │◀── SyncState(내카드) ────│──── SyncState(상대카드hidden) ──▶│
```

| 예시 | 로직 |
|---|---|
| 스팸 필터 | `SendMessage` → 검증 실패 시 드랍 |
| 서버 권위 | `StartRound` → `DealCards(shuffled)` 서버에서 생성 |
| 타이머 | `StartTimer` → `delay(30s)` 후 `TimeExpired` emit |
| 권한 체크 | `KickUser` → 방장인지 확인 후 `UserKicked` emit |

### Per-Session State Mapper — 세션별 다른 뷰

Redis 전환 영향 **없음**.

```
                      Room Store (Node 로컬)
                            │
                    State: { hands: {
                      alice: [A♠, K♥],
                      bob:   [7♦, 2♣]
                    }}
                            │
              ┌─────────────┼─────────────┐
              │             │             │
        sessionMapper    sessionMapper
        (alice)          (bob)
              │             │
              ▼             ▼
    SyncState({          SyncState({
      alice: [A♠, K♥],    alice: [??, ??],
      bob:   [??, ??]      bob:   [7♦, 2♣]
    })                   })
              │             │
              ▼             ▼
           Alice           Bob
    (자기 카드만 보임)  (자기 카드만 보임)
```

| 예시 | 로직 |
|---|---|
| 포커 핸드 | 자기 카드만 보임, 상대 카드는 hidden |
| 마피아 게임 | 마피아끼리만 서로 보임 |
| 시험 | 학생마다 다른 문제 세트 |

### Node-Level handleClient — 연결 시점 로직

Redis 전환 영향 **거의 없음** (글로벌 조회 필요 시 Redis 사용).

| 예시 | 로직 |
|---|---|
| JWT 인증 | 토큰 검증 후 roomId 결정 |
| 매칭 | 스킬 기반 매칭 → 적절한 룸 배정 |
| 초기 상태 | 현재 상태를 새 클라이언트에게 전송 |

### Central `onUpstreamAction` — 크로스 노드 로직 (유일한 재배치 대상)

**Redis 전환 시 재배치 필요.** 하지만 대부분 단순 릴레이.

#### 케이스 A: 단순 릴레이 (~90%) — Redis Pub/Sub가 자동 처리

```
  Before (Central):                        After (Redis):

  Node 1 ──WS──▶ Central ──WS──▶ Node 2   Node 1 ──PUBLISH──▶ Redis ──▶ Node 2
                    │                                   │
              onUpstreamAction:                   (로직 없음,
              for (node in nodes)                  자동 fan-out)
                if (node != sender)
                  sendToNode(node, ...)

  → Central의 relay 로직이 Redis의 기본 동작으로 대체
```

#### 케이스 B: 크로스 룸 알림 — 노드에서 직접 PUBLISH

```
  Before:                                  After:

  Node 1 (Room A)                          Node 1 (Room A)
     │                                        │
     │─ MatchResult ──▶ Central               │─ PUBLISH room:B:actions
     │                    │                   │     { MatchResult }
     │              route to Room B            │
     │                    │                    ▼
     │                    ▼               Redis channel room:B:actions
     │              Node 2 (Room B)            │
     │                                         ▼
     │                                    Node 2 (Room B)

  → Central의 라우팅 로직 대신, 노드가 직접 다른 룸 채널에 publish
```

#### 케이스 C: 글로벌 브로드캐스트 — 글로벌 채널 사용

```
  Before:                                  After:

  Central                                  Any Node
     │                                        │
  broadcastToAllRooms(                     PUBLISH global:broadcast
    SystemNotice("점검"))                     { SystemNotice("점검") }
     │                                        │
     ├──▶ Node 1 (Room A, B)                  ▼
     ├──▶ Node 2 (Room C)              Redis global:broadcast
     └──▶ Node 3 (Room D, E)                  │
                                          ┌────┼────┐
                                          ▼    ▼    ▼
                                        Node1 Node2 Node3
                                        (각 노드가 global:broadcast 구독)
```

#### 케이스 D: 토너먼트 진행 — 전용 룸 or 서비스

```
  Before:                                  After:

  Central                                  "tournament-1" 룸 (전용)
     │                                        │
  onUpstreamAction:                      Reducer:
    collect match results                   matchResults += result
    if (all matches done)                   if (allMatchesDone)
      create next round                       emit(CreateNextRound)
      notify rooms                            │
     │                                        ├─ PUBLISH room:semifinal-A
     ├──▶ Room semi-A                         └─ PUBLISH room:semifinal-B
     └──▶ Room semi-B

  → 토너먼트 로직을 Central 콜백 대신 전용 Room Store의 Reducer에 배치
  → 크로스 룸 알림은 케이스 B 방식으로 처리
```

| 케이스 | 현재 (Central) | Redis 전환 후 |
|---|---|---|
| 단순 릴레이 (~90%) | `sendToNode()` 반복 | Redis Pub/Sub 자동 (로직 불필요) |
| 크로스 룸 알림 | roomId 바꿔서 relay | 노드에서 다른 채널에 PUBLISH |
| 글로벌 브로드캐스트 | `broadcastToAllRooms()` | `PUBLISH global:broadcast` |
| 토너먼트 진행 | 매치 결과 수집 → 다음 라운드 | 전용 "토너먼트" 룸 or 별도 서비스 |

### 글로벌 상태 로직 — Central에서도 해결 못 하는 영역

현재 Central에도 없지만, 프로덕션에서 필요해지는 것들.
**Central이든 Redis든 어차피 별도 인프라 필요.**

```
  매칭메이킹:

  Client A (Node 1)     Redis                  Client B (Node 2)
     │                    │                        │
     │─ FindMatch ──▶ Node 1                       │
     │              LPUSH matchqueue "alice"        │
     │                    │                        │─ FindMatch ──▶ Node 2
     │                    │                   LPUSH matchqueue "bob"
     │                    │                        │
     │                    │◀── BRPOP matchqueue ───│  (매칭 서비스 or 노드)
     │                    │    → "alice", "bob"
     │                    │
     │              Create Room "match-42"
     │                    │
     │◀── JoinRoom("match-42") ──────────── JoinRoom("match-42") ──▶│
```

```
  리더보드:

  Node 1 (Room A)       Redis Sorted Set           Client (조회)
     │                       │                        │
  GameOver(score=100)        │                        │
     │                       │                        │
  ZADD leaderboard           │                        │
    100 "alice"              │                        │
     │                       │                        │
     │                  ┌────┴────┐                   │
     │                  │ alice:100│                   │─ GetLeaderboard
     │                  │ bob:  85│                   │
     │                  │ carol:72│◀── ZREVRANGE ─────│
     │                  └─────────┘    leaderboard    │
     │                                 0 9            │
     │                                                │◀── Top 10 결과
```

| 케이스 | Redis 해결 방법 |
|---|---|
| 매칭메이킹 | Redis List + BRPOP |
| 리더보드 | Redis Sorted Set (ZADD/ZRANGE) |
| 전체 접속자 수 | Redis INCR/DECR + HyperLogLog |
| Rate Limiting | Redis INCR + TTL (sliding window) |
| 유저 밴 목록 | Redis Hash or DB |

### 핵심 인사이트

```
비즈니스 로직의 ~95%는 Room Store (Reducer + Middleware)에 있다.
Central의 onUpstreamAction은 대부분 "단순 릴레이"로 Redis Pub/Sub가 자동 처리.
글로벌 상태가 필요한 로직은 Central에서도 해결 못 함 → Redis 자료구조가 정답.
```

---

## 결론: Node-Mediator의 유일한 가치

> **"외부 인프라 없이 시작할 수 있다"** — 이것이 전부.

프로덕션에서 HA, 상태복구, 모니터링이 필요한 순간 Redis/Kafka는 불가피.
Node-Mediator는 **"Kafka가 필요해지기 전까지의 부트스트랩"**으로 포지셔닝하고,
`RedisPubSubTransport`를 프로덕션 그레이드 대안으로 제공하는 것이 전략적으로 맞음.

기존 `NodeTransport<A>` 인터페이스가 이미 이 전환을 위해 설계되어 있으므로,
전환 비용은 transport 생성 **1줄 변경**에 불과함.
