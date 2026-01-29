# FlowDux Multiplayer Game Server Architecture (ASCII)

> **Design Document** — 이 문서는 FlowDux 기반 멀티플레이어 게임 서버의 **목표 아키텍처**를 설명합니다.
> 현재 구현 상태는 [§5. 현재 구현 상태](#5-현재-구현-상태)를 참고하세요.

## 1. 전체 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│                          CLIENTS                                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐       ┌──────────┐      │
│  │ Player 1 │  │ Player 2 │  │ Player 3 │  ...  │ Player N │      │
│  │ (Android) │  │  (iOS)   │  │  (Web)   │       │ (Desktop)│      │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘       └────┬─────┘      │
│       │              │              │                   │            │
│       └──────────────┴──────┬───────┴───────────────────┘            │
│                             │ WSS + JWT                              │
└─────────────────────────────┼───────────────────────────────────────┘
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     LOAD BALANCER (Nginx)                            │
│              Sticky Session (by room/user ID)                       │
└─────────────────────────────┬───────────────────────────────────────┘
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    GAME SERVER (Ktor)                                │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                  AUTH MIDDLEWARE                               │  │
│  │  WebSocket Upgrade → JWT 검증 → User ID 추출 → Session 생성  │  │
│  └──────────────────────────┬────────────────────────────────────┘  │
│                              ▼                                      │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                    ROOM MANAGER                                │  │
│  │  • Room 생성/삭제/매칭                                        │  │
│  │  • Player → Room 매핑                                         │  │
│  │  • Room 당 최대 인원 관리                                     │  │
│  └──────┬──────────────────┬──────────────────┬─────────────────┘  │
│         ▼                  ▼                  ▼                     │
│  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐              │
│  │   ROOM A    │   │   ROOM B    │   │   ROOM C    │              │
│  │             │   │             │   │             │              │
│  │ ┌─────────┐ │   │ ┌─────────┐ │   │ ┌─────────┐ │              │
│  │ │ FlowDux │ │   │ │ FlowDux │ │   │ │ FlowDux │ │              │
│  │ │  Store  │ │   │ │  Store  │ │   │ │  Store  │ │              │
│  │ └────┬────┘ │   │ └─────────┘ │   │ └─────────┘ │              │
│  │      │      │   │             │   │             │              │
│  │      ▼      │   │             │   │             │              │
│  │ ┌─────────┐ │   └─────────────┘   └─────────────┘              │
│  │ │Middleware│ │                                                   │
│  │ │ Pipeline │ │                                                   │
│  │ │         │ │   ROOM A 상세:                                    │
│  │ │ 1.Valid  │ │   ┌──────────────────────────────────────────┐   │
│  │ │ 2.Auth   │ │   │           MIDDLEWARE PIPELINE            │   │
│  │ │ 3.Logic  │ │   │                                          │   │
│  │ │ 4.View   │ │   │  ┌──────────┐  Action이 유효한가?       │   │
│  │ └────┬────┘ │   │  │Validation├─→ 좌표 범위, 턴 순서 등   │   │
│  │      │      │   │  └────┬─────┘                             │   │
│  │      ▼      │   │       ▼                                    │   │
│  │ ┌─────────┐ │   │  ┌──────────┐  이 플레이어가 이 액션을   │   │
│  │ │Tick Loop│ │   │  │AuthCheck ├─→ 수행할 권한이 있는가?    │   │
│  │ │ 60fps   │ │   │  └────┬─────┘                             │   │
│  │ └────┬────┘ │   │       ▼                                    │   │
│  │      │      │   │  ┌──────────┐  충돌 판정, 점수 계산,     │   │
│  │      ▼      │   │  │GameLogic ├─→ 물리 연산 등             │   │
│  │ ┌─────────┐ │   │  └────┬─────┘                             │   │
│  │ │Broadcast│ │   │       ▼                                    │   │
│  │ │per player│ │   │  ┌──────────┐  플레이어별 보이는         │   │
│  │ │(StateView)│   │  │StateView ├─→ 상태만 필터링 (FoW 등)  │   │
│  │ └─────────┘ │   │  └──────────┘                             │   │
│  │ P1,P2,P3   │   └──────────────────────────────────────────┘   │
│  └─────────────┘                                                   │
│                                                                     │
└──────────┬──────────────────────────┬───────────────────────────────┘
           ▼                          ▼
┌─────────────────────┐   ┌─────────────────────┐
│    REDIS (Pub/Sub)   │   │    DATABASE (RDB)    │
│                     │   │                     │
│ • 서버 간 Room 동기화│   │ • User 프로필       │
│ • Session 저장      │   │ • 매치 히스토리      │
│ • Presence 관리     │   │ • 랭킹/리더보드     │
│ • 매치메이킹 큐     │   │ • 인벤토리/재화     │
└─────────────────────┘   └─────────────────────┘
```

## 2. 요청 흐름

```
Player Action 발생 (예: 캐릭터 이동)
  │
  ▼
Client: ClientRemoteMiddleware → TypedClientConnection.send(action)
  │  내부: ActionCodec.encode() → MessageCodec.encodeActionMessage()
  ▼
WSS 전송 ──────────────────────────────────────────────────┐
  │                                                         │
  ▼                                                         │
Server: Ktor WebSocket Handler                              │
  │  JWT에서 userId 확인                                    │
  ▼                                                         │
RoomManager.getRoom(userId)                                 │
  │                                                         │
  ▼                                                         │
ServerRemoteMiddleware (FlowHolderAction으로 수신)           │
  │  TypedServerConnection.incoming 에서 디코딩:            │
  │    내부: MessageCodec → ActionCodec.decode()            │
  │  store.dispatch(action)  ← Middleware Pipeline 통과     │
  ▼                                                         │
StateView: 플레이어별 필터링                                │
  │  Player 1 → 자기 주변 상태만                            │
  │  Player 2 → 자기 주변 상태만                            │
  ▼                                                         │
Tick Batcher (16.67ms 간격)                                 │
  │  누적된 상태 변경을 묶어서 전송                         │
  ▼                                                         │
각 Player에게 WSS 응답  ◄──────────────────────────────────┘
```

## 3. 스케일 아웃

```
                    ┌──────────────┐
                    │ Load Balancer│
                    └──────┬───────┘
              ┌────────────┼────────────┐
              ▼            ▼            ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │ Server 1 │ │ Server 2 │ │ Server 3 │
        │ Room A,B │ │ Room C,D │ │ Room E,F │
        └────┬─────┘ └────┬─────┘ └────┬─────┘
             └─────────────┼─────────────┘
                           ▼
                    ┌──────────────┐
                    │  Redis Pub/Sub│  ← 서버 간 이벤트 전달
                    │  (클러스터)   │     (매칭 결과, 글로벌 이벤트)
                    └──────────────┘
```

## 4. 핵심 설계 포인트

| 구성요소 | 역할 | FlowDux 연관 |
|---------|------|-------------|
| **Room = Store** | Room 1개 = FlowDux Store 1개 | 상태 격리, 독립적 생명주기 |
| **Middleware** | 검증 → 인증 → 로직 → 필터 | 기존 FlowDux 미들웨어 파이프라인 그대로 |
| **Tick Loop** | 16.67ms마다 상태 변경 배치 전송 | [#76](https://github.com/chibimoons/flowdux/issues/76) 해결 후 구현 가능 |
| **StateView** | 플레이어별 보이는 상태만 전송 | 신규 기능 필요 |
| **Redis** | 서버 간 통신, 매칭 | FlowDux 외부, 인프라 레벨 |

## 5. 현재 구현 상태

- Room 내부 (Store + Middleware + Broadcast): **구현 가능**
- KtorWebSocketClientConnection disconnect 정리: [#76](https://github.com/chibimoons/flowdux/issues/76)
- ResponseCollector race condition 수정: [#77](https://github.com/chibimoons/flowdux/issues/77)
- StateView, Tick Batching: 추가 개발 필요
