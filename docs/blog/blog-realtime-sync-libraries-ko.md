# 실시간 상태 동기화 라이브러리 비교: flowdux의 포지셔닝

*Redux 스타일 Action/Reducer 패턴으로 클라이언트-서버 상태를 동기화하는 라이브러리들의 현황*

---

## 들어가며

실시간 협업 도구, 멀티플레이어 게임, 라이브 대시보드를 만들다 보면 공통된 문제에 직면합니다: **여러 클라이언트 간 상태를 어떻게 일관되게 유지할 것인가?**

이 글에서는 WebSocket 기반 실시간 상태 동기화 라이브러리들을 조사하고, flowdux의 remote 모듈이 이 생태계에서 어떤 위치를 차지하는지 분석합니다.

---

## 1. CRDT 기반 실시간 동기화 라이브러리

### Yjs

[Yjs](https://github.com/yjs/yjs)는 가장 널리 사용되는 CRDT(Conflict-free Replicated Data Type) 라이브러리입니다.

**특징:**
- 고성능 바이너리 인코딩으로 대용량 문서 처리
- 네트워크 독립적 (P2P, WebSocket, WebRTC 모두 지원)
- 풍부한 에디터 통합 (ProseMirror, Quill, Monaco, CodeMirror 등)
- Offline editing, undo/redo, shared cursors 지원

```javascript
import * as Y from 'yjs'
import { WebsocketProvider } from 'y-websocket'

const ydoc = new Y.Doc()
const provider = new WebsocketProvider('wss://server.com', 'room-name', ydoc)

// 자동으로 모든 클라이언트에 동기화됨
const ymap = ydoc.getMap('shared-state')
ymap.set('count', 42)
```

**적합한 케이스:** 텍스트 협업 편집, 화이트보드, 디자인 도구

### Automerge

[Automerge](https://automerge.org/)는 JSON 데이터 모델 기반 CRDT로, Rust로 구현되어 다양한 언어 바인딩을 제공합니다.

**특징:**
- 익숙한 JSON 객체 조작 방식
- Rust 코어 + WASM/JS 바인딩
- 네트워크 레이어 분리 (automerge-repo)

```javascript
import * as Automerge from '@automerge/automerge'

let doc = Automerge.init()
doc = Automerge.change(doc, 'Add item', doc => {
  doc.items = []
  doc.items.push({ text: 'Hello', done: false })
})
```

**적합한 케이스:** 오프라인 우선 앱, 다중 언어 환경

### diffsync

[diffsync](https://github.com/janmonschke/diffsync)는 Differential Synchronization 알고리즘을 socket.io 기반으로 구현한 라이브러리입니다.

**특징:**
- JSON 객체의 실시간 협업 편집
- 변경 사항만 전송하는 효율적인 동기화
- socket.io 기반 간편한 설정

**적합한 케이스:** 단순한 JSON 상태 동기화가 필요한 프로젝트

---

## 2. 관리형 실시간 플랫폼

### Liveblocks

[Liveblocks](https://liveblocks.io/)는 Yjs를 기반으로 완전 관리형 협업 인프라를 제공합니다.

**특징:**
- 호스팅된 WebSocket 인프라 및 저장소
- Webhook 이벤트, REST API
- 브라우저 DevTools 확장
- Presence (커서 위치, 사용자 상태) 기능 내장

```javascript
import { createClient } from '@liveblocks/client'

const client = createClient({ publicApiKey: 'pk_xxx' })

const { room } = client.enterRoom('my-room', {
  initialPresence: { cursor: null }
})

room.subscribe('storage', (storage) => {
  // 자동 동기화된 상태
})
```

**적합한 케이스:** 빠른 개발이 필요한 협업 기능, 인프라 관리를 원하지 않는 팀

### Convex

[Convex](https://www.convex.dev/)는 오픈소스 reactive 데이터베이스로, 쿼리 의존성을 자동 추적하여 변경 시 구독자에게 푸시합니다.

**특징:**
- TypeScript로 서버 함수 작성
- 쿼리 의존성 자동 추적
- 변경 시 자동 구독 업데이트
- React 통합 최적화

```typescript
// convex/messages.ts
export const list = query({
  handler: async (ctx) => {
    return await ctx.db.query("messages").collect()
  },
})

// React 컴포넌트
const messages = useQuery(api.messages.list)
// messages가 DB에서 변경되면 자동 업데이트
```

**적합한 케이스:** 실시간 대시보드, 채팅, 알림 시스템

### PartyKit

[PartyKit](https://www.partykit.io/)은 Cloudflare Durable Objects 기반으로 전 세계 엣지에서 실시간 서버를 실행합니다.

**특징:**
- 전역 분산 (사용자 근처 데이터센터에서 실행)
- Yjs, Automerge, Replicache, XState, tldraw 등과 통합
- 간편한 room 기반 API

```typescript
// server.ts
export default class MyParty implements Party.Server {
  onConnect(conn: Party.Connection) {
    conn.send("Hello!")
  }
  onMessage(message: string, sender: Party.Connection) {
    this.room.broadcast(message)
  }
}
```

**적합한 케이스:** 멀티플레이어 게임, 글로벌 사용자를 위한 저지연 실시간 앱

### Vaultrice

[Vaultrice](https://www.vaultrice.com/)는 전역 분산 키-값 저장소로, localStorage 유사 API와 WebSocket 자동 동기화를 제공합니다.

**특징:**
- 익숙한 localStorage 스타일 API
- 탭, 기기, 도메인 간 자동 동기화
- Presence 감지 내장

**적합한 케이스:** 간단한 상태 동기화, 사용자 설정 공유

---

## 3. Redux + WebSocket 통합 패턴

Redux 생태계에서는 WebSocket을 미들웨어로 통합하는 패턴이 일반적입니다.

### 기본 패턴

```typescript
// websocketMiddleware.ts
const websocketMiddleware = (store) => {
  let socket = null

  return (next) => (action) => {
    switch (action.type) {
      case 'WS_CONNECT':
        socket = new WebSocket(action.payload.url)

        socket.onmessage = (event) => {
          const data = JSON.parse(event.data)
          store.dispatch({ type: 'WS_MESSAGE', payload: data })
        }
        break

      case 'WS_SEND':
        socket?.send(JSON.stringify(action.payload))
        break
    }

    return next(action)
  }
}
```

**문제점:**
- 보일러플레이트가 많음
- 메시지 타입별 핸들링 로직 직접 구현 필요
- 클라이언트-서버 간 어떤 action이 전송되는지 명시적이지 않음
- 동기화 충돌 처리 수동 구현

### Redux-Saga + WebSocket

[Redux-Saga](https://redux-saga.js.org/)의 channel을 사용한 실시간 이벤트 처리:

```typescript
function* watchMessages() {
  const channel = yield call(createWebSocketChannel, socket)

  while (true) {
    const message = yield take(channel)
    yield put({ type: 'MESSAGE_RECEIVED', payload: message })
  }
}
```

**장점:** 복잡한 비동기 흐름 제어
**단점:** 학습 곡선, 추가 추상화 레이어

---

## 4. 동기화 전용 라이브러리

### Replicache

[Replicache](https://replicache.dev/)는 클라이언트 측 동기화 라이브러리로, optimistic update와 서버와의 eventual consistency를 제공합니다.

**특징:**
- 즉각적인 로컬 mutation
- 백그라운드 서버 동기화
- 충돌 자동 해결

```typescript
const rep = new Replicache({
  mutators: {
    async createTodo(tx, todo) {
      await tx.put(`todo/${todo.id}`, todo)
    }
  }
})

// 즉시 로컬에 반영, 서버와 비동기 동기화
await rep.mutate.createTodo({ id: '1', text: 'Hello' })
```

**적합한 케이스:** 오프라인 지원이 필요한 협업 앱

### TinyBase

[TinyBase](https://tinybase.org/)는 경량 reactive 데이터 스토어로, PartyKit과 통합하여 실시간 동기화를 지원합니다.

**적합한 케이스:** 작은 규모의 실시간 앱

---

## 5. flowdux remote의 포지셔닝

### 기존 솔루션들의 한계

| 카테고리 | 대표 라이브러리 | 한계 |
|----------|----------------|------|
| CRDT | Yjs, Automerge | Operation 기반, Action/Reducer 패턴 아님 |
| 관리형 | Liveblocks, Convex | 벤더 종속, 커스터마이징 제한 |
| Redux+WS | 미들웨어 직접 구현 | 보일러플레이트, 타입 안전성 부족 |

### flowdux remote의 차별점

**1. 선언적 Action 라우팅**

```kotlin
// SharedAction 마커로 어떤 action이 동기화되는지 명시
sealed interface GameAction : Action {
    // 클라이언트 → 서버
    data class Move(val x: Int, val y: Int) : GameAction, ServerSharedAction

    // 서버 → 클라이언트
    data class StateSync(val state: GameState) : GameAction, ClientSharedAction

    // 로컬 전용 (동기화 안 됨)
    data object ToggleSound : GameAction
}
```

`ServerSharedAction`은 클라이언트에서 서버로, `ClientSharedAction`은 서버에서 클라이언트로 자동 전송됩니다. 마커 인터페이스만 붙이면 미들웨어가 알아서 라우팅합니다.

**2. 동일한 Reducer 로직 공유**

```kotlin
// 클라이언트와 서버에서 동일한 reducer 사용
val gameReducer = reducerBuilder<GameState, GameAction> {
    on<Move> { state, action ->
        state.copy(playerX = action.x, playerY = action.y)
    }
    on<StateSync> { _, action ->
        action.state  // 서버 상태로 덮어쓰기
    }
}
```

Kotlin Multiplatform으로 reducer 로직을 공유하면 클라이언트-서버 간 상태 일관성이 보장됩니다.

**3. 타입 안전한 직렬화**

```kotlin
// ActionCodec으로 타입 안전한 직렬화
val codec = KotlinxSerializationActionCodec(
    GameAction.serializer(),
    json = Json { serializersModule = gameModule }
)

// TypedClientConnection이 자동으로 직렬화/역직렬화
val typedConnection = KtorTypedClientConnection(codec, wsSession)
```

**4. 미들웨어 체인 통합**

```kotlin
val clientStore = createStore(
    initialState = GameState(),
    reducer = gameReducer,
    middlewares = listOf(
        loggingMiddleware,
        ClientRemoteMiddleware(typedConnection),  // ServerSharedAction 가로채서 전송
        validationMiddleware
    )
)
```

기존 미들웨어 체인에 자연스럽게 통합됩니다.

### 비교 요약

| 기능 | flowdux | Yjs/Automerge | Liveblocks | Redux+WS |
|------|---------|---------------|------------|----------|
| Action 기반 상태 변경 | ✅ | ❌ (CRDT ops) | ❌ | ✅ |
| 선언적 동기화 라우팅 | ✅ | ❌ | 부분적 | ❌ |
| Reducer 패턴 | ✅ | ❌ | ❌ | ✅ |
| 타입 안전성 | ✅ (Kotlin) | 부분적 | 부분적 | 수동 |
| 서버 권위 모델 | ✅ | CRDT 자동 | CRDT 자동 | 수동 |
| 보일러플레이트 | 낮음 | 낮음 | 낮음 | 높음 |
| 셀프 호스팅 | ✅ | ✅ | ❌ | ✅ |

---

## 6. 언제 무엇을 선택할까?

### CRDT (Yjs, Automerge) 선택

- 텍스트 협업 편집 (문서, 코드)
- 완전한 P2P 오프라인 지원 필요
- 자동 충돌 해결이 중요

### 관리형 플랫폼 (Liveblocks, Convex) 선택

- 빠른 MVP 개발
- 인프라 관리 불필요
- 표준적인 협업 기능 (커서, presence)

### flowdux remote 선택

- Redux 스타일 아키텍처 선호
- Kotlin Multiplatform 프로젝트
- 서버 권위 모델 필요 (게임, 금융)
- 클라이언트-서버 로직 공유
- 셀프 호스팅 필요

---

## 결론

실시간 상태 동기화는 다양한 접근법이 있으며, 프로젝트 요구사항에 따라 선택이 달라집니다.

- **충돌 자동 해결**이 중요하면 → CRDT (Yjs, Automerge)
- **빠른 개발**이 중요하면 → 관리형 플랫폼 (Liveblocks, Convex)
- **서버 권위 + Redux 패턴**이 중요하면 → flowdux remote

flowdux remote는 "Redux 스타일 Action/Reducer 패턴을 유지하면서 클라이언트-서버 간 상태를 동기화하고 싶다"는 니치에 특화되어 있습니다. 마커 인터페이스 기반의 선언적 라우팅은 어떤 action이 네트워크를 타는지 코드만 봐도 명확하게 파악할 수 있게 해줍니다.

---

## 참고 자료

- [Yjs - GitHub](https://github.com/yjs/yjs)
- [Best CRDT Libraries 2025 | Velt](https://velt.dev/blog/best-crdt-libraries-real-time-data-sync)
- [diffsync - GitHub](https://github.com/janmonschke/diffsync)
- [Convex - Realtime](https://www.convex.dev/realtime)
- [PartyKit Docs](https://docs.partykit.io/how-partykit-works/)
- [WebSockets in Redux](https://www.taniarascia.com/websockets-in-redux/)
- [Replicache vs Yjs Discussion](https://github.com/rocicorp/replicache/discussions/1001)

---

*flowdux는 Kotlin Multiplatform을 위한 경량 Redux 스타일 상태 관리 라이브러리입니다. [GitHub](https://github.com/chibimoons/flowdux)에서 확인하세요.*

```kotlin
implementation("com.github.chibimoons.flowdux:flowdux:1.4.0")
implementation("com.github.chibimoons.flowdux:flowdux-remote-client:1.4.0")
implementation("com.github.chibimoons.flowdux:flowdux-remote-server:1.4.0")
```

---

**태그:** #Kotlin #KotlinMultiplatform #상태관리 #Redux #WebSocket #실시간동기화 #CRDT #멀티플레이어
