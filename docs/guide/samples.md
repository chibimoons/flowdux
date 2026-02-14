# Sample Apps

FlowDux 샘플 앱 가이드입니다. 각 샘플은 특정 기능이나 패턴을 보여줍니다.

## Sample Overview

| Sample | 패턴/기능 | 플랫폼 | 설명 |
|--------|----------|--------|------|
| [jvm](#jvm-console-sample) | 기본 Store, Middleware, ExecutionStrategy | JVM | FlowDux 핵심 기능 데모 |
| [android](#android-sample) | Android 통합 | Android | ViewModel + Compose 연동 |
| [kmm](#kmm-sample) | Kotlin Multiplatform | Android/iOS | 공유 비즈니스 로직 |
| [web](#web-javascript-sample) | JS 타겟 | Browser | Kotlin/JS 웹 앱 |
| [wasm](#webassembly-wasm-sample) | WASM 타겟 | Browser | Kotlin/WASM 웹 앱 |
| [remote-simple](#remote-simple-sample) | 1:1 WebSocket | JVM | 단일 클라이언트-서버 |
| [remote-multi](#remote-multi-client-sample) | Room Store (단일 방) | JVM | N 클라이언트 = 1 Store |
| [remote-multiroom](#remote-multi-room-sample) | Room Store (다중 방) | JVM | 독립된 여러 방 관리 |
| [remote-scaling](#remote-scaling-sample) | 병렬 브로드캐스트, 스케일링 | JVM | 대규모 동시 연결 |
| [remote-poker](#remote-poker-sample) | Per-Client Store | JVM | 비공개 상태 관리 (포커) |
| [remote-auth](#remote-auth-sample) | In-Band WebSocket 인증 | JVM | 토큰 기반 인증 채팅 |
| [remote-multiplexer](#remote-multiplexer-sample) | Connection Multiplexer | JVM | 단일 WS, 다중 방 동시 참여 |
| [remote-node-mediator](#remote-node-mediator-sample) | Node Mediator | JVM | Central↔Node 분산 채팅 |

---

## JVM Console Sample

**학습 포인트:** Store 생성, Action dispatch, Middleware, ExecutionStrategy

FlowDux의 핵심 기능을 콘솔에서 확인합니다:
- 기본 Counter (Increment, Decrement, Add)
- FlowHolderAction (Flow를 반환하는 Action)
- ExecutionStrategy (takeLatest, debounce, takeLeading, group)

```bash
./gradlew :kotlin:sample-jvm:run
```

**출력 예시:**
```
=== Flowdux Sample: Counter ===

State: count = 0
> Dispatching Increment
State: count = 1
...

=== Execution Strategy Examples ===

> takeLatest: Rapid search (only latest completes)
  Dispatching Search('a'), Search('ab'), Search('abc') rapidly...
  Result: Only 'abc' search completed!

> debounce: Wait 200ms after last input
  Result: Only last FetchData executed after 200ms quiet period!
```

---

## Android Sample

**학습 포인트:** ViewModel 통합, Jetpack Compose, Android lifecycle

Android 앱에서 FlowDux를 사용하는 방법을 보여줍니다.

```bash
./gradlew :kotlin:sample-android:assembleDebug
```

**APK 위치:** `kotlin/samples/flowdux/android/build/outputs/apk/debug/sample-android-debug.apk`

---

## KMM Sample

**학습 포인트:** 공유 비즈니스 로직, 플랫폼별 UI, expect/actual

Kotlin Multiplatform으로 Android와 iOS에서 동일한 Store를 사용합니다.

```
kotlin/samples/flowdux/kmm/
├── shared/           # 공유 Kotlin 코드 (commonMain)
│   └── CounterStore  # 공유 비즈니스 로직
├── androidApp/       # Android UI (Compose)
└── iosApp/           # iOS UI (SwiftUI)
```

### Android

```bash
./gradlew :kotlin:sample-kmm:androidApp:assembleDebug
```

### iOS

```bash
# Shared framework 빌드
./gradlew :kotlin:sample-kmm:shared:linkDebugFrameworkIosSimulatorArm64

# iOS 앱 빌드
xcodebuild -project kotlin/samples/flowdux/kmm/iosApp/iosApp.xcodeproj \
  -target iosApp -sdk iphonesimulator -arch arm64 build
```

---

## Web (JavaScript) Sample

**학습 포인트:** Kotlin/JS, 브라우저 DOM 연동

브라우저에서 실행되는 Counter 앱입니다.

```bash
./gradlew :kotlin:sample-web:jsBrowserDevelopmentRun
```

`http://localhost:8080`에서 확인할 수 있습니다.

---

## WebAssembly (WASM) Sample

**학습 포인트:** Kotlin/WASM, 최신 브라우저 타겟

WASM으로 컴파일된 Counter 앱입니다.

```bash
./gradlew :kotlin:sample-wasm:wasmJsBrowserDevelopmentRun
```

`http://localhost:8080`에서 확인할 수 있습니다.

---

## Remote Simple Sample

**학습 포인트:** WebSocket 기본, 1:1 통신, SharedAction

가장 단순한 클라이언트-서버 구조입니다. 클라이언트가 연결할 때마다 새 Store가 생성됩니다.

```
┌────────┐         ┌────────┐
│ Client │ ──WS──► │ Server │
│ Store  │         │ Store  │  (1:1 관계)
└────────┘         └────────┘
```

### 서버 시작

```bash
./gradlew :kotlin:sample-remote-simple:server:run
```

### 클라이언트 실행

```bash
./gradlew :kotlin:sample-remote-simple:client:run
```

---

## Remote Multi-Client Sample

**학습 포인트:** Room Store 패턴 (기본), 다중 클라이언트, 상태 브로드캐스트

여러 클라이언트가 **하나의 Store**를 공유합니다. 채팅방처럼 모든 참가자가 같은 상태를 봅니다.

```
┌────────┐
│Client 1│─┐
└────────┘ │      ┌────────┐
┌────────┐ ├─WS─► │ Server │  (N:1 관계)
│Client 2│─┤      │ Store  │
└────────┘ │      └────────┘
┌────────┐ │
│Client 3│─┘
└────────┘
```

### 서버 시작

```bash
./gradlew :kotlin:sample-remote-multi:server:run
```

### 클라이언트 실행 (여러 터미널에서)

```bash
./gradlew :kotlin:sample-remote-multi:client:run
# 또는 이름 지정: ./gradlew :kotlin:sample-remote-multi:client:run --args="Alice"
```

### 시스템 공지 (System Announcements)

서버에서 연결된 모든 클라이언트에게 시스템 공지를 보낼 수 있습니다:

```bash
# 공지 보내기
curl -X POST http://localhost:8080/announce -d "서버 점검 예정"

# 점검 모드 전환
curl -X POST http://localhost:8080/maintenance/true
curl -X POST http://localhost:8080/maintenance/false
```

클라이언트에서 확인:
```
  *** SYSTEM: 서버 점검 예정 ***
```

이 기능은 `SharedStateServer.broadcast()`를 사용하여 구현됩니다. 자세한 내용은 [Remote 가이드](./remote.md#2-server-setup)를 참조하세요.

---

## Remote Multi-Room Sample

**학습 포인트:** Room Store 패턴 (다중 방), RoomManager, 동적 방 생성/삭제

여러 **독립된 방**을 관리합니다. 각 방은 자체 Store를 가지며, 방 간 메시지는 격리됩니다.

```
┌─────────────────────────────────────────────────┐
│                   Server                         │
│  ┌─────────────────────────────────────────┐    │
│  │            RoomManager                   │    │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐   │    │
│  │  │ Room A  │ │ Room B  │ │ Room C  │   │    │
│  │  │ (Store) │ │ (Store) │ │ (Store) │   │    │
│  │  └────┬────┘ └────┬────┘ └────┬────┘   │    │
│  └───────┼───────────┼───────────┼────────┘    │
└──────────┼───────────┼───────────┼─────────────┘
           │           │           │
      ┌────┴────┐ ┌────┴────┐ ┌────┴────┐
      │ C1, C2  │ │ C3, C4  │ │   C5    │
      └─────────┘ └─────────┘ └─────────┘
```

### 서버 시작

```bash
./gradlew :kotlin:sample-remote-multiroom:server:run
```

### 클라이언트 실행

```bash
./gradlew :kotlin:sample-remote-multiroom:client:run
# 또는: ./gradlew :kotlin:sample-remote-multiroom:client:run --args="Alice general"
```

### 클라이언트 명령어

| 명령어 | 설명 |
|--------|------|
| `/join <room>` | 다른 방으로 이동 |
| `/rooms` | 추천 방 목록 |
| `/users` | 현재 방의 유저 목록 |
| `/history` | 메시지 히스토리 |
| `/quit` | 종료 |

### 데모 시나리오

1. 터미널 3개 열기
2. 서버 시작
3. Client 1: `Alice`로 `general` 방 입장
4. Client 2: `Bob`으로 `general` 방 입장 → Alice와 Bob이 서로 보임
5. Client 3: `Charlie`로 `random` 방 입장 → 혼자 보임
6. Client 2: `/join random` → Bob이 random으로 이동, Charlie와 대화 가능
7. `general` 방에는 Alice만 남음

---

## Remote Scaling Sample

**학습 포인트:** BroadcastConfig, SessionRegistry, 병렬 브로드캐스트

대규모 동시 연결을 처리하기 위한 스케일링 아키텍처를 보여줍니다.

```
┌───────────────────────────────────────────────────┐
│                     Server                         │
│  ┌─────────────────────────────────────────────┐  │
│  │           SessionBroadcaster                 │  │
│  │  concurrency = 32 (병렬 전송)                │  │
│  └──────────────────┬──────────────────────────┘  │
│                     │                              │
│  ┌──────────────────┼──────────────────────────┐  │
│  │          SessionRegistry                     │  │
│  │  ┌─────┐ ┌─────┐ ┌─────┐      ┌─────┐      │  │
│  │  │ C1  │ │ C2  │ │ C3  │ ···  │ CN  │      │  │
│  │  └─────┘ └─────┘ └─────┘      └─────┘      │  │
│  └─────────────────────────────────────────────┘  │
└───────────────────────────────────────────────────┘
```

### 핵심 컴포넌트

| 컴포넌트 | 역할 |
|----------|------|
| `BroadcastConfig` | 브로드캐스트 동시성 설정 (1=순차, 32=병렬) |
| `SessionRegistry` | 세션 저장소 인터페이스 (커스텀 구현 가능) |
| `InMemorySessionRegistry` | 기본 인메모리 구현 |
| `SessionBroadcaster` | 병렬 메시지 전송 처리 |

### 서버 시작

```bash
./gradlew :kotlin:sample-remote-scaling:server:run
```

### 테스트 엔드포인트

```bash
# 서버 상태 조회
curl http://localhost:8080/stats

# 수동 브로드캐스트 트리거
curl -X POST http://localhost:8080/broadcast

# 스트레스 테스트 (1000회 increment)
curl -X POST http://localhost:8080/stress/1000
```

### WebSocket 클라이언트 연결

```bash
# websocat 사용 예시
websocat ws://localhost:8080/ws
```

### 스케일링 단계

| 단계 | 규모 | 구현 |
|------|------|------|
| 기본 | ~10k | 순차 브로드캐스트 |
| Stage 1 | ~100k | `BroadcastConfig(concurrency = 32)` |
| Stage 2 | ~1M | 커스텀 `SessionRegistry` (Redis) |
| Stage 3 | ~10M+ | Kafka + Redis Cluster |

자세한 내용은 [Scaling Architecture](./scaling.md) 문서를 참조하세요.

---

## Remote Poker Sample

**학습 포인트:** Per-Client Store 패턴, Room Store 조합, 비공개 정보 관리

서버가 각 플레이어의 비공개 패를 관리하는 포커 게임 예제입니다.

```
┌─────────────────────────────────────────────────────────────┐
│                    Poker Table (Room Store)                  │
│  상태: 게임 진행, 베팅, 공개 카드, 턴 순서                    │
└──────────────────────────┬──────────────────────────────────┘
                           │ dispatch (비공개 카드 분배)
         ┌─────────────────┼─────────────────┐
         ▼                 ▼                 ▼
    ┌─────────┐       ┌─────────┐       ┌─────────┐
    │Player 1 │       │Player 2 │       │Player 3 │
    │ Store   │       │ Store   │       │ Store   │
    │(비공개패)│       │(비공개패)│       │(비공개패)│
    └────┬────┘       └────┬────┘       └────┬────┘
         │                 │                 │
         ▼                 ▼                 ▼
        C1                C2                C3
                    (WebSocket Clients)
```

| 패턴 | 용도 |
|------|------|
| Room Store | 포커 테이블 (공개 정보: 베팅, 턴, 커뮤니티 카드) |
| Per-Client Store | 각 플레이어의 비공개 패 |

### 서버 시작

```bash
./gradlew :kotlin:sample-remote-poker:server:run
```

### 클라이언트 실행 (여러 터미널에서)

```bash
./gradlew :kotlin:sample-remote-poker:client:run --args="Alice" --console=plain
./gradlew :kotlin:sample-remote-poker:client:run --args="Bob" --console=plain
```

### 게임 제어 (관리자 엔드포인트)

```bash
# 게임 시작 (2명 이상 접속 필요)
curl -X POST http://localhost:8080/start

# 다음 단계로 진행 (FLOP → TURN → RIVER)
curl -X POST http://localhost:8080/advance

# 승자 결정
curl -X POST http://localhost:8080/winner
```

### 클라이언트 명령어

| 명령어 | 설명 |
|--------|------|
| `bet <amount>` | 베팅 (자신의 턴일 때만) |
| `fold` | 폴드 |
| `check` | 체크 |
| `call` | 콜 |
| `status` | 현재 테이블 상태 확인 |
| `quit` | 종료 |

### 검증 포인트

- 각 플레이어가 **자신의 패만** 볼 수 있는지 확인
- 공개 정보 (베팅, 턴, 커뮤니티 카드)는 모두에게 보이는지 확인
- 게임 진행이 정상 동작하는지 확인

---

## Remote Auth Sample

**학습 포인트:** In-Band WebSocket 인증, AuthVerifier, AuthPrincipal, getOrElse 패턴

토큰 기반 인증이 적용된 채팅 앱이다. 클라이언트는 연결 후 첫 메시지로 토큰을 전송하고,
서버는 `AuthVerifier`로 검증 후 인증된 세션만 허용한다.

```
┌────────┐  1. WebSocket Open      ┌────────┐
│ Client │ ──────────────────────►  │ Server │
│        │  2. {"type":"auth",      │        │
│        │      "token":"user:Alice"│        │  AuthVerifier
│        │  ──────────────────────► │        │  ├─ 실패 → close
│        │  3. {"type":"auth_ok"}   │        │  └─ 성공:
│        │ ◄────────────────────── │        │
│ Store  │ ═══ 정상 메시지 교환 ═══ │ Store  │
└────────┘                          └────────┘
```

### 서버 시작

```bash
./gradlew :kotlin:sample-remote-auth:server:run
```

### 클라이언트 실행 (여러 터미널에서)

```bash
./gradlew :kotlin:sample-remote-auth:client:run --args="Alice" --console=plain
./gradlew :kotlin:sample-remote-auth:client:run --args="Bob" --console=plain
```

### 클라이언트 명령어

| 명령어 | 설명 |
|--------|------|
| `<메시지>` | 채팅 메시지 전송 |
| `/users` | 현재 접속 유저 목록 |
| `/history` | 메시지 히스토리 |
| `/quit` | 종료 |

### 인증 흐름

1. 클라이언트가 `user:{name}` 형식의 토큰으로 인증 요청
2. 서버의 `AuthVerifier`가 토큰을 검증하고 `ChatPrincipal`을 생성
3. 인증 성공 시 `auth_ok` 응답 → 정상 채팅 시작
4. 인증 실패 시 `auth_error` 응답 → 연결 종료

자세한 인증 아키텍처는 [Remote Authentication](./remote-authentication.md) 참조.

---

## Remote Multiplexer Sample

**학습 포인트:** ConnectionMultiplexer, RoutedAction, 단일 WebSocket 다중 방

하나의 WebSocket 연결로 여러 채팅방에 동시 참여하는 예제입니다. `ClientConnectionMultiplexer`와 `ServerConnectionMultiplexer`가 `RoutedAction`을 사용하여 방별로 메시지를 라우팅합니다.

```
┌────────────── 단일 WebSocket ──────────────┐
│  ┌─────────┐  ┌─────────┐  ┌─────────┐    │
│  │ general │  │ random  │  │ kotlin  │    │
│  │ (Store) │  │ (Store) │  │ (Store) │    │
│  └────┬────┘  └────┬────┘  └────┬────┘    │
│       └────────────┼────────────┘          │
│                    │ RoutedAction           │
└────────────────────┼───────────────────────┘
                     │
                   Client
```

### 서버 시작

```bash
./gradlew :kotlin:sample-remote-multiplexer:server:run
```

### 클라이언트 실행 (여러 터미널에서)

```bash
./gradlew :kotlin:sample-remote-multiplexer:client:run --args="Alice"
./gradlew :kotlin:sample-remote-multiplexer:client:run --args="Bob"
```

### 클라이언트 명령어

| 명령어 | 설명 |
|--------|------|
| `/join <room>` | 방 참여 |
| `/leave <room>` | 방 나가기 |
| `/rooms` | 참여 중인 방 목록 |
| `/switch <room>` | 활성 방 전환 |
| `/quit` | 종료 |

### 데모 시나리오

1. 터미널 3개 열기
2. 서버 시작
3. Client 1: Alice로 접속 → 자동 `general` 입장
4. Client 2: Bob으로 접속 → Alice와 같은 `general`
5. Alice: `/join random` → 동일 WS에서 `random` 추가 참여
6. Alice: `/switch random` + 메시지 → `random` 방에서 혼자 채팅
7. Bob: `/join random` → Alice와 `random`에서도 대화 가능

자세한 패턴 설명은 [Multiplexer Pattern](./pattern-multiplexer.md)을 참조하세요.

---

## Remote Node Mediator Sample

**학습 포인트:** CentralNodeManager, NodeMediator, InMemoryRoomRegistry, 분산 노드 라우팅, 멀티룸

Central 서버가 여러 Node 서버를 관리하는 분산 채팅 예제입니다. 각 Node는 `NodeMediator`로 Central에 연결하고, 로컬 클라이언트를 처리합니다. 클라이언트는 `/join <room>`으로 방을 전환할 수 있으며, cross-node 메시지는 Central을 통해 릴레이됩니다.

```
┌─────────────────────────────────────────────┐
│            Central (:8080)                   │
│  CentralNodeManager + InMemoryRoomRegistry  │
└──────┬───────────────────┬──────────────────┘
       │ WS /node/node-1   │ WS /node/node-2
┌──────▼──────┐     ┌──────▼──────┐
│ Node (:8081)│     │ Node (:8082)│
│ NodeMediator│     │ NodeMediator│
│ RoomServer  │     │ RoomServer  │
│ ┌─────────┐ │     │ ┌─────────┐ │
│ │  lobby  │ │     │ │  lobby  │ │
│ │ game-1  │ │     │ │ game-1  │ │
│ └─────────┘ │     │ └─────────┘ │
└──────┬──────┘     └──────┬──────┘
       │ WS /ws            │ WS /ws
   Client A             Client B
```

### Central 서버 시작

```bash
./gradlew :kotlin:sample-remote-node-mediator:central:run
```

### Node 서버 시작 (여러 터미널에서)

```bash
./gradlew :kotlin:sample-remote-node-mediator:node:run --args="node-1 8081"
./gradlew :kotlin:sample-remote-node-mediator:node:run --args="node-2 8082"
```

### 클라이언트 실행

```bash
./gradlew :kotlin:sample-remote-node-mediator:client:run --args="Alice lobby localhost 8081"
./gradlew :kotlin:sample-remote-node-mediator:client:run --args="Bob lobby localhost 8082"
```

### 클라이언트 명령어

| 명령어 | 설명 |
|--------|------|
| `/join <room>` | 다른 방으로 이동 (기존 방 자동 퇴장) |
| `/users` | 현재 방의 접속 유저 목록 |
| `/room` | 현재 방 이름 확인 |
| `/quit` | 종료 |

### 데모 시나리오

**기본: cross-node 채팅**

1. Central 서버 시작 (port 8080)
2. Node 서버 2개 시작 (port 8081, 8082) — 각 Node가 Central에 WebSocket 연결
3. Alice: node-1에 접속 → `lobby` room 자동 생성
4. Bob: node-2에 접속 → node-2에도 `lobby` room 생성 (같은 room, 다른 Node)
5. Alice가 메시지 전송 → Bob에게 Central 통해 전달

**멀티룸: 방 전환과 격리**

6. Alice: `/join game-1` → lobby 자동 퇴장, game-1 입장
7. Bob: lobby에서 메시지 전송 → Alice에게 안 감 (방 격리)
8. Bob: `/join game-1` → node-2에 game-1이 Central 릴레이로 자동 생성
9. Bob: game-1에서 메시지 전송 → Alice에게 전달 (같은 방, 다른 Node)

```
시간  Alice (node-1)           Bob (node-2)
 │    lobby에서 입장            lobby에서 입장
 │    "Hello!" ─────────────► "Hello!" 수신 (cross-node)
 │    /join game-1
 │    lobby 퇴장 ──────────► "Alice left" 표시
 │    game-1 입장
 │                              "Hi!" (lobby에서) ──✗── Alice에게 안 감
 │                              /join game-1
 │                              lobby 퇴장, game-1 입장
 │    "Bob joined" 표시 ◄──── game-1 입장
 │                              "Hey!" ────────────► Alice에게 전달
```

### 내부 동작 워크스루

Alice가 node-1에서 "Hello!"를 보내면 Bob이 node-2에서 받기까지:

```
Alice (Client)                Node-1                    Central                   Node-2                    Bob (Client)
     │                          │                          │                         │                          │
     │── SendMessage ──────────►│                          │                         │                          │
     │   {user:"Alice",         │                          │                         │                          │
     │    text:"Hello!"}        │                          │                         │                          │
     │                          │                          │                         │                          │
     │                   ┌──────┴──────┐                   │                         │                          │
     │                   │ 1. dispatch │                   │                         │                          │
     │                   │ Processor:  │                   │                         │                          │
     │                   │  SendMessage│                   │                         │                          │
     │                   │  → Message  │                   │                         │                          │
     │                   │    Received │                   │                         │                          │
     │                   │ Reducer:    │                   │                         │                          │
     │                   │  messages+= │                   │                         │                          │
     │                   └──────┬──────┘                   │                         │                          │
     │                          │                          │                         │                          │
     │                          │── forwardToCentral() ───►│                         │                          │
     │                          │   NodeAction(            │                         │                          │
     │                          │     roomId="lobby",      │                         │                          │
     │                          │     action=SendMessage)  │                         │                          │
     │                          │                          │                         │                          │
     │                          │                   ┌──────┴──────┐                  │                          │
     │                          │                   │onUpstream   │                  │                          │
     │                          │                   │ node-1 제외  │                  │                          │
     │                          │                   │ async relay │                  │                          │
     │                          │                   └──────┬──────┘                  │                          │
     │                          │                          │                         │                          │
     │                          │                          │── sendToNode(node-2) ──►│                          │
     │                          │                          │   NodeAction(           │                          │
     │                          │                          │     roomId="lobby",     │                          │
     │                          │                          │     action=SendMessage) │                          │
     │                          │                          │                         │                          │
     │                          │                          │                  ┌──────┴──────┐                   │
     │                          │                          │                  │ roomHandler │                   │
     │                          │                          │                  │ dispatch()  │                   │
     │                          │                          │                  │ Processor → │                   │
     │                          │                          │                  │ Reducer     │                   │
     │                          │                          │                  └──────┬──────┘                   │
     │                          │                          │                         │                          │
     │◄── SyncState ───────────│                          │                         │── SyncState ────────────►│
     │    {messages:[...],      │                          │                         │   {messages:[...],       │
     │     users:[Alice,Bob]}   │                          │                         │    users:[Alice,Bob]}    │
     │                          │                          │                         │                          │
```

#### 각 컴포넌트의 역할

| 컴포넌트 | 위치 | 역할 |
|----------|------|------|
| **Client** | 클라이언트 프로세스 | `SyncMiddleware`로 Node에 WS 연결, `SharedChatAction` 송수신 |
| **Node (RoomServer)** | Node 서버 | `createSharedStateRoomServer()`로 로컬 room store 관리. Processor가 `SharedChatAction` → `ServerRoomAction` 변환, Reducer가 상태 반영 |
| **Node (NodeMediator)** | Node 서버 | Central과 WS 연결, `forwardToCentral()`로 상향 전송, room handler로 하향 수신 |
| **Central (NodeManager)** | Central 서버 | Node 연결 관리, `onUpstreamAction`에서 발신 Node 제외 후 비동기 릴레이 |

#### 핵심 코드 흐름 (Node — 방 전환)

```kotlin
is SharedChatAction.JoinRoom -> {
    val roomId = action.roomId  // 클라이언트가 지정한 방

    // 기존 방이 있으면 자동 퇴장
    val oldRoomId = sessionRooms[sessionId]
    if (oldRoomId != null && oldRoomId != roomId) {
        val oldRoom = roomServer.getRoom(oldRoomId)
        oldRoom?.store?.dispatch(SharedChatAction.LeaveRoom(action.user))
        mediator.forwardToCentral(oldRoomId, SharedChatAction.LeaveRoom(action.user))
    }

    // 새 방 생성/참여 + Central 등록
    val room = roomServer.getOrCreateRoom(roomId)
    if (!mediator.hasRoom(roomId)) {
        mediator.registerRoom(roomId) { room.store.dispatch(it) }
    }

    subscribeToRoom(room)  // 클라이언트에 새 방 상태 전송
    room.store.dispatch(action)
    mediator.forwardToCentral(roomId, action)
}
```

#### 핵심 코드 흐름 (Central — 릴레이)

```kotlin
onUpstreamAction = { nodeId, roomId, action ->
    // 비동기 릴레이 (발신 Node 제외)
    scope.launch {
        for (targetNodeId in manager.connectedNodeIds()) {
            if (targetNodeId != nodeId) {
                manager.sendToNode(targetNodeId, roomId, action)
            }
        }
    }
}
```

#### 방 격리 원리

Central은 `NodeAction(roomId, action)` 형태로 메시지를 중계합니다. 수신 Node의 `NodeMediator`는 roomId로 등록된 handler를 찾아 해당 room store에만 dispatch합니다. 다른 room의 store에는 영향을 주지 않으므로, 같은 Node에 여러 room이 있어도 메시지가 격리됩니다.

자세한 패턴 설명은 [Node Mediator Pattern](./pattern-node-mediator.md)을 참조하세요.

---

## 관련 문서

- [Server Patterns Overview](./server-patterns.md) — 패턴 선택 가이드 (Single Client, Shared State, Room, Per-Client)
- [Remote (WebSocket)](./remote.md) — 클라이언트-서버 설정 가이드
- [Remote Authentication](./remote-authentication.md) — In-Band WebSocket 인증 아키텍처
- [Scaling Architecture](./scaling.md) — 병렬 브로드캐스트, 대규모 연결
- [Room Pattern](./pattern-room.md) — 다중 방 관리 패턴 상세
- [Per-Client Pattern](./pattern-per-client.md) — 비공개 상태 관리 패턴
- [Multiplexer Pattern](./pattern-multiplexer.md) — 단일 WebSocket 다중 방 패턴
- [Node Mediator Pattern](./pattern-node-mediator.md) — Central↔Node 분산 라우팅 패턴
- [FlowDux Remote vs Raw WebSocket](./flowdux-remote-vs-raw.md) — Use Case별 비교 및 선택 가이드
