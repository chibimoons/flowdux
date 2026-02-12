# JWT Integration Guide

FlowDux Remote Auth의 `AuthVerifier`에 JWT 인증을 통합하는 방법을 설명한다.
FlowDux는 토큰 형식에 의존하지 않으므로, JWT 라이브러리는 사용자가 직접 선택한다.

---

## Overview

```
Client                              Server
  │                                   │
  │ {"type":"auth","token":"eyJ..."}  │
  │ ─────────────────────────────────►│
  │                                   │  AuthVerifier.verify("eyJ...")
  │                                   │    └─ JWT 라이브러리로 검증
  │                                   │    └─ claims → AuthPrincipal 매핑
  │        {"type":"auth_ok"}         │
  │ ◄─────────────────────────────── │
  │                                   │
```

`AuthVerifier`는 토큰 문자열을 받아서 `AuthResult`를 반환하는 단일 함수다.
JWT 검증 로직은 이 함수 안에서 자유롭게 구현하면 된다.

```kotlin
val jwtVerifier = AuthVerifier<MyPrincipal> { token ->
    // JWT 라이브러리로 token 검증
    // claims에서 principal 생성
}
```

---

## 라이브러리 선택

| 라이브러리 | 용도 | 알고리즘 | 비고 |
|---|---|---|---|
| [auth0/java-jwt](https://github.com/auth0/java-jwt) | JVM 서버 | HS256, RS256, ES256 | Ktor 내부에서도 사용. 가장 간결한 API |
| [jjwt](https://github.com/jwtk/jjwt) | JVM 서버 | 전체 JWA suite | 가장 포괄적. JWE/JWK 지원 |
| [nimbus-jose-jwt](https://connect2id.com/products/nimbus-jose-jwt) | JVM 서버 | 전체 JWA suite | Spring Security 내부 사용. 엔터프라이즈 |
| [jwt-kt](https://github.com/Appstractive/jwt-kt) | KMP (실험적) | HS/RS/PS/ES 256-512 | 유일한 KMP 옵션. ~28 stars |

> **권장**: JVM 서버에서는 **auth0/java-jwt**가 가장 간결하고 안정적이다.
> Ktor 서버를 사용한다면 이미 transitive dependency로 포함될 수 있다.

### Gradle 의존성

```kotlin
// auth0/java-jwt (권장)
dependencies {
    implementation("com.auth0:java-jwt:4.5.0")
    implementation("com.auth0:jwks-rsa:0.22.1")  // JWKS 사용 시 (Firebase, Supabase)
}

// 또는 JJWT
dependencies {
    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")
}
```

---

## 1. HS256 + 공유 시크릿

가장 단순한 구성. 서버가 직접 토큰을 발급하고 검증한다.
프로토타입, 내부 서비스, 자체 인증 서버에 적합.

### Principal 정의

```kotlin
data class UserPrincipal(
    val userId: String,
    val displayName: String,
    val roles: Set<String> = emptySet(),
) : AuthPrincipal
```

### AuthVerifier 구현 (auth0/java-jwt)

```kotlin
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException

val jwtVerifier = AuthVerifier<UserPrincipal> { token ->
    try {
        val verifier = JWT.require(Algorithm.HMAC256(System.getenv("JWT_SECRET")))
            .withIssuer("my-app")
            .build()

        val decoded = verifier.verify(token)

        AuthResult.Success(
            UserPrincipal(
                userId = decoded.subject,
                displayName = decoded.getClaim("name").asString() ?: decoded.subject,
                roles = decoded.getClaim("roles").asList(String::class.java)?.toSet() ?: emptySet(),
            )
        )
    } catch (e: JWTVerificationException) {
        AuthResult.Failure("Invalid token: ${e.message}")
    }
}
```

### AuthVerifier 구현 (JJWT)

```kotlin
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys

val secretKey = Keys.hmacShaKeyFor(System.getenv("JWT_SECRET").toByteArray())

val jwtVerifier = AuthVerifier<UserPrincipal> { token ->
    try {
        val claims = Jwts.parser()
            .verifyWith(secretKey)
            .requireIssuer("my-app")
            .build()
            .parseSignedClaims(token)
            .payload

        AuthResult.Success(
            UserPrincipal(
                userId = claims.subject,
                displayName = claims["name", String::class.java] ?: claims.subject,
            )
        )
    } catch (e: Exception) {
        AuthResult.Failure("Invalid token: ${e.message}")
    }
}
```

### 서버 적용

```kotlin
webSocket("/chat") {
    val authed = KtorWebSocketServerConnection(this)
        .withAuth(jwtVerifier)

    val principal = authed.awaitAuth(this).getOrElse { reason ->
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason))
        return@webSocket
    }

    println("Authenticated: ${principal.displayName} (roles: ${principal.roles})")

    val connection = authed.typedJsonAs<SharedAction, AppAction>()
    server.handleClient(principal.userId, connection)
}
```

### 토큰 발급 (참고)

```kotlin
// 서버측 토큰 발급 예시 (로그인 API 등에서)
fun issueToken(userId: String, name: String): String {
    return JWT.create()
        .withIssuer("my-app")
        .withSubject(userId)
        .withClaim("name", name)
        .withClaim("roles", listOf("user"))
        .withExpiresAt(Date.from(Instant.now().plus(Duration.ofHours(24))))
        .sign(Algorithm.HMAC256(System.getenv("JWT_SECRET")))
}
```

### 클라이언트

```kotlin
val connection = KtorWebSocketClientConnection.create(
    host = "localhost",
    port = 8080,
    path = "/chat",
)
    .withAuth(token = loginResponse.accessToken)  // 로그인 API에서 받은 JWT
    .typedJsonAs<SharedAction, AppAction>()
```

---

## 2. Firebase Auth (RS256 + JWKS)

Firebase Authentication을 사용하는 모바일/웹 앱에서 가장 보편적인 구성.
클라이언트는 Firebase SDK로 ID 토큰을 발급받고, 서버는 Google의 공개키로 검증한다.

```
Mobile App                    FlowDux Server              Google JWKS
    │                              │                          │
    │ Firebase.auth()              │                          │
    │ .getIdToken()                │                          │
    │ ──► "eyJhbG..."             │                          │
    │                              │                          │
    │ auth: {"token":"eyJ..."}     │                          │
    │ ───────────────────────────► │                          │
    │                              │ fetch public keys        │
    │                              │ ────────────────────────►│
    │                              │ ◄────────────────────── │
    │                              │ RS256 verify             │
    │       {"type":"auth_ok"}     │                          │
    │ ◄─────────────────────────── │                          │
```

### Principal 정의

```kotlin
data class FirebasePrincipal(
    val uid: String,
    val email: String?,
    val name: String?,
    val emailVerified: Boolean,
) : AuthPrincipal
```

### JWKS Provider 설정

```kotlin
import com.auth0.jwk.JwkProviderBuilder
import java.net.URI
import java.util.concurrent.TimeUnit

// Google의 JWKS 엔드포인트 — 공개키를 자동 캐싱하고 갱신
private val jwkProvider = JwkProviderBuilder(
    URI("https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com").toURL()
)
    .cached(10, 24, TimeUnit.HOURS)   // 최대 10개 키, 24시간 캐시
    .rateLimited(10, 1, TimeUnit.MINUTES)  // 분당 최대 10번 요청
    .build()
```

### AuthVerifier 구현

```kotlin
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.security.interfaces.RSAPublicKey

private const val FIREBASE_PROJECT_ID = "your-firebase-project-id"

val firebaseVerifier = AuthVerifier<FirebasePrincipal> { token ->
    try {
        // 1. JWT 헤더에서 kid(Key ID) 추출
        val decoded = JWT.decode(token)
        val keyId = decoded.keyId
            ?: return@AuthVerifier AuthResult.Failure("Missing kid in JWT header")

        // 2. JWKS에서 공개키 조회
        val publicKey = jwkProvider.get(keyId).publicKey as RSAPublicKey

        // 3. RS256 서명 검증 + claims 검증
        val verified = JWT.require(Algorithm.RSA256(publicKey, null))
            .withIssuer("https://securetoken.google.com/$FIREBASE_PROJECT_ID")
            .withAudience(FIREBASE_PROJECT_ID)
            .build()
            .verify(token)

        // 4. Principal 생성
        AuthResult.Success(
            FirebasePrincipal(
                uid = verified.subject,
                email = verified.getClaim("email").asString(),
                name = verified.getClaim("name").asString(),
                emailVerified = verified.getClaim("email_verified").asBoolean() ?: false,
            )
        )
    } catch (e: JWTVerificationException) {
        AuthResult.Failure("Firebase token invalid: ${e.message}")
    } catch (e: Exception) {
        AuthResult.Failure("Token verification error: ${e.message}")
    }
}
```

### 서버 적용

```kotlin
webSocket("/game") {
    val authed = KtorWebSocketServerConnection(this)
        .withAuth(firebaseVerifier)

    val principal = authed.awaitAuth(this).getOrElse { reason ->
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, reason))
        return@webSocket
    }

    println("Firebase user: ${principal.name} (${principal.uid})")

    val connection = authed.typedJsonAs<SharedGameAction, GameAction>()
    server.handleClient(principal.uid, connection)
}
```

### 클라이언트 (Android)

```kotlin
// Firebase SDK로 ID 토큰 획득
val idToken = Firebase.auth.currentUser
    ?.getIdToken(false)
    ?.await()
    ?.token
    ?: throw IllegalStateException("Not signed in")

val connection = KtorWebSocketClientConnection.create(
    host = "your-server.com",
    port = 443,
    path = "/game",
)
    .withAuth(token = idToken)
    .typedJsonAs<SharedGameAction, GameAction>()
```

### 클라이언트 (Web/JS — 토큰 갱신)

```kotlin
val connection = KtorWebSocketClientConnection.create(
    host = "your-server.com",
    port = 443,
    path = "/game",
)
    .withAuth {
        // 매 연결 시 최신 토큰 제공 — 만료된 토큰 방지
        Firebase.auth.currentUser?.getIdToken(true)?.await()?.token
            ?: throw AuthenticationException("Not signed in")
    }
    .typedJsonAs<SharedGameAction, GameAction>()
```

> **주의**: Firebase ID 토큰은 기본 1시간 유효.
> 장시간 WebSocket 연결에서는 토큰 만료 전 재연결을 고려해야 한다.

---

## 3. Supabase Auth (RS256 + JWKS)

Supabase를 사용하는 프로젝트에 적합. 2025년 5월 이후 생성된 프로젝트는 기본 RS256.

```
Client App                    FlowDux Server              Supabase JWKS
    │                              │                          │
    │ supabase.auth                │                          │
    │ .signIn(...)                 │                          │
    │ ──► access_token             │                          │
    │                              │                          │
    │ auth: {"token":"eyJ..."}     │                          │
    │ ───────────────────────────► │                          │
    │                              │ fetch public keys        │
    │                              │ ────────────────────────►│
    │                              │ ◄────────────────────── │
    │                              │ RS256 verify             │
    │       {"type":"auth_ok"}     │                          │
    │ ◄─────────────────────────── │                          │
```

### Principal 정의

```kotlin
data class SupabasePrincipal(
    val userId: String,
    val email: String?,
    val role: String,
) : AuthPrincipal
```

### JWKS Provider 설정

```kotlin
import com.auth0.jwk.JwkProviderBuilder
import java.net.URI
import java.util.concurrent.TimeUnit

private const val SUPABASE_PROJECT_REF = "your-project-ref"

private val jwkProvider = JwkProviderBuilder(
    URI("https://$SUPABASE_PROJECT_REF.supabase.co/auth/v1/.well-known/jwks.json").toURL()
)
    .cached(10, 24, TimeUnit.HOURS)
    .rateLimited(10, 1, TimeUnit.MINUTES)
    .build()
```

### AuthVerifier 구현

```kotlin
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import java.security.interfaces.RSAPublicKey

val supabaseVerifier = AuthVerifier<SupabasePrincipal> { token ->
    try {
        val decoded = JWT.decode(token)
        val keyId = decoded.keyId
            ?: return@AuthVerifier AuthResult.Failure("Missing kid in JWT header")

        val publicKey = jwkProvider.get(keyId).publicKey as RSAPublicKey

        val verified = JWT.require(Algorithm.RSA256(publicKey, null))
            .withIssuer("https://$SUPABASE_PROJECT_REF.supabase.co/auth/v1")
            .withAudience("authenticated")
            .build()
            .verify(token)

        AuthResult.Success(
            SupabasePrincipal(
                userId = verified.subject,
                email = verified.getClaim("email").asString(),
                role = verified.getClaim("role").asString() ?: "authenticated",
            )
        )
    } catch (e: JWTVerificationException) {
        AuthResult.Failure("Supabase token invalid: ${e.message}")
    } catch (e: Exception) {
        AuthResult.Failure("Token verification error: ${e.message}")
    }
}
```

### Supabase HS256 (레거시 프로젝트)

2025년 5월 이전 생성된 프로젝트는 HS256을 사용할 수 있다.
이 경우 JWKS가 아닌 JWT Secret으로 직접 검증한다.

```kotlin
val supabaseHmacVerifier = AuthVerifier<SupabasePrincipal> { token ->
    try {
        val jwtSecret = System.getenv("SUPABASE_JWT_SECRET")

        val verified = JWT.require(Algorithm.HMAC256(jwtSecret))
            .withIssuer("https://$SUPABASE_PROJECT_REF.supabase.co/auth/v1")
            .withAudience("authenticated")
            .build()
            .verify(token)

        AuthResult.Success(
            SupabasePrincipal(
                userId = verified.subject,
                email = verified.getClaim("email").asString(),
                role = verified.getClaim("role").asString() ?: "authenticated",
            )
        )
    } catch (e: JWTVerificationException) {
        AuthResult.Failure("Supabase token invalid: ${e.message}")
    }
}
```

### 클라이언트

```kotlin
// Supabase Kotlin Client로 access token 획득
val session = supabase.auth.currentSessionOrNull()
    ?: throw AuthenticationException("Not signed in")

val connection = KtorWebSocketClientConnection.create(
    host = "your-server.com",
    port = 443,
    path = "/chat",
)
    .withAuth(token = session.accessToken)
    .typedJsonAs<SharedAction, AppAction>()
```

---

## 공통 패턴

### Verifier를 클래스로 분리

프로덕션 코드에서는 verifier를 별도 클래스로 분리하면 테스트하기 쉽다.

```kotlin
class JwtAuthVerifier(
    private val issuer: String,
    private val audience: String,
    private val jwkProvider: JwkProvider,
) : AuthVerifier<UserPrincipal> {

    override suspend fun verify(token: String): AuthResult<UserPrincipal> {
        return try {
            val decoded = JWT.decode(token)
            val publicKey = jwkProvider.get(decoded.keyId).publicKey as RSAPublicKey

            val verified = JWT.require(Algorithm.RSA256(publicKey, null))
                .withIssuer(issuer)
                .withAudience(audience)
                .build()
                .verify(token)

            AuthResult.Success(mapToPrincipal(verified))
        } catch (e: JWTVerificationException) {
            AuthResult.Failure("Token invalid: ${e.message}")
        }
    }

    private fun mapToPrincipal(jwt: DecodedJWT) = UserPrincipal(
        userId = jwt.subject,
        displayName = jwt.getClaim("name").asString() ?: jwt.subject,
    )
}
```

### 테스트

`AuthVerifier`는 fun interface이므로 테스트에서 쉽게 모킹할 수 있다.

```kotlin
// 항상 성공하는 verifier
val testVerifier = AuthVerifier<UserPrincipal> { token ->
    AuthResult.Success(UserPrincipal(userId = "test-user", displayName = "Test"))
}

// 항상 실패하는 verifier
val rejectVerifier = AuthVerifier<UserPrincipal> { _ ->
    AuthResult.Failure("Rejected")
}

// 특정 토큰만 허용하는 verifier
val tokenVerifier = AuthVerifier<UserPrincipal> { token ->
    if (token == "valid-token") {
        AuthResult.Success(UserPrincipal(userId = "user-1", displayName = "Alice"))
    } else {
        AuthResult.Failure("Invalid token")
    }
}
```

실제 JWT 검증 로직을 테스트할 때는, 테스트용 키 페어를 생성해서 토큰을 직접 발급/검증한다.

```kotlin
class JwtAuthVerifierTest {
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply {
        initialize(2048)
    }.generateKeyPair()

    private fun issueTestToken(subject: String, name: String): String {
        return JWT.create()
            .withIssuer("test-issuer")
            .withAudience("test-audience")
            .withSubject(subject)
            .withClaim("name", name)
            .withExpiresAt(Date.from(Instant.now().plus(Duration.ofHours(1))))
            .sign(Algorithm.RSA256(keyPair.public as RSAPublicKey, keyPair.private as RSAPrivateKey))
    }

    @Test
    fun validToken_returnsSuccess() = runTest {
        val verifier = createVerifier(keyPair.public as RSAPublicKey)
        val token = issueTestToken(subject = "user-1", name = "Alice")

        val result = verifier.verify(token)

        assertIs<AuthResult.Success<UserPrincipal>>(result)
        assertEquals("user-1", result.principal.userId)
        assertEquals("Alice", result.principal.displayName)
    }

    @Test
    fun expiredToken_returnsFailure() = runTest {
        val verifier = createVerifier(keyPair.public as RSAPublicKey)
        val token = JWT.create()
            .withIssuer("test-issuer")
            .withAudience("test-audience")
            .withSubject("user-1")
            .withExpiresAt(Date.from(Instant.now().minus(Duration.ofHours(1))))
            .sign(Algorithm.RSA256(keyPair.public as RSAPublicKey, keyPair.private as RSAPrivateKey))

        val result = verifier.verify(token)

        assertIs<AuthResult.Failure>(result)
    }
}
```

---

## 비교 요약

| 시나리오 | 알고리즘 | 키 관리 | 의존성 | 복잡도 |
|---|---|---|---|---|
| HS256 공유 시크릿 | HMAC-SHA256 | 환경변수 하나 | `java-jwt` | 낮음 |
| Firebase Auth | RS256 | Google JWKS (자동) | `java-jwt` + `jwks-rsa` | 중간 |
| Supabase Auth (신규) | RS256 | Supabase JWKS (자동) | `java-jwt` + `jwks-rsa` | 중간 |
| Supabase Auth (레거시) | HS256 | JWT Secret | `java-jwt` | 낮음 |

---

## 관련 문서

- [Remote Authentication](./remote-authentication.md) — Auth 모듈 아키텍처 및 사용법
- [WebSocket Authentication (Ktor-Level)](./websocket-authentication.md) — Ktor 레벨 인증 패턴
- [Sample Apps](./samples.md) — Auth 샘플 앱 실행 방법
