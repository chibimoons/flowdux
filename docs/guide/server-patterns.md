# Server Architecture Patterns

FlowDux Remote는 다양한 실시간 앱 요구사항에 맞는 서버 아키텍처 패턴을 제공합니다.

## Pattern Overview

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Server Architecture Patterns                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1:1:1 Single Client        1:1:N Shared State       1:N:M Room              │
│  ┌──────┐   ┌───────┐      ┌──────┐   ┌───────┐     ┌──────┐   ┌───────┐   │
│  │Server│──▶│Client │      │Server│──▶│Client1│     │Server│──▶│ Room1 │──▶C│
│  │Store │   └───────┘      │Store │──▶│Client2│     │      │   └───────┘   │
│  └──────┘                  └──────┘──▶│Client3│     │      │──▶│ Room2 │──▶C│
│                                       └───────┘     └──────┘   └───────┘   │
│                                                                              │
│  1:N:N Per-Client           Hybrid (Room + Per-Client)                       │
│  ┌──────┐   ┌───────┐      ┌──────┐   ┌───────┐   ┌─────────┐              │
│  │Server│──▶│Store1 │──▶C1 │ Room │──▶│Public │──▶│All      │              │
│  │      │──▶│Store2 │──▶C2 │ Store│   │ State │   │Clients  │              │
│  │      │──▶│Store3 │──▶C3 │      │──▶│Private│──▶│Per-Client│             │
│  └──────┘   └───────┘      └──────┘   │ Store │   └─────────┘              │
│                                       └───────┘                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Pattern Comparison

| 패턴 | 비율 | Store 수 | 상태 공유 | 대표 Use Case |
|------|------|---------|----------|---------------|
| [Single Client](./pattern-single-client.md) | 1:1:1 | 클라이언트당 1개 | 없음 | 개인 대시보드, 단일 사용자 도구 |
| [Shared State](./pattern-shared-state.md) | 1:1:N | 서버에 1개 | 전체 공유 | 채팅방, 실시간 대시보드 |
| [Room](./pattern-room.md) | 1:N:M | 방당 1개 | 방 내 공유 | 다중 채팅방, 게임 로비 |
| [Per-Client](./pattern-per-client.md) | 1:N:N | 클라이언트당 1개 | 개별 | 포커(비공개 패), 개인화 뷰 |

## Pattern Selection Guide

### 질문 1: 클라이언트 간 상태 공유가 필요한가요?

```
                    상태 공유 필요?
                         │
            ┌────────────┴────────────┐
            │                         │
           아니오                      예
            │                         │
            ▼                         ▼
     ┌─────────────┐          그룹으로 분리 필요?
     │Single Client│                  │
     │   (1:1:1)   │       ┌──────────┴──────────┐
     └─────────────┘       │                     │
                         아니오                   예
                           │                     │
                           ▼                     ▼
                    ┌─────────────┐       ┌─────────────┐
                    │Shared State │       │    Room     │
                    │   (1:1:N)   │       │   (1:N:M)   │
                    └─────────────┘       └─────────────┘
```

### 질문 2: 클라이언트별 비공개 정보가 필요한가요?

```
                  비공개 정보 필요?
                         │
            ┌────────────┴────────────┐
            │                         │
           아니오                      예
            │                         │
            ▼                         ▼
     위 결과 사용              ┌─────────────┐
                              │ Per-Client  │
                              │   (1:N:N)   │
                              └─────────────┘
                                    │
                              공개 정보도 필요?
                                    │
                           ┌────────┴────────┐
                          아니오              예
                           │                 │
                           ▼                 ▼
                    Per-Client만      Room + Per-Client
                                        (Hybrid)
```

## Use Case → Pattern Mapping

### 협업 도구 (Collaborative Tools)

| Use Case | 패턴 | 이유 |
|----------|------|------|
| 실시간 문서 편집 (Google Docs) | Shared State | 모든 참가자가 같은 문서 상태 공유 |
| 화이트보드 | Shared State | 동일한 캔버스 상태 공유 |
| 프로젝트 보드 (Trello) | Room | 프로젝트별 독립된 보드 |
| 코드 리뷰 도구 | Shared State | PR당 하나의 공유 상태 |

### 채팅/메시징 (Chat & Messaging)

| Use Case | 패턴 | 이유 |
|----------|------|------|
| 단일 채팅방 | Shared State | 모든 메시지 전체 공유 |
| 다중 채팅방 (Slack) | Room | 채널별 독립된 상태 |
| 1:1 DM | Single Client 또는 Room | 두 사용자만의 독립 공간 |
| 그룹 DM | Room | 참가자 그룹별 독립 |

### 게임 (Games)

| Use Case | 패턴 | 이유 |
|----------|------|------|
| 틱택토, 체스 (공개 게임) | Shared State 또는 Room | 모든 정보 공개 |
| 포커, 마피아 (비공개 정보) | Room + Per-Client | 공개 + 비공개 정보 분리 |
| 게임 로비 | Room | 방별 독립 |
| 멀티플레이어 매치 | Room | 매치별 독립 |

### 대시보드/모니터링 (Dashboards)

| Use Case | 패턴 | 이유 |
|----------|------|------|
| 팀 대시보드 | Shared State | 모든 팀원 동일 뷰 |
| 개인 대시보드 | Single Client | 사용자별 독립 |
| 권한별 대시보드 | Per-Client | 역할별 다른 데이터 |
| 실시간 모니터링 | Shared State | 동일 메트릭 공유 |

### 교육/이벤트 (Education & Events)

| Use Case | 패턴 | 이유 |
|----------|------|------|
| 라이브 강의 | Shared State | 모든 학생 동일 콘텐츠 |
| 온라인 시험 | Per-Client | 개인별 문제/답안 |
| 퀴즈 쇼 | Room + Per-Client | 공개 점수 + 개인 답안 |
| 라이브 투표 | Shared State | 실시간 집계 공유 |

### 금융/거래 (Finance & Trading)

| Use Case | 패턴 | 이유 |
|----------|------|------|
| 실시간 시세 | Shared State | 동일 시세 전체 공유 |
| 포트폴리오 뷰 | Per-Client | 개인 자산 정보 |
| 경매 | Room + Per-Client | 공개 입찰 + 개인 예산 |
| 주문 시스템 | Per-Client | 개인 주문 내역 |

## API Quick Reference

### Single Client (1:1:1)
```kotlin
// createSingleClientServer: 간편한 팩토리 함수 사용
webSocket("/ws") {
    val server = createSingleClientServer(
        initialState = UserState(),
        reducer = userReducer,
        connection = connection,
    )
    server.serve { SharedAction.SyncState(it) }
}
```

### Shared State (1:1:N)
```kotlin
val server = createSharedStateServer(
    initialState = ChatState(),
    reducer = chatReducer,
    stateMapper = { SharedAction.SyncState(it) },
)

webSocket("/chat") {
    server.handleClient(sessionId, connection)
}
```

### Room (1:N:M)
```kotlin
// SharedStateServer 방: 방 내 상태 공유
val chatRoomServer = createSharedStateRoomServer(
    initialStateFactory = { roomId -> ChatState(roomId = roomId) },
    reducer = chatReducer,
    stateMapper = { SharedAction.SyncState(it) },
    scope = applicationScope,
)

// PerClientServer 방: 방 내 클라이언트별 비공개 상태
val pokerLobby = createPerClientRoomServer(
    initialStateFactory = { tableId, playerId -> PlayerState(tableId, playerId) },
    reducer = playerReducer,
    stateMapper = { SyncHand(it.hand) },
    scope = applicationScope,
)

webSocket("/room/{roomId}") {
    val roomId = call.parameters["roomId"]!!
    val room = chatRoomServer.getOrCreateRoom(roomId)
    room.handleClient(sessionId, connection)
}

// 빈 방 정리
chatRoomServer.cleanupEmptyRooms()
```

### Per-Client (1:N:N)
```kotlin
// createPerClientServer: 클라이언트당 독립 Store
val playerServer = createPerClientServer(
    initialStateFactory = { playerId -> PlayerState(playerId = playerId) },
    reducer = playerReducer,
    stateMapper = { SharedAction.SyncHand(it.hand) },
    scope = applicationScope,
)

webSocket("/game/{playerId}") {
    val playerId = call.parameters["playerId"]!!
    playerServer.handleClient(playerId, connection)
}
```

### Hybrid (Room + Per-Client)
```kotlin
// 공개 상태 + 비공개 상태 조합
val roomStore = createSharedStateServer(...)

class PlayerSession(connection: TypedServerConnection<...>) {
    val store = createSingleClientServer(
        initialState = PlayerState(),
        reducer = playerReducer,
        connection = connection,
    )
}

webSocket("/game/{playerId}") {
    val session = PlayerSession(connection)
    coroutineScope {
        launch { roomStore.handleClient(playerId, connection) }  // 공개
        launch { session.store.serve { SyncHand(it.hand) } }     // 비공개
    }
}
```

## Pattern Guides

각 패턴의 상세 사용법은 개별 가이드를 참조하세요:

1. **[Single Client Pattern](./pattern-single-client.md)** — 1:1:1 패턴, 개인화된 독립 세션
2. **[Shared State Pattern](./pattern-shared-state.md)** — 1:1:N 패턴, 다중 클라이언트 상태 공유
3. **[Room Pattern](./pattern-room.md)** — 1:N:M 패턴, 그룹별 독립 상태
4. **[Per-Client Pattern](./pattern-per-client.md)** — 1:N:N 패턴, 클라이언트별 비공개 상태

## Related

- [Samples](./samples.md) — 각 패턴의 샘플 앱 실행 방법
- [Scaling](./scaling.md) — 대규모 연결 처리
- [FlowDux Remote vs Raw WebSocket](./flowdux-remote-vs-raw.md) — 비교 분석
