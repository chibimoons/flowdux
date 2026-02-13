# FlowDux Server Architecture Patterns

## Overview

FlowDux remote 서버의 세 가지 아키텍처 패턴과 적용 시나리오를 정리한다.

```
패턴 1: Central Store       — 글로벌 이벤트 (공지, 설정)
패턴 2: Room Store           — 그룹 단위 상호작용 (채팅방, 게임 매치)
패턴 3: Per-Client Store     — 유저별 서버 권위 상태 (포커 패, 포트폴리오)
```

실제 시스템에서는 이 세 패턴이 **조합**되어 사용된다.

---

## Pattern 1: Central Store

모든 클라이언트에게 동일한 이벤트를 전파하는 구조.

```
┌─────────────────┐
│  Central Store   │  상태: 공지, 시스템 설정, 점검 정보
│  (1 instance)    │
└────────┬────────┘
         │ broadcast
    ┌────┼────┐
    ▼    ▼    ▼
   C1   C2   C3   ...CN
```

### 적합한 경우
- 시스템 공지사항
- 서버 점검 알림
- 글로벌 설정 변경 (환율, 이벤트 활성화 등)
- 모든 유저에게 동일한 정보를 전달하는 모든 경우

### 확장 전략
- Central Store 자체는 단일 인스턴스 (쓰기 빈도 낮음)
- 분배 레이어를 분리하여 확장

```
Central Store (1개)
    │
    ├─ Node A Mediator → C1~C3000 (로컬 분배)
    ├─ Node B Mediator → C3001~C6000
    └─ Node C Mediator → C6001~C9000

Central Store 관점: 연결 3개 (노드 수)
실제 클라이언트: 9000개
```

### 기존 사례
- Akka DistributedPubSub (노드당 1회 전송, 로컬 분배)
- Phoenix PubSub (PG2 기반 클러스터링)
- Orleans Streams (가상 스트림 기반 fan-out)

---

## Pattern 2: Room Store

그룹(방) 단위로 메시지를 라우팅하는 구조.

```
┌──────┐ ┌──────┐ ┌──────┐
│Room 1│ │Room 2│ │Room 3│   방마다 독립된 Store
└──┬───┘ └──┬───┘ └──┬───┘
   │        │        │
┌──┼──┐  ┌──┼──┐  ┌──┼──┐
▼  ▼  ▼  ▼  ▼  ▼  ▼  ▼  ▼
C1 C2 C3 C3 C4 C5 C6 C7 C8
             ↑
          C3는 Room 1, 2 모두 구독
```

### 적합한 경우
- 채팅방 (메시지는 방 구성원에게만)
- 게임 매치 (매치 상태는 참가자에게만)
- 수업/강의실 (자료는 수강생에게만)
- 팀 단위 협업 공간

### 핵심 이점
- 메시지 라우팅이 O(방 인원)이지 O(전체 유저)가 아님
- 방 간 상태 격리
- 방 단위 수평 확장 가능 (방을 다른 노드에 배치)

### 기존 사례
- Socket.IO Rooms
- Phoenix Channels (topic 기반)
- Colyseus Rooms
- Discord Guild/Channel

---

## Pattern 3: Per-Client Store

클라이언트마다 전용 서버 Store를 두는 구조.

```
┌──┐ ┌──┐ ┌──┐ ┌──┐
│S1│ │S2│ │S3│ │S4│  클라이언트별 전용 Store
└─┬┘ └─┬┘ └─┬┘ └─┬┘
  ▼    ▼    ▼    ▼
 C1   C2   C3   C4
```

### 적합한 경우
- 포커/카드 게임 — 서버가 각 플레이어의 패를 권위적으로 관리
- 주식/금융 — 유저별 포트폴리오 상태
- 시험 시스템 — 유저별 답안, 남은 시간
- 서버가 유저별 상태를 **권위적으로** 관리해야 하는 모든 경우

### 기존 사례
- Akka Session Actor (클라이언트당 Child Actor)
- Orleans Grain (클라이언트당 Virtual Actor)
- Phoenix LiveView (연결당 GenServer 프로세스)
- Cloudflare Durable Objects (엔티티당 Object)

### 확장성
- 인스턴스당 오버헤드: ~1KB (상태 크기에 따라 가변)
- 10,000 클라이언트 × 1KB = ~10MB (단일 서버에서 충분)
- 병목은 Store 메모리가 아니라 네트워크 I/O, 직렬화, WebSocket 관리

---

## Combined Architecture

실제 시스템은 세 패턴을 조합한다.

```
┌─────────────────┐
│  Central Store   │  공지, 시스템 이벤트
└────────┬────────┘
         │
    ┌────┼─────────────┐
    ▼    ▼             ▼
┌──────┐ ┌──────┐  ┌──────┐
│Room 1│ │Room 2│  │Room N│  채팅방, 게임 매치
└──┬───┘ └──┬───┘  └──┬───┘
   │        │         │
   ▼        ▼         ▼
┌──────────────────────────┐
│  Per-Client Store         │  유저별 서버 상태 (필요한 경우만)
└──────────┬───────────────┘
           ▼
        Client (WebSocket)
```

### 예시: 온라인 포커

| 패턴 | 용도 |
|---|---|
| Central Store | 토너먼트 공지, 시스템 점검 |
| Room Store | 포커 테이블 (게임 진행 상태, 베팅) |
| Per-Client Store | 각 플레이어의 비공개 패 |

### 예시: 대규모 채팅 (Slack/Discord)

| 패턴 | 용도 |
|---|---|
| Central Store | 서비스 공지, 글로벌 설정 |
| Room Store | 채팅 채널 (메시지 라우팅) |
| Per-Client Store | 불필요 (채팅은 stateless relay) |

### 예시: 온라인 시험

| 패턴 | 용도 |
|---|---|
| Central Store | 시험 시작/종료 신호 |
| Room Store | 시험방 (시험 문제 배포) |
| Per-Client Store | 각 수험자의 답안, 남은 시간 |

---

## Scaling Strategy

### 단일 서버 (1만 이하)

```
Central Store + Room Stores + Client Stores
모두 같은 프로세스에서 실행
```

### 멀티 노드 (~100만)

[NodeMediator](../guide/pattern-node-mediator.md)를 사용하여 Central Store와 다수의 Node를 연결합니다.

```
Central Store ←→ Node A (Room 1~100, Client Store 3만개)
              ←→ Node B (Room 101~200, Client Store 3만개)
              ←→ Node C (Room 201~300, Client Store 3만개)

노드 간 통신: NodeMediator (WebSocket, NodeAction 프로토콜)
Central Store 관점: 연결 3개 (CentralNodeManager)
```

- 보수적: 100 nodes × 10K clients = **100만** 동시 연결
- 적극적 (OS 튜닝): 500 nodes × 50K clients = **2,500만** 동시 연결
- 병목: Central의 메시지 중계 throughput (relay fan-out이 Node 수에 비례)

### Central 샤딩 (~1,000만)

단일 Central이 병목이 되면 room 기반으로 Central을 분할합니다:

```
Router (L7 LB)
  ├── room 1~1000  → Central-A ←→ Node 1~50
  ├── room 1001~2000 → Central-B ←→ Node 51~100
  └── room 2001~3000 → Central-C ←→ Node 101~150
```

Central 수에 비례하여 throughput이 선형 증가합니다.

### 대규모 (~1억+)

Central↔Node 간 WebSocket을 Event Bus로 대체하면 Central이 stateless가 됩니다:

```
Node ──► Kafka / Redis Streams ◄── Node

Room Store 분산:
  - 활성 방은 메모리에 유지
  - 비활성 방은 상태를 DB에 저장 후 메모리에서 제거
  - 재접속 시 DB에서 복원 (Virtual Actor 패턴)
```

### 확장 병목 순서

1. WebSocket 연결 수 (파일 디스크립터, OS 튜닝)
2. Central relay throughput (Node 수 × 메시지 빈도)
3. 브로드캐스트 fan-out (O(N) 직렬화/전송)
4. 네트워크 대역폭 (클라이언트수 × 메시지크기 × 빈도)
5. GC 압력 (JVM, 다수 소형 객체)
6. Store 메모리 (가장 마지막에 문제됨)
