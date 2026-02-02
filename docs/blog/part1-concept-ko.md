# REST API를 설계하지 않아도 된다면?

*Action/State 모델로 클라이언트-서버 통신을 다시 생각하다*

*3부작 시리즈의 Part 1입니다. [Part 2: REST 없이 실시간 채팅 만들기 →](#) | [Part 3: Action/State 스케일링 →](#)*

> **안내:** 이 시리즈는 *개념 제안*입니다. 클라이언트-서버 통신의 대안 패턴으로서 Action/State 모델을 소개합니다 — 모든 상황에서 REST를 대체하자는 주장이 아닙니다. 아키텍처 아이디어를 공유하고 토론을 이끌어내는 것이 목적입니다.

---

새 기능이 생기면 새 API 엔드포인트를 만듭니다. 에디터를 열면 결정의 연속이 시작됩니다:

- URL을 뭘로 하지? `/api/v1/chat/messages`? `/api/v1/messages`?
- `POST`? `PUT`?
- 상태 코드는? `200`? `201`? 충돌이면 `409`?
- 요청 바디에 뭘 넣지? 응답은?
- 페이지네이션은? 필터링? 정렬 쿼리 파라미터?
- 아, 실시간도 필요하네 — WebSocket 채널을 별도로 추가해야겠다.

이걸 앱의 모든 기능에 곱합니다. 하나하나의 결정이 어려운 건 아닙니다. 하지만 너무 *많고*, 전부 누적됩니다. URL 설계, HTTP 메서드 시맨틱, 상태 코드 분류, DTO 버저닝, 인증 헤더, 콘텐츠 네고시에이션 — 본질적으로 "클라이언트가 뭔가를 하고 싶고, 서버가 응답해야 한다"는 것에 대해 하나의 학문을 만들어 버린 셈입니다.

이 모든 걸 걷어내고, 의미하는 바를 그대로 말하면 어떨까요?

---

## 제안: Action과 State

아이디어는 이겁니다. REST 엔드포인트를 설계하는 대신, 두 가지만 설계합니다:

1. **Action** — 사용자가 무엇을 할 수 있는가?
2. **State** — 그 결과 세상이 어떻게 바뀌는가?

끝입니다. URL 없음. HTTP 메서드 없음. 상태 코드 없음. 요청/응답 DTO 없음. 액션과 상태만.

클라이언트가 액션을 *디스패치*합니다. 서버가 처리하고, 상태를 업데이트하고, 새 상태를 *푸시*합니다. 단일 지속 WebSocket 연결 위에서.

구체적으로 봅시다. 채팅 앱을 만든다고 할 때, REST 방식은 이렇습니다:

```
POST   /api/v1/chat/join       { "user": "Alice" }     → 200 { state }
GET    /api/v1/chat/messages                            → 200 { messages: [...] }
POST   /api/v1/chat/messages   { "user": "Alice", "text": "Hello" }  → 201 { message }
DELETE /api/v1/chat/leave       { "user": "Alice" }     → 200 { }
```

4개의 엔드포인트, 각각 고유한 URL, 메서드, 요청 형태, 응답 형태가 있습니다. 여기에 *다른* 사용자가 메시지를 보낼 때 실시간 업데이트도 필요하다면 — WebSocket이나 SSE 채널을 덧붙여야 합니다. 설계하고 유지해야 할 다섯 번째 항목이 생깁니다.

같은 기능을 Action/State로 하면:

```
dispatch(JoinRoom("Alice"))               →  상태 업데이트 푸시
dispatch(SendMessage("Alice", "Hello"))   →  상태 업데이트 푸시
dispatch(LeaveRoom("Alice"))              →  상태 업데이트 푸시

GET /chat/messages?                       →  불필요 — 이미 State에 있음
```

클라이언트가 액션을 디스패치합니다. 서버가 처리하고 업데이트된 상태를 푸시합니다. 다른 사용자에 대한 실시간 업데이트? 이미 일어나고 있습니다 — 모든 상태 변경이 연결된 모든 클라이언트에 푸시됩니다. 별도의 실시간 채널을 설계할 필요가 없습니다.

---

## API 스펙이 인터페이스 하나

REST에서 API 계약은 URL 패턴, OpenAPI 스펙, 요청/응답 타입, 에러 스키마 등에 흩어져 있습니다. Action/State 모델에서는 전체 계약이 한 곳에 들어갑니다:

```kotlin
// Action — 사용자가 무엇을 할 수 있는가
@Serializable
sealed interface SharedChatAction {
    // Client → Server
    @Serializable
    data class JoinRoom(val user: String) : SharedChatAction, ServerSharedAction

    @Serializable
    data class LeaveRoom(val user: String) : SharedChatAction, ServerSharedAction

    @Serializable
    data class SendMessage(val user: String, val text: String) : SharedChatAction, ServerSharedAction

    // Server → Client
    @Serializable
    data class SyncState(val state: ChatState) : SharedChatAction, ClientSharedAction
}

// State — 세상이 어떻게 생겼는가
@Serializable
data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val users: Set<String> = emptySet(),
    val lastEvent: ChatEvent? = null,
)

@Serializable
data class ChatMessage(val user: String, val text: String)

@Serializable
sealed interface ChatEvent {
    @Serializable data class UserJoined(val user: String) : ChatEvent
    @Serializable data class UserLeft(val user: String) : ChatEvent
    @Serializable data class MessageReceived(val user: String, val text: String) : ChatEvent
}
```

이것이 API 스펙 전체입니다. Action은 사용자가 무엇을 할 수 있는지를, State는 그 결과 세상이 어떻게 바뀌는지를 정의합니다. 클라이언트가 보낼 수 있는 모든 액션은 `ServerSharedAction`이고, 서버가 돌려보내는 모든 응답은 `ClientSharedAction`입니다. 방향이 타입에 인코딩되어 있습니다. 클라이언트와 서버 사이의 공유 모듈이 계약을 컴파일 타임에 보장합니다 — 문서화 시점도, 통합 테스트 시점도 아닌.

액션 필드 이름을 바꾸면 클라이언트가 업데이트될 때까지 컴파일이 안 됩니다. OpenAPI 스펙으로 이걸 얻어 보세요.

---

## 자연스럽게 따라오는 세 가지 이점

### 1. 실시간 반응성은 부산물이지, 기능이 아니다

REST에서 실시간은 항상 추가 작업입니다. 엔드포인트를 설계한 다음, 라이브 업데이트가 필요하다는 걸 깨닫고, 그 위에 WebSocket이나 SSE나 폴링을 얹습니다. 자체 메시지 포맷과 연결 라이프사이클을 가진 두 번째 통신 채널입니다.

지속 연결 위의 Action/State에서는 실시간이 기본 동작입니다. 클라이언트 A가 `SendMessage`를 디스패치하면, 서버가 상태를 업데이트하고, 새 상태가 *모든* 연결된 클라이언트에 푸시됩니다 — 아무것도 요청하지 않은 클라이언트 B에게도. 폴링이 없습니다. 별도의 구독이 없습니다. 요청-응답을 처리하는 것과 같은 메커니즘이 브로드캐스트도 처리합니다.

```
  클라이언트 A                  서버                    클라이언트 B
     │                          │                          │
     │── SendMessage("Hi") ───→│                          │
     │                          │── 상태 업데이트 ────────→│
     │←── SyncState(newState) ──│                          │
```

### 2. 양쪽의 대칭적 데이터 흐름

클라이언트와 서버 모두 같은 단방향 패턴을 따릅니다:

```
dispatch(action) → 미들웨어 파이프라인 → 리듀서 → 새로운 상태
```

서버는 액션을 받아서 미들웨어(검증, 비즈니스 로직, 영속화)를 통과시키고, 상태를 리듀스하고, 동기화합니다. 클라이언트는 동기화된 상태를 받아서 리듀서를 통과시키고 UI를 업데이트합니다.

이 구조적 대칭은 양쪽을 같은 방식으로 이해할 수 있게 합니다. 서버 로직은 미들웨어 파이프라인입니다. 클라이언트 로직도 미들웨어 파이프라인입니다. 멘탈 모델이 전이됩니다.

### 3. 대폭 줄어든 설계 표면

REST는 기능 하나당 많은 설계 질문에 답하게 만듭니다:

| REST | Action/State |
|------|-------------|
| URL 경로 설계 | *(불필요)* |
| HTTP 메서드 선택 | *(불필요)* |
| 상태 코드 매핑 | *(불필요)* |
| 요청 DTO | 액션 필드 |
| 응답 DTO | 상태 형태 |
| 페이지네이션 파라미터 | *(상태에 데이터 포함)* |
| 인증 헤더 체계 | *(미들웨어가 처리)* |
| 실시간 채널 | *(같은 채널)* |

Action(사용자가 하는 것)과 State(결과의 모습)를 설계하면 됩니다. 나머지는 인프라가 처리합니다.

---

## 자주 제기되는 우려에 대해

가장 자주 듣는 반론입니다. 솔직하게 답합니다.

### "WebSocket 로드밸런싱이 복잡하다"

그렇지 않습니다. ALB, Nginx, Envoy 모두 WebSocket 연결을 네이티브로 지원합니다. 연결이 맺어지면 유지됩니다 — 이걸 스티키 세션이라고 하는데, 모든 주요 로드밸런서가 기본으로 처리합니다. 운영 오버헤드는 HTTP keep-alive 연결 관리와 비슷한 수준입니다.

### "요청-응답 1:1 대응이 안 된다"

REST가 실제로 여기서 무엇을 주는지 생각해 봅시다. `POST /join`을 보내고 응답이 돌아옵니다. 하지만 요청과 응답 사이에 다른 클라이언트가 상태를 바꿨을 수 있습니다. 응답은 당신의 액션만이 아니라 그 중간 변경까지 *모두* 반영된 상태입니다.

Action/State 모델은 오히려 이에 대해 더 정직합니다. 받는 상태는 다른 클라이언트의 액션을 포함해 *일어난 모든 것*을 반영합니다. 당신의 요청 결과인 척하는 오래된 스냅샷을 보는 일이 없습니다.

### "에러 핸들링 표준이 없다"

REST에는 상태 코드가 있습니다 — `400`, `404`, `500`. 하지만 실제로는 모든 API가 커스텀 에러 응답 바디를 따로 정의합니다: `{ "code": "INVALID_NAME", "message": "..." }`. `400 Bad Request` 상태 코드만으로는 절대 충분하지 않습니다. 에러를 명시적으로 모델링하는 작업량은 양쪽 다 동일합니다.

Action/State 모델에서 에러는 나머지와 같은 방식으로 모델링됩니다 — 상태의 일부로, 또는 명시적인 에러 액션으로:

```kotlin
// 방법 1: 상태에 에러 포함
data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val error: ChatError? = null,  // 에러 없으면 null
)

// 방법 2: 에러를 액션으로
data class ActionFailed(
    val originalAction: SharedChatAction,
    val reason: String
) : SharedChatAction, ClientSharedAction
```

### "파일 업로드는? 캐싱은?"

파일 업로드는 HTTP로 하면 됩니다. 이건 패턴의 한계가 아닙니다 — 대용량 바이너리 전송에는 HTTP가 진짜로 더 낫기 때문입니다. 파일은 HTTP로, 앱 로직은 Action/State로. 둘은 자연스럽게 공존합니다.

캐싱은요: 캐싱은 중복 요청을 피하기 위해 존재합니다. 푸시 모델에서는 서버가 변경이 있을 때만 상태 업데이트를 보냅니다. 캐싱할 중복 요청 자체가 없습니다. 캐싱이 해결하려는 문제가 존재하지 않습니다.

---

## 이 패턴이 맞을 때 — 그리고 안 맞을 때

경계에 대해 솔직해집시다.

**잘 맞는 경우:**
- 사용자가 상호작용하고 상태가 자주 변하는 앱 (채팅, 협업, 게임, 대시보드)
- 어차피 결국 실시간이 필요할 시나리오
- 지속 연결이 가능한 앱 (모바일 앱, SPA, 데스크톱 앱)
- 프로토타이핑 — API 설계 단계를 통째로 건너뛰고 바로 "사용자가 뭘 하나?"로 갈 수 있음

**안 맞는 경우:**
- 서드파티가 소비하는 공개 API (REST의 발견성과 도구 생태계가 우세)
- 실시간 필요 없는 단순 CRUD + 다양한 컨슈머
- WebSocket 연결 유지가 불가한 환경 (일부 기업 프록시, WebSocket 미지원 서버리스 플랫폼)
- 대부분의 트래픽이 바이너리인 파일 중심 워크플로우

이것은 REST의 보편적 대체재가 아닙니다. 지속 연결이 가능한 애플리케이션에서 대량의 설계 결정을 제거하는 대안 패러다임입니다.

---

## 참조 구현

우리는 이 패턴을 Kotlin으로 구현한 [flowdux-remote](https://github.com/user/flowdux)라는 라이브러리를 만들었습니다. 제공하는 것:

- **타입 안전한 와이어 계약** — `sealed interface`와 방향 마커(`ServerSharedAction` / `ClientSharedAction`)
- **자동 상태 동기화** — 서버에서 `serve {}`를 호출하면 상태 변경이 클라이언트에 푸시
- **양쪽의 미들웨어 파이프라인** — 관심사의 깔끔한 분리
- **Kotlin Multiplatform** 지원 (Android, iOS, 데스크톱, 서버)

위의 코드 예제(`SharedChatAction` 인터페이스)는 실제 flowdux-remote 코드입니다. 이 시리즈의 Part 2에서 이걸로 완전한 채팅 앱을 만들어 보겠습니다.

하지만 패턴 자체는 프레임워크에 종속되지 않습니다. 어떤 언어의 로우 WebSocket으로도 구현할 수 있습니다. 핵심 인사이트는 아키텍처적입니다: **엔드포인트와 DTO가 아닌, 액션과 상태를 설계하라.**

---

## 사고의 전환

여기서 가장 깊은 변화는 기술적인 것이 아닙니다 — 문제를 바라보는 방식입니다.

```
REST 사고 모델:
  "어떤 엔드포인트가 필요하지?" → "어떤 HTTP 메서드?" → "응답 포맷은?"
  → "아, 실시간도 필요한데..." → 또 다른 채널 설계

Action/State 사고 모델:
  "사용자가 뭘 하지?" → 그게 Action
  "결과로 뭐가 바뀌지?" → 그게 State
  (실시간은 자동으로 따라온다)
```

REST는 자원 지향적입니다: *명사*(users, messages, rooms)를 모델링하고 *동사*(GET, POST, DELETE)를 적용합니다. Action/State 모델은 행위 지향적입니다: *무엇이 일어나는가*와 *그 결과 무엇이 되는가*를 모델링합니다.

어느 쪽이 보편적으로 우월하지는 않습니다. 하지만 인터랙티브한 애플리케이션 — 여러 사용자가 공유 상태에 실시간으로 영향을 미치는 종류 — 에서는 Action/State 모델이 REST가 관리하게 만드는 부수적 복잡성의 한 레이어 전체를 제거합니다.

---

## 다음 편에서

**Part 2**에서는 이 패턴으로 실시간 채팅 앱을 처음부터 만듭니다. REST 엔드포인트 없이. 실시간을 위한 별도 WebSocket 채널 없이. 액션, 상태, 그리고 양쪽의 미들웨어 파이프라인만으로. 결과 코드를 전통적 REST 구현과 나란히 비교합니다.

**Part 3**에서는 패턴을 극한까지 밀어봅니다: 다중 Room, 플레이어별 상태 뷰, 게임 서버를 위한 틱 기반 배칭, 서버 인스턴스 간 수평 확장.

---

*이 아이디어가 공감되든, 완전히 틀렸다고 생각하든 — 의견을 듣고 싶습니다. 최고의 패턴은 솔직한 토론에서 나옵니다.*

*Part 2, 3은 [Medium에서 팔로우](#)해 주세요.*
