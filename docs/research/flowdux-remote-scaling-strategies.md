# flowdux-remote 스케일링 전략 다이어그램

> 작성일: 2026-01-31
> **Note**: 이 문서는 초기 시각화 자료입니다. 전체 설계 내용은 [flowdux-remote Scaling Design Document](../design/flowdux-remote-scaling.md) Part 1에 통합되어 있습니다.

## 1. Room 파티셔닝

### 현재 구조 (단일 Store)

```
┌─────────────────────────────────────────────────┐
│                   Server Process                 │
│                                                  │
│  ┌────────────────────────────────────────────┐  │
│  │           Store<GameState, GameAction>      │  │
│  │  ┌──────────────────────────────────────┐  │  │
│  │  │  MultiClientServerRemoteMiddleware   │  │  │
│  │  │                                      │  │  │
│  │  │  sessions = {                        │  │  │
│  │  │    "alice" → TypedServerConnection   │  │  │
│  │  │    "bob"   → TypedServerConnection   │  │  │
│  │  │    "carol" → TypedServerConnection   │  │  │
│  │  │    "dave"  → TypedServerConnection   │  │  │
│  │  │    "eve"   → TypedServerConnection   │  │  │
│  │  │  }                                   │  │  │
│  │  └──────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────┘  │
│                                                  │
│  문제: alice가 보낸 액션이 dave, eve에게도        │
│  broadcast됨 (같은 게임방이 아닌데도)             │
└─────────────────────────────────────────────────┘
```

### Room 파티셔닝 적용 후

```
┌──────────────────────────────────────────────────────────┐
│                      Server Process                       │
│                                                           │
│  ┌─────────────────────┐    ┌─────────────────────┐      │
│  │  Room "game-1"      │    │  Room "game-2"      │      │
│  │  RemoteServer        │    │  RemoteServer         │      │
│  │  (Store + Session)   │    │  (Store + Session)    │      │
│  │  ┌───────────────┐  │    │  ┌───────────────┐   │      │
│  │  │ Middleware     │  │    │  │ Middleware     │   │      │
│  │  │               │  │    │  │               │   │      │
│  │  │ "alice" ─► ws │  │    │  │ "dave" ─► ws  │   │      │
│  │  │ "bob"   ─► ws │  │    │  │ "eve"  ─► ws  │   │      │
│  │  │ "carol" ─► ws │  │    │  │               │   │      │
│  │  └───────────────┘  │    │  └───────────────┘   │      │
│  └─────────────────────┘    └──────────────────────┘      │
│                                                           │
│  ┌──────────────────────────────────────────────────┐     │
│  │  RoomManager                                      │     │
│  │                                                   │     │
│  │  rooms = {                                        │     │
│  │    "game-1" → RemoteServer (alice, bob, carol)    │     │
│  │    "game-2" → RemoteServer (dave, eve)            │     │
│  │  }                                                │     │
│  │                                                   │     │
│  │  fun handleClient(sessionId, connection) {        │     │
│  │    val roomId = resolveRoom(sessionId)             │     │
│  │    rooms[roomId].handleClient(sessionId, conn)     │     │
│  │  }                                                │     │
│  └──────────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────────┘

 alice의 액션 → game-1의 Store만 통과 → bob, carol에게만 broadcast
 dave의 액션  → game-2의 Store만 통과 → eve에게만 broadcast
```

**핵심**: `RemoteServer`가 Store + `RemoteServerSession`을 캡슐화하고 있으므로, Room별로 `RemoteServer` 인스턴스를 만들면 자연스럽게 상태가 격리됨. 추가 인프라 불필요.

---

## 2. Pub/Sub Backplane

### 문제 상황 (서버가 여러 대일 때)

```
              Load Balancer
             ┌─────┴─────┐
             ▼           ▼
        ┌─────────┐  ┌─────────┐
        │Server 1 │  │Server 2 │
        │         │  │         │
        │ Store   │  │ Store   │
        │ (Room A)│  │ (Room A)│  ← 같은 Room인데 Store가 2개?
        │         │  │         │
        │ alice ● │  │ ● bob   │
        │ carol ● │  │ ● dave  │
        └─────────┘  └─────────┘

  alice가 메시지 전송 → Server 1의 Store만 업데이트
  bob, dave는 Server 2에 있어서 메시지를 못 받음 ✗
```

### Pub/Sub Backplane 적용 후

```
        ┌─────────┐              ┌─────────┐
        │Server 1 │              │Server 2 │
        │         │              │         │
        │ Store   │              │ Store   │
        │ (replica)              │ (replica)│
        │         │              │         │
        │ alice ● │              │ ● bob   │
        │ carol ● │              │ ● dave  │
        └────┬────┘              └────┬────┘
             │                        │
             │   subscribe("room-A")  │
             │          ┌─────┐       │
             ├─────────►│Redis│◄──────┤
             │          │NATS │       │
             │          └─────┘       │
             │        Pub/Sub         │
             │        Backplane       │
             ▼                        ▼

  ① alice가 메시지 전송
  ② Server 1의 Store가 처리 → ClientSharedAction 발생
  ③ Middleware가 action을 Backplane에 publish("room-A", action)
  ④ Server 2가 subscribe("room-A")로 action 수신
  ⑤ Server 2가 로컬의 bob, dave에게 전달

  전체 흐름:

  alice ──► Server 1 ──► Store ──► Middleware
                                      │
                              ┌───────┴───────┐
                              ▼               ▼
                         로컬 전달        publish(action)
                         carol ✓              │
                                         ┌────┴────┐
                                         │  Redis  │
                                         └────┬────┘
                                              │
                                         subscribe
                                              │
                                              ▼
                                         Server 2
                                         bob  ✓
                                         dave ✓
```

### 구현 관점에서 변경 포인트

```
현재:
  MultiClientServerRemoteMiddleware
    └── broadcast(action)  →  로컬 sessions만 순회

변경:
  MultiClientServerRemoteMiddleware
    └── broadcast(action)
          ├── 로컬 sessions 순회 (기존)
          └── backplane.publish("room-A", action)  ← 추가

  + BackplaneSubscriber (새 컴포넌트)
    └── backplane.subscribe("room-A") { action ->
          로컬 sessions에 전달
        }
```

**핵심**: `ServerConnection` 추상화가 이미 있으므로, Backplane을 또 다른 `ServerConnection` 구현체로 볼 수도 있고, Middleware 레벨에서 publish/subscribe를 추가할 수도 있음.

---

## 3. Gateway 분리

### 현재 구조 (연결 + 로직이 한 프로세스)

```
┌──────────────────────────────────────┐
│           Server Process              │
│                                      │
│  WebSocket 연결 관리                  │
│  + Serialization (ActionCodec)       │
│  + Store (Reducer + Middleware)       │
│  + State Broadcasting                │
│                                      │
│  ● 배포 시 → 모든 WebSocket 끊김     │
│  ● Store 버그 → WebSocket도 죽음     │
│  ● 스케일링 단위가 통째로 묶여있음    │
└──────────────────────────────────────┘
```

### Gateway 분리 적용 후

```
┌─────────────────────────────────────────────────────────────┐
│                        Gateway Layer                         │
│                  (WebSocket 연결만 관리)                      │
│                                                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │  Gateway 1   │  │  Gateway 2   │  │  Gateway 3   │       │
│  │              │  │              │  │              │       │
│  │  alice ── ws │  │  bob ── ws   │  │  dave ── ws  │       │
│  │  carol ── ws │  │              │  │  eve  ── ws  │       │
│  │              │  │              │  │              │       │
│  │  역할:       │  │  역할:       │  │  역할:       │       │
│  │  - WS 연결   │  │  - WS 연결   │  │  - WS 연결   │       │
│  │  - 직렬화    │  │  - 직렬화    │  │  - 직렬화    │       │
│  │  - 라우팅    │  │  - 라우팅    │  │  - 라우팅    │       │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘       │
│         │                 │                 │                │
└─────────┼─────────────────┼─────────────────┼────────────────┘
          │    Internal Protocol (gRPC / MQ)  │
          │                 │                 │
┌─────────┼─────────────────┼─────────────────┼────────────────┐
│         ▼                 ▼                 ▼                │
│                    Logic Layer                                │
│              (Store + 비즈니스 로직)                           │
│                                                              │
│  ┌─────────────────────┐    ┌─────────────────────┐         │
│  │  Logic Server 1     │    │  Logic Server 2     │         │
│  │                     │    │                      │         │
│  │  Room "game-1"      │    │  Room "game-2"       │         │
│  │  Store<GameState>   │    │  Store<GameState>    │         │
│  │  Reducer            │    │  Reducer             │         │
│  │  Middleware          │    │  Middleware           │         │
│  │                     │    │                      │         │
│  │  WebSocket 모름 ✓   │    │  WebSocket 모름 ✓    │         │
│  └─────────────────────┘    └──────────────────────┘         │
│                                                              │
│  ● 독립 배포 가능 (Gateway 유지한 채 로직만 재시작)           │
│  ● 독립 스케일링 (연결 많으면 Gateway 추가,                   │
│    로직 무거우면 Logic Server 추가)                           │
└──────────────────────────────────────────────────────────────┘
```

### 메시지 흐름

```
  alice ─ws─► Gateway 1 ─gRPC─► Logic Server 1
                                      │
                                   Store 처리
                                      │
                                  ClientSharedAction
                                      │
                              ┌───────┴───────┐
                              ▼               ▼
                    Logic → Gateway 1    Logic → Gateway 3
                         │                    │
                    carol ✓              dave ✓ (같은 Room이면)
```

### Gateway와 Logic 간 통신

```
┌──────────┐                         ┌──────────────┐
│ Gateway  │  ── action(encoded) ──► │ Logic Server │
│          │                         │              │
│          │  ◄── response(actions)──│  Store       │
│          │                         │  Reducer     │
│          │  ◄── push(broadcast) ── │  Middleware   │
└──────────┘                         └──────────────┘

Gateway가 알아야 할 것:
  - 클라이언트 ↔ WebSocket 매핑
  - 클라이언트 ↔ Logic Server 라우팅 테이블
  - Serialization (ActionCodec)

Gateway가 모르는 것:
  - 게임 로직, 상태, 리듀서 (전혀 모름)
```

---

## 세 가지 전략 비교

```
복잡도    낮 ◄──────────────────────────────► 높
          │                                    │
          │  1. Room         2. Pub/Sub    3. Gateway
          │  파티셔닝        Backplane      분리
          │                                    │
스케일    단일 서버 내       다수 서버       연결/로직
범위      상태 격리          상태 공유       완전 분리
          │                                    │
변경량    RoomManager       Middleware에     아키텍처
          추가만            publish 추가    재설계
          │                 + Subscriber     │
필요      없음              Redis 또는       gRPC/MQ
인프라                      NATS             + 브로커
```

이 세 전략은 **순차적으로 적용 가능**하다. 1 → 2 → 3 순서로 필요에 따라 점진적으로 도입할 수 있다.
