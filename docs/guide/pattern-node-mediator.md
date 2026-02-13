# Node Mediator Pattern (수평 확장)

Node Mediator 패턴은 **단일 서버의 WebSocket 연결 한계**(~10,000)를 넘기기 위한 수평 확장 레이어입니다. Central Store(1개)와 다수의 Node 사이에서 액션을 중계하는 stateless mediator 구조입니다.

## 단일 서버 vs Node Mediator

| 항목 | 단일 서버 | Node Mediator |
|------|----------|---------------|
| WebSocket 한계 | ~10,000 | 노드 수 × 10,000 |
| Store 위치 | 서버 1대 | Central 1대 + Node N대 |
| 확장 방식 | 수직 확장 (스케일 업) | 수평 확장 (스케일 아웃) |
| 노드 간 통신 | 불필요 | Central ↔ Node (노드당 1개 WS) |
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
Central Store (1개)
    │
    ├── CentralNodeManager
    │     ├── Node A (NodeMediator) → Room Stores, Client Stores
    │     ├── Node B (NodeMediator) → Room Stores, Client Stores
    │     └── Node C (NodeMediator) → Room Stores, Client Stores
    │
    └── RoomRegistry (room → node 매핑)

Central Store ↔ Node: 노드당 1개 WebSocket
Node 내부: 로컬 dispatch
```

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
import io.flowdux.remote.nodemediator.InMemoryRoomRegistry

val roomRegistry = InMemoryRoomRegistry()
val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

val centralNodeManager = CentralNodeManager<SharedAction>(
    roomRegistry = roomRegistry,
    scope = scope,
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

// 모든 node에 브로드캐스트
centralNodeManager.broadcastToAllNodes("room-1", SyncState(state))
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
import io.flowdux.remote.nodemediator.typedNodeActionJson

val centralConnection = KtorWebSocketClientConnection.create(
    host = "central-server",
    port = 8080,
    path = "/node/node-A",
).typedNodeActionJson<SharedAction>()

val mediator = NodeMediator(
    nodeId = "node-A",
    centralConnection = centralConnection,
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

## 직렬화 설정

### Extension Functions 사용 (권장)

```kotlin
// Node-side (클라이언트 연결)
val physical = clientConnection.typedNodeActionJson<SharedAction>()

// Central-side (서버 연결)
val physical = serverConnection.typedNodeActionJson<SharedAction>()
```

### 직접 Codec 생성

```kotlin
import io.flowdux.remote.nodemediator.nodeRoutedActionCodecOf
import io.flowdux.remote.serialization.JsonMessageCodec

val nodeCodec = nodeRoutedActionCodecOf<SharedAction>()
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

## 제약사항

- **Room migration 미지원** — 실행 중 room을 다른 node로 이동하는 기능은 제공하지 않습니다
- **Central 단일 장애점** — Central Store가 다운되면 모든 node가 영향받습니다
- **Event Bus 미지원** — Kafka/Redis Streams 기반 확장은 향후 별도 모듈로 제공 예정

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
