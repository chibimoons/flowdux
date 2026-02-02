# WebSocket 기반 아키텍처 스케일링 패턴 조사

> 조사일: 2026-01-30

## 실제 사례별 규모

| 서비스 | 동시 접속 | 핵심 전략 |
|--------|-----------|-----------|
| **Discord** | Gateway당 ~5,000유저 (shard), 음성 260만 동시접속, 220Gbps 트래픽 | Elixir/BEAM, 샤딩, Gateway 패턴 |
| **Slack** | 500만+ 동시 WebSocket 세션 | Gateway + Channel Server, Consistent Hashing, Actor 모델 |
| **Figma** | 문서당 1 프로세스 | CRDT (last-writer-wins), 문서 단위 파티셔닝, 33ms 간격 업데이트 |
| **Phoenix** | 단일 서버 200만 접속 (128GB RAM) | Erlang VM 경량 프로세스, 내장 분산 모델 |
| **MigratoryData** | 1,000-1,200만 (12코어 1U 서버) | Java/Linux |
| **WhatsApp** | 200만 (24코어) | Erlang/FreeBSD |

---

## 1. Gateway 패턴

가장 보편적인 대규모 WebSocket 아키텍처. **연결 관리**와 **비즈니스 로직**을 분리한다.

```
Clients ──► Gateway Servers (연결만 관리)
                  │
            Internal Protocol (gRPC, MQ)
                  │
            Business Logic Servers (상태/로직)
```

### Discord
- Gateway가 샤딩됨 (~5,000 유저/샤드). 각 샤드가 독립적 메시지 라우터.
- 연결 시 pub/sub 클라이언트 생성 → 유저의 친구, 서버, 그룹 ID 구독
- Bitfield 기반 권한 체크 후 이벤트 전달
- Rate limit: 60초당 120 이벤트 (평균 2 event/sec)

### Slack
- **Gateway Server (GS)**: 유저 정보 + WebSocket 채널 구독 보유. 다중 리전 배포.
- **Channel Server (CS)**: Consistent hashing으로 채널 서브셋 매핑. 피크 시 호스트당 1,600만 채널.
- **Admin Server (AS)**: Stateless, webapp 백엔드와 CS 간 인터페이스.
- **Presence Server (PS)**: 온라인 유저 추적.
- 메시지 흐름: Client → GS → CS (consistent hash) → 모든 구독 GS로 broadcast

### Centrifugo (오픈소스 Gateway)
- 프로덕션 레디 실시간 메시징 서버. 100만+ 접속 검증됨.
- WebSocket, HTTP-streaming, SSE, gRPC, WebTransport 지원.
- Redis/NATS backplane. v6(2025.01): 브로커와 Presence에 다른 백엔드 혼용 가능.

**핵심 이점**: Gateway 재시작 없이 비즈니스 로직 배포 가능 → Zero-downtime deployment.

---

## 2. Pub/Sub Backplane

수평 확장의 핵심. 서버 인스턴스 간 메시지를 외부 브로커로 중계.

```
Server 1 ──┐              ┌── Server 1의 로컬 클라이언트에 전달
Server 2 ──┤── Redis/NATS ─┤── Server 2의 로컬 클라이언트에 전달
Server 3 ──┘   (Backplane) └── Server 3의 로컬 클라이언트에 전달
```

### 브로커 비교

| | Redis Pub/Sub | Apache Kafka | NATS |
|---|---|---|---|
| 지연시간 | 극저 (in-memory) | 높음 (디스크 기반) | 극저 |
| 전달 보장 | At-most-once (Streams로 at-least-once) | At-least-once / Exactly-once | At-most-once (JetStream으로 at-least-once) |
| WebSocket 네이티브 | X | X | O (v2.2.0+) |
| 운영 복잡도 | 낮음-중간 | 높음 (JVM, 8+코어, 64-128GB RAM, SSD) | 낮음 |
| 적합 용도 | 단순 fan-out, 가장 보편적 backplane | 이벤트 소싱, 감사 로그, 상류 파이프라인 | 경량 실시간 메시징, 직접 WebSocket 통신 |
| 실사용 | Socket.io, SignalR, lichess | Slack (내구성 큐잉), Centrifugo (CDC) | Centrifugo 브로커, Resgate |

**참고**: Kafka는 last-mile WebSocket 전달에는 지연이 높아 적합하지 않음. 보통 Kafka → Redis/NATS → WebSocket 구조로 사용.

---

## 3. 샤딩 (Discord 방식)

```
Shard 0: Users 0-4999     → Server A
Shard 1: Users 5000-9999  → Server B
Shard 2: Users 10000-14999 → Server C
```

- 유저를 논리적 단위(~5,000명)로 분할
- 각 샤드가 독립적 → 장애 격리 (한 샤드 크래시 시 소수 유저만 영향)
- 샤드 단위로 서버 간 동적 이동 가능

---

## 4. Room/Channel 파티셔닝

### Socket.io
- in-memory room + `socket.io-redis` adapter로 서버 간 동기화
- Sticky session 필수 (연결 상태가 메모리에 있으므로)
- Namespace 파티셔닝으로 논리적 그룹 분리

### Phoenix Channels (Elixir)
- Erlang VM의 내장 분산 모델 활용 → 외부 브로커 불필요
- 채널당 supervisor 스폰, `:partitions` 옵션으로 CPU 코어별 분배
- 내장 presence tracking

### Figma (문서 단위 파티셔닝)
- 문서당 별도 프로세스 생성. 같은 문서 편집자는 같은 프로세스에 연결.
- 크로스 프로세스 상태 동기화 불필요 (일반적 케이스에서).
- DynamoDB 백킹 스토어 (Postgres는 쓰기 볼륨 감당 불가).

### Slack (Consistent Hashing)
- Channel Server가 consistent hashing으로 채널 서브셋 담당
- CHARM(Consistent Hash Ring Manager)이 서버 장애 시 20초 내 교체

---

## 5. 상태 동기화 패턴

### Pattern 1: 외부 공유 상태 저장소
Redis/DB에 세션 상태 저장. 서버 인스턴스가 stateless가 됨. 가장 보편적.

### Pattern 2: Pub/Sub 이벤트 전파
서버 간 "동기화" 대신, 이벤트를 브로커에 발행 → 각 서버가 로컬 연결에 해당 이벤트 전달.

### Pattern 3: CRDT (Conflict-Free Replicated Data Types)
Figma 사용. 수학적으로 eventual consistency 보장. 서버가 source of truth, 잘못된 상태 변경 거부.

### Pattern 4: Consistent Hashing
Slack 사용. 상태를 해시 링으로 파티셔닝. 서버 추가/제거 시 최소 재분배.

### Pattern 5: Actor Model
Slack Gateway Server 사용. 액터별 경량 상태 격리, 메시지 기반 통신.

### 전달 보장 수준
- **At-most-once**: Fire-and-forget. 타이핑 표시, 커서 위치 등 일시적 데이터에 적합.
- **At-least-once**: 도착 보장, 중복 가능. 클라이언트 멱등성 키 필요 (Slack 방식).
- **Exactly-once**: 가장 복잡. 실시간 WebSocket에서는 거의 불필요.

---

## 6. Backpressure & Flow Control

WebSocket은 수신자가 느릴 때 알려주는 내장 메커니즘이 없음.

### 문제점
- Slow client의 버퍼 누적 → 서버 메모리 고갈 (OOM)
- GC pressure → 전체 클라이언트 지연
- 한 slow consumer가 같은 서버의 모든 클라이언트에 영향

### 전략

| 전략 | 설명 |
|------|------|
| **TCP Backpressure 모니터링** | `socket.write()` 반환값 + `drain` 이벤트 감지 |
| **클라이언트별 버퍼 제한** | 임계치 초과 시 비중요 메시지 폐기 또는 연결 해제 |
| **Delta 압축** | 전체 state 대신 변경분만 전송 (Colyseus, Figma) |
| **우선순위 기반 드롭** | 중요도 낮은 메시지(typing, presence)부터 폐기 |
| **Rate Limiting** | Discord: 60초당 120이벤트 초과 시 연결 해제 |
| **Reactive Streams** | Kotlin Flow/Channel, Project Reactor 등 내장 backpressure |

---

## 7. 재연결 & 세션 유지

### Reconnection Storm 방지
배포 시 모든 연결이 동시 재연결 → 시스템 과부하. **Funnel 전략**: 배치 단위로 끊기 (예: 3초마다 1,000명씩).

### Exponential Backoff + Jitter
1. 재연결 간격 지수 증가 (1s, 2s, 4s, 8s, 16s...)
2. 랜덤 jitter로 동시 재연결 방지
3. 최대 재시도 횟수 설정
4. 시도당 타임아웃 설정

### WebSocket Close Code 활용
- **1012 (Service Restart)**: 서버 재시작 중, 곧 재연결 가능
- **1013 (Try Again Later)**: 서버 과부하, 백오프 후 재연결

### 세션 복원 방식

| 방식 | 설명 |
|------|------|
| **외부 세션 저장소** | Redis에 세션 상태 저장 → 어느 서버에 재연결해도 복원 |
| **Resume 프로토콜 (Discord)** | Session ID + sequence number로 놓친 이벤트 재전송 |
| **Fresh Download (Figma)** | 재연결 시 문서 전체 다운로드 → 오프라인 편집 적용 → 동기화 재개 |
| **멱등성 키 (Slack)** | Kafka + Redis로 중복 처리 방지 |

### 재연결 후 복구 절차
1. 재인증 (토큰 갱신)
2. 채널/Room 재구독
3. 놓친 메시지 fetch (cursor/sequence number 기반)
4. 로컬 상태와 서버 상태 reconcile

---

## 8. 연결 관리 수치

### 이론적 한계
- 서버의 65,536 포트 제한은 **오해**. 서버는 source IP + source port로 구분 → 이론상 2^48 연결 가능.
- 실제 제한: 메모리, 파일 디스크립터, CPU (heartbeat 처리).
- Linux 커널: 1,000만 소켓 유지에 약 32GB RAM (소켓당 ~3.2KB, 커널 레벨).

### 시스템 튜닝
- `fs.file-max=12000500` (파일 디스크립터 한도)
- `smp_affinity`로 하드웨어 인터럽트 CPU 분산
- TCP 스택 튜닝 (버퍼 사이즈, keepalive 간격)
- Event-driven I/O (Linux: epoll, BSD: kqueue)

### 로드밸런싱
- **HAProxy**: WebSocket LB에 가장 많이 사용. `LimitNOFILE` 100,000+ 설정.
- **Envoy Proxy**: Slack에서 사용.
- **Direct routing**: 노드별 연결 수 추적 → 최소 연결 노드 IP를 클라이언트에 직접 제공.
- **Load shedding**: 용량 임계 시 새 연결 거부 (명확한 에러 메시지).

---

## 9. 규모별 권장 아키텍처

### Small (< 10K 접속)
- 단일 WebSocket 서버 프로세스
- In-memory 상태 관리
- 외부 메시지 브로커 불필요

### Medium (10K - 100K)
- 다수 WebSocket 서버 인스턴스 + 로드밸런서 (HAProxy/Nginx)
- Redis Pub/Sub backplane
- Sticky session 또는 Redis 세션 저장소
- Centrifugo 도입 고려

### Large (100K - 1M)
- Gateway 패턴 (연결/로직 분리)
- NATS 또는 Redis Cluster
- Kubernetes HPA 자동 확장
- 클라이언트별 backpressure 모니터링
- Exponential backoff + jitter
- Load shedding

### Hyperscale (1M+)
- 샤딩 (~5,000 유저/샤드)
- 다중 리전 배포
- Consistent hashing 상태 파티셔닝
- Kafka (이벤트 소싱) + NATS/Redis (실시간 전달)
- CRDT 상태 충돌 해결
- Actor 모델 또는 Erlang/Elixir 경량 프로세스
- DynamoDB 등 수평 확장 DB

---

## 10. flowdux-remote 현황과 시사점

### 현재 상태
- 단일 Store, in-memory 세션 관리 (`MultiClientServerRemoteMiddleware`)
- 매 state 변경마다 전체 클라이언트 broadcast (`StoreServeExt.serveState()`)
- `createSessionAwareRemoteServer`의 `sessionStateMapper`로 클라이언트별 데이터 차별화 가능
- **Small 규모 (< 10K)에 적합**

### 스케일링 시 고려사항
1. **Room 파티셔닝**: `RemoteServerSession`이 이미 독립적이므로 Room별 생성이 자연스러움
2. **Pub/Sub Backplane**: `ServerConnection` 추상화 위에 Redis/NATS 기반 cross-server 라우팅 계층 추가
3. **Gateway 분리**: WebSocket 연결 관리를 별도 계층으로, Store 로직은 내부 서비스로
4. **Delta sync**: `stateMapper`를 `(prevState, currentState) -> DeltaAction?`으로 확장
5. **Backpressure**: Kotlin Flow/Channel의 내장 backpressure 활용 가능

---

## 참고 자료

- [Ably - WebSocket Architecture Best Practices](https://ably.com/topic/websocket-architecture-best-practices)
- [Ably - Scaling WebSockets for High-Concurrency](https://ably.com/topic/the-challenge-of-scaling-websockets)
- [Discord Gateway Documentation](https://discord.com/developers/docs/events/gateway)
- [Discord - Hyperscale Infrastructure Analysis](https://d4dummies.com/architecting-for-hyperscale-an-in-depth-analysis-of-discords-billion-message-per-day-infrastructure/)
- [Slack Engineering - Real-Time Messaging](https://slack.engineering/real-time-messaging/)
- [ByteByteGo - How Slack Supports Billions of Daily Messages](https://blog.bytebytego.com/p/how-slack-supports-billions-of-daily)
- [Figma Blog - How Figma's Multiplayer Technology Works](https://www.figma.com/blog/how-figmas-multiplayer-technology-works/)
- [Figma Blog - Making Multiplayer More Reliable](https://www.figma.com/blog/making-multiplayer-more-reliable/)
- [Phoenix - Road to 2 Million WebSocket Connections](https://www.phoenixframework.org/blog/the-road-to-2-million-websocket-connections)
- [TSH - How to Scale WebSocket](https://tsh.io/blog/how-to-scale-websocket)
- [Centrifugo v6 Released](https://centrifugal.dev/blog/2025/01/16/centrifugo-v6-released)
- [MigratoryData - Solved the C10M Problem](https://migratorydata.com/blog/migratorydata-solved-the-c10m-problem/)
- [NATS Documentation - Compare NATS](https://docs.nats.io/nats-concepts/overview/compare-nats)
- [Microsoft - SignalR Scale](https://learn.microsoft.com/en-us/aspnet/core/signalr/scale)
- [Spoon Radio - Scaling to Millions of WebSocket Connections](https://medium.com/@elliekang/scaling-to-a-millions-websocket-concurrent-connections-at-spoon-radio-bbadd6ec1901)
