# Action/State 모델로 REST 대체

> 아이디어 문서 -- 2026-01-30

## 핵심 아이디어

WebSocket 연결이 수립된 이후, 클라이언트-서버 간 모든 앱 로직 통신을 Action dispatch(요청)와 State sync(응답)로 처리한다. REST endpoint 설계 없이 Action과 State만 설계하면 된다.

## REST와의 1:1 대응

```
REST                                    Action/State
─────────────────────────────────────────────────────────────
POST /chat/join  {user: "Alice"}    →   dispatch(JoinRoom("Alice"))
         ↓                                       ↓ WebSocket
Response 200 {state: ...}           ←   SyncState(chatState)

GET  /chat/messages                 →   (불필요. 이미 State에 있음)
DELETE /chat/leave {user: "Alice"}  →   dispatch(LeaveRoom("Alice"))
```

REST에서는 요청마다 endpoint URL, HTTP method, status code, request/response DTO를 각각 설계한다. Action/State 모델에서는 shared 모듈의 sealed interface 하나가 이 전체를 대체한다.

```kotlin
// 이것이 API 스펙 전체다
sealed interface SharedChatAction : ChatAction {
    data class JoinRoom(val user: String) : SharedChatAction, ServerSharedAction
    data class LeaveRoom(val user: String) : SharedChatAction, ServerSharedAction
    data class SendMessage(val user: String, val text: String) : SharedChatAction, ServerSharedAction
    data class SyncState(val state: ChatState) : SharedChatAction, ClientSharedAction
}
```

## 왜 가능한가

### 실시간 반응성이 부산물

REST에서 실시간성이 필요하면 polling, SSE, WebSocket을 별도로 붙여야 한다. Action/State 모델에서는 서버 상태 변경이 곧 클라이언트 상태 변경이다. 다른 클라이언트가 `SendMessage`를 보내서 서버 상태가 바뀌면, 내 클라이언트에도 `SyncState`가 자동으로 내려온다. 추가 구현이 없다.

### 단방향 데이터 흐름 유지

양쪽 모두 `dispatch -> middleware -> reducer -> state` 파이프라인이 동일하다. 서버 코드와 클라이언트 코드의 구조적 대칭성이 유지된다.

### 설계 단순화

REST API를 설계할 때 결정해야 하는 것들:

- URL 경로 (`/api/v1/chat/messages`)
- HTTP method (`GET`, `POST`, `PUT`, `DELETE`)
- Status code (`200`, `201`, `400`, `404`, `409`, `500`)
- Request DTO, Response DTO
- 인증 헤더, 페이지네이션 쿼리 파라미터
- 실시간이 필요하면 별도 WebSocket/SSE 설계

Action/State 모델에서 결정해야 하는 것들:

- Action (무엇을 할 것인가)
- State (결과가 무엇인가)

## REST와 비교할 때 흔히 제기되는 우려에 대해

### "로드밸런싱이 복잡하다"

아니다. sticky session 자체가 복잡한 게 아니다. ALB, Nginx, Envoy 등 대부분의 로드밸런서가 WebSocket을 기본 지원하고, 연결이 맺어지면 유지되는 것뿐이다.

### "Request-Response 1:1 매핑이 안 된다"

REST도 마찬가지다. `POST /join` 응답이 돌아오는 사이에 다른 클라이언트가 상태를 바꿨을 수 있고, 응답에는 그게 반영되어 있다. Action/State 모델은 오히려 중간 상태 변경까지 실시간으로 받기 때문에 클라이언트가 항상 최신 상태를 가진다.

### "에러 핸들링에 표준이 없다"

REST의 4xx/5xx가 표준화되어 있지만, 실제로는 모든 API가 자체 에러 포맷을 정의한다. `400 Bad Request`만으로는 부족하니까 `{ "code": "INVALID_NAME", "message": "..." }` 같은 응답 본문을 설계하게 된다. 에러를 명시적으로 모델링하는 작업량은 동일하다. Action/State 모델에서는 State에 에러 필드를 두거나 에러 Action을 정의하면 된다.

### "파일 업/다운로드, 캐싱은?"

파일 전송은 HTTP로 하면 된다. 앱 로직은 Action/State, 파일만 HTTP -- 이건 패턴의 한계가 아니라 용도가 다른 것이다. 캐싱은 "같은 요청을 반복하지 않기 위한 것"인데, push 모델에서는 서버가 변경 시에만 State를 내려보내므로 중복 요청 자체가 없다.

## 사고 모델의 전환

이 패턴의 핵심은 "API를 설계한다"에서 "Action과 State를 설계한다"로의 전환이다.

```
REST 사고 모델:
  "어떤 endpoint를 만들까?" → "어떤 method를 쓸까?" → "응답 포맷은?" → "실시간도 필요한데..."

Action/State 사고 모델:
  "사용자가 무엇을 하는가?" → Action
  "그 결과 상태가 어떻게 바뀌는가?" → State
  (실시간 반응성은 자동으로 따라온다)
```

## 적용 조건

- WebSocket 연결을 유지할 수 있는 환경 (모바일 앱, SPA, 데스크톱 앱)
- 앱 로직 중심의 통신 (CRUD 포함)
- 파일 전송 등 HTTP 고유 기능은 별도로 처리 가능

## positioning.md와의 관계

기존 `flowdux-remote-positioning.md`는 flowdux-remote를 "실시간 양방향 상태 동기화" 도구로 포지셔닝하고, REST와의 "공존 모델"을 제안했다. 이 문서는 그 전제를 재검토한다 -- 연결이 수립된 이후라면, 공존이 아니라 대체가 가능하다.
