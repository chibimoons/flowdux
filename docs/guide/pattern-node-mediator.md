# Node Mediator Pattern (수평 확장)

Node Mediator 패턴은 **단일 서버의 WebSocket 연결 한계**(~10,000)를 넘기기 위한 수평 확장 레이어입니다. Central(1개)이 다수의 Node 사이에서 액션을 중계하는 구조입니다. Central은 FlowDux Store를 사용하지 않는 **순수 메시지 릴레이**이므로, Kafka/Redis 같은 외부 인프라 없이 FlowDux만으로 분산 아키텍처를 구성할 수 있습니다.

## 단일 서버 vs Node Mediator

| 항목 | 단일 서버 | Node Mediator |
|------|----------|---------------|
| WebSocket 한계 | ~10,000 | 노드 수 × 10,000 |
| Store 위치 | 서버 1대 | Node N대 (Central은 릴레이 전용) |
| 확장 방식 | 수직 확장 (스케일 업) | 수평 확장 (스케일 아웃) |
| 노드 간 통신 | 불필요 | Central ↔ Node (노드당 1개 WS) |
| 외부 인프라 | 불필요 | 불필요 (FlowDux만으로 구성) |
| 적합한 시나리오 | 소~중규모 | 대규모 (10만+ 연결) |

## 언제 사용하나요?

| Use Case | 설명 |
|----------|------|
| **대규모 채팅** | 10만 이상 동시 접속 |
| **게임 서버** | 여러 물리 서버에 방 분산 |
| **IoT 플랫폼** | 수만 대 디바이스 연결 |
| **라이브 스트리밍** | 대규모 시청자 참여 |

단일 서버로 충분하다면 [Scaling Guide](./scaling.md)의 병렬 브로드캐스트만으로도 ~100k 연결을 처리할 수 있습니다.

## 아키텍처

```
Central (릴레이 전용 — Store 없음)
    │
    ├── CentralNodeManager (메시지 중계)
    │     ├── Node A (NodeMediator → NodeTransport) → Room Stores, Client Stores
    │     ├── Node B (NodeMediator → NodeTransport) → Room Stores, Client Stores
    │     └── Node C (NodeMediator → NodeTransport) → Room Stores, Client Stores
    │
    └── RoomRegistry (room → node 매핑)

Central ↔ Node: NodeTransport 추상화 (WebSocket, Kafka 등)
Node 내부: 로컬 Room Store에서 상태 관리 + 클라이언트에 브로드캐스트
```

> **Central의 역할:** Central은 FlowDux Store를 사용하지 않습니다. Reducer, Middleware, State가 없는 순수 메시지 릴레이입니다. 상태 관리는 각 Node의 Room Store에서 독립적으로 수행됩니다. 이 구조 덕분에 Kafka/Redis 같은 외부 메시지 브로커 없이도 Node 간 통신이 가능합니다.

### 데이터 흐름

**하향 (Central → Node):**
```
Central Store → CentralNodeManager.sendToRoom(roomId, action)
    → RoomRegistry 조회 → 대상 Node의 connection으로 NodeAction 전송
    → Node의 NodeMediator가 수신 → 등록된 room handler에 dispatch
```

**상향 (Node → Central):**
```
로컬 Store에서 Central 전파 필요한 액션 발생
    → NodeMediator.forwardToCentral(roomId, action)
    → NodeAction 전송 → CentralNodeManager가 수신
    → onUpstreamAction 콜백으로 전달
```

### End-to-End 메시지 흐름

Alice(Node-1)가 메시지를 보내면 Bob(Node-2)이 받기까지의 전체 경로:

```
Client A       Node-1         Central        Node-2       Client B
(Alice)     (NodeMediator)  (NodeManager)  (NodeMediator)   (Bob)
   │              │               │              │             │
   │─ SendMessage►│               │              │             │
   │  (WS /ws)    │               │              │             │
   │              │─ dispatch()   │              │             │
   │              │  (로컬 반영)   │              │             │
   │              │               │              │             │
   │              │─ forward() ──►│              │             │
   │              │  NodeAction   │              │             │
   │              │               │              │             │
   │              │               │─ relay() ───►│             │
   │              │               │ (발신자 제외)  │             │
   │              │               │              │             │
   │              │               │              │─ dispatch() │
   │              │               │              │  (로컬 반영)  │
   │              │               │              │             │
   │◄─ SyncState ─│               │              │─ SyncState─►│
   │              │               │              │             │
```

**단계별 설명:**

1. **Client → Node** — Alice가 `SendMessage`를 Node-1의 WS `/ws`로 전송
2. **Node 로컬 처리** — Node-1이 로컬 room store에 `dispatch()` → Processor가 `ServerRoomAction.MessageReceived`로 변환 → Reducer가 상태 반영
3. **Node → Central** — `mediator.forwardToCentral(roomId, action)` → `NodeAction`으로 래핑하여 Central로 전송
4. **Central 릴레이** — `onUpstreamAction` 콜백에서 발신 Node(node-1)를 **제외**하고 나머지 Node에 비동기 릴레이
5. **Central → Node** — Node-2가 `NodeAction`을 수신 → 등록된 room handler가 로컬 store에 `dispatch()`
6. **Node → Client** — 각 Node의 store 상태 변경 → `SyncState` 액션으로 연결된 클라이언트에 브로드캐스트

> **핵심:** 각 Node는 자체 room store를 독립적으로 운영합니다. Central은 상태를 관리하지 않고 **액션만 중계**합니다.

## NodeAction 프로토콜

Central ↔ Node 간 모든 메시지는 `NodeAction`으로 래핑됩니다:

```json
{"roomId": "room-1", "action": {"type": "SendMessage", "text": "hello"}}
```

```kotlin
@Serializable
data class NodeAction<A : Action>(
    val roomId: String,
    val action: A,
) : Action
```

## Central-side 구성

### 1. CentralNodeManager 생성

```kotlin
import io.flowdux.remote.nodemediator.CentralNodeManager
import io.flowdux.remote.nodemediator.registry.InMemoryRoomRegistry

val roomRegistry = InMemoryRoomRegistry()

val centralNodeManager = CentralNodeManager<SharedAction>(
    roomRegistry = roomRegistry,
    onUpstreamAction = { nodeId, roomId, action ->
        // Node에서 올라온 액션을 Central Store에 dispatch
        centralStore.dispatch(action)
    },
    onEvent = { event ->
        when (event) {
            is NodeMediatorEvent.NodeConnected ->
                println("Node ${event.nodeId} connected")
            is NodeMediatorEvent.NodeDisconnected ->
                println("Node ${event.nodeId} disconnected")
            else -> {}
        }
    },
)
```

### 2. WebSocket 엔드포인트 (Ktor)

```kotlin
import io.flowdux.remote.ktor.KtorWebSocketServerConnection
import io.flowdux.remote.nodemediator.typedNodeActionJson

webSocket("/node/{nodeId}") {
    val nodeId = call.parameters["nodeId"]!!
    val connection = KtorWebSocketServerConnection(this)
        .typedNodeActionJson<SharedAction>()

    centralNodeManager.handleNode(nodeId, connection)
}
```

### 3. Central → Node 액션 전송

```kotlin
// 특정 room에 액션 전송 (RoomRegistry 기반 라우팅)
centralNodeManager.sendToRoom("room-1", SyncState(state))

// 특정 node에 직접 전송
centralNodeManager.sendToNode("node-A", "room-1", SyncState(state))

// 모든 node에 특정 room 브로드캐스트
centralNodeManager.broadcastToAllNodes("room-1", SyncState(state))

// 전체 room에 브로드캐스트 (전체 공지 등)
centralNodeManager.broadcastToAllRooms(SystemNotice("서버 점검 안내"))
```

### 4. Central Relay 패턴 (cross-node 릴레이)

Node에서 올라온 액션을 다른 Node로 릴레이하는 일반적인 패턴입니다:

```kotlin
val centralNodeManager = CentralNodeManager<SharedAction>(
    // ...
    onUpstreamAction = { nodeId, roomId, action ->
        // 비동기 릴레이 — CancellationException이 발신 Node의
        // handleNode을 중단시키지 않도록 별도 코루틴에서 실행
        scope.launch {
            val allNodes = centralNodeManager.connectedNodeIds()
            for (targetNodeId in allNodes) {
                if (targetNodeId != nodeId) {  // 발신 Node 제외
                    try {
                        centralNodeManager.sendToNode(targetNodeId, roomId, action)
                    } catch (_: Exception) {
                        // 개별 Node 전송 실패는 무시 (다른 Node 전송 계속)
                    }
                }
            }
        }
    },
)
```

> **주의:** `onUpstreamAction`은 `handleNode`의 `incoming.collect` 안에서 동기적으로 호출됩니다. `sendToNode()`가 던지는 `CancellationException`이 발신 Node의 collect를 중단시킬 수 있으므로, 반드시 `scope.launch { }` 안에서 비동기로 릴레이해야 합니다.

## Node-side 구성

### 1. NodeMediator 생성 및 연결

```kotlin
import io.flowdux.remote.ktor.KtorWebSocketClientConnection
import io.flowdux.remote.nodemediator.NodeMediator
import io.flowdux.remote.nodemediator.webSocketNodeTransport

val transport = KtorWebSocketClientConnection.create(
    host = "central-server",
    port = 8080,
    path = "/node/node-A",
).webSocketNodeTransport<SharedAction>()

val mediator = NodeMediator(
    nodeId = "node-A",
    transport = transport,
    scope = scope,
    onEvent = { event -> println("Mediator event: $event") },
)
mediator.connect()
```

### 2. Room Handler 등록

```kotlin
// Room의 Store에 Central에서 온 액션을 dispatch
mediator.registerRoom("room-1") { action ->
    roomStore.dispatch(action)
}

// Room 해제
mediator.unregisterRoom("room-1")
```

### 3. Central로 액션 전달 (상향)

```kotlin
// 로컬 Store에서 Central 전파가 필요한 액션이 발생했을 때
mediator.forwardToCentral("room-1", SomeAction)
```

## RoomRegistry

Room-to-Node 매핑을 관리합니다. Central-side에서 `sendToRoom()`이 호출될 때 어느 Node로 보낼지 결정합니다.

```kotlin
interface RoomRegistry {
    suspend fun getNodeForRoom(roomId: String): String?
    suspend fun assignRoom(roomId: String, nodeId: String)
    suspend fun unassignRoom(roomId: String)
    suspend fun getRoomsForNode(nodeId: String): Set<String>
    suspend fun getAllAssignments(): Map<String, String>
    suspend fun nodeIds(): Set<String>
}
```

### InMemoryRoomRegistry (기본)

단일 프로세스 Central에 적합합니다:

```kotlin
val registry = InMemoryRoomRegistry()
registry.assignRoom("room-1", "node-A")
registry.assignRoom("room-2", "node-B")

val nodeId = registry.getNodeForRoom("room-1")  // "node-A"
```

### 커스텀 RoomRegistry (Redis 등)

분산 Central 환경에서는 Redis 기반으로 구현할 수 있습니다:

```kotlin
class RedisRoomRegistry(private val redis: RedisClient) : RoomRegistry {
    override suspend fun getNodeForRoom(roomId: String): String? {
        return redis.hget("room:assignments", roomId)
    }
    override suspend fun assignRoom(roomId: String, nodeId: String) {
        redis.hset("room:assignments", roomId, nodeId)
    }
    // ...
}
```

## 전송 계층 추상화 (NodeTransport)

`NodeMediator`는 `NodeTransport<A>` 인터페이스를 통해 전송 계층과 분리되어 있습니다. 전송 방식을 변경할 때 `NodeMediator` 코드는 수정할 필요 없이 transport 생성 1줄만 교체하면 됩니다.

```kotlin
interface NodeTransport<A : Action> {
    val incoming: Flow<NodeAction<A>>
    suspend fun send(action: NodeAction<A>)
    suspend fun subscribeRoom(roomId: String)
    suspend fun unsubscribeRoom(roomId: String)
    suspend fun connect()
    suspend fun disconnect()
}
```

### WebSocket (기본)

`WebSocketNodeTransport`는 `TypedClientConnection`을 래핑합니다. Central이 라우팅을 담당하므로 `subscribeRoom`/`unsubscribeRoom`은 no-op입니다:

```kotlin
val transport = KtorWebSocketClientConnection.create(host, port, "/node/node-A")
    .webSocketNodeTransport<SharedAction>()
```

### Kafka 마이그레이션 경로

WebSocket → Kafka 전환 시 `KafkaNodeTransport`를 구현하면 됩니다. `subscribeRoom`에서 topic을 subscribe하고, `unsubscribeRoom`에서 unsubscribe합니다:

```kotlin
// Before (WebSocket):
val transport = clientConnection.webSocketNodeTransport<SharedAction>()

// After (Kafka):
val transport = KafkaNodeTransport<SharedAction>(bootstrapServers = "kafka:9092")

// NodeMediator 코드는 동일
val mediator = NodeMediator(nodeId = "node-A", transport = transport, scope = scope)
```

## 직렬화 설정

### Extension Functions 사용 (권장)

```kotlin
// Node-side — NodeTransport 생성 (NodeMediator에 전달)
val transport = clientConnection.webSocketNodeTransport<SharedAction>()

// Node-side — TypedClientConnection 생성 (직접 사용 시)
val typed = clientConnection.typedNodeActionJson<SharedAction>()

// Central-side (서버 연결)
val physical = serverConnection.typedNodeActionJson<SharedAction>()
```

### 직접 Codec 생성

```kotlin
import io.flowdux.remote.nodemediator.NodeAction
import io.flowdux.remote.serialization.JsonMessageCodec
import io.flowdux.remote.serialization.actionCodecOf

val nodeCodec = actionCodecOf<NodeAction<SharedAction>>()
val physical = rawConnection.typed(nodeCodec, JsonMessageCodec())
```

## 이벤트 모니터링

`NodeMediatorEvent`로 Central과 Node의 상태를 모니터링합니다:

```kotlin
sealed interface NodeMediatorEvent {
    data class NodeConnected(val nodeId: String)
    data class NodeDisconnected(val nodeId: String, val cause: Exception?)
    data class RoutingStopped(val cause: Exception)
    data class ConnectionFailed(val cause: Exception)
    data class MessageDropped(val roomId: String)
    data class CallbackFailed(val roomId: String, val cause: Exception)
}
```

## 생명주기

```
Node                              Central
  │                                  │
  ├── mediator.connect() ─────────►  │ handleNode(nodeId, conn)
  │                                  │   → NodeConnected 이벤트
  │                                  │
  ├── registerRoom("room-1") ─────   │ registry.assignRoom("room-1", "node-A")
  │   (로컬 핸들러 등록)              │   (레지스트리 등록)
  │                                  │
  │   ◄──── sendToRoom("room-1") ──  │ Central Store 상태 변경
  │   NodeAction으로 수신              │
  │   → room handler 호출            │
  │                                  │
  ├── forwardToCentral() ──────────► │ onUpstreamAction 콜백
  │   NodeAction으로 전송              │
  │                                  │
  ├── mediator.disconnect() ──────►  │ NodeDisconnected 이벤트
  │                                  │   → 레지스트리 정리
```

## 용량 한계와 확장 전략

### Central의 본질: 외부 인프라 없는 메시지 릴레이

`CentralNodeManager`는 Node 간 메시지를 중계하는 **순수 릴레이**입니다:

```kotlin
// Central의 전체 로직 — Store/Reducer/Middleware 없음
onUpstreamAction = { nodeId, roomId, action ->
    // 발신 Node 제외, 나머지 Node에 relay
    for (targetNodeId in allNodes) {
        if (targetNodeId != nodeId) sendToNode(targetNodeId, roomId, action)
    }
}
```

이 역할은 Kafka나 Redis 같은 외부 메시지 브로커가 하는 일과 동일합니다. 차이점은:

| | CentralNodeManager | Kafka / Redis |
|--|---------------------|---------------|
| **외부 인프라** | 불필요 (FlowDux만으로 구성) | Kafka 클러스터 / Redis 클러스터 필요 |
| **배포 복잡도** | JVM 프로세스 1개 | 브로커 클러스터 운영 필요 |
| **HA (고가용성)** | 직접 구현 필요 | 브로커가 기본 제공 |
| **확장 한계** | ~100만 (단일 프로세스) | ~1억+ (클러스터 수평 확장) |
| **레이턴시** | ~sub-ms (직접 WebSocket) | ~수ms (브로커 경유) |

**결론:** 규모가 ~100만 이내라면 `CentralNodeManager`로 외부 의존성 없이 운영하고, 그 이상이 필요할 때 Event Bus로 전환합니다.

### 계층별 연결 한계

| 계층 | 연결 한계 | 병목 요인 |
|------|----------|----------|
| **Central ← Node** | ~수백~수천 | 노드당 WS 1개라 연결 수는 여유. **메시지 throughput**이 실제 병목 |
| **Node ← Client** | ~10K (기본) / ~50K-100K (OS 튜닝) | 파일 디스크립터, 메모리, broadcast fan-out |

### 최대 처리 용량

```
보수적: 100 nodes × 10K clients/node = 100만 동시 연결
적극적: 500 nodes × 50K clients/node = 2,500만 동시 연결
```

단, **연결 수 ≠ 처리 용량**입니다. Central의 메시지 중계 throughput이 실제 한계를 결정합니다:

```
Room "lobby"에 10개 Node의 유저가 참여 중
→ 메시지 1건 = Central이 9개 Node에 relay
→ 초당 1,000건 메시지 = Central이 초당 9,000건 send

Node가 많아질수록 Central의 relay 부하가 선형 증가
→ 멀티룸으로 room당 Node 수를 줄이면 부하 크게 감소
```

### Central 한계 도달 시 확장 전략

#### 계층 추가 (비권장)

```
Client → Node → Sub-Central → Central
```

Node 레이어를 한 단계 더 추가하는 방식은 레이턴시가 2배로 늘고 구조 복잡도가 급증하며, Central 병목은 해결되지 않습니다.

#### 1. Room 기반 Central 샤딩 (가장 실용적)

```
Central-A: room 1~1000 담당
Central-B: room 1001~2000 담당
Central-C: room 2001~3000 담당

Router (L7 Load Balancer)
  ├── room=lobby  → Central-A
  ├── room=game-1 → Central-B
  └── room=game-2 → Central-C
```

Room 단위로 Central을 분할하면 throughput이 Central 수만큼 선형 증가합니다. Node는 자신이 가진 room에 해당하는 Central에만 연결합니다. 각 Central은 여전히 `CentralNodeManager`이므로 외부 인프라가 필요 없습니다.

#### 2. Event Bus 도입 (대규모)

```
현재:  Node ──WS──► Central ──WS──► Node
변경:  Node ──► Kafka / Redis Streams ◄── Node

Central 서버 자체가 사라지고, 브로커 클러스터가 대체
```

Central↔Node 간 WebSocket을 Event Bus로 대체하면 Central이 완전히 제거됩니다. 각 Node가 room topic을 subscribe/publish하는 구조입니다:

```
Node-1                        Kafka                        Node-2
  │                             │                             │
  ├─ publish("room:lobby", ──►│                             │
  │   SendMessage)              │── deliver ────────────────►│
  │                             │   (room:lobby subscribers)  │
  │                             │                             │
  ├─ subscribe("room:game-1")─►│                             │
  │                             │◄── publish("room:game-1",──│
  │◄── deliver ────────────────│    JoinRoom)                │
```

**Event Bus의 한계:**

| 병목 | Kafka | Redis Streams |
|------|-------|---------------|
| 메시지 throughput | 수백만/sec (파티션으로 무한 확장) | 수십만/sec (클러스터 샤딩) |
| 장애 대응 | 브로커 클러스터 = HA 기본 제공 | Sentinel/Cluster = HA |
| 레이턴시 | ~수ms (배치 처리) | ~sub-ms |

Event Bus 기반 실질 한계:
```
1,000 Nodes × 100K clients/node = 1억 동시 연결
남은 병목: Node 수 × Node당 클라이언트 수, 네트워크 대역폭
Central throughput 병목은 완전히 제거됨
```

#### 3. Direct Mesh (특수 케이스)

```
같은 room을 공유하는 Node끼리 직접 연결
Central을 거치지 않아 레이턴시/throughput 최적
```

Node 수가 적고(~10개), 대부분의 Node가 같은 room을 공유하는 경우에 적합합니다. Node 수가 많으면 mesh 연결 수가 O(N²)로 폭증하는 단점이 있습니다.

### 확장 단계 요약

| 규모 | 아키텍처 | 외부 인프라 | 구현 |
|------|----------|-----------|------|
| ~100만 | Central 1대 + Node N대 | **불필요** | **현재 Node Mediator 구조** |
| ~1,000만 | Central 샤딩 (room 기반) | **불필요** | Room→Central 라우터 + 복수 Central |
| ~1억+ | Event Bus | Kafka / Redis 클러스터 | Central 제거, topic 기반 pub/sub |

### 확장 시나리오: 서버 1대에서 시작

물리 서버 1대로 시작하여 트래픽 증가에 따라 확장해 나가는 실전 시나리오입니다. 클라이언트는 nodeId를 알 필요 없이 Node 서버(또는 Load Balancer) 주소로만 접속합니다.

#### Stage 0: 단일 서버 (FlowDux 단독)

```
┌──────────────────────────┐
│       물리 서버 1대        │
│                          │
│  Ktor Server             │
│    ├── Store (상태 관리)   │
│    ├── WS /ws (클라이언트) │
│    └── Room Store × N    │
│                          │
│  Client → :8080/ws       │
└──────────────────────────┘
```

- **동접**: ~10,000
- **외부 인프라**: 없음
- **코드**: Room Pattern / `handleClient()` 단독
- **전환 신호**: 동접 5,000~8,000 근접, 응답 지연 증가

#### Stage 1: 단일 서버 + OS 튜닝

코드 변경 없이 OS 설정만 조정합니다:

```bash
# /etc/sysctl.conf
net.core.somaxconn = 65535
fs.file-max = 1000000

# /etc/security/limits.conf
* soft nofile 100000
* hard nofile 100000
```

- **동접**: ~50,000~100,000
- **코드 변경**: 없음
- **전환 신호**: 단일 서버 스펙 한계, 장애 시 전체 서비스 중단 (SPOF)

#### Stage 2: Node Mediator 도입 (2~3대)

```
┌──────────────────┐
│  서버 1: Central  │
│  (릴레이 전용)     │
│  CentralNode     │
│  Manager         │
│  + RoomRegistry  │
└──────┬───────────┘
       │ WS 1개씩
  ┌────┴────┐
  │         │
┌─┴──┐  ┌──┴─┐
│서버2│  │서버3│
│Node│  │Node│
│ A  │  │ B  │
│~10K│  │~10K│
└────┘  └────┘
```

- **동접**: ~20,000~30,000
- **외부 인프라**: 없음 (FlowDux만으로 구성)
- **코드 변경**: 기존 Room Store 유지, `NodeMediator` + `webSocketNodeTransport` 추가
- **전환 신호**: Node 10대 이상, Central CPU 포화

#### Stage 3: Node 수평 확장 (10~50대)

```
             Central (서버 1대)
                  │
     ┌────┬──────┼──────┬────┐
   Node  Node  Node  Node  Node ...
   ×10K  ×10K  ×10K  ×10K  ×10K
```

- **동접**: ~100,000~500,000
- **외부 인프라**: 없음
- **코드 변경**: 없음 (Node 서버 인스턴스 추가만)
- Node 앞에 L7 Load Balancer 배치 → 클라이언트 분산
- **전환 신호**: Central 1대의 relay throughput 한계

#### Stage 4: Central 샤딩 (50~100대)

```
           L7 Router (room 기반)
          ┌────────┴────────┐
     Central-A          Central-B
     room 1~500         room 501~1000
       │  │               │  │
     Node Node          Node Node ...
```

- **동접**: ~1,000만
- **외부 인프라**: 없음 (L7 라우터만 추가)
- **코드 변경**: Node가 room에 따라 해당 Central에 연결하도록 설정 변경
- **전환 신호**: Central 샤드 수십 개 이상, 운영 복잡도 급증

#### Stage 5: Kafka 도입 (100대+)

```
           ┌─────────────────┐
           │  Kafka Cluster  │  ← 여기서 처음으로 외부 인프라 등장
           │  (매니지드 서비스) │
           └──┬────┬────┬───┘
              │    │    │
           Node  Node  Node × 100+
```

- **동접**: ~1억
- **외부 인프라**: Kafka 클러스터 (AWS MSK, Confluent Cloud 등)
- **코드 변경**: transport 1줄만 교체
- Central 서버 제거 — Kafka가 대체
- 공지: Admin이 `__broadcast` topic에 publish

```kotlin
// Before (Stage 2~4):
val transport = clientConnection.webSocketNodeTransport<SharedAction>()

// After (Stage 5):
val transport = KafkaNodeTransport<SharedAction>(bootstrapServers = "kafka:9092")

// NodeMediator 코드 동일
val mediator = NodeMediator(nodeId, transport, scope)
```

#### 시나리오 요약

| Stage | 서버 수 | 동접 | 외부 인프라 | 코드 변경 |
|-------|--------|------|-----------|----------|
| **0** | 1대 | ~10K | 없음 | — |
| **1** | 1대 (튜닝) | ~100K | 없음 | 없음 |
| **2** | 2~3대 | ~30K | 없음 | NodeMediator 추가 |
| **3** | 10~50대 | ~500K | 없음 | 없음 (Node 추가만) |
| **4** | 50~100대 | ~1,000만 | 없음 | Central 라우팅 설정 |
| **5** | 100대+ | ~1억 | Kafka | transport 1줄 교체 |

> **대안: 처음부터 Central + Node로 시작하기**
>
> Stage 0~1을 건너뛰고 서버 1대에서 Central과 Node를 함께 실행하는 방법도 있습니다.
> localhost 경유 오버헤드는 sub-ms 수준으로 무시할 수 있으며, 확장 시 코드 변경 없이
> Central을 별도 서버로 분리하고 Node 서버를 추가하기만 하면 됩니다.
>
> ```
> ┌──────────────────────────────┐
> │         물리 서버 1대          │
> │                              │
> │  [Central]  ←── localhost ──  [Node]  ←── Client
> │  :8080                       :8081
> └──────────────────────────────┘
>
> # 확장 시: 환경변수만 변경
> Node-A: CENTRAL_HOST=10.0.0.1  (기존 서버)
> Node-B: CENTRAL_HOST=10.0.0.1  (신규 서버 추가)
> ```
>
> 초기 복잡도가 약간 높지만 마이그레이션 비용이 0이므로, 확장을 예상하는 경우 권장합니다.

## 제약사항

- **Room migration 미지원** — 실행 중 room을 다른 node로 이동하는 기능은 제공하지 않습니다
- **Central 단일 장애점** — Central이 다운되면 모든 node가 영향받습니다 (Central 샤딩 또는 Event Bus로 완화)
- **Central은 순수 릴레이** — Central에는 Store/Reducer/Middleware가 없습니다. 상태 관리는 Node에서만 수행됩니다. 이 덕분에 외부 인프라 없이 운영 가능하지만, ~100만 이상 규모에서는 Event Bus로 전환이 필요합니다

## 다른 패턴으로 전환

| 신호 | 전환 대상 |
|------|----------|
| "단일 서버로 충분해요" | [Scaling Guide](./scaling.md) |
| "동시에 여러 방에 참여해야 해요" | [Multiplexer Pattern](./pattern-multiplexer.md) |
| "방 하나만 있으면 돼요" | [Shared State Pattern](./pattern-shared-state.md) |

## Related

- [Scaling Guide](./scaling.md) — 병렬 브로드캐스트, 대규모 연결 처리
- [Multiplexer Pattern](./pattern-multiplexer.md) — 단일 WebSocket 다중 방
- [Server Patterns Overview](./server-patterns.md) — 패턴 선택 가이드
- [Room Pattern](./pattern-room.md) — 방별 WebSocket 패턴
