# flowdux-remote: 포지셔닝

> 설계 문서 — 2026-01-29 (updated 2026-01-30)

## REST와의 관계

flowdux-remote는 REST의 보완재가 아니라, 연결 수립 이후 앱 로직 통신의 대체재다.

### REST가 설계하는 것

- URL 경로 (`/api/v1/chat/messages`)
- HTTP method (`GET`, `POST`, `PUT`, `DELETE`)
- Status code (`200`, `201`, `400`, `404`, `409`, `500`)
- Request DTO, Response DTO
- 인증 헤더, 페이지네이션 쿼리 파라미터
- 실시간이 필요하면 별도 WebSocket/SSE 설계

### flowdux-remote가 설계하는 것

- Action (사용자가 무엇을 하는가)
- State (그 결과 상태가 어떻게 바뀌는가)

실시간 반응성은 자동으로 따라온다.

### 1:1 대응

```
POST /chat/join  {user: "Alice"}    →   dispatch(JoinRoom("Alice"))
         ↓                                       ↓ WebSocket
Response 200 {state: ...}           ←   SyncState(chatState)

GET  /chat/messages                 →   (불필요. 이미 State에 있음)
DELETE /chat/leave {user: "Alice"}  →   dispatch(LeaveRoom("Alice"))
```

shared 모듈의 sealed interface 하나가 API 스펙 전체를 대체한다.

### HTTP가 여전히 필요한 영역

파일 업/다운로드 등 HTTP 고유 기능은 HTTP로 처리한다. 이건 패턴의 한계가 아니라 용도가 다른 것이다.

### 흔히 제기되는 우려에 대해

**"로드밸런싱이 복잡하다"** — 아니다. ALB, Nginx, Envoy 등 대부분의 로드밸런서가 WebSocket을 기본 지원한다. 연결이 맺어지면 유지되는 것뿐이다.

**"Request-Response 1:1 매핑이 안 된다"** — REST도 마찬가지다. 응답이 돌아오는 사이에 다른 클라이언트가 상태를 바꿨을 수 있다. Action/State 모델은 오히려 항상 최신 상태를 가진다.

**"에러 핸들링에 표준이 없다"** — REST의 4xx/5xx도 실제로는 부족해서 모든 API가 자체 에러 포맷을 정의한다. 에러를 명시적으로 모델링하는 작업량은 동일하다.

**"캐싱을 못 한다"** — push 모델에서는 서버가 변경 시에만 State를 내려보내므로 중복 요청 자체가 없다. 캐싱이 해결하려는 문제가 존재하지 않는다.

---

## WebSocket 영역에서의 차별점

### 현재 WebSocket 개발의 고통

- 메시지 포맷 직접 설계 (JSON 스키마, 타입 구분)
- 양방향 상태 동기화 직접 구현
- 클라이언트/서버 코드가 완전히 분리되어 계약이 깨지기 쉬움
- 재연결, 상태 복구 등 인프라 코드가 비즈니스 로직보다 많음

### flowdux-remote가 이미 해결하고 있는 것

- **타입 안전한 와이어 계약**: 공유 액션을 `sealed interface`로 정의하고, `ServerSharedAction`/`ClientSharedAction` 마커로 방향을 명시하여 컴파일 타임에 계약 보장
- **방향성이 타입에 녹아있음**: `ServerSharedAction`/`ClientSharedAction` 마커로 어떤 액션이 어느 방향인지 명확
- **인프라 분리**: 미들웨어가 직렬화/라우팅 처리, 개발자는 비즈니스 로직만 작성
- **선택적 상태 동기화**: `serve`로 서버 State에서 필요한 것만 골라 클라이언트에 전송

### 핵심 모델의 차별화

"액션 기반 양방향 프로토콜 + 타입 안전한 공유 계약 + 미들웨어 파이프라인"

이 조합은 Socket.IO나 Ktor raw WebSocket에는 없는 구조적 장점이다.

#### 비교

| | Socket.IO | Ktor WebSocket | flowdux-remote |
|---|---|---|---|
| 타입 안전 계약 | X (문자열 이벤트) | X (raw Frame) | O (sealed interface) |
| 방향성 마커 | X | X | O (ServerShared/ClientShared) |
| 상태 동기화 | 직접 구현 | 직접 구현 | `serve` 한 줄 |
| 미들웨어 파이프라인 | X | X | O (ActionProcessor) |
| 비즈니스 로직 분리 | 낮음 | 낮음 | 높음 |

---

## 현재 상태와 로드맵

### 현재 위치

핵심 모델은 설득력 있고, 단일 세션에서는 이미 동작하는 단계.

구현 완료:
- `ServerRemoteMiddleware` / `ClientRemoteMiddleware`
- `TypedServerConnection` / `TypedClientConnection`
- `ActionCodec` + `MessageCodec` (직렬화 계층)
- `serveState` (서버→클라이언트 상태 동기화)
- 모듈별 독립 State (서버/클라이언트 각각 로컬 State + 선택적 동기화)
- KMP 지원 (Kotlin Multiplatform)

### 게임체인저가 되기 위해 필요한 것

- [ ] 재연결 시 자동 상태 복구
- [ ] 멀티 클라이언트 지원 (현재는 1 connection = 1 store)
- [ ] Room/Channel 추상화
- [ ] 상태 diff 전송 (전체 State 대신 변경분만)
- [ ] 브라우저 클라이언트 (JS/TS SDK)
- [x] boilerplate 감소 (`Store.serve()` — StartListening 내부화, use/serve 패턴)
