# 서버가 클라이언트에 Action을 Dispatch한다면?

실시간 채팅 앱을 만든다고 가정해봅시다. 서버에서 "새 메시지가 도착했어"를 클라이언트에 어떻게 전달할까요? REST API 폴링? WebSocket으로 JSON 전송? 그리고 클라이언트는 그 데이터를 받아서 어떻게 상태를 업데이트할까요?

이 글에서는 기존 접근법들을 살펴보고, 조금 다른 방식을 소개합니다.

## 기존 접근법들

서버가 클라이언트를 제어하는 패턴은 이미 다양하게 존재합니다.

**Server-Driven UI (SDUI)** — Airbnb, Shopify 등에서 사용. 서버가 UI 구조(컴포넌트 트리, 레이아웃)를 JSON으로 전송하면 클라이언트가 렌더링합니다. 앱 업데이트 없이 UI를 변경할 수 있어 강력하지만, 클라이언트가 단순 렌더러가 됩니다.

**HTML-over-the-Wire** — Phoenix LiveView, Laravel Livewire, HTMX 등. 서버가 HTML 조각을 직접 보내 DOM을 업데이트합니다. 프론트엔드 복잡도를 크게 줄여주지만, 서버 연결이 필수입니다.

**Server-Side Redux** — 서버에 Redux Store를 두고, 상태 변경 시 새 State를 클라이언트에 동기화합니다. 상태 일관성이 보장되지만, 클라이언트 자체 로직이 제한됩니다.

이들의 공통점은 서버가 **"결과"**(UI 구조, HTML, State)를 전송한다는 것입니다. 클라이언트는 받은 결과를 그대로 반영합니다.

## 다른 접근: Action을 전송한다

만약 서버가 결과 대신 **"명령"**을 보내면 어떨까요? 서버가 **"이렇게 보여줘"**가 아니라 **"이 Action을 dispatch해"**라고 전송합니다.

```
┌────────────┐                    ┌────────────┐
│   Server   │  ── Action ──▶    │   Client   │
│   Store    │  ◀── Action ──    │   Store    │
└────────────┘                    └────────────┘
```

양쪽 모두 독립적인 Store를 가지고, 서로 Action을 주고받습니다.

```kotlin
// shared 모듈에 정의된 Action
sealed interface ChatAction {
    // Client → Server (ServerSharedAction을 구현하면 서버로 전송됨)
    data class SendMessage(val text: String) : ChatAction, ServerSharedAction

    // Server → Client (ClientSharedAction을 구현하면 클라이언트로 전송됨)
    data class SyncState(val messages: List<Message>) : ChatAction, ClientSharedAction
    data class UserKicked(val reason: String) : ChatAction, ClientSharedAction
}
```

서버에서 `store.dispatch(UserKicked("스팸"))`을 호출하면, 이 Action이 WebSocket을 통해 클라이언트로 전송되고, 클라이언트의 Reducer가 이를 처리합니다.

## 차이점

| | 기존 패턴 | Action-Driven |
|---|----------|---------------|
| **전송** | 결과 (UI/State) | 명령 (Action) |
| **클라이언트** | 렌더러 | 자체 Store 보유 |
| **오프라인** | 동작 불가 | 로컬 Action 동작 |
| **타입** | 별도 스키마 | 컴파일 타임 검증 |

핵심은 클라이언트가 **"dumb renderer"가 아니라 자체 로직을 가진 독립적인 애플리케이션**이라는 점입니다.

서버 연결이 끊겨도 로컬 Action(UI 토글, 입력 처리 등)은 계속 동작하고, 재연결 시 서버와 동기화됩니다.

## 언제 유용한가

- 클라이언트에 복잡한 로컬 로직이 필요할 때 (예: 오프라인 편집, 낙관적 업데이트)
- 오프라인 지원이 중요할 때
- 서버-클라이언트 간 타입 안전성이 필요할 때
- 멀티플랫폼(Android, iOS, Web)에서 동일한 로직을 공유할 때 (Kotlin Multiplatform)

## 언제 다른 걸 써야 하나

- **단순한 CRUD 앱** → REST API + 클라이언트 상태관리로 충분
- **서버 중심 앱, SEO 중요** → LiveView, HTMX가 더 간단
- **빠른 프로토타이핑** → Livewire, LiveView가 생산성 높음

모든 상황에 맞는 은탄환은 없습니다. 요구사항에 맞는 도구를 선택하세요.

## 참고

- [Airbnb Server-Driven UI](https://www.infoq.com/news/2021/07/airbnb-server-driven-ui/)
- [Phoenix LiveView](https://github.com/phoenixframework/phoenix_live_view)
- [HTMX - Hypermedia-Driven Applications](https://htmx.org/essays/hypermedia-driven-applications/)
- [flowdux-remote 샘플](https://github.com/chibimoons/flowdux/tree/main/kotlin/samples/flowdux-remote)
