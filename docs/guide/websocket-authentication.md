# WebSocket Authentication Guide

## Overview

이 문서는 **Ktor 라우팅 레벨**에서 인증을 처리하는 패턴을 다룬다.
Ktor가 인증을 끝내고, 인증된 userId를 sessionId로 넘기는 구조다.

> **In-Band 인증 모듈**: FlowDux는 `flowdux-remote-auth` 모듈을 통해
> WebSocket 연결 후 첫 메시지로 토큰을 교환하는 **인밴드 인증**도 지원한다.
> Transport에 독립적이며, 브라우저에서도 완전히 동작한다.
> 자세한 내용은 [Remote Authentication](./remote-authentication.md)를 참조.

```
인증 (Ktor 영역)  →  세션 관리 (FlowDux 영역)
      ↑                      ↑
  "누구인가"           "어떤 Store에 연결할까"
```

프레임워크 변경 없이 현재 API로 모든 인증 패턴을 구현할 수 있다.

---

## Connection Flow

```
Client                        Ktor                         FlowDux
  │                             │                             │
  │ GET /ws?token=eyJ...        │                             │
  │ Connection: Upgrade         │                             │
  │───────────────────────────→ │                             │
  │                             │ authService.verify(token)   │
  │                             │ ├─ 실패 → 401, 연결 거부    │
  │                             │ └─ 성공:                    │
  │ 101 Switching Protocols     │                             │
  │←─────────────────────────── │                             │
  │                             │                             │
  │                             │ handleClient(userId, conn)  │
  │                             │────────────────────────────→│
  │                             │                             │
  │ { action: "sendMsg" }       │                             │
  │═══════════════════════════→ │═══════════════════════════→ │
  │                             │                             │
```

---

## Pattern 1: HTTP Upgrade 시 토큰 (Query Parameter)

브라우저 WebSocket에서 가장 보편적인 방식.
`new WebSocket("ws://host/ws?token=xxx")`

```kotlin
routing {
    webSocket("/ws") {
        // ── 인증 (FlowDux 밖) ────────────────────
        val token = call.request.queryParameters["token"]
        val user = authService.verify(token)
        if (user == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
            return@webSocket
        }
        // ── 인증 완료 ────────────────────────────

        val connection = KtorWebSocketServerConnection(this)
            .typedJsonAs<SharedAction, GameAction>()

        server.handleClient(user.id, connection)
    }
}
```

**장점**: 간단, 연결 시점에 인증 완료
**주의**: 토큰이 URL에 노출됨 — 짧은 수명(1~5분) 토큰 사용 권장

---

## Pattern 2: HTTP Header 토큰

네이티브 앱, 서버 간 통신에서 사용.
브라우저 WebSocket API는 커스텀 헤더를 지원하지 않으므로 브라우저에서는 사용 불가.

```kotlin
routing {
    webSocket("/ws") {
        val token = call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")
        val user = authService.verify(token)
        if (user == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
            return@webSocket
        }

        val connection = KtorWebSocketServerConnection(this)
            .typedJsonAs<SharedAction, GameAction>()

        server.handleClient(user.id, connection)
    }
}
```

---

## Pattern 3: 첫 메시지 인증

연결은 허용하되, 첫 메시지로 토큰을 보내야 인증되는 방식.
토큰 갱신, 재인증이 필요한 경우에 유용.

```kotlin
routing {
    webSocket("/ws") {
        // ── 연결 허용, 미인증 상태 ────────────────
        val authFrame = withTimeoutOrNull(5_000) {
            incoming.receive()
        }
        if (authFrame == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Auth timeout"))
            return@webSocket
        }

        val authMsg = (authFrame as Frame.Text).readText()
        val token = Json.parseToJsonElement(authMsg)
            .jsonObject["token"]?.jsonPrimitive?.content

        val user = authService.verify(token)
        if (user == null) {
            outgoing.send(Frame.Text("""{"type":"auth_failed"}"""))
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid token"))
            return@webSocket
        }
        outgoing.send(Frame.Text("""{"type":"auth_ok","userId":"${user.id}"}"""))
        // ── 인증 완료 ────────────────────────────

        // auth 메시지는 이미 소비됨 → FlowDux에 들어가지 않음
        val connection = KtorWebSocketServerConnection(this)
            .typedJsonAs<SharedAction, GameAction>()

        server.handleClient(user.id, connection)
    }
}
```

**장점**: 프로토콜 제약 없음, 재인증 가능
**주의**: 인증 전 타임아웃 필수 (위 예시: 5초)

---

## Pattern 4: Query + Header 복합 (권장)

헤더를 먼저 확인하고, 없으면 쿼리 파라미터를 확인하는 방식.
네이티브 앱과 브라우저 모두 대응 가능.

```kotlin
routing {
    webSocket("/ws") {
        val token = call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")
            ?: call.request.queryParameters["token"]

        val user = authService.verify(token)
        if (user == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
            return@webSocket
        }

        val connection = KtorWebSocketServerConnection(this)
            .typedJsonAs<SharedAction, GameAction>()

        server.handleClient(user.id, connection)
    }
}
```

---

## Action-Level Authorization

연결 인증과 별개로, 특정 액션에 대한 권한 검사는 Processor에서 처리한다.

```kotlin
val processors = Middleware.ActionProcessorBuilder<ChatState, ChatAction>().apply {
    on<ChatAction.DeleteMessage> { state, action ->
        val sender = state.sessions[action.sessionId]
        if (sender?.role != Role.ADMIN) {
            emit(ChatAction.Error(action.sessionId, "Permission denied"))
            return@on
        }
        emit(action)
    }
    on<ChatAction.BanUser> { state, action ->
        val sender = state.sessions[action.sessionId]
        if (sender?.role != Role.ADMIN) {
            emit(ChatAction.Error(action.sessionId, "Permission denied"))
            return@on
        }
        emit(action)
    }
}.build()
```

---

## Token Refresh

WebSocket 연결 중 토큰이 만료되는 경우, 별도 coroutine에서 처리한다.
FlowDux 파이프라인과 무관하게 Ktor 레벨에서 동작.

```kotlin
webSocket("/ws") {
    val token = call.request.queryParameters["token"]
    val user = authService.verify(token) ?: run {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
        return@webSocket
    }

    val connection = KtorWebSocketServerConnection(this)
        .typedJsonAs<SharedAction, GameAction>()

    // 토큰 만료 감시 (FlowDux와 독립)
    val tokenWatcher = launch {
        while (isActive) {
            delay(authService.tokenTtl - 30_000) // 만료 30초 전
            if (!authService.isValid(user.tokenId)) {
                connection.send(GameAction.TokenExpired)
                delay(10_000) // 갱신 유예
                if (!authService.isValid(user.tokenId)) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Token expired"))
                }
            }
        }
    }

    try {
        server.handleClient(user.id, connection)
    } finally {
        tokenWatcher.cancel()
    }
}
```

---

## Summary

| 인증 단계 | 위치 | FlowDux 변경 |
|---|---|---|
| 연결 인증 (누구인가) | Ktor 라우팅 | 불필요 |
| 세션 식별 (어떤 유저) | `sessionId = user.id` | 불필요 |
| 액션 권한 (뭘 할 수 있나) | Processor | 불필요 |
| 토큰 만료 | Ktor coroutine | 불필요 |

FlowDux는 인증을 몰라도 된다.
Ktor가 인증을 끝내고, 인증된 userId를 sessionId로 넘기면
FlowDux는 그 세션이 인증된 유저라고 신뢰한다.

---

## 관련 문서

- [Remote Authentication](./remote-authentication.md) — `flowdux-remote-auth` 모듈 (인밴드 인증)
- [JWT Integration Guide](./jwt-integration.md) — HS256, Firebase Auth, Supabase Auth 통합
- [Remote (WebSocket)](./remote.md) — 기본 클라이언트-서버 설정 가이드
