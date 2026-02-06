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

이 기능은 `RemoteServer.broadcast()`를 사용하여 구현됩니다. 자세한 내용은 [Remote 가이드](./remote.md#multi-client-server)를 참조하세요.

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

## 관련 문서

- [Remote (WebSocket)](./remote.md) — 클라이언트-서버 설정 가이드
- [Room Store Pattern](./room-store.md) — 다중 방 관리 패턴 상세
- [Server Architecture Patterns](../design/server-architecture-patterns.md) — 아키텍처 패턴 설계 문서
