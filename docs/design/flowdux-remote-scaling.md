# flowdux-remote Scaling Design Document

> Status: Draft
> Created: 2026-02-01
> Related: [WebSocket Scaling Patterns Research](../research/websocket-scaling-patterns.md)

## 목차

**Part 1: Scaling**

1. [현재 아키텍처](#1-현재-아키텍처)
2. [스케일링 병목 분석](#2-스케일링-병목-분석)
3. [전략 1: Room 파티셔닝](#3-전략-1-room-파티셔닝)
4. [전략 2: Pub/Sub Backplane](#4-전략-2-pubsub-backplane)
5. [전략 3: Gateway 분리](#5-전략-3-gateway-분리)
6. [전략 비교 및 도입 로드맵](#6-전략-비교-및-도입-로드맵)
7. [업계 레퍼런스](#7-업계-레퍼런스)

**Part 2: Versioning & Deployment**

8. [버전 관리 기본 원칙](#8-버전-관리-기본-원칙)
9. [현재 코드베이스의 버전 지원 현황](#9-현재-코드베이스의-버전-지원-현황)
10. [케이스별 배포 전략](#10-케이스별-배포-전략)
11. [flowdux-remote가 제공해야 하는 것](#11-flowdux-remote가-제공해야-하는-것)
12. [앱 개발자가 처리하는 것](#12-앱-개발자가-처리하는-것)

**Part 3: Use Case Feasibility (PR #95)**

13. [PR #95 아키텍처 변경 요약](#13-pr-95-아키텍처-변경-요약)
14. [패턴별 Use Case 구현 가능성](#14-패턴별-use-case-구현-가능성)
15. [복합 아키텍처 제약](#15-복합-아키텍처-제약)
16. [Use Case 종합 평가](#16-use-case-종합-평가)

**Part 4: Channel Multiplexing**

17. [멀티플렉싱 개요](#17-멀티플렉싱-개요)
18. [현재 코드베이스의 1:1 가정 분석](#18-현재-코드베이스의-11-가정-분석)
19. [구현 접근 방식 비교](#19-구현-접근-방식-비교)
20. [권장 설계: Multiplexing Wrapper](#20-권장-설계-multiplexing-wrapper)
21. [클라이언트측 설계](#21-클라이언트측-설계)
22. [Wire Protocol 확장](#22-wire-protocol-확장)
23. [멀티플렉싱 도입 전략](#23-멀티플렉싱-도입-전략)

---

## 1. 현재 아키텍처

### 1.1 모듈 구조

```
flowdux-remote-core          SharedAction, ServerSharedAction, ClientSharedAction
flowdux-remote-client        SyncMiddleware, ClientConnection, TypedClientConnection
flowdux-remote-server        MultiClientSingleClientSyncMiddleware, RemoteServerSession, ServerConnection
flowdux-remote-serialization ActionCodec, MessageCodec, DefaultTyped*Connection
flowdux-remote-ktor          KtorWebSocket*Connection (transport 구현체)
```

### 1.2 핵심 인터페이스

**전송 계층 (Transport)**

| 인터페이스 | 역할 | 핵심 멤버 |
|-----------|------|----------|
| `ServerConnection` | 서버측 raw transport (1 클라이언트) | `incoming: Flow<String>`, `send(String)` |
| `ClientConnection` | 클라이언트측 raw transport | `connectionState: StateFlow`, `incoming: Flow<String>`, `send(String)`, `connect()`, `disconnect()` |
| `TypedServerConnection<A>` | 타입 안전 서버 연결 | `incoming: Flow<A>`, `send(A)` |
| `TypedClientConnection<A>` | 타입 안전 클라이언트 연결 | `incoming: Flow<A>`, `send(A)`, `connect()`, `disconnect()` |

**액션 마커 (Action Markers)**

| 마커 | 방향 | Middleware 동작 |
|------|------|----------------|
| `ServerSharedAction` | Client → Server | `SyncMiddleware`가 가로채서 서버로 전송. 로컬 Store에는 emit하지 않음. |
| `ClientSharedAction` | Server → Client | `MultiClientSingleClientSyncMiddleware`가 가로채서 클라이언트로 broadcast. 로컬 Store에는 emit하지 않음. |
| `SessionAwareAction<A>` *(미구현, 설계 중)* | Server → Client (per-session) | `forSession(sessionId): A?`로 클라이언트별 다른 액션 전송. null 반환 시 해당 세션 스킵. 현재는 `createSessionAwareSharedStateServer`의 `sessionStateMapper`로 동일 기능 제공. |

**세션 관리**

| 클래스 | 역할 | 핵심 멤버 |
|--------|------|----------|
| `RemoteServerSession<A>` | 멀티 클라이언트 세션 관리자 | `handleClient(id, conn)`, `broadcast(action)`, `sendToClient(id, action)`, `sendPerSession(mapper)` |
| `MultiClientSingleClientSyncMiddleware<S, A>` | 서버 미들웨어 | `ClientSharedAction` 가로채기, `InternalAddSession` 처리 (FlowHolderAction으로 수신 리스닝 시작) |

### 1.3 현재 데이터 흐름

```
┌───────────────────────────────────────────────────────────────────┐
│                          Server Process                           │
│                                                                   │
│  ┌─────────────────────────────────────────────────────────────┐  │
│  │ Store<S, A>                                                 │  │
│  │                                                             │  │
│  │  Reducer ◄── action ◄── MultiClientSingleClientSyncMiddleware   │  │
│  │    │                          │              │              │  │
│  │    ▼                          │              │              │  │
│  │  new state ──► serveState() ──┘     sessions(MutableMap)    │  │
│  │                (broadcast)          │  Mutex로 보호         │  │
│  │                                     │                       │  │
│  │                                     ├─ "alice" → TypedConn  │  │
│  │                                     ├─ "bob"   → TypedConn  │  │
│  │                                     └─ "carol" → TypedConn  │  │
│  └─────────────────────────────────────────────────────────────┘  │
│                                                                   │
│  클라이언트 액션 수신:                                             │
│    SessionListenerAction (FlowHolderAction, concurrent)           │
│    → connection.incoming.map { it as A } → dispatch to Store      │
│                                                                   │
│  상태 브로드캐스트:                                                │
│    serveState(wrapState) → state 변경마다 wrapState(state)를       │
│    dispatch → ClientSharedAction이면 전체 세션에 broadcast         │
└───────────────────────────────────────────────────────────────────┘

  Client                          Server
  ──────                          ──────
  dispatch(ServerSharedAction)
      │
      ▼
  SyncMiddleware
  → connection.send(action)  ─────►  SessionListenerAction
  → 로컬 emit 안 함                  → dispatch to Store
                                      → Reducer 처리
                                      → state 변경
                                      → serveState()
                                      → ClientSharedAction
                                      → broadcast to all
                              ◄─────
  ServerListenerAction
  → incoming action 수신
  → dispatch to local Store
```

### 1.4 세션 관리 생명주기

```kotlin
// RemoteServerSession.handleClient()
suspend fun handleClient(sessionId: String, connection: TypedServerConnection<A>) {
    addSession(sessionId, connection)           // ① Mutex lock → map에 추가
    // → InternalAddSession dispatch
    // → SessionListenerAction(FlowHolderAction) emit
    // → connection.incoming 리스닝 시작
    try {
        awaitCancellation()                     // ② WebSocket 연결 유지
    } finally {
        removeSession(sessionId)                // ③ 연결 종료 시 자동 정리
    }
}
```

---

## 2. 스케일링 병목 분석

### 2.1 단일 Store (Vertical Only)

모든 클라이언트 액션이 하나의 `Store`를 통과한다. Reducer는 순차적으로 실행되므로, 액션 처리량이 단일 코루틴의 처리 속도에 바운드된다.

```
Client A ─┐
Client B ─┤──► 단일 Store (순차 Reducer) ──► broadcast
Client C ─┘

병목: Reducer 처리 속도 = 전체 시스템 처리량 상한
```

**영향 범위**: 서버 인스턴스를 추가해도 Store 간 상태 동기화 메커니즘이 없어 수평 확장 불가.

### 2.2 Broadcast Storm

`serveState()`가 매 state 변경에 대해 `wrapState(currentState)`를 호출하고, 결과가 `ClientSharedAction`이면 **모든 세션에 broadcast**한다.

```
부하 = (state 변경 빈도) × (연결된 클라이언트 수)

예: 초당 100회 state 변경 × 1,000 클라이언트 = 초당 100,000 메시지 전송
```

### 2.3 순차 Broadcast

`RemoteServerSession.broadcast()`에서 세션 스냅샷을 순회하며 하나씩 전송한다. 에러 격리는 되어 있지만, slow client가 전체 broadcast 루프를 지연시킬 수 있다.

```kotlin
// RemoteServerSession.broadcast() 현재 구현
suspend fun broadcast(action: A) {
    val snapshot = mutex.withLock { sessions.values.toList() }
    for (connection in snapshot) {
        try {
            connection.send(action)    // ← slow client 시 여기서 블로킹
        } catch (_: Exception) { }
    }
}
```

### 2.4 In-Memory 세션

세션 정보가 `MutableMap`에만 존재하므로:
- 서버 재시작 시 모든 세션 소실
- 서버 간 세션 공유 불가
- 로드밸런서가 연결을 다른 서버로 보내면 세션 유실

### 2.5 범위별 병목 요약

| 규모 | 주요 병목 | 증상 |
|------|----------|------|
| ~100 클라이언트 | 없음 | 현재 아키텍처로 충분 |
| ~1,000 | Broadcast 지연 | slow client가 다른 클라이언트 지연 유발 |
| ~10,000 | Store 처리량 + 네트워크 | 단일 Reducer 포화, broadcast 부하 급증 |
| ~100,000+ | 단일 서버 한계 | 수평 확장 필수, 현재 아키텍처로 불가 |

---

## 3. 전략 1: Room 파티셔닝

### 3.1 개요

단일 서버 내에서 논리적 단위(Room)별로 독립적인 `RemoteServer`를 생성하여 상태를 격리한다. `RemoteServer`가 Store + `RemoteServerSession`을 캡슐화하고 있으므로, 상위에 `RoomManager`만 추가하면 된다.

### 3.2 아키텍처

```
┌───────────────────────────────────────────────────────────────┐
│                        Server Process                         │
│                                                               │
│  ┌──────────────────────┐     ┌──────────────────────┐       │
│  │  Room "game-1"       │     │  Room "game-2"       │       │
│  │  RemoteServer         │     │  RemoteServer         │       │
│  │  (Store + Session)    │     │  (Store + Session)    │       │
│  │  ┌────────────────┐  │     │  ┌────────────────┐  │       │
│  │  │ Middleware      │  │     │  │ Middleware      │  │       │
│  │  │                │  │     │  │                │  │       │
│  │  │ "alice" ─► ws  │  │     │  │ "dave" ─► ws   │  │       │
│  │  │ "bob"   ─► ws  │  │     │  │ "eve"  ─► ws   │  │       │
│  │  │ "carol" ─► ws  │  │     │  │                │  │       │
│  │  └────────────────┘  │     │  └────────────────┘  │       │
│  └──────────────────────┘     └──────────────────────┘       │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │  RoomManager                                             │  │
│  │                                                          │  │
│  │  rooms: Map<String, RemoteServer>                        │  │
│  │                                                          │  │
│  │  handleClient(sessionId, roomId, connection)             │  │
│  │    → rooms[roomId].handleClient(sessionId, connection)   │  │
│  │                                                          │  │
│  │  createRoom(roomId, initialState, reducer)               │  │
│  │    → RemoteServerSession 생성 및 등록                     │  │
│  │                                                          │  │
│  │  closeRoom(roomId)                                       │  │
│  │    → session.close() 및 제거                              │  │
│  └─────────────────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────────────────┘
```

### 3.3 데이터 흐름

```
alice가 "game-1"에서 액션 전송:

  alice ──► WebSocket ──► RoomManager.resolve("game-1")
                              │
                              ▼
                    RemoteServerSession("game-1")
                              │
                         Store 처리
                              │
                    ClientSharedAction
                              │
                    broadcast to game-1 sessions only
                              │
                         bob ✓  carol ✓
                         dave ✗  eve ✗  (다른 Room)
```

### 3.4 구현 변경 범위

| 컴포넌트 | 변경 |
|----------|------|
| `RoomManager` | **신규**. Room 생성/삭제/조회, 클라이언트-Room 라우팅. |
| `RemoteServer` | Room당 1개 인스턴스 생성. Store + Session을 캡슐화. |
| `RemoteServerSession` | 변경 없음. `RemoteServer` 내부에서 세션 관리. |
| `MultiClientSingleClientSyncMiddleware` | 변경 없음. Session 단위로 동작. |
| Ktor 엔드포인트 | Room ID를 URL path 또는 쿼리로 수신하여 RoomManager에 전달. |

### 3.5 적용 시나리오

- 게임 로비/방 시스템 (각 방이 독립적 상태)
- 채팅 채널 (채널별 독립 메시지 히스토리)
- 문서 협업 (Figma 방식: 문서당 1 세션)

### 3.6 한계

- **단일 서버 바운드**: Room 수가 늘어도 모두 같은 프로세스에서 실행.
- **Room 간 통신 불가**: Room A의 상태를 Room B에서 참조할 수 없음. 필요하다면 별도 상위 레이어 필요.
- **Hot Room 문제**: 한 Room에 유저가 몰리면 해당 Room의 Store가 병목.

---

## 4. 전략 2: Pub/Sub Backplane

### 4.1 개요

서버 인스턴스를 여러 대로 늘리고, 외부 메시지 브로커(Redis Pub/Sub, NATS 등)를 통해 인스턴스 간 액션을 중계한다. 같은 Room의 클라이언트가 서로 다른 서버에 연결되어 있어도 메시지를 주고받을 수 있게 된다.

### 4.2 문제 상황 (Backplane 없이 다중 서버)

```
              Load Balancer
             ┌─────┴─────┐
             ▼           ▼
        ┌─────────┐  ┌─────────┐
        │Server 1 │  │Server 2 │
        │         │  │         │
        │ Room A  │  │ Room A  │  ← 같은 Room이 서버마다 별도 Store
        │ Store   │  │ Store   │
        │         │  │         │
        │ alice ● │  │ ● bob   │
        │ carol ● │  │ ● dave  │
        └─────────┘  └─────────┘

  alice → Server 1 → broadcast → carol만 수신 ✓
                                  bob, dave 수신 불가 ✗
```

### 4.3 Backplane 적용 아키텍처

```
        ┌─────────────┐                    ┌─────────────┐
        │  Server 1   │                    │  Server 2   │
        │             │                    │             │
        │  Room A     │                    │  Room A     │
        │  (primary)  │                    │  (relay)    │
        │             │                    │             │
        │  alice ── ws│                    │ws ── bob    │
        │  carol ── ws│                    │ws ── dave   │
        └──────┬──────┘                    └──────┬──────┘
               │                                  │
               │    pub/sub channel: "room-A"     │
               │          ┌──────────┐            │
               ├─publish─►│  Redis   │◄─subscribe─┤
               │          │  / NATS  │            │
               └─subscribe┤          ├─publish────┘
                          └──────────┘
```

### 4.4 데이터 흐름

```
① alice가 Server 1에서 액션 전송
② Server 1의 Store가 처리 → state 변경 → ClientSharedAction 발생
③ Middleware가 두 경로로 전달:
   ├── 로컬: carol에게 직접 전송 ✓
   └── Backplane: publish("room-A", encoded action)
④ Server 2가 subscribe("room-A")로 action 수신
⑤ Server 2가 로컬의 bob, dave에게 전달 ✓

  alice ──► Server 1 ──► Store ──► Middleware
                                       │
                               ┌───────┴────────┐
                               ▼                ▼
                          로컬 전달         publish("room-A")
                          carol ✓               │
                                          ┌─────┴─────┐
                                          │   Redis   │
                                          └─────┬─────┘
                                           subscribe
                                                │
                                                ▼
                                           Server 2
                                           bob ✓  dave ✓
```

### 4.5 설계 고려사항

**Primary/Relay 모델 vs. Full Replica 모델**

| 모델 | 설명 | 장단점 |
|------|------|--------|
| **Primary/Relay** | Room당 하나의 서버만 Store를 가짐. 다른 서버는 액션을 primary로 포워딩하고, broadcast만 relay. | 상태 일관성 보장. primary 장애 시 failover 필요. |
| **Full Replica** | 모든 서버가 같은 Room의 Store를 각각 가짐. Backplane으로 액션을 공유하여 각 Store가 동일하게 처리. | 장애 격리 우수. 상태 drift 위험 (동일 Reducer 보장 필요). |

**Primary/Relay가 더 적합한 이유:**
- flowdux의 Reducer가 순수 함수라도, 액션 순서에 따라 결과가 달라질 수 있음
- Primary가 유일한 source of truth이므로 상태 일관성이 보장됨
- Relay 서버는 클라이언트 액션을 primary로 포워딩하기만 하면 됨

```
Primary/Relay 흐름:

  bob (Server 2) ──► Server 2 (relay)
                          │
                     forward to primary via backplane
                          │
                          ▼
                     Server 1 (primary)
                          │
                     Store 처리 → broadcast via backplane
                          │
                     ┌────┴────┐
                     ▼         ▼
                 Server 1   Server 2
                 alice ✓    bob ✓
                 carol ✓    dave ✓
```

### 4.6 구현 변경 범위

| 컴포넌트 | 변경 |
|----------|------|
| `Backplane` 인터페이스 | **신규**. `publish(channel, action)`, `subscribe(channel): Flow<A>` |
| `RedisBackplane` / `NatsBackplane` | **신규**. Backplane 구현체. |
| `RemoteServerSession` | `broadcast()` 확장: 로컬 전송 + backplane publish |
| `BackplaneRelay` | **신규**. subscribe → 로컬 세션에 전달하는 컴포넌트. |
| `RoomManager` | Room별 primary/relay 역할 관리. |

### 4.7 브로커 선택 기준

| | Redis Pub/Sub | NATS |
|---|---|---|
| 지연시간 | 극저 (in-memory) | 극저 |
| 전달 보장 | At-most-once | At-most-once (JetStream으로 at-least-once) |
| 운영 복잡도 | 낮음-중간 | 낮음 |
| WebSocket 네이티브 | X | O (v2.2.0+) |
| Kotlin 클라이언트 | Lettuce, Jedis | nats.java |
| 적합 상황 | 이미 Redis 인프라가 있을 때 | 새로 구축하거나 경량 운영 원할 때 |

### 4.8 한계

- **외부 인프라 의존**: Redis 또는 NATS 클러스터 운영 필요.
- **네트워크 홉 추가**: 메시지가 서버 → 브로커 → 서버를 거치므로 지연 증가.
- **Primary 장애**: Primary/Relay 모델에서 primary 서버 장애 시 failover 로직 필요.
- **메시지 유실 가능**: At-most-once 전달에서 브로커-서버 간 순간 단절 시 메시지 소실.

---

## 5. 전략 3: Gateway 분리

### 5.1 개요

WebSocket 연결 관리(Gateway)와 비즈니스 로직(Logic Server)을 별도 프로세스/서비스로 분리한다. Gateway는 연결 유지와 직렬화만 담당하고, 액션 처리와 상태 관리는 Logic Server가 담당한다.

### 5.2 현재 구조의 문제

```
┌────────────────────────────────┐
│        Server Process          │
│                                │
│  WebSocket 연결 관리            │
│  + Serialization (ActionCodec) │
│  + Store (Reducer + Middleware)│
│  + State Broadcasting          │
│                                │
│  문제:                          │
│  ● 배포 시 → 모든 연결 끊김     │
│  ● Store 버그 → 연결도 죽음     │
│  ● 스케일링 단위가 묶여있음     │
└────────────────────────────────┘
```

### 5.3 분리 아키텍처

```
┌─────────────────────────────────────────────────────────────────┐
│                         Gateway Layer                           │
│                   (WebSocket 연결만 관리)                        │
│                                                                 │
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐       │
│  │  Gateway 1    │  │  Gateway 2    │  │  Gateway 3    │       │
│  │               │  │               │  │               │       │
│  │  alice ── ws  │  │  bob ── ws    │  │  dave ── ws   │       │
│  │  carol ── ws  │  │               │  │  eve  ── ws   │       │
│  │               │  │               │  │               │       │
│  │  담당:        │  │  담당:        │  │  담당:        │       │
│  │  - WS 수립/유지│  │  - WS 수립/유지│  │  - WS 수립/유지│       │
│  │  - 직렬화     │  │  - 직렬화     │  │  - 직렬화     │       │
│  │  - 라우팅     │  │  - 라우팅     │  │  - 라우팅     │       │
│  └───────┬───────┘  └───────┬───────┘  └───────┬───────┘       │
│          │                  │                  │                │
└──────────┼──────────────────┼──────────────────┼────────────────┘
           │   Internal Protocol (gRPC / MQ)     │
           │                  │                  │
┌──────────┼──────────────────┼──────────────────┼────────────────┐
│          ▼                  ▼                  ▼                │
│                       Logic Layer                               │
│                 (Store + 비즈니스 로직)                           │
│                                                                 │
│  ┌──────────────────────┐     ┌──────────────────────┐         │
│  │  Logic Server 1      │     │  Logic Server 2      │         │
│  │                      │     │                      │         │
│  │  Room "game-1"       │     │  Room "game-2"       │         │
│  │  Store<GameState>    │     │  Store<GameState>    │         │
│  │  Reducer             │     │  Reducer             │         │
│  │  Middleware           │     │  Middleware           │         │
│  │                      │     │                      │         │
│  │  WebSocket 인지 없음 │     │  WebSocket 인지 없음 │         │
│  └──────────────────────┘     └──────────────────────┘         │
│                                                                 │
│  ● Gateway 유지한 채 Logic만 재시작 가능 (zero-downtime deploy) │
│  ● 연결 수 증가 → Gateway 추가                                  │
│  ● 로직 부하 증가 → Logic Server 추가                           │
└─────────────────────────────────────────────────────────────────┘
```

### 5.4 Gateway ↔ Logic Server 통신

```
┌───────────┐                           ┌────────────────┐
│  Gateway  │  ── action(encoded) ────► │  Logic Server  │
│           │                           │                │
│           │  ◄── response(actions) ── │  Store         │
│           │                           │  Reducer       │
│           │  ◄── push(broadcast) ──── │  Middleware     │
└───────────┘                           └────────────────┘
```

**Gateway의 책임:**

| 책임 | 설명 |
|------|------|
| WebSocket 수립/유지 | 클라이언트와의 연결 생명주기 관리 |
| 직렬화/역직렬화 | `ActionCodec`을 사용한 액션 인코딩/디코딩 |
| 라우팅 | 클라이언트 → 어느 Logic Server로 보낼지 결정 |
| 연결 레지스트리 | `sessionId → WebSocket` 매핑 유지 |

**Gateway가 모르는 것:**
- 게임 로직, 상태, Reducer (전혀 모름)
- 어떤 액션이 어떤 결과를 만드는지 (전혀 모름)

**Logic Server의 책임:**

| 책임 | 설명 |
|------|------|
| Store 운영 | Reducer + Middleware로 액션 처리 |
| 상태 관리 | 게임/채팅 등 비즈니스 상태 유지 |
| Broadcast 결정 | 어떤 클라이언트에 어떤 액션을 보낼지 결정 |

**Logic Server가 모르는 것:**
- WebSocket 연결 상태 (전혀 모름)
- 클라이언트가 어느 Gateway에 있는지 (몰라도 됨)

### 5.5 flowdux-remote 추상화와의 관계

현재 `ServerConnection` / `TypedServerConnection` 인터페이스가 이미 전송 계층을 추상화하고 있다. Gateway 패턴에서는 이 인터페이스의 **구현체**가 WebSocket 직접 연결 대신 **Gateway와의 내부 프로토콜 연결**이 된다.

```
현재:
  TypedServerConnection 구현체 = KtorWebSocketServerConnection + Codec
    → WebSocket 프레임 직접 읽기/쓰기

Gateway 분리 후:
  TypedServerConnection 구현체 = GatewayConnection
    → gRPC/MQ를 통해 Gateway에 메시지 전달
    → Gateway가 실제 WebSocket에 전달
```

### 5.6 구현 변경 범위

| 컴포넌트 | 변경 |
|----------|------|
| `GatewayServer` | **신규 서비스**. WebSocket 수립, 직렬화, 라우팅. |
| `GatewayConnection` | **신규**. `TypedServerConnection` 구현체. Gateway ↔ Logic 통신. |
| `RemoteServerSession` | 변경 없음. `TypedServerConnection`이 추상화되어 있으므로. |
| `MultiClientSingleClientSyncMiddleware` | 변경 없음. |
| 내부 프로토콜 | **신규**. Gateway ↔ Logic 간 gRPC 또는 MQ 프로토콜 정의. |
| 라우팅 테이블 | **신규**. sessionId → Logic Server 매핑. |

### 5.7 한계

- **아키텍처 복잡도 대폭 증가**: 두 개의 서비스 타입을 운영/배포/모니터링해야 함.
- **내부 프로토콜 설계 필요**: Gateway ↔ Logic 간 통신 프로토콜 정의, 에러 처리, 재연결 로직.
- **지연 시간 증가**: 클라이언트 → Gateway → Logic → Gateway → 클라이언트 (2 hop 추가).
- **디버깅 어려움**: 메시지가 여러 서비스를 거치므로 추적이 복잡.

---

## 6. 전략 비교 및 도입 로드맵

### 6.1 전략 비교

```
복잡도     낮 ◄─────────────────────────────────► 높

            1. Room           2. Pub/Sub       3. Gateway
            파티셔닝          Backplane         분리
            │                 │                 │
스케일     단일 서버 내       다수 서버          연결/로직
범위       상태 격리          상태 공유          완전 분리
            │                 │                 │
변경량     RoomManager       Middleware +       아키텍처
           추가만             Backplane 추가    재설계
            │                 │                 │
필요       없음               Redis 또는        gRPC/MQ
인프라                        NATS              + 브로커
            │                 │                 │
적합       ~10K 접속          ~100K 접속        ~1M+ 접속
규모
```

### 6.2 단계별 도입 로드맵

이 세 전략은 순차적으로 적용 가능하다. 각 단계가 이전 단계를 기반으로 확장된다.

```
Phase 1                    Phase 2                     Phase 3
Room 파티셔닝              + Pub/Sub Backplane          + Gateway 분리
─────────────              ──────────────────           ──────────────

단일 서버                  다수 서버                    Gateway + Logic
Room별 독립 Store          Room별 primary/relay         연결/로직 완전 분리
RoomManager 추가           Backplane 인터페이스 추가    GatewayConnection 구현

현재 코드 변경 최소         Middleware 확장              TypedServerConnection
                           외부 브로커 도입              구현체 교체
```

**Phase 1: Room 파티셔닝**
- 전제조건: 없음
- 산출물: `RoomManager`, Room 생성/삭제/조회 API
- 기대효과: 논리적 상태 격리, broadcast 범위 축소

**Phase 2: Pub/Sub Backplane**
- 전제조건: Phase 1 (Room 단위 관리가 되어야 채널 구분 가능)
- 산출물: `Backplane` 인터페이스, Redis/NATS 구현체, `BackplaneRelay`
- 기대효과: 수평 확장 가능, 서버 인스턴스 추가로 처리량 증가

**Phase 3: Gateway 분리**
- 전제조건: Phase 2 (서버 간 통신이 가능해야 Gateway/Logic 분리 가능)
- 산출물: `GatewayServer`, `GatewayConnection`, 내부 프로토콜 정의
- 기대효과: 독립 스케일링, zero-downtime 배포, 대규모 연결 수용

### 6.3 추가 고려사항 (Phase와 독립적)

| 항목 | 설명 | 적용 시점 |
|------|------|----------|
| **Delta Sync** | 전체 state 대신 변경분만 전송. `stateMapper`를 `(prev, current) -> DeltaAction?`으로 확장. | 네트워크 부하가 문제될 때 |
| **Parallel Broadcast** | `broadcast()`에서 `coroutineScope` + `launch`로 병렬 전송. | Phase 1부터 즉시 적용 가능 |
| **Backpressure** | 클라이언트별 버퍼 제한, slow client 감지 및 연결 해제. Kotlin Flow의 `buffer()` 활용. | 클라이언트 수 증가 시 |
| **Reconnection** | Exponential backoff + jitter. Resume 프로토콜 (session ID + sequence number). | 프로덕션 배포 전 |

---

## 7. 업계 레퍼런스

| 서비스 | 규모 | 핵심 패턴 | flowdux-remote 적용 시사점 |
|--------|------|----------|---------------------------|
| **Discord** | 샤드당 5K유저, 260만 동시 음성 | Gateway 샤딩, Elixir/BEAM | Phase 3 + 샤딩 |
| **Slack** | 500만 동시 세션 | Gateway + Channel Server, Consistent Hashing | Phase 2-3, 채널 기반 파티셔닝 |
| **Figma** | 문서당 1 프로세스 | CRDT, 문서 단위 파티셔닝 | Phase 1과 동일한 패턴 |
| **Socket.io** | - | Redis adapter, Sticky session | Phase 2와 유사 |
| **Phoenix** | 단일 서버 200만 접속 | Erlang VM 분산 모델, 내장 PubSub | Phase 1-2, 외부 브로커 불필요 모델 참고 |
| **Centrifugo** | 100만+ 접속 | Redis/NATS backplane, 다중 transport | Phase 2-3, 오픈소스 Gateway 참고 |

상세 조사 내용은 [WebSocket Scaling Patterns Research](../research/websocket-scaling-patterns.md) 참조.

---

# Part 2: Versioning & Deployment

## 8. 버전 관리 기본 원칙

### 8.1 전제: 모노리포 구조

```
app/
├── shared/       ← Action, State 계약 (양쪽 의존)
├── backend/      ← Server (즉시 배포 가능)
└── client/       ← Mobile/Web (유저가 업데이트해야 함)
```

서버와 클라이언트의 배포 시점이 다르다. 서버는 즉시 반영되지만, 클라이언트는 스토어 심사와 유저의 업데이트 행동에 의존한다.

### 8.2 기본 골자: 강제 업데이트 (강업)

```
배포 흐름:

  ① shared 계약 변경 (Action, State)
  ② server 배포 (새 코드, 즉시 반영)
  ③ client 빌드 → 스토어 심사 → 릴리즈
  ④ 구버전 클라이언트에게 강제 업데이트 안내
  ⑤ 강업 유예 기간 이후 구버전 연결 거부
```

### 8.3 핵심 원칙

```
원칙 1: Store/Reducer/State/Middleware는 항상 최신 1벌만 유지.
        버전별 Store를 따로 운영하지 않는다.

원칙 2: 호환성은 경계(입출력)에서 처리한다.
        Inbound:  구버전 클라이언트 액션 → 현재 내부 액션 변환
        Outbound: 현재 상태 → 구버전 클라이언트 형식 변환

원칙 3: 최소 지원 버전 정책.
        서버는 최근 N 버전까지만 지원.
        이전 버전은 강제 업데이트 안내.
```

### 8.4 버전별 Store가 비현실적인 이유

```
버전별 Store 접근법의 문제:

  서버 코드베이스:
  ├── v1/ (SharedAction v1, State v1, Reducer v1, Store v1)
  ├── v2/ (SharedAction v2, State v2, Reducer v2, Store v2)
  └── v3/ ...

  문제 1: 게임방에 v1 유저와 v2 유저가 섞여있으면?
           → Store를 2개 돌려야 함? 상태 동기화는?

  문제 2: v5까지 갔을 때 Reducer가 5벌
           → 비즈니스 로직 수정 시 5벌 모두 수정?

  문제 3: State v1과 State v2의 구조가 다르면
           → v1 유저의 액션을 v2 State에 어떻게 적용?

  결론: 유지보수 불가능.
```

올바른 접근은 REST API 버전 관리와 동일하다:

```
REST API:
  /api/v1/users → Controller가 v1 DTO로 변환 → 내부 Service는 1벌
  /api/v2/users → Controller가 v2 DTO로 변환 → 같은 Service

flowdux-remote:
  v1 client action → Inbound Adapter가 변환 → Store는 1벌
  v2 client action → Inbound Adapter가 변환 → 같은 Store
```

---

## 9. 현재 코드베이스의 버전 지원 현황

flowdux-remote에는 버전 호환을 위한 인프라가 **전혀 없다.**

### 9.1 없는 것들

| 항목 | 현재 상태 | 위치 |
|------|----------|------|
| Wire protocol 버전 필드 | **없음** | `JsonMessageCodec.kt` — 고정 포맷 `{"type":"action","payload":{...}}` |
| Handshake/협상 | **없음** | `KtorWebSocketClientConnection.connect()` — TCP 연결 후 즉시 CONNECTED |
| 역직렬화 에러 처리 | **없음** | `DefaultTypedClientConnection.incoming` — decode 실패 시 예외 전파 → 연결 끊김 |
| 서버측 연결 종료 | **없음** | `ServerConnection`, `TypedServerConnection` — close/reject 메서드 없음 |
| 최소 버전 체크 | **없음** | 샘플 서버 — auth/version 미들웨어 없이 즉시 `handleClient()` 호출 |

### 9.2 있는 것들 (활용 가능)

| 항목 | 상태 | 활용 가능성 |
|------|------|------------|
| `ActionCodec` 추상화 | 있음 | `decode()`를 `decodeOrNull()`로 확장 가능 |
| `MessageCodec` 추상화 | 있음 | envelope에 버전 필드 추가 가능 |
| `SessionAwareAction` | **미구현** (설계만 됨) | 구현 시 클라이언트 버전별 다른 응답 전송에 활용 가능. 현재는 `createSessionAwareSharedStateServer`의 `sessionStateMapper`로 대체 가능. |
| `RemoteServerSession.handleClient()` | 있음 | handshake 단계를 앞에 삽입 가능 |
| Ktor 라우팅 | 있음 | WebSocket 엔드포인트 앞에서 버전 체크 가능 (앱 개발자 영역) |

### 9.3 현재 역직렬화 동작

```kotlin
// DefaultTypedClientConnection.incoming (현재)
connection.incoming.transform { raw ->
    val response = messageCodec.decodeServerMessage(raw)
    for (actionJson in response.actions) {
        emit(actionCodec.decode(actionJson))  // ← 실패 시 예외 → Flow 종료 → 연결 끊김
    }
}

// DefaultTypedServerConnection.incoming (현재)
connection.incoming.map { raw ->
    val actionJson = messageCodec.decodeActionFromClient(raw)
    actionCodec.decode(actionJson)  // ← 실패 시 예외 → Flow 종료 → 연결 끊김
}
```

모르는 Action 타입이 하나라도 오면 **전체 연결이 끊긴다.** 이는 `SerializableActionCodecTest.kt:82-86`에서 확인된 동작이다.

---

## 10. 케이스별 배포 전략

### Case 1: 서버만 배포, 클라이언트 변경 없음

```
변경: 서버 내부 로직만 수정 (Reducer 최적화, 버그 수정)
shared 모듈: 변경 없음

전략: 그냥 배포하면 됨. 별도 조치 불필요.
```

라이브러리 지원 필요: **없음**

---

### Case 2: Shared에 optional 필드 추가

```
변경: 기존 Action/State에 default value가 있는 필드 추가

  // v1
  data class SyncState(val players: List<Player>)

  // v2
  data class SyncState(
      val players: List<Player>,
      val rankings: List<Rank> = emptyList(),  // optional, default 있음
  )

  v1 클라이언트 → v2 서버: rankings 없이 보냄 → default로 처리됨 ✓
  v2 서버 → v1 클라이언트: rankings 포함하여 보냄 → ignoreUnknownKeys=true면 무시됨 ✓

전략: 강업 불필요. 자연 호환.
```

라이브러리 지원 필요: **`ignoreUnknownKeys = true` 기본 활성화**

---

### Case 3: Shared에 새 Action 타입 추가

```
변경: sealed interface에 새 subtype 추가

  // v2에서 추가
  data class UserTyping(...) : ChatAction, ClientSharedAction

  v2 서버 → v1 클라이언트: UserTyping 전송 → v1이 역직렬화 실패 → 크래시

전략: 강업이 기본.
      유예 기간 동안은 lenient decode로 구버전 클라이언트가 안전하게 스킵.
```

라이브러리 지원 필요: **lenient decode (`decodeOrNull`)**

---

### Case 4: 기존 Action 필드 Breaking 변경

```
변경: 기존 Action의 필드 타입/이름/구조 변경

  // v1: data class SendMessage(val user: String, val text: String)
  // v2: data class SendMessage(val userId: Int, val content: MessageContent)

  wire format이 완전히 달라짐. 통신 불가.

전략: 반드시 강업. 유예 기간 없음.
      서버 배포 전에 클라이언트가 먼저 배포되어야 하거나,
      과도기 동안 두 필드를 모두 지원하는 중간 버전을 경유.
```

라이브러리 지원 필요: **없음** (앱 개발자의 계약 설계 영역)

---

### Case 5: 기존 Action 제거

```
변경: 더 이상 사용하지 않는 Action 제거

  v1 클라이언트 → v2 서버: 제거된 Action 전송 → 서버 역직렬화 실패

전략: 반드시 강업. 제거 전에 deprecated 표시 → 사용 중단 확인 후 제거.
```

라이브러리 지원 필요: **서버측 lenient decode** (모르는 Action을 에러 대신 무시)

---

### Case 6: 강업이 불가능한 상황 (스토어 심사 지연)

```
상황: 서버는 v2로 배포했는데 클라이언트 v2가 앱스토어 심사 중.

전략 A (권장): 서버 배포를 클라이언트 승인 후로 지연
  ① client v2 스토어 심사 통과
  ② server v2 배포
  ③ 강업 플래그 활성화

전략 B: 서버가 양쪽 모두 처리 (경계 변환)
  서버 v2가 v1 wire format도 수신/발신 가능하도록
  Inbound/Outbound 변환 레이어 구성

전략 C: 버전별 엔드포인트
  /ws/v1 → v1 호환 처리
  /ws/v2 → v2 전체 기능
  → 앱 개발자가 Ktor 라우팅에서 처리 (라이브러리 영역 아님)
```

---

### 케이스 요약

| Case | 변경 유형 | 강업 필요 | 유예 가능 | 라이브러리 지원 필요 |
|------|----------|----------|----------|-------------------|
| 1 | 서버 내부만 변경 | 불필요 | - | 없음 |
| 2 | optional 필드 추가 | 불필요 | - | `ignoreUnknownKeys` |
| 3 | 새 Action 추가 | 필요 | 가능 (lenient decode) | lenient decode |
| 4 | 기존 필드 Breaking 변경 | 필수 | 불가 | 없음 |
| 5 | 기존 Action 제거 | 필수 | 서버측만 가능 | 서버측 lenient decode |
| 6 | 심사 지연 | 필수 | 배포 순서로 해결 | (전략에 따라 다름) |

---

## 11. flowdux-remote가 제공해야 하는 것

코드베이스 분석 결과, 라이브러리 차원에서 **반드시 제공해야** 하는 것과 **제공하면 좋은** 것을 구분한다.

### 11.1 필수: Lenient Deserialization

모르는 Action 타입이 하나라도 오면 전체 연결이 끊기는 현재 동작은, 버전 호환 이전에 기본적인 안정성 문제이다.

**필요한 변경:**

```
ActionCodec 인터페이스:
  fun decode(json: String): A                  // 기존 (유지)
  fun decodeOrNull(json: String): A?           // 추가 (실패 시 null)

DefaultTypedClientConnection.incoming (변경 후):
  connection.incoming.transform { raw ->
      val response = messageCodec.decodeServerMessage(raw)
      for (actionJson in response.actions) {
          val action = actionCodec.decodeOrNull(actionJson)
          if (action != null) emit(action)     // null이면 스킵
      }
  }

DefaultTypedServerConnection.incoming (변경 후):
  connection.incoming.mapNotNull { raw ->
      val actionJson = messageCodec.decodeActionFromClient(raw)
      actionCodec.decodeOrNull(actionJson)     // null이면 스킵
  }
```

**이것이 없으면:**
- Case 3 (새 Action 추가)에서 구버전 클라이언트 크래시
- Case 5 (Action 제거)에서 서버 크래시
- **강업 유예 기간 자체가 불가능** (모르는 Action 하나에 전체 연결이 끊기므로)

### 11.2 필수: ignoreUnknownKeys 기본 활성화

**현재:**

```kotlin
// SerializableActionCodec.kt
val DefaultJson: Json = Json {
    classDiscriminator = "type"
    // ignoreUnknownKeys 미설정 → 기본값 false
}
```

**필요한 변경:**

```kotlin
val DefaultJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true   // ← 추가
}
```

**이것이 없으면:**
- Case 2 (optional 필드 추가)조차 구버전 클라이언트가 크래시
- 가장 안전한 변경 유형도 강업이 필수가 됨

### 11.3 필수: 서버측 연결 종료 메커니즘

**현재:**

```kotlin
interface ServerConnection {
    val incoming: Flow<String>
    suspend fun send(message: String)
    // close()가 없음
}

interface TypedServerConnection<A : Action> {
    val incoming: Flow<A>
    suspend fun send(action: A)
    // close()가 없음
}
```

**필요한 변경:**

```kotlin
interface ServerConnection {
    val incoming: Flow<String>
    suspend fun send(message: String)
    suspend fun close(reason: String = "")       // ← 추가
}

interface TypedServerConnection<A : Action> {
    val incoming: Flow<A>
    suspend fun send(action: A)
    suspend fun close(reason: String = "")       // ← 추가
}
```

**이것이 없으면:**
- 강업 알림 후 연결 종료를 라이브러리 레벨에서 할 수 없음
- 앱 개발자가 Ktor의 WebSocketSession을 직접 참조하여 close해야 함 (추상화 누수)

### 11.4 권장: Wire Protocol 버전 필드

**현재 wire format:**

```json
Client → Server: {"type":"action","payload":{...}}
Server → Client: {"type":"response","actions":[{...},...]}
```

**제안:**

```json
Client → Server: {"v":1,"type":"action","payload":{...}}
Server → Client: {"v":1,"type":"response","actions":[{...},...]}
```

`"v"` 필드를 envelope 레벨에서 추가하면:
- 향후 wire format 자체를 변경해야 할 때 하위 호환 가능
- 서버가 클라이언트의 프로토콜 버전을 식별 가능
- `MessageCodec`에서 버전별 파싱 분기 가능

**지금 추가하지 않으면 나중에 wire format을 변경할 방법이 없다.**

### 11.5 선택: Handshake 프로토콜

```
현재:
  Client ──ws connect──► Server ──► 즉시 handleClient() ──► 메시지 수신 시작

제안:
  Client ──ws connect──► Server
  Client ──handshake──► Server  {"type":"handshake","appVersion":"2.0.0","protocolVersion":1}
  Server ──────────────► Client {"type":"handshake_ack","status":"ok"}
                                또는 {"type":"handshake_ack","status":"update_required","minVersion":"2.0.0"}
  (OK이면)
  Server ──► handleClient() ──► 메시지 수신 시작
```

앱 개발자가 Ktor 라우팅 레벨에서 직접 구현할 수도 있으므로 **선택 사항**이다.
다만 라이브러리에서 제공하면 일관된 패턴을 보장할 수 있다.

### 11.6 라이브러리 지원 항목 요약

```
                            없으면                있으면
                            ──────                ──────

필수 ┌ Lenient decode       강업 유예              모르는 Action을
     │ (decodeOrNull)       자체가 불가능.         안전하게 스킵.
     │                      크래시.                연결 유지.
     │
     ├ ignoreUnknownKeys    optional 필드          가장 안전한 변경이
     │                      추가도 크래시.         강업 없이 가능.
     │
     └ close() 메서드       강업 통보 후           라이브러리 레벨에서
                            연결 종료 불가.        깔끔한 연결 종료.

권장 ─ Wire protocol "v"    향후 wire format       미래의 프로토콜
                            변경 방법 없음.        변경이 안전해짐.

선택 ─ Handshake            앱 개발자가            일관된 버전 협상
                            직접 구현.             패턴 제공.
```

---

## 12. 앱 개발자가 처리하는 것

라이브러리가 위 인프라를 제공한 후, 실제 버전 정책과 배포 전략은 앱 개발자의 영역이다.

### 12.1 강업 플로우 (앱 개발자 구현)

```kotlin
// Server (Ktor)
val MIN_APP_VERSION = "2.0.0"

webSocket("/ws") {
    // ① 첫 메시지로 handshake 수신
    val handshake = incoming.receive()
    val clientVersion = parseVersion(handshake)

    // ② 버전 체크
    if (clientVersion < MIN_APP_VERSION) {
        send("update_required")
        close(CloseReason(4000, "Version too old"))
        return@webSocket
    }

    // ③ 정상 연결
    val connection = KtorWebSocketServerConnection(this)
        .typedJson<SharedAction>()
    session.handleClient(sessionId, connection)
}
```

```kotlin
// Client
connection.connect()
connection.send(Handshake(appVersion = "1.9.0"))

when (response) {
    "update_required" -> showForceUpdateDialog()
    "ok"              -> startListening()
}
```

### 12.2 배포 순서 가이드

```
Non-Breaking 변경 (Case 1, 2):

  ① server 배포 → 끝
  클라이언트 변경 불필요 또는 자연 호환


Breaking 변경 (Case 3, 4, 5):

  ① client v2 빌드 → 스토어 심사 제출
  ② client v2 스토어 승인 확인
  ③ server v2 배포 (구버전 수용 가능 상태)
  ④ 강업 유예 기간 (1~2주)
     - 서버: lenient decode로 구버전 액션 수신 가능
     - 서버: 새 Action은 구버전에게 보내도 스킵됨 (lenient decode)
  ⑤ MIN_APP_VERSION을 v2로 올림
  ⑥ 구버전 클라이언트 연결 시 강업 안내 + 연결 종료
```

### 12.3 Shared 모듈 변경 체크리스트 (앱 개발자용)

```
변경 전:
  □ 기존 Action/State의 필드를 제거하거나 이름을 바꾸는가?
    → YES: Breaking. 강업 필수. 배포 순서 준수.
    → NO: 다음 체크.

  □ 기존 Action/State에 필드를 추가하는가?
    → default value가 있는가?
      → YES: Non-Breaking. 서버만 배포. (Case 2)
      → NO: Breaking. 강업 필수.

  □ sealed interface에 새 Action을 추가하는가?
    → 새 Action이 서버→클라이언트 방향인가? (ClientSharedAction)
      → YES: 구버전 클라이언트가 받게 됨. Lenient decode 의존. (Case 3)
      → NO (ServerSharedAction): 구버전 클라이언트는 보내지 않으므로 안전.

  □ 기존 Action을 제거하는가?
    → Breaking. 강업 필수. (Case 5)
```

### 12.4 kotlinx.serialization 호환 규칙

```kotlin
// ✅ Non-Breaking: optional 필드 추가 (default value 포함)
@Serializable
data class SyncState(
    val state: ChatState,
    val serverVersion: Int = 1,    // 구버전이 보내지 않아도 default로 처리됨
)

// ✅ Non-Breaking: 새 action 추가 (lenient decode 전제)
sealed interface SharedChatAction {
    data class SyncState(...)          // 기존
    data class PlayerKicked(...)       // 신규 ← 구버전이 못 읽으면 스킵
}

// ❌ Breaking: 필드 제거
data class SyncState(
    // val state: ChatState  ← 제거하면 구버전 역직렬화 실패
    val newState: GameState
)

// ❌ Breaking: @SerialName 변경
@SerialName("sync_state_v2")  // 구버전이 "sync_state"로 기대하므로 실패
data class SyncState(...)
```

---

# Part 3: Use Case Feasibility (PR #95)

## 13. PR #95 아키텍처 변경 요약

PR #95는 서버측 아키텍처를 대폭 리팩토링하여 관심사를 분리했다.

### 13.1 변경 전 (Before PR #95)

```
MultiClientSingleClientSyncMiddleware
├── 세션 저장소 (MutableMap + Mutex)     ← 직접 관리
├── 액션 라우팅 (ClientSharedAction 가로채기)
├── 프로세서 실행
└── 수신 리스닝 (FlowHolderAction)
```

Middleware가 세션 저장, 액션 라우팅, broadcast를 모두 담당. Store와 세션이 강결합.

### 13.2 변경 후 (After PR #95)

```
RemoteServer<S, A>                       ← 신규 Facade
├── Store<S, A>                          (기존)
├── RemoteServerSession<A>               ← 순수 세션 레지스트리로 분리
│   ├── handleClient(sessionId, conn)
│   ├── broadcast(action)
│   ├── sendToClient(sessionId, action)
│   ├── sendPerSession(mapper)
│   ├── sessionIds()
│   └── sessionCount()
├── MultiClientSingleClientSyncMiddleware    ← 액션 라우팅만 담당
│   ├── InternalAddSession 처리
│   ├── ClientSharedAction → session.broadcast()
│   └── Processor 실행 + pass-through
└── serveJob                             ← 상태 변경 → broadcast 코루틴

팩토리 함수:
├── createSharedStateServer()                 ← 전체 broadcast (stateMapper)
└── createSessionAwareSharedStateServer()     ← 세션별 매핑 (sessionStateMapper)
```

### 13.3 핵심 변경 포인트

| 항목 | Before | After |
|------|--------|-------|
| 세션 저장 | `MultiClientSingleClientSyncMiddleware` 내부 `MutableMap` | `RemoteServerSession` (독립 클래스) |
| 세션 정리 | `InternalRemoveSession` 액션 dispatch | `handleClient()` 의 `finally` 블록 |
| Store + 세션 조합 | 앱 개발자가 직접 조립 | `createSharedStateServer()` 팩토리 함수 |
| 세션별 상태 전송 | 없음 | `createSessionAwareSharedStateServer()` + `sessionStateMapper` |
| 외부 API | Store 직접 사용 | `RemoteServer` facade (`handleClient`, `broadcast`, `sendToClient`) |

### 13.4 SessionAwareAction과 sessionStateMapper

PR #95는 세션별로 다른 상태를 전송하는 두 가지 메커니즘을 도입한다:

**1. `createSessionAwareSharedStateServer` + `sessionStateMapper`:**

```kotlin
val server = createSessionAwareSharedStateServer(
    initialState = PokerState(),
    reducer = pokerReducer,
    sessionStateMapper = { state, sessionId ->
        val hand = state.hands[sessionId] ?: return@createSessionAwareSharedStateServer null
        PokerAction.SyncPlayerView(hand = hand, communityCards = state.communityCards)
    },
    scope = scope,
)
```

Store의 `state` Flow를 collect하면서, 상태 변경마다 각 세션에 대해 mapper를 호출. null 반환 시 해당 세션 스킵.

**2. `SessionAwareAction<A>` 인터페이스 (설계됨):**

```kotlin
interface SessionAwareAction<A : Action> {
    fun forSession(sessionId: String): A?
}
```

액션 자체가 세션별 변환 로직을 내포. Middleware가 `ClientSharedAction` 대신 `SessionAwareAction`을 감지하면 `sendPerSession()`으로 처리.

---

## 14. 패턴별 Use Case 구현 가능성

설계 문서의 3가지 서버 아키텍처 패턴을 PR #95 API로 구현 가능한지 검토한다.

### 14.1 Pattern A: Central Store (글로벌 broadcast)

```
사용 사례: 채팅, 대시보드, 실시간 알림
특징: 모든 클라이언트가 동일한 상태를 공유
```

**구현:**

```kotlin
val server = createSharedStateServer(
    initialState = ChatState(),
    reducer = chatReducer,
    stateMapper = { state -> SyncState(state) },
    scope = applicationScope,
)

// WebSocket 핸들러
webSocket("/chat") {
    val conn = KtorWebSocketServerConnection(this)
        .typedJson<ChatAction>() as TypedServerConnection<ChatAction>
    server.handleClient(sessionId, conn)
}
```

**평가: ✅ 완전 구현 가능**
- `createSharedStateServer` + `stateMapper`로 직접 지원
- 상태 변경 → 모든 클라이언트에 동일한 액션 broadcast
- `handleClient()`가 세션 등록/해제/리스닝을 자동 처리

---

### 14.2 Pattern B: Room Store (방/채널별 독립 상태)

```
사용 사례: 게임 로비, 채팅 채널, 문서 협업
특징: Room별 독립 Store, 같은 Room 내에서만 상태 공유
```

**구현:**

```kotlin
class RoomManager {
    private val rooms = mutableMapOf<String, RemoteServer<GameState, GameAction>>()

    fun createRoom(roomId: String): RemoteServer<GameState, GameAction> {
        val server = createSharedStateServer(
            initialState = GameState(),
            reducer = gameReducer,
            stateMapper = { state -> SyncGameState(state) },
            scope = roomScope,
        )
        rooms[roomId] = server
        return server
    }

    suspend fun joinRoom(roomId: String, sessionId: String, conn: TypedServerConnection<GameAction>) {
        rooms[roomId]?.handleClient(sessionId, conn)
    }

    fun closeRoom(roomId: String) {
        rooms.remove(roomId)?.close()
    }
}
```

**평가: ✅ 완전 구현 가능**
- `RemoteServer` 인스턴스를 Room당 1개 생성
- `RemoteServerSession`이 Store와 분리되어 있으므로 독립적 생명주기 관리 용이
- 각 Room의 broadcast가 해당 Room 세션에만 도달
- RoomManager는 앱 개발자가 구현 (라이브러리 범위 밖)

---

### 14.3 Pattern C: Per-Client View (세션별 다른 상태 전송)

```
사용 사례: 포커 게임 (각 플레이어에게 자기 패만 전송), 권한별 다른 뷰
특징: 하나의 Store, 세션별로 다른 상태 projection
```

**구현:**

```kotlin
val server = createSessionAwareSharedStateServer(
    initialState = PokerState(),
    reducer = pokerReducer,
    sessionStateMapper = { state, sessionId ->
        val hand = state.hands[sessionId] ?: return@createSessionAwareSharedStateServer null
        PokerAction.SyncPlayerView(hand = hand, communityCards = state.communityCards)
    },
    scope = applicationScope,
)
```

**평가: ✅ 완전 구현 가능**
- `createSessionAwareSharedStateServer` + `sessionStateMapper`로 직접 지원
- 상태 변경마다 `sendPerSession()`이 각 세션에 mapper 결과를 전송
- mapper가 null 반환 시 해당 세션 스킵 (관전자 등)

---

### 14.4 Pattern D: Per-Client Store (세션별 독립 Store)

```
사용 사례: 개인화된 피드, 사용자별 독립 상태
특징: 세션마다 독립 Store 운영
```

**구현:**

```kotlin
// 연결마다 RemoteServer 인스턴스 생성
webSocket("/personal") {
    val conn = KtorWebSocketServerConnection(this)
        .typedJson<PersonalAction>() as TypedServerConnection<PersonalAction>

    val server = createSharedStateServer(
        initialState = PersonalState(userId = sessionId),
        reducer = personalReducer,
        stateMapper = { state -> SyncPersonalState(state) },
        scope = connectionScope,
    )
    server.handleClient(sessionId, conn)
    // 연결 종료 시 server.close()
}
```

**평가: ✅ 구현 가능하나 비효율적**
- 연결당 `RemoteServer` + `Store` 인스턴스 생성
- 기술적으로 작동하지만 오버헤드가 큼 (Store당 코루틴, middleware 파이프라인 등)
- 대안: 단일 Store + `sessionStateMapper`로 세션별 projection이 더 효율적 (Pattern C)

---

## 15. 복합 아키텍처 제약

### 15.1 문제: 한 클라이언트가 여러 Store를 구독

```
사용 사례:
  게임 클라이언트가 "로비" + "게임방" 두 개의 Store를 동시에 구독하고 싶은 경우.

  예: 게임 플레이 중에도 로비의 채팅/공지를 수신하고 싶다.
```

**현재 API의 제약:**

```kotlin
// RemoteServer.handleClient() 내부
suspend fun handleClient(sessionId: String, connection: TypedServerConnection<A>) {
    store.dispatch(InternalAddSession(sessionId, connection) as A)
    session.handleClient(sessionId, connection)  // ← awaitCancellation()으로 suspend
}

// RemoteServerSession.handleClient() 내부
suspend fun handleClient(sessionId: String, connection: TypedServerConnection<A>) {
    addSession(sessionId, connection)
    try {
        awaitCancellation()  // ← 여기서 영원히 대기. 이 연결은 다른 곳에서 사용 불가.
    } finally {
        removeSession(sessionId)
    }
}
```

`handleClient()`가 `awaitCancellation()`으로 suspend하므로, **하나의 connection은 하나의 RemoteServer에만 바인딩**된다.

```
제약:
  1 WebSocket connection = 1 RemoteServer 바인딩

  client ── ws ──► server
                     └── RemoteServer("lobby")  에 바인딩
                         handleClient() 가 awaitCancellation()
                         이 connection으로 다른 RemoteServer 접근 불가
```

### 15.2 복합 아키텍처 다이어그램

```
원하는 구조:

  Client
    ├── ws connection ──► Lobby (RemoteServer)    ← 로비 채팅, 공지 수신
    │                     └── Store<LobbyState>
    │
    └── (같은 connection) ──► Game (RemoteServer)  ← 게임 상태 수신
                              └── Store<GameState>

  ✗ 현재 API로는 불가능 (1 connection = 1 RemoteServer)


실현 가능한 구조:

  Client
    ├── ws connection 1 ──► Lobby (RemoteServer)   ✓
    │                       └── Store<LobbyState>
    │
    └── ws connection 2 ──► Game (RemoteServer)    ✓
                            └── Store<GameState>

  ✓ WebSocket 연결을 2개 열면 가능
```

### 15.3 우회 방안

**방안 A: 다중 WebSocket 연결 (가장 단순)**

```kotlin
// Client
val lobbyConnection = KtorWebSocketClientConnection("ws://server/lobby")
val gameConnection = KtorWebSocketClientConnection("ws://server/game/123")

val lobbyStore = createStore(middlewares = listOf(SyncMiddleware(lobbyConnection)))
val gameStore = createStore(middlewares = listOf(SyncMiddleware(gameConnection)))
```

- 장점: 현재 API 변경 없이 즉시 가능
- 단점: 클라이언트당 WebSocket 수 증가, 서버 리소스 사용 증가

**방안 B: Composite Store (앱 레벨 패턴)**

```kotlin
// 하나의 Store에서 Lobby + Game 상태를 모두 관리
data class CompositeState(
    val lobby: LobbyState,
    val game: GameState?,
) : State

val server = createSessionAwareSharedStateServer(
    initialState = CompositeState(lobby = LobbyState(), game = null),
    reducer = compositeReducer,
    sessionStateMapper = { state, sessionId -> /* 세션별 projection */ },
    scope = scope,
)
```

- 장점: 단일 WebSocket, 단일 Store
- 단점: 관심사가 섞임. Reducer/Action이 복잡해짐.

**방안 C: 라이브러리 레벨 Multiplexing (향후)**

```
단일 WebSocket 위에 논리적 채널을 다중화하는 프로토콜.

  Client ── ws ──► Server
                     ├── channel "lobby" ──► Lobby RemoteServer
                     └── channel "game"  ──► Game RemoteServer

  wire format:
    {"channel":"lobby","type":"action","payload":{...}}
    {"channel":"game","type":"action","payload":{...}}
```

- 장점: 단일 WebSocket으로 여러 Store 구독
- 단점: wire protocol 변경 필요, `MessageCodec` 확장, 클라이언트 미들웨어 변경
- 시기: Part 1 Phase 1 (Room 파티셔닝) 도입 시 함께 고려

---

## 16. Use Case 종합 평가

### 16.1 구현 가능성 매트릭스

| Pattern | Use Case | PR #95 API | 구현 복잡도 | 비고 |
|---------|----------|-----------|------------|------|
| **A. Central Store** | 채팅, 대시보드 | ✅ `createSharedStateServer` | 낮음 | 직접 지원 |
| **B. Room Store** | 게임 방, 채널 | ✅ `RemoteServer` × N | 중간 | RoomManager 앱 구현 |
| **C. Per-Client View** | 포커, 권한별 뷰 | ✅ `createSessionAwareSharedStateServer` | 낮음 | 직접 지원 |
| **D. Per-Client Store** | 개인화 피드 | ✅ `RemoteServer` per conn | 중간 | 비효율적, Pattern C 권장 |
| **E. Combined (멀티 Store 구독)** | 로비 + 게임 동시 | ⚠️ 제한적 | 높음 | 다중 WS 또는 Composite Store |

### 16.2 잘된 점 (PR #95)

```
✓ RemoteServerSession 분리
  → Room 패턴 구현 시 독립적 세션 레지스트리 활용 가능

✓ RemoteServer facade
  → 앱 개발자에게 깔끔한 API 제공 (handleClient, broadcast, sendToClient)

✓ createSessionAwareSharedStateServer
  → Per-Client View 패턴을 직접 지원. 포커, 마피아 게임 등 핵심 사용 사례 커버.

✓ InternalRemoveSession 제거
  → finally 블록 기반 정리로 세션 누수 위험 감소

✓ Middleware가 세션 저장을 몰라도 됨
  → MultiClientSingleClientSyncMiddleware의 단일 책임 원칙 달성
```

### 16.3 남은 Gap

```
1. Combined Architecture (Multiplexing)
   ─ 1 connection = 1 RemoteServer 제약
   ─ 당장은 다중 WebSocket으로 우회 가능
   ─ Phase 1 (Room 파티셔닝) 설계 시 채널 멀티플렉싱 검토 필요

2. 버전 인프라 (Part 2 참조)
   ─ lenient decode (decodeOrNull)
   ─ ignoreUnknownKeys = true
   ─ close() 메서드
   ─ wire protocol 버전 필드
   → PR #95 범위 밖이지만, 프로덕션 배포 전 필수

3. Backpressure / Slow Client 처리
   ─ broadcast()가 여전히 순차 전송
   ─ slow client가 다른 클라이언트 지연 유발 가능
   ─ 병렬 broadcast 또는 per-client 버퍼 미구현

4. 서버 → 클라이언트 초기 상태 전송
   ─ handleClient() 후 현재 상태를 새 클라이언트에게 즉시 전송하는
     표준 패턴이 없음. 앱 개발자가 직접 구현해야 함.
```

### 16.4 결론

PR #95는 서버측 아키텍처의 관심사 분리를 성공적으로 달성했으며, 설계 문서에서 정의한 **개별 패턴(A, B, C, D) 모두 구현 가능**하다. 가장 큰 제약인 **복합 아키텍처(Pattern E)**는 다중 WebSocket 연결로 우회 가능하며, 향후 Room 파티셔닝(Phase 1) 도입 시 채널 멀티플렉싱으로 근본적으로 해결할 수 있다.

우선순위:
1. **즉시**: Part 2의 버전 인프라 (lenient decode, ignoreUnknownKeys, close())
2. **Phase 1과 함께**: Room 파티셔닝 + 멀티플렉싱 프로토콜 설계
3. **필요 시**: 병렬 broadcast, backpressure 처리

---

# Part 4: Channel Multiplexing

## 17. 멀티플렉싱 개요

### 17.1 문제 정의

Part 3 §15에서 식별한 **1 connection = 1 RemoteServer** 제약의 근본적 해결책으로, 단일 WebSocket 연결 위에 논리적 채널을 다중화하는 프로토콜을 설계한다.

```
현재:
  Client ── ws 1 ──► RemoteServer("lobby")
  Client ── ws 2 ──► RemoteServer("game")
  → WebSocket 연결 수 = 구독하는 Store 수

멀티플렉싱 후:
  Client ── ws 1 ──► MultiplexedConnection
                       ├── channel "lobby" ──► RemoteServer("lobby")
                       └── channel "game"  ──► RemoteServer("game")
  → WebSocket 연결 수 = 1 (고정)
```

### 17.2 기대 효과

| 항목 | Before (다중 WS) | After (멀티플렉싱) |
|------|-----------------|-------------------|
| 서버 리소스 | 채널당 1 TCP 연결 + 1 TLS 핸드셰이크 | 1 TCP 연결 공유 |
| 클라이언트 복잡도 | Store × N, Middleware × N, Connection × N | Store × N, Middleware × N, Connection × 1 |
| 모바일 배터리/대역폭 | 연결 수에 비례 | 최소화 |
| 로드밸런서 부하 | 연결 수에 비례 | 최소화 |
| Sticky session | 연결마다 별도 라우팅 필요 | 한 번만 라우팅 |

---

## 18. 현재 코드베이스의 1:1 가정 분석

코드베이스의 모든 레이어가 **1 connection = 1 channel**을 전제로 설계되어 있다. 멀티플렉싱 구현 시 각 레이어의 영향 범위를 분석한다.

### 18.1 레이어별 1:1 가정

```
┌─────────────────────────────────────────────────────────────────┐
│  Layer 1: Wire Protocol (MessageCodec)                          │
│                                                                 │
│  {"type":"action","payload":{...}}     ← 채널 정보 없음         │
│  {"type":"response","actions":[...]}   ← 채널 정보 없음         │
│                                                                 │
│  영향: MessageCodec 인터페이스 확장 필요                         │
├─────────────────────────────────────────────────────────────────┤
│  Layer 2: Transport (ServerConnection / ClientConnection)       │
│                                                                 │
│  interface ServerConnection {                                   │
│      val incoming: Flow<String>    ← 단일 스트림, 채널 구분 없음 │
│      suspend fun send(String)      ← 단일 목적지                │
│  }                                                              │
│                                                                 │
│  영향: 변경 불필요 (하위 레이어, 그대로 사용)                     │
├─────────────────────────────────────────────────────────────────┤
│  Layer 3: Typed Connection (TypedServerConnection)              │
│                                                                 │
│  interface TypedServerConnection<A> {                           │
│      val incoming: Flow<A>         ← 단일 타입, 단일 채널       │
│      suspend fun send(A)                                        │
│  }                                                              │
│                                                                 │
│  영향: 변경 불필요 (가상 구현체로 대체)                           │
├─────────────────────────────────────────────────────────────────┤
│  Layer 4: Middleware (MultiClientSingleClientSyncMiddleware)         │
│                                                                 │
│  InternalAddSession(sessionId, connection)                      │
│  → 1 session = 1 TypedServerConnection                         │
│                                                                 │
│  영향: 변경 불필요 (가상 connection을 받으므로)                   │
├─────────────────────────────────────────────────────────────────┤
│  Layer 5: Session (RemoteServerSession)                         │
│                                                                 │
│  sessions: Map<String, TypedServerConnection>                   │
│  handleClient() → awaitCancellation()                           │
│                                                                 │
│  영향: 변경 불필요 (가상 connection을 받으므로)                   │
├─────────────────────────────────────────────────────────────────┤
│  Layer 6: Facade (RemoteServer)                                 │
│                                                                 │
│  handleClient(sessionId, connection)                            │
│  → Store + Session 조합                                         │
│                                                                 │
│  영향: 변경 불필요 (가상 connection을 받으므로)                   │
└─────────────────────────────────────────────────────────────────┘
```

### 18.2 핵심 인사이트

```
변경 필요:
  ① MessageCodec (wire envelope에 채널 필드 추가)
  ② 신규 Multiplexing 레이어 (demux/mux)

변경 불필요:
  ③ ServerConnection / ClientConnection     ← raw transport, 그대로 사용
  ④ TypedServerConnection                   ← 인터페이스 유지, 구현체만 교체
  ⑤ MultiClientSingleClientSyncMiddleware       ← 가상 connection이 투명하게 동작
  ⑥ RemoteServerSession                     ← 가상 connection이 투명하게 동작
  ⑦ RemoteServer                            ← 가상 connection이 투명하게 동작
```

`TypedServerConnection` 추상화가 이미 전송 계층을 캡슐화하고 있기 때문에, **구현체 레벨에서 멀티플렉싱을 삽입하면 상위 레이어(④~⑦)는 변경 없이 동작**한다.

---

## 19. 구현 접근 방식 비교

### 19.1 4가지 접근 방식

| 접근 | 설명 | 변경 범위 | 복잡도 |
|------|------|----------|--------|
| **A. Wire Envelope 확장만** | MessageCodec에 채널 필드 추가 | MessageCodec | 낮음 (불충분) |
| **B. Multiplexing Wrapper** | Transport 위에 demux/mux 레이어 추가 | 신규 레이어 + MessageCodec | 중간 |
| **C. Middleware 통합** | MultiClientSingleClientSyncMiddleware 내에서 채널 라우팅 | Middleware 대폭 수정 | 높음 |
| **D. 채널별 독립 Store** | 채널마다 RemoteServer, 물리 WS 공유 | B와 실질 동일 | 높음 |

### 19.2 각 접근 방식의 문제점

**A. Wire Envelope 확장만:**
```
MessageCodec에 "ch" 필드를 추가하면 wire level에서는 채널 구분이 되지만,
TypedServerConnection.incoming이 여전히 단일 Flow<A>이므로
상위 레이어에서 채널별 분리를 할 수 없다.

→ 단독으로는 불충분. B의 전제 조건으로만 유효.
```

**C. Middleware 통합:**
```
MultiClientSingleClientSyncMiddleware 내부에서 채널별 라우팅을 처리하면:
- InternalAddSession에 채널 정보 추가 필요
- ClientSharedAction broadcast 시 채널별 필터링 필요
- Processor에도 채널 컨텍스트 전달 필요
- 단일 책임 원칙 위반 (PR #95에서 분리한 관심사를 다시 합침)

→ 복잡하고 기존 설계 의도에 반함.
```

**D. 채널별 독립 Store:**
```
물리 WS를 공유하면서 채널별 RemoteServer를 만드는 것은
결국 B (Multiplexing Wrapper)를 구현한 위에 RemoteServer를 올리는 것.

→ B를 구현하면 D는 자동으로 가능해짐. 별도 접근 불필요.
```

### 19.3 결론: 접근 B (Multiplexing Wrapper)

```
A는 B의 전제 조건 (wire 변경)
B가 핵심 구현 (demux/mux 레이어)
C는 과도하고 설계 위반
D는 B를 구현하면 자동 해결

→ B를 구현하면 A + D가 모두 해결됨
→ C는 불필요
```

---

## 20. 권장 설계: Multiplexing Wrapper

### 20.1 아키텍처

```
┌─────────────────────────────────────────────────────────────────┐
│  Client                                                         │
│                                                                 │
│  ws ──────────────────────────────────────────────────────────  │
│                                                                 │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  Server                                                         │
│                                                                 │
│  KtorWebSocketServerConnection (기존, 변경 없음)                │
│           │                                                     │
│           ▼                                                     │
│  ┌───────────────────────────────────────────────────────┐      │
│  │  MultiplexedServerConnection (신규)                    │      │
│  │                                                       │      │
│  │  raw ServerConnection                                 │      │
│  │       │                                               │      │
│  │       ▼                                               │      │
│  │  incoming 메시지 수신                                  │      │
│  │       │                                               │      │
│  │  ChannelAwareMessageCodec.decode()                    │      │
│  │       │                                               │      │
│  │  ┌────┴────────────────────────────┐                  │      │
│  │  │  channel = "lobby"              │  channel = "game"│      │
│  │  │         │                       │       │          │      │
│  │  │         ▼                       │       ▼          │      │
│  │  │  VirtualTypedServerConn         │  VirtualTyped... │      │
│  │  │  (implements TypedServerConn)   │  (implements...) │      │
│  │  └─────────┬───────────────────────┘───────┬──────────┘      │
│  └────────────┼───────────────────────────────┼──────────┘      │
│               │                               │                 │
│               ▼                               ▼                 │
│  RemoteServer("lobby")           RemoteServer("game")           │
│  └─ handleClient(id, vConn)      └─ handleClient(id, vConn)    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 20.2 핵심 컴포넌트

**ChannelAwareMessageCodec:**

```kotlin
// 기존 MessageCodec 확장
interface ChannelAwareMessageCodec : MessageCodec {
    fun encodeActionMessage(channelId: String, actionJson: String): String
    fun decodeChannelAndAction(raw: String): ChannelMessage
    fun encodeChannelResponse(channelId: String, actions: List<String>): String
}

data class ChannelMessage(
    val channelId: String,
    val payload: String,    // 기존 action JSON
)
```

**MultiplexedServerConnection:**

```kotlin
class MultiplexedServerConnection<A : Action>(
    private val raw: ServerConnection,
    private val codec: ChannelAwareMessageCodec,
    private val actionCodec: ActionCodec<A>,
) {
    // 채널별 가상 연결을 생성
    fun channelConnection(channelId: String): TypedServerConnection<A> {
        return VirtualTypedServerConnection(channelId)
    }

    // 내부: raw incoming을 채널별로 분배
    private val channelFlows = mutableMapOf<String, MutableSharedFlow<A>>()

    init {
        // raw.incoming을 수신하여 채널별로 분배하는 코루틴
        scope.launch {
            raw.incoming.collect { rawMessage ->
                val decoded = codec.decodeChannelAndAction(rawMessage)
                val action = actionCodec.decode(decoded.payload)
                channelFlows[decoded.channelId]?.emit(action)
            }
        }
    }

    private inner class VirtualTypedServerConnection(
        private val channelId: String,
    ) : TypedServerConnection<A> {

        override val incoming: Flow<A>
            get() = channelFlows.getOrPut(channelId) {
                MutableSharedFlow()
            }

        override suspend fun send(action: A) {
            val actionJson = actionCodec.encode(action)
            val wire = codec.encodeChannelResponse(channelId, listOf(actionJson))
            raw.send(wire)
        }
    }
}
```

**서버측 사용 예시:**

```kotlin
// Ktor 라우팅
webSocket("/multiplexed") {
    val raw = KtorWebSocketServerConnection(this)
    val mux = MultiplexedServerConnection<SharedAction>(
        raw = raw,
        codec = JsonChannelAwareMessageCodec(),
        actionCodec = serializableActionCodec,
    )

    // 각 채널을 해당 RemoteServer에 연결
    coroutineScope {
        launch {
            val lobbyConn = mux.channelConnection("lobby")
            lobbyServer.handleClient(sessionId, lobbyConn)
        }
        launch {
            val gameConn = mux.channelConnection("game")
            gameServer.handleClient(sessionId, gameConn)
        }
    }
}
```

### 20.3 기존 API와의 호환성

```
기존 사용 방식 (멀티플렉싱 없이):
  val conn = KtorWebSocketServerConnection(this)
      .typedJson<SharedAction>()
  server.handleClient(sessionId, conn)
  → 그대로 동작. 변경 불필요.

멀티플렉싱 사용 방식 (opt-in):
  val raw = KtorWebSocketServerConnection(this)
  val mux = MultiplexedServerConnection(raw, codec, actionCodec)
  server.handleClient(sessionId, mux.channelConnection("ch1"))
  → 선택적으로 사용. 기존 코드 영향 없음.
```

### 20.4 TypedServerConnection 투명성

`VirtualTypedServerConnection`은 `TypedServerConnection<A>` 인터페이스를 구현하므로, 상위 레이어에서는 물리적 WebSocket 연결인지 가상 채널 연결인지 구분할 수 없다.

```
RemoteServer.handleClient(sessionId, connection)
                                        │
                          ┌─────────────┴──────────────┐
                          │                            │
            TypedServerConnection           TypedServerConnection
            (물리: WebSocket 직접)          (가상: VirtualTypedServerConn)
                          │                            │
                     기존 방식                    멀티플렉싱 방식
                          │                            │
                    동일하게 동작                 동일하게 동작
```

---

## 21. 클라이언트측 설계

### 21.1 현재 클라이언트 구조

```kotlin
// 현재: 1 connection = 1 SyncMiddleware = 1 Store
val connection = KtorWebSocketClientConnection("ws://server/chat")
val middleware = SyncMiddleware<ChatState, ChatAction>(connection)
val store = createStore(middlewares = listOf(middleware), ...)
```

### 21.2 멀티플렉싱 클라이언트

```kotlin
// 멀티플렉싱: 1 connection, 채널별 Store
val raw = KtorWebSocketClientConnection("ws://server/multiplexed")
val mux = MultiplexedClientConnection<SharedAction>(
    raw = raw,
    codec = JsonChannelAwareMessageCodec(),
    actionCodec = serializableActionCodec,
)

// 채널별 독립 Store
val lobbyConn = mux.channelConnection("lobby")
val lobbyMiddleware = SyncMiddleware<LobbyState, LobbyAction>(lobbyConn)
val lobbyStore = createStore(middlewares = listOf(lobbyMiddleware), ...)

val gameConn = mux.channelConnection("game")
val gameMiddleware = SyncMiddleware<GameState, GameAction>(gameConn)
val gameStore = createStore(middlewares = listOf(gameMiddleware), ...)
```

### 21.3 MultiplexedClientConnection

```kotlin
class MultiplexedClientConnection<A : Action>(
    private val raw: ClientConnection,
    private val codec: ChannelAwareMessageCodec,
    private val actionCodec: ActionCodec<A>,
) {
    fun channelConnection(channelId: String): TypedClientConnection<A> {
        return VirtualTypedClientConnection(channelId)
    }

    private inner class VirtualTypedClientConnection(
        private val channelId: String,
    ) : TypedClientConnection<A> {

        override val connectionState: StateFlow<ConnectionState>
            get() = raw.connectionState

        override val incoming: Flow<A> = raw.incoming
            .map { codec.decodeChannelAndAction(it) }
            .filter { it.channelId == channelId }
            .map { actionCodec.decode(it.payload) }

        override suspend fun send(action: A) {
            val actionJson = actionCodec.encode(action)
            val wire = codec.encodeActionMessage(channelId, actionJson)
            raw.send(wire)
        }

        override suspend fun connect() = raw.connect()
        override suspend fun disconnect() = raw.disconnect()
    }
}
```

### 21.4 클라이언트측 고려사항

```
1. 연결 생명주기 공유
   ─ connect()/disconnect()는 물리 연결 기준
   ─ 한 채널만 disconnect하고 싶으면? → 채널 구독/해제 프로토콜 필요
   ─ 초기 버전에서는 물리 연결과 모든 채널이 함께 connect/disconnect

2. connectionState 공유
   ─ 모든 가상 연결이 같은 connectionState를 참조
   ─ 물리 연결이 끊기면 모든 채널이 동시에 Disconnected

3. Action 타입 분리
   ─ 채널마다 다른 Action sealed interface를 쓰는 경우
   ─ MultiplexedConnection의 제네릭 타입 A가 모든 채널의 합집합이어야 함
   ─ 또는 채널별로 별도의 ActionCodec을 사용
```

---

## 22. Wire Protocol 확장

### 22.1 현재 wire format

```json
Client → Server: {"type":"action","payload":{"type":"SendMessage","text":"hello"}}
Server → Client: {"type":"response","actions":[{"type":"SyncState","state":{...}}]}
```

### 22.2 멀티플렉싱 wire format

```json
Client → Server: {"v":1,"ch":"lobby","type":"action","payload":{"type":"SendMessage","text":"hello"}}
Server → Client: {"v":1,"ch":"lobby","type":"response","actions":[{"type":"SyncState","state":{...}}]}
```

| 필드 | 설명 | 필수 |
|------|------|------|
| `"v"` | wire protocol 버전 (Part 2 §11.4) | 권장 |
| `"ch"` | 채널 ID | 멀티플렉싱 시 필수 |
| `"type"` | 메시지 타입 (기존) | 필수 |
| `"payload"` / `"actions"` | 액션 데이터 (기존) | 필수 |

### 22.3 하위 호환성

```
규칙: "ch" 필드가 없는 메시지 = 기본 채널 (default channel)

기존 클라이언트 (멀티플렉싱 미지원):
  → {"type":"action","payload":{...}}
  → 서버가 수신 시 ch = "__default__" 로 처리
  → 단일 RemoteServer에 라우팅

멀티플렉싱 클라이언트:
  → {"ch":"lobby","type":"action","payload":{...}}
  → 서버가 수신 시 ch = "lobby" 로 라우팅

→ 기존 코드와 완전 하위 호환
```

### 22.4 채널 제어 메시지 (향후)

```json
채널 구독:   {"type":"subscribe","ch":"game"}
채널 해제:   {"type":"unsubscribe","ch":"lobby"}
채널 목록:   {"type":"channels"}  →  {"type":"channels","list":["lobby","game"]}
```

초기 버전에서는 서버측에서 채널을 할당하고 (Ktor 라우팅 시점에 결정), 향후 클라이언트가 동적으로 채널을 구독/해제하는 프로토콜로 확장 가능.

---

## 23. 멀티플렉싱 도입 전략

### 23.1 Part 2와의 통합

Part 2 §11.4에서 제안한 wire protocol 버전 필드(`"v"`)와 멀티플렉싱 채널 필드(`"ch"`)를 함께 도입하면 wire format 변경을 한 번만 하면 된다.

```json
Before: {"type":"action","payload":{...}}
After:  {"v":1,"ch":"lobby","type":"action","payload":{...}}
                                             ▲
        ├─ Part 2 ─┤  ├─ Part 4 ─┤          │ 기존 필드 유지
```

### 23.2 Phase 1 (Room 파티셔닝)과의 통합

Part 1 Phase 1에서 `RoomManager`를 도입할 때, 멀티플렉싱을 함께 구현하면:

```
Phase 1 Without Multiplexing:
  Client ── ws 1 ──► RoomManager ──► Room "lobby"
  Client ── ws 2 ──► RoomManager ──► Room "game"

Phase 1 With Multiplexing:
  Client ── ws 1 ──► MultiplexedConnection
                       ├── ch "lobby" ──► Room "lobby"
                       └── ch "game"  ──► Room "game"
```

Room 파티셔닝의 `RoomManager`가 채널 라우팅과 자연스럽게 매핑된다.

### 23.3 구현 순서

```
Step 1: Wire Protocol 확장
  ─ ChannelAwareMessageCodec 구현
  ─ "v" + "ch" 필드 추가
  ─ 하위 호환 유지 ("ch" 없으면 기본 채널)
  ─ 관련 파일: MessageCodec, JsonMessageCodec

Step 2: 서버측 Multiplexing
  ─ MultiplexedServerConnection 구현
  ─ VirtualTypedServerConnection 구현
  ─ 기존 TypedServerConnection 인터페이스 유지
  ─ 관련 파일: 신규 파일 2개

Step 3: 클라이언트측 Multiplexing
  ─ MultiplexedClientConnection 구현
  ─ VirtualTypedClientConnection 구현
  ─ 기존 TypedClientConnection 인터페이스 유지
  ─ 관련 파일: 신규 파일 2개

Step 4: 통합 테스트
  ─ 단일 WebSocket + 다중 채널 E2E 테스트
  ─ 하위 호환 테스트 (멀티플렉싱 미사용 시)
  ─ 채널 생성/해제 테스트

Step 5 (향후): 동적 채널 구독
  ─ subscribe/unsubscribe 프로토콜
  ─ 클라이언트 주도 채널 관리
```

### 23.4 모듈 배치

```
flowdux-remote-core
  └── (변경 없음)

flowdux-remote-server
  └── MultiplexedServerConnection       ← 신규
  └── VirtualTypedServerConnection      ← 신규

flowdux-remote-client
  └── MultiplexedClientConnection       ← 신규
  └── VirtualTypedClientConnection      ← 신규

flowdux-remote-serialization
  └── ChannelAwareMessageCodec          ← 신규
  └── JsonChannelAwareMessageCodec      ← 신규 (JsonMessageCodec 확장)

flowdux-remote-ktor
  └── (변경 없음. KtorWebSocket*Connection은 raw transport로 그대로 사용)
```

### 23.5 영향 범위 요약

```
변경:
  ┌─ MessageCodec 계열          wire envelope에 "v" + "ch" 추가
  └─ 신규 파일 4~6개             Multiplexed*Connection, Virtual*Connection

변경 없음:
  ├─ ServerConnection            raw transport (그대로)
  ├─ ClientConnection            raw transport (그대로)
  ├─ TypedServerConnection       인터페이스 유지 (구현체만 추가)
  ├─ TypedClientConnection       인터페이스 유지 (구현체만 추가)
  ├─ MultiClientSingleClientSyncMiddleware   가상 connection에 투명
  ├─ SyncMiddleware              가상 connection에 투명
  ├─ RemoteServerSession                 가상 connection에 투명
  ├─ RemoteServer                        가상 connection에 투명
  ├─ KtorWebSocket*Connection            raw transport (그대로)
  └─ 기존 사용 코드                       opt-in이므로 영향 없음
```

### 23.6 전체 로드맵 업데이트

Part 1 §6.2의 로드맵에 멀티플렉싱을 통합한다:

```
Phase 1                     Phase 1.5                  Phase 2
Room 파티셔닝               + Multiplexing              + Pub/Sub Backplane
──────────────              ──────────────              ──────────────────

RoomManager 추가            Wire protocol "v"+"ch"      다수 서버
Room별 독립 Store           MultiplexedConnection       Room별 primary/relay
                            단일 WS → 다중 Room          Backplane 인터페이스

                                Phase 3
                                + Gateway 분리
                                ──────────────

                                Gateway + Logic
                                연결/로직 완전 분리
                                GatewayConnection 구현
```

