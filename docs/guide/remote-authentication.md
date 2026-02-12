# Remote Authentication

FlowDux Remote Auth 모듈은 WebSocket 연결에 **인밴드(in-band) 인증 핸드셰이크**를 추가한다.
연결이 성립된 후, 첫 메시지로 토큰을 교환하여 인증을 완료하는 방식이다.

```
Client                                Server
  │                                     │
  │ ──── WebSocket Open ──────────────► │
  │                                     │
  │ {"type":"auth","token":"eyJ..."}    │
  │ ──────────────────────────────────► │  AuthVerifier.verify(token)
  │                                     │  ├─ 실패 → auth_error + close
  │          {"type":"auth_ok"}         │  └─ 성공:
  │ ◄────────────────────────────────── │
  │                                     │
  │ ════ 인증 완료, 정상 메시지 교환 ═══ │
  │                                     │
```

## Ktor-Level vs In-Band Auth

| | Ktor-Level Auth | In-Band Auth (이 모듈) |
|---|---|---|
| **인증 시점** | HTTP Upgrade 요청 시 | WebSocket 연결 후 첫 메시지 |
| **토큰 전달** | Query param / HTTP header | WebSocket 메시지 |
| **브라우저 지원** | Header 불가 (query만) | 완전 지원 |
| **Transport 의존** | Ktor 전용 | Transport 무관 (KMP) |
| **토큰 갱신** | 재연결 필요 | 프로토콜 확장 가능 |

Ktor 레벨 인증 패턴은 [WebSocket Authentication Guide](./websocket-authentication.md)를 참조.

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    flowdux-remote-auth                    │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │  Shared (io.flowdux.remote.auth)                  │   │
│  │    AuthConfig         handshake timeout 설정       │   │
│  │    AuthProtocol       wire protocol (JSON)         │   │
│  │    AuthenticationException                         │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
│  ┌─────────────────────┐  ┌─────────────────────────┐   │
│  │  Client              │  │  Server                  │   │
│  │  (.auth.client)      │  │  (.auth.server)          │   │
│  │                      │  │                          │   │
│  │  CredentialProvider  │  │  AuthPrincipal           │   │
│  │  AuthClientConnection│  │  AuthVerifier            │   │
│  │  .withAuth()         │  │  AuthResult              │   │
│  │                      │  │  AuthServerConnection    │   │
│  │                      │  │  .withAuth()             │   │
│  │                      │  │  .getOrElse()            │   │
│  └─────────────────────┘  └─────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### Wire Protocol

인증 메시지는 일반 액션 메시지와 분리된 `type` 네임스페이스를 사용한다.

| 방향 | 메시지 | 설명 |
|------|--------|------|
| Client → Server | `{"type":"auth","token":"..."}` | 인증 요청 |
| Server → Client | `{"type":"auth_ok"}` | 인증 성공 |
| Server → Client | `{"type":"auth_error","reason":"..."}` | 인증 실패 |

인증 메시지는 핸드셰이크 이후에도 자동 필터링되어 비즈니스 로직에 노출되지 않는다.

---

## Installation

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.chibimoons:flowdux-remote-auth:$flowduxVersion")
        }
    }
}
```

`flowdux-remote-auth`는 `flowdux-remote-client`와 `flowdux-remote-server`에 대한 의존성을 이미 포함한다.

---

## Server Setup

### 1. Principal 정의

도메인에 맞는 인증 주체(identity)를 정의한다.

```kotlin
data class UserPrincipal(
    val userId: String,
    val displayName: String,
    val roles: Set<String> = emptySet(),
) : AuthPrincipal
```

### 2. Verifier 구현

토큰을 검증하고 `AuthResult`를 반환하는 `AuthVerifier`를 구현한다.

```kotlin
// JWT 예시
val jwtVerifier = AuthVerifier<UserPrincipal> { token ->
    try {
        val decoded = JWT.decode(token, secret)
        AuthResult.Success(
            UserPrincipal(
                userId = decoded.subject,
                displayName = decoded.name,
                roles = decoded.roles,
            )
        )
    } catch (e: JWTException) {
        AuthResult.Failure("Invalid token: ${e.message}")
    }
}

// API Key 예시
val apiKeyVerifier = AuthVerifier<UserPrincipal> { token ->
    val user = apiKeyStore.lookup(token)
    if (user != null) AuthResult.Success(user)
    else AuthResult.Failure("Unknown API key")
}
```

### 3. WebSocket 라우트에 적용

```kotlin
webSocket("/chat") {
    // 1. Raw connection을 auth로 감싸기
    val authed = KtorWebSocketServerConnection(this)
        .withAuth(jwtVerifier)

    // 2. 인증 대기 (fail-fast)
    val principal = authed.awaitAuth(this).getOrElse { reason ->
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason))
        return@webSocket
    }

    // 3. Typed connection으로 변환
    val connection = authed.typedJsonAs<SharedChatAction, ChatAction>()

    // 4. 세션 시작 — principal.userId를 sessionId로 사용
    server.handleClient(principal.userId, connection)
}
```

### `getOrElse` — Fail-Fast 패턴

`AuthResult.getOrElse`는 인증 실패 시 즉시 반환하는 헬퍼다.
중첩된 `when` 분기 없이 flat한 코드를 작성할 수 있다.

```kotlin
// getOrElse 사용 (권장)
val principal = authed.awaitAuth(scope).getOrElse { reason ->
    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason))
    return@webSocket
}
// principal은 여기서 보장됨

// when 패턴도 가능 (세밀한 제어가 필요할 때)
when (val result = authed.awaitAuth(scope)) {
    is AuthResult.Success -> { /* ... */ }
    is AuthResult.Failure -> { /* ... */ }
}
```

### Handshake Timeout

기본 타임아웃은 10초이며, `AuthConfig`로 조절할 수 있다.

```kotlin
val authed = KtorWebSocketServerConnection(this)
    .withAuth(
        verifier = jwtVerifier,
        config = AuthConfig(handshakeTimeout = 5.seconds),
    )
```

---

## Client Setup

### 1. 연결 체이닝

클라이언트는 `.withAuth()`로 인증을 추가한다.
`connect()` 시 자동으로 토큰 전송 → 응답 대기 → 인증 완료가 처리된다.

```kotlin
val connection = KtorWebSocketClientConnection.create(
    host = "localhost",
    port = 8080,
    path = "/chat",
)
    .withAuth(token = "user:Alice")              // static token
    .typedJsonAs<SharedChatAction, ChatAction>()
```

### 2. Token Provider 패턴

정적 토큰 외에 동적 토큰을 위한 3가지 오버로드를 제공한다.

```kotlin
// 1. Static token — 테스트, 데모용
.withAuth(token = "my-api-key")

// 2. Lambda — 동적 토큰
.withAuth { tokenStore.getAccessToken() }

// 3. CredentialProvider — 재사용 가능한 인터페이스
.withAuth(CredentialProvider { oauthClient.refreshToken() })
```

### 3. Store 생성

인증된 connection을 `SyncMiddleware`에 전달하여 Store를 생성한다.

```kotlin
val store = createClientStore(
    initialState = ChatState(),
    syncMiddleware = ChatSyncMiddleware(connection),
    reducer = chatReducer,
)

// 연결 시작
store.dispatch(ChatAction.Connect)
```

### Send Gating

`AuthClientConnection`은 인증이 완료될 때까지 `send()`를 자동으로 대기시킨다.
인증 실패 후 `send()` 호출 시 `AuthenticationException`이 발생한다.

```
connect()  ──►  CONNECTING  ──►  send token  ──►  wait auth_ok  ──►  CONNECTED
                     │                                                     │
                     │  send() 호출 시 여기서 suspend                       │  send() 정상 동작
                     │                                                     │
                     └─ auth 실패 → DISCONNECTED → send() throws AuthenticationException
```

---

## Connection Chain

인증 모듈은 기존 연결 체인에 데코레이터로 삽입된다.

### Server

```
KtorWebSocketServerConnection       Raw string transport
        ↓ .withAuth(verifier)
AuthServerConnection                 Auth handshake + message filtering
        ↓ .typedJsonAs<Wire, App>()
TypedServerConnection<App>           Serialized action transport
        ↓
server.handleClient(sessionId, ─)    Session registration + forwarding
```

### Client

```
KtorWebSocketClientConnection       Raw string transport
        ↓ .withAuth(token)
AuthClientConnection                 Auto token send + response wait
        ↓ .typedJsonAs<Wire, App>()
TypedClientConnection<App>           Serialized action transport
        ↓
SyncMiddleware(connection)           Store ↔ Server sync
```

인증 레이어는 완전히 투명하다 — 이후의 `typedJsonAs()`, `handleClient()`, `SyncMiddleware`는 인증을 알지 못한다.

---

## Complete Example

[Auth Chat Sample](../../kotlin/samples/flowdux-remote/auth/) 참조.

### Server

```kotlin
// 1. Principal 정의
data class ChatPrincipal(
    val userId: String,
    val displayName: String,
) : AuthPrincipal

// 2. Verifier 정의
val tokenVerifier = AuthVerifier<ChatPrincipal> { token ->
    if (token.startsWith("user:")) {
        val name = token.removePrefix("user:")
        AuthResult.Success(ChatPrincipal(userId = name, displayName = name))
    } else {
        AuthResult.Failure("Invalid token format")
    }
}

// 3. SharedStateServer 생성
val server = createSharedStateServer(
    initialState = ServerChatState(),
    reducer = serverChatReducer,
    processors = chatProcessors(),
    stateMapper = { state -> SharedChatAction.SyncState(state.toChatState()) },
    scope = applicationScope,
)

// 4. WebSocket 라우트
embeddedServer(CIO, port = 8080) {
    install(WebSockets)

    routing {
        webSocket("/chat") {
            val authed = KtorWebSocketServerConnection(this)
                .withAuth(tokenVerifier)

            val principal = authed.awaitAuth(this).getOrElse { reason ->
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason))
                return@webSocket
            }

            val connection = authed.typedJsonAs<SharedChatAction, ChatAction>()

            try {
                server.handleClient(principal.userId, connection)
            } finally {
                println("Disconnected: ${principal.userId}")
            }
        }
    }
}.start(wait = true)
```

### Client

```kotlin
// 1. 인증된 연결 생성
val connection = KtorWebSocketClientConnection.create(
    host = "localhost",
    port = 8080,
    path = "/chat",
)
    .withAuth(token = "user:$username")
    .typedJsonAs<SharedChatAction, ChatAction>()

// 2. Store 생성
val store = createClientStore(
    initialState = ClientChatState(),
    syncMiddleware = ChatRemoteMiddleware(connection),
    reducer = clientChatReducer,
)

// 3. 연결 및 사용
store.dispatch(ClientChatAction.Connect)
store.dispatch(SharedChatAction.JoinRoom(username))
store.dispatch(SharedChatAction.SendMessage(username, "Hello!"))
```

---

## Module Structure

```
kotlin/remote/auth/src/commonMain/kotlin/io/flowdux/remote/auth/
├── AuthConfig.kt                 # handshake timeout 설정
├── AuthProtocol.kt               # wire protocol (internal)
├── AuthenticationException.kt    # 인증 실패 예외
├── client/
│   ├── AuthClientConnection.kt   # ClientConnection decorator
│   ├── ClientAuthExt.kt          # .withAuth() extensions (3 overloads)
│   └── CredentialProvider.kt     # token provider interface
└── server/
    ├── AuthPrincipal.kt          # identity marker interface
    ├── AuthResult.kt             # Success/Failure + getOrElse()
    ├── AuthServerConnection.kt   # ServerConnection decorator
    ├── AuthVerifier.kt           # token verifier interface
    └── ServerAuthExt.kt          # .withAuth() extension
```

---

## 관련 문서

- [JWT Integration Guide](./jwt-integration.md) — HS256, Firebase Auth, Supabase Auth 통합 가이드
- [Remote (WebSocket)](./remote.md) — 기본 클라이언트-서버 설정 가이드
- [WebSocket Authentication (Ktor-Level)](./websocket-authentication.md) — Ktor 레벨 인증 패턴
- [Server Patterns](./server-patterns.md) — SharedState, Room, Per-Client 패턴
- [Auth WebSocket Patterns (Design)](../design/auth-websocket-patterns.md) — 프레임워크 비교 분석
- [Sample Apps](./samples.md) — 샘플 앱 실행 방법
