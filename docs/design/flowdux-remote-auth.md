# flowdux-remote: 인증 설계

> 설계 문서 — 2026-01-31

## 요약

flowdux-remote에 인증을 적용하는 방법을 두 가지 축으로 분석한다.

1. **외부 접근** — flowdux-remote 코드 수정 없이 Ktor 생태계의 플러그인만으로 인증 적용
2. **내부 접근** — flowdux-remote 자체에 인증 레이어를 추가하여 프레임워크 수준에서 지원

결론: **외부 접근만으로도 실용적인 인증이 가능**하며, 내부 접근은 멀티플랫폼 지원이나 Transport 독립적 인증이 필요할 때 고려한다.

---

## 현재 아키텍처의 확장 포인트

flowdux-remote에는 이미 인증을 붙일 수 있는 두 가지 결정적인 확장 포인트가 존재한다.

### 1. `KtorWebSocketClientConnection`이 외부 `HttpClient`를 받는다

```kotlin
// KtorWebSocketClientConnection.kt
class KtorWebSocketClientConnection(
    private val url: String,
    httpClient: HttpClient? = null,  // ← 외부에서 인증이 설정된 HttpClient 주입 가능
) : ClientConnection
```

`httpClient`에 Ktor Auth 플러그인이 설치된 인스턴스를 넘기면 WebSocket handshake 시 인증 헤더가 자동으로 포함된다.

### 2. 서버 WebSocket 라우트가 표준 Ktor 라우팅이다

```kotlin
// 샘플 서버 코드
routing {
    webSocket("/chat") {  // ← 표준 Ktor 라우트. authenticate {} 블록으로 감쌀 수 있음
        val connection = KtorWebSocketServerConnection(this)
        // ...
    }
}
```

Ktor의 `authenticate("provider") { }` 블록 안에 WebSocket 라우트를 넣으면, handshake 시점에 HTTP 수준 인증이 적용된다.

---

## 방안 1: 외부 프레임워크 활용 (코드 수정 없음)

### 1-A. Ktor Authentication Plugin + JWT — 가장 권장

WebSocket handshake는 HTTP Upgrade 요청이므로, 이 시점에 JWT 검증을 수행한다.

**서버**

```kotlin
// build.gradle.kts
implementation("io.ktor:ktor-server-auth:$ktor_version")
implementation("io.ktor:ktor-server-auth-jwt:$ktor_version")

// Application.kt
install(Authentication) {
    jwt("auth-jwt") {
        realm = "flowdux"
        verifier(JWT.require(Algorithm.HMAC256(secret)).build())
        validate { credential ->
            if (credential.payload.getClaim("userId").asString() != null)
                JWTPrincipal(credential.payload)
            else null
        }
    }
}

install(WebSockets)

routing {
    authenticate("auth-jwt") {
        webSocket("/chat") {
            // 인증된 사용자 정보 접근
            val principal = call.principal<JWTPrincipal>()!!
            val userId = principal.payload.getClaim("userId").asString()

            // 아래는 기존 flowdux-remote 코드 그대로
            val connection = KtorWebSocketServerConnection(this)
                .typedJson<SharedChatAction>() as TypedServerConnection<ChatAction>
            session.handleClient(userId, connection)
        }
    }
}
```

**클라이언트**

```kotlin
// build.gradle.kts
implementation("io.ktor:ktor-client-auth:$ktor_version")

// Client setup
val httpClient = HttpClient(CIO) {
    install(WebSockets)
    install(Auth) {
        bearer {
            loadTokens {
                BearerTokens(accessToken = jwtToken, refreshToken = "")
            }
            refreshTokens {
                // 토큰 갱신 로직
                BearerTokens(newAccessToken, newRefreshToken)
            }
        }
    }
}

// flowdux-remote 코드 수정 없이 httpClient만 주입
val clientConnection = KtorWebSocketClientConnection(
    url = "ws://localhost:8080/chat",
    httpClient = httpClient  // ← 인증이 포함된 HttpClient
)
val typedConnection = clientConnection.typedJson<SharedChatAction>()
```

| 항목 | 평가 |
|------|------|
| flowdux-remote 수정 | **없음** |
| 인증 방식 | HTTP handshake 시 Bearer token |
| 토큰 갱신 | Ktor Auth 플러그인이 자동 처리 |
| 사용자 식별 | `call.principal<JWTPrincipal>()` |
| 멀티플랫폼 | JVM, iOS (Ktor Client Auth는 KMP 지원) |

**주의사항:** WebSocket 프로토콜 특성상 일부 환경에서는 Authorization 헤더를 직접 설정할 수 없다. 이 경우 query parameter로 토큰을 전달하는 fallback이 필요하다.

```kotlin
// Query parameter fallback (클라이언트)
val clientConnection = KtorWebSocketClientConnection(
    url = "ws://localhost:8080/chat?token=$jwtToken",
    httpClient = httpClient
)

// Query parameter fallback (서버)
install(Authentication) {
    jwt("auth-jwt") {
        authHeader { call ->
            call.request.queryParameters["token"]?.let {
                parseAuthorizationHeader("Bearer $it")
            } ?: call.request.parseAuthorizationHeaderOrNull()
        }
        // ... verifier, validate 동일
    }
}
```

---

### 1-B. Ktor Sessions Plugin (세션 기반)

로그인 후 세션 쿠키로 인증 상태를 유지한다. WebSocket handshake가 HTTP이므로 쿠키가 자동 전달된다.

**서버**

```kotlin
data class UserSession(val userId: String, val name: String)

install(Sessions) {
    cookie<UserSession>("USER_SESSION") {
        cookie.path = "/"
        cookie.maxAgeInSeconds = 3600
    }
}

routing {
    // 로그인 엔드포인트 (REST)
    post("/login") {
        val credentials = call.receive<LoginRequest>()
        val user = authenticate(credentials)  // 사용자 인증
        call.sessions.set(UserSession(user.id, user.name))
        call.respond(HttpStatusCode.OK)
    }

    // 세션 검증 후 WebSocket 접근 허용
    webSocket("/chat") {
        val userSession = call.sessions.get<UserSession>()
            ?: return@webSocket close(
                CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No session")
            )

        // 기존 flowdux-remote 코드 그대로
        val connection = KtorWebSocketServerConnection(this)
            .typedJson<SharedChatAction>() as TypedServerConnection<ChatAction>
        session.handleClient(userSession.userId, connection)
    }
}
```

| 항목 | 평가 |
|------|------|
| flowdux-remote 수정 | **없음** |
| 인증 방식 | HTTP 세션 쿠키 |
| 적합한 환경 | 브라우저 클라이언트 (쿠키 자동 전달) |
| 비브라우저 클라이언트 | 쿠키 관리를 직접 구현해야 함 |
| 멀티플랫폼 | 제한적 (브라우저 우선) |

---

### 1-C. Reverse Proxy 수준 인증 (Nginx / Envoy / API Gateway)

flowdux 서버 앞단에 프록시를 두고, 프록시가 토큰 검증을 담당한다.

```
Client → [Nginx + JWT 검증] → Ktor Server (flowdux-remote)
```

```nginx
# nginx.conf
location /chat {
    # JWT 검증 (nginx-jwt 모듈 또는 lua-resty-jwt)
    auth_jwt "flowdux";
    auth_jwt_key_file /etc/nginx/jwt_secret.key;

    # 통과 시 WebSocket 프록시
    proxy_pass http://backend:8080;
    proxy_http_version 1.1;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "Upgrade";
    proxy_set_header X-User-Id $jwt_claim_userId;
}
```

| 항목 | 평가 |
|------|------|
| flowdux-remote 수정 | **없음** |
| 서버 애플리케이션 수정 | 최소 (X-User-Id 헤더 읽기 정도) |
| 적합한 환경 | 프로덕션 배포, 마이크로서비스 |
| 장점 | 언어/프레임워크 무관, 인프라 수준 보안 |
| 단점 | 인프라 설정 복잡도 증가, 로컬 개발 불편 |

---

### 1-D. OAuth2 / OIDC (Ktor OAuth Plugin)

소셜 로그인 등 외부 IdP와 연동한다. 로그인 흐름은 REST, 인증 후 WebSocket 접근은 세션 또는 JWT로 처리한다.

```kotlin
install(Authentication) {
    oauth("auth-oauth") {
        urlProvider = { "http://localhost:8080/callback" }
        providerLookup = {
            OAuthServerSettings.OAuth2ServerSettings(
                name = "google",
                authorizeUrl = "https://accounts.google.com/o/oauth2/auth",
                accessTokenUrl = "https://oauth2.googleapis.com/token",
                clientId = clientId,
                clientSecret = clientSecret,
                defaultScopes = listOf("openid", "profile", "email"),
            )
        }
        client = HttpClient(CIO)
    }
}

routing {
    authenticate("auth-oauth") {
        get("/login") { /* Ktor가 자동으로 OAuth 흐름 시작 */ }
        get("/callback") {
            val principal = call.principal<OAuthAccessTokenResponse.OAuth2>()!!
            // JWT 발급 또는 세션 설정 후 WebSocket에서 사용
            val jwt = issueJwt(principal)
            call.respond(mapOf("token" to jwt))
        }
    }
}
```

이 방안은 단독으로 쓰이지 않고, 방안 1-A(JWT) 또는 1-B(Session)와 조합된다.

---

## 방안 2: flowdux-remote 내부에 인증 레이어 추가

외부 접근이 Ktor에 종속되는 반면, 내부 접근은 Transport 독립적인 인증을 제공한다.

### 설계 원칙

1. **기존 API 호환** — 현재 인증 없이 동작하는 코드가 그대로 동작해야 한다
2. **Transport 독립** — Ktor뿐 아니라 다른 WebSocket 구현, SSE 등에서도 동작
3. **선택적 적용** — 인증이 필요 없는 환경에서는 인증 레이어를 사용하지 않아도 됨

### 2-A. 연결 수준 인증 (Connection-Level Auth)

WebSocket 연결 수립 후, 첫 메시지로 인증을 수행한다.

**새로운 인터페이스**

```kotlin
// flowdux-remote-core에 추가
interface AuthenticatedAction : Action

// 인증 요청/응답 프로토콜
data class AuthRequest(val token: String) : AuthenticatedAction
data class AuthResponse(val success: Boolean, val userId: String?) : AuthenticatedAction
```

**연결 래퍼**

```kotlin
// flowdux-remote-server에 추가
class AuthenticatedServerConnection<A : Action>(
    private val connection: TypedServerConnection<A>,
    private val authenticate: suspend (token: String) -> AuthResult,
) : TypedServerConnection<A> {

    data class AuthResult(val success: Boolean, val userId: String)

    private var _userId: String? = null
    val userId: String get() = _userId ?: error("Not authenticated")

    // 첫 메시지를 인증 토큰으로 처리하고, 이후 메시지부터 정상 Flow로 전달
    override val incoming: Flow<A> = flow {
        val firstMessage = connection.incoming.first()
        // ... 인증 처리
        // 나머지 메시지 emit
        emitAll(connection.incoming)
    }
}
```

**사용 예시**

```kotlin
webSocket("/chat") {
    val rawConnection = KtorWebSocketServerConnection(this)
        .typedJson<SharedChatAction>() as TypedServerConnection<ChatAction>

    val authConnection = AuthenticatedServerConnection(rawConnection) { token ->
        val claims = verifyJwt(token)
        AuthResult(success = claims != null, userId = claims?.userId ?: "")
    }

    session.handleClient(authConnection.userId, authConnection)
}
```

| 항목 | 평가 |
|------|------|
| Transport 독립 | **O** — WebSocket, SSE, TCP 등 무관 |
| 기존 코드 호환 | **O** — 래퍼이므로 기존 코드 영향 없음 |
| 복잡도 | 중간 — 인증 핸드셰이크 프로토콜 추가 |
| 멀티플랫폼 | **O** — commonMain에 작성 가능 |

### 2-B. 미들웨어 수준 인증 (Middleware-Level Auth)

인증 전용 미들웨어를 만들어 액션 파이프라인에서 인증을 처리한다.

```kotlin
class AuthMiddleware<S : State, A : Action>(
    private val verifyToken: suspend (String) -> AuthResult?,
) : Middleware<S, A> {

    override val name = "AuthMiddleware"

    override fun process(getState: () -> S, action: A): Flow<A> = flow {
        when (action) {
            is AuthAction.Login -> {
                val result = verifyToken(action.token)
                if (result != null) {
                    emit(AuthAction.Authenticated(result.userId) as A)
                } else {
                    emit(AuthAction.AuthFailed("Invalid token") as A)
                }
            }
            // 인증 전 상태에서 다른 액션 차단
            else -> {
                val state = getState()
                if (state is Authenticatable && !state.isAuthenticated) {
                    emit(AuthAction.AuthRequired() as A)
                    return@flow
                }
                emit(action)
            }
        }
    }
}
```

이 방식은 인증 상태를 State에 포함시키며, 인증/미인증 전환이 액션으로 표현된다. flowdux의 철학에 가장 부합하지만, State 설계에 대한 제약이 생긴다.

### 2-C. MessageCodec 확장 (Wire-Level Auth)

`MessageCodec`에 인증 메시지 타입을 추가한다.

```kotlin
// 현재 와이어 프로토콜
// Client → Server: {"type":"action","payload":{...}}
// Server → Client: {"type":"response","actions":[...]}

// 인증 메시지 추가
// Client → Server: {"type":"auth","token":"..."}
// Server → Client: {"type":"auth_result","success":true,"userId":"..."}
```

이 방식은 액션 직렬화와 독립적으로 인증을 처리하며, 기존 액션 계층에 영향을 주지 않는다. 단, `MessageCodec` 인터페이스 변경이 필요하다.

---

## 방안 비교

| 기준 | 1-A (Ktor JWT) | 1-B (Session) | 1-C (Proxy) | 2-A (Connection Auth) | 2-B (Middleware Auth) |
|------|:-:|:-:|:-:|:-:|:-:|
| flowdux 수정 | 없음 | 없음 | 없음 | 새 모듈 | 새 모듈 |
| 구현 난이도 | 낮음 | 낮음 | 중간 | 중간 | 높음 |
| Transport 독립 | X (Ktor) | X (Ktor) | X (HTTP) | **O** | **O** |
| 멀티플랫폼 | O (KMP) | 제한적 | N/A | **O** | **O** |
| 토큰 갱신 | 자동 | 세션 만료 | 프록시 의존 | 직접 구현 | 직접 구현 |
| 기존 코드 영향 | 없음 | 없음 | 없음 | 없음 | State 제약 |
| 프로덕션 검증 | Ktor 공식 | Ktor 공식 | 인프라 표준 | 자체 구현 | 자체 구현 |

---

## 권장 접근 전략

### 단기: 외부 접근으로 시작 (방안 1-A)

- Ktor JWT Plugin이 가장 실용적이고 검증된 방식이다
- flowdux-remote 코드 변경 없이 즉시 적용 가능하다
- `KtorWebSocketClientConnection`의 `httpClient` 파라미터가 이미 이 패턴을 지원한다
- `sessionId`로 `UUID` 대신 JWT의 `userId`를 사용하면 사용자 식별도 자연스럽게 해결된다

**적용 시 변경 사항:**
- 샘플 앱에 JWT 인증 예제 추가
- 문서에 인증 가이드 추가
- flowdux-remote 라이브러리 코드 변경 **없음**

### 중장기: 필요 시 내부 접근 병행 (방안 2-A)

다음 조건에서 내부 접근을 고려한다:

1. **Ktor 이외의 Transport 지원** 시 (예: raw TCP, gRPC)
2. **브라우저 JS 클라이언트** 지원 시 (HTTP 헤더 제어가 제한적)
3. **연결 중 재인증** 이 필요할 때 (토큰 만료 후 연결 유지)

이 경우 방안 2-A(Connection-Level Auth)를 `flowdux-remote-auth` 모듈로 분리하여 추가한다. 기존 모듈에는 영향을 주지 않는다.

---

## 아키텍처 다이어그램

### 외부 접근 (Ktor JWT)

```
┌─────────────────────────────────┐     ┌──────────────────────────────────────┐
│          Client                 │     │              Server (Ktor)           │
│                                 │     │                                      │
│  ┌─────────────────────┐        │     │  ┌──────────────────────────┐        │
│  │ Ktor HttpClient     │        │     │  │ Ktor Auth Plugin (JWT)   │        │
│  │  + Auth Plugin      │────────┼─────┼──│  verify token on         │        │
│  │  + Bearer Token     │  WS    │     │  │  HTTP Upgrade request    │        │
│  └─────────┬───────────┘ Upgrade│     │  └────────────┬─────────────┘        │
│            │                    │     │               │                      │
│  ┌─────────▼───────────┐        │     │  ┌────────────▼─────────────┐        │
│  │ KtorWSClientConn    │        │     │  │ authenticate("jwt") {    │        │
│  │  (httpClient 주입)   │◄───────┼─────┼──►   webSocket("/chat") {  │        │
│  └─────────┬───────────┘  WS    │     │  │     principal → userId   │        │
│            │              Frames│     │  │   }                      │        │
│  ┌─────────▼───────────┐        │     │  │ }                        │        │
│  │ TypedClientConn     │        │     │  └────────────┬─────────────┘        │
│  │  .typedJson<>()     │        │     │               │                      │
│  └─────────┬───────────┘        │     │  ┌────────────▼─────────────┐        │
│            │                    │     │  │ KtorWSServerConn          │        │
│  ┌─────────▼───────────┐        │     │  │  .typedJson<>()          │        │
│  │ ClientRemote        │        │     │  └────────────┬─────────────┘        │
│  │  Middleware          │        │     │               │                      │
│  └─────────┬───────────┘        │     │  ┌────────────▼─────────────┐        │
│            │                    │     │  │ RemoteServerSession       │        │
│  ┌─────────▼───────────┐        │     │  │  .handleClient(userId,   │        │
│  │ Store               │        │     │  │    connection)            │        │
│  └─────────────────────┘        │     │  └──────────────────────────┘        │
└─────────────────────────────────┘     └──────────────────────────────────────┘
```

### 내부 접근 (Connection-Level Auth)

```
┌─────────────────────────────────┐     ┌──────────────────────────────────────┐
│          Client                 │     │              Server                  │
│                                 │     │                                      │
│  ┌─────────────────────┐        │     │  ┌──────────────────────────┐        │
│  │ ClientConnection    │────────┼─────┼──│ ServerConnection         │        │
│  └─────────┬───────────┘  WS    │     │  └────────────┬─────────────┘        │
│            │                    │     │               │                      │
│  ┌─────────▼───────────┐        │     │  ┌────────────▼─────────────┐        │
│  │ TypedClientConn     │        │     │  │ TypedServerConn          │        │
│  └─────────┬───────────┘        │     │  └────────────┬─────────────┘        │
│            │                    │     │               │                      │
│  ┌─────────▼───────────┐        │     │  ┌────────────▼─────────────┐        │
│  │ AuthClientConn      │        │     │  │ AuthServerConn           │        │
│  │  (첫 메시지: token)  │─ auth ─┼─────┼──│  (토큰 검증 → userId)     │        │
│  └─────────┬───────────┘        │     │  └────────────┬─────────────┘        │
│            │                    │     │               │                      │
│  ┌─────────▼───────────┐        │     │  ┌────────────▼─────────────┐        │
│  │ ClientRemote        │        │     │  │ RemoteServerSession       │        │
│  │  Middleware          │        │     │  │  .handleClient(userId,   │        │
│  └─────────┬───────────┘        │     │  │    authConnection)        │        │
│            │                    │     │  └──────────────────────────┘        │
│  ┌─────────▼───────────┐        │     │                                      │
│  │ Store               │        │     │                                      │
│  └─────────────────────┘        │     │                                      │
└─────────────────────────────────┘     └──────────────────────────────────────┘
```

---

## 참고: 현재 코드의 인증 관련 진입점

| 파일 | 확장 포인트 | 용도 |
|------|------------|------|
| `KtorWebSocketClientConnection.kt` | `httpClient: HttpClient?` 생성자 파라미터 | 인증이 설정된 HttpClient 주입 |
| `KtorWebSocketServerConnection.kt` | Ktor `WebSocketSession` 래핑 | `call.principal<>()` 접근 가능 |
| `RemoteServerSession.handleClient()` | `sessionId: String` 파라미터 | JWT userId를 sessionId로 사용 |
| `MultiClientServerRemoteMiddleware` | `InternalAddSession` | 세션 등록 시 인증 정보 전달 가능 |
