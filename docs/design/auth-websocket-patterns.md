# Authenticated WebSocket Connection Patterns

## 배경

flowdux-remote-auth 모듈의 서버 사이드 API가 `when` 분기로 인해 중첩이 깊어지는 문제를 개선하기 위해,
주요 프레임워크들의 인증된 WebSocket 연결 패턴을 조사하고 비교 분석한다.

---

## 프레임워크별 패턴

### 1. Phoenix Channels (Elixir) — Gold Standard

`connect/3` 콜백에서 인증 → `{:ok, socket}` 또는 `:error` 반환.
인증된 identity는 `socket.assigns`에 저장되어 이후 모든 채널에서 사용.

```elixir
def connect(%{"token" => token}, socket, _connect_info) do
  case Phoenix.Token.verify(socket, "user socket", token) do
    {:ok, user_id} ->
      {:ok, assign(socket, :current_user, user_id)}
    {:error, _reason} ->
      :error
  end
end
```

- **Fail-fast**: `:error` 반환 시 연결 즉시 거부
- **Identity 전파**: `socket.assigns.current_user`로 모든 채널에서 접근
- **2단계 인증**: socket-level (`connect/3`) + channel-level (`join/3`)

### 2. Socket.IO (Node.js) — Middleware Pipeline

`io.use()` 미들웨어에서 인증. `next(Error)` 호출 시 연결 거부.

```javascript
io.use((socket, next) => {
    const token = socket.handshake.auth.token;
    try {
        const user = jwt.verify(token, JWT_SECRET);
        socket.data.user = user;
        next();
    } catch (err) {
        next(new Error("Authentication failed"));
    }
});

io.on("connection", (socket) => {
    const user = socket.data.user;  // 인증된 상태만 다룸
});
```

- **8–12줄**로 완결
- **핸들러는 인증된 상태만 다룸** — success/failure 분기 없음
- **Composable**: 여러 `io.use()` 체이닝 가능

### 3. Ktor (Kotlin) — Route-Level Authentication

`authenticate {}` 블록으로 라우트를 감싸면 HTTP upgrade 단계에서 인증.

```kotlin
install(Authentication) {
    bearer("auth-bearer") {
        authenticate { credential ->
            if (isValid(credential.token)) UserIdPrincipal("user") else null
        }
    }
}

routing {
    authenticate("auth-bearer") {
        webSocket("/chat") {
            val user = call.principal<UserIdPrincipal>()!!.name
            // 인증된 상태에서 바로 시작
        }
    }
}
```

- **투명**: 핸들러 코드에 인증 로직 없음
- **`call.principal<T>()`**로 identity 접근
- **제한**: HTTP upgrade 레벨만 지원, in-band WebSocket 인증은 미지원

### 4. Apollo GraphQL Subscriptions (graphql-ws)

`onConnect` 훅에서 gate-keeping, `context` 훅에서 identity 주입.

```typescript
useServer({
    schema,
    onConnect: async (ctx) => {
        const token = ctx.connectionParams?.authentication;
        if (!token || !await verifyToken(token)) return false;  // 4403 Forbidden
    },
    context: async (ctx) => {
        const user = await getUserFromToken(ctx.connectionParams?.authentication);
        return { user };
    },
}, wsServer);
```

- **gate-keeping과 context 빌딩 분리**
- **`return false`** 하나로 즉시 거부

### 5. SignalR (.NET) — Declarative Authorization

ASP.NET Core 미들웨어 + `[Authorize]` 어트리뷰트.

```csharp
[Authorize]
public class ChatHub : Hub {
    public async Task SendMessage(string message) {
        var userId = Context.UserIdentifier;
        // ...
    }
}
```

- **선언적**: `[Authorize]` 한 줄로 인증 적용
- **Hub/Method 레벨 세분화 가능**
- **WebSocket 토큰 전달**: query string → `OnMessageReceived` 이벤트에서 추출 (workaround)

### 6. Supabase Realtime

플랫폼 레벨에서 JWT 검증 + RLS 정책으로 선언적 인가.

```javascript
const channel = supabase.channel('room-1', {
    config: { private: true },
});
```

- **애플리케이션 코드 0줄**: 인증은 인프라에서 처리
- **Token refresh**: wire protocol에 `access_token` 메시지 타입 내장

---

## 비교 요약

| 프레임워크 | 인증 위치 | 코드량 | Identity 접근 | Fail-Fast | 투명도 |
|---|---|---|---|---|---|
| **Phoenix** | `connect/3` 콜백 | 10–15줄 | `socket.assigns` | `:error` 반환 | Medium |
| **Socket.IO** | `io.use()` 미들웨어 | 8–12줄 | `socket.data` | `next(Error)` | High |
| **Ktor** | `authenticate {}` 라우트 | 10–15줄 | `call.principal<T>()` | 401 응답 | High |
| **Apollo** | `onConnect` 훅 | 15–20줄 | `context.user` | `return false` | Medium |
| **SignalR** | ASP.NET 미들웨어 | 20–25줄 | `Context.User` | 401 응답 | High |
| **Supabase** | 플랫폼 + RLS | 0줄 | JWT claims | 채널 거부 | Very High |

---

## 공통 원칙

### 1. Fail-Fast / Early Rejection
모든 프레임워크가 인증 실패 시 **즉시 거부**하는 패턴을 채택.
핸들러 코드에 success/failure 분기 중첩이 없다.

### 2. Authenticate Once, Use Everywhere
인증은 연결 수립 시 한 번만 수행. 이후 identity는 자동 전파되어
핸들러에서 별도 인증 로직 없이 바로 사용.

### 3. 핸들러는 인증된 상태만 다룸
미들웨어/인터셉터가 인증을 처리하므로,
핸들러 코드는 항상 "인증이 성공한 상태"를 전제로 작성됨.

---

## flowdux-remote-auth 적용

### 현재 문제

```kotlin
// when 분기로 인한 nesting
webSocket("/chat") {
    val authed = KtorWebSocketServerConnection(this)
        .withAuth(tokenVerifier)

    when (val result = authed.awaitAuth(this)) {
        is AuthResult.Success -> {
            val connection = authed.typedJsonAs<SharedChatAction, ChatAction>()
            server.handleClient(result.principal.userId, connection)
        }
        is AuthResult.Failure -> {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, result.reason))
        }
    }
}
```

### 개선: `getOrElse` 확장 (Phoenix-style Fail-Fast)

```kotlin
// AuthResult에 getOrElse 확장 추가
inline fun <P : AuthPrincipal> AuthResult<P>.getOrElse(
    onFailure: (reason: String) -> Nothing
): P = when (this) {
    is AuthResult.Success -> principal
    is AuthResult.Failure -> onFailure(reason)
}
```

사용 예:

```kotlin
// flat — nesting 없음
webSocket("/chat") {
    val authed = KtorWebSocketServerConnection(this)
        .withAuth(tokenVerifier)

    val principal = authed.awaitAuth(this).getOrElse { reason ->
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason))
        return@webSocket
    }

    val connection = authed.typedJsonAs<SharedChatAction, ChatAction>()
    server.handleClient(principal.userId, connection)
}
```

- `when` 중첩 제거 → flat flow
- Phoenix의 `connect/3 → :error → return` 패턴과 동일 구조
- 기존 `AuthResult` sealed interface와 100% 하위 호환
- `when` 패턴도 그대로 사용 가능 (선택적)
