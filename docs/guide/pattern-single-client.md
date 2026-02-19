# Single Client Pattern (1:1:1)

Single Client 패턴은 각 클라이언트가 독립된 Store를 가지는 가장 단순한 구조입니다.

## 작성해야 할 파일

이 패턴을 구현하려면 다음 파일들을 작성해야 합니다:

```
shared/                           # 공유 모듈
├── State.kt                      # 상태 정의 (UserState + 관련 data classes)
└── Actions.kt                    # 공유 액션 (@Serializable sealed interface)

server/                           # 서버 모듈
├── Main.kt                       # 서버 엔트리 (Ktor, WebSocket 라우팅)
├── Reducer.kt                    # 서버 리듀서
└── Processors.kt                 # (선택) 미들웨어 프로세서

client/                           # 클라이언트 모듈
├── Main.kt                       # 클라이언트 엔트리 (연결, Store 생성)
├── Reducer.kt                    # 클라이언트 리듀서 (SyncState 처리)
└── RemoteMiddleware.kt           # SyncMiddleware 상속 (Connect/Disconnect 처리)
```

### 각 파일의 역할

| 파일 | 필수 | 설명 |
|------|:----:|------|
| `shared/State.kt` | ✅ | `@Serializable` 상태 클래스 |
| `shared/Actions.kt` | ✅ | `ServerSharedAction`, `ClientSharedAction` 마커가 붙은 액션 |
| `server/Main.kt` | ✅ | `createSingleClientServer()` + WebSocket 라우팅 |
| `server/Reducer.kt` | ✅ | 서버 상태 변경 로직 |
| `server/Processors.kt` | ⬜ | 클라이언트 액션 → 서버 내부 액션 변환 |
| `client/Main.kt` | ✅ | 연결 생성, Store 생성, 상태 관찰 |
| `client/Reducer.kt` | ✅ | `SyncState` → 상태 적용 |
| `client/RemoteMiddleware.kt` | ✅ | `SyncMiddleware` 상속, Connect/Disconnect 처리 |

## 개요

```
┌─────────────────────────────────────────────────────────────┐
│                      Server                                  │
│                                                              │
│  ┌─────────┐      ┌─────────┐      ┌─────────┐             │
│  │ Store 1 │      │ Store 2 │      │ Store 3 │             │
│  └────┬────┘      └────┬────┘      └────┬────┘             │
│       │                │                │                   │
└───────┼────────────────┼────────────────┼───────────────────┘
        │                │                │
        ▼                ▼                ▼
   ┌────────┐       ┌────────┐       ┌────────┐
   │Client 1│       │Client 2│       │Client 3│
   └────────┘       └────────┘       └────────┘
```

**특징:**
- 1 Server : 1 Store : 1 Client
- 클라이언트 간 상태 공유 없음
- 각 연결마다 새 Store 생성
- 연결 종료 시 Store 정리

## 언제 사용하나요?

| Use Case | 설명 |
|----------|------|
| **개인 대시보드** | 사용자별 맞춤 데이터 표시 |
| **개인 설정 도구** | 사용자별 설정 관리 |
| **단일 사용자 앱** | 1:1 서비스 (상담, 코칭) |
| **독립 세션** | 각 탭/창이 독립적으로 동작 |
| **테스트/프로토타입** | 간단한 구현으로 빠른 검증 |

## 기본 구현

### Server

```kotlin
import io.flowdux.remote.server.pattern.createSingleClientServer
import io.flowdux.remote.server.serve
import io.flowdux.remote.ktor.KtorWebSocketServerConnection
import io.flowdux.remote.serialization.typedJsonAs

fun main() {
    embeddedServer(CIO, port = 8080) {
        install(WebSockets)

        routing {
            webSocket("/ws") {
                println("Client connected")

                val connection = KtorWebSocketServerConnection(this)
                    .typedJsonAs<SharedUserAction, UserAction>()

                // createSingleClientServer 팩토리 사용 (내부적으로 SingleClientSyncMiddleware 설정)
                val server = createSingleClientServer(
                    initialState = UserState(),
                    reducer = userReducer,
                    connection = connection,
                )

                try {
                    // 상태 변경 시 클라이언트에 자동 전송
                    server.serve { state ->
                        SharedUserAction.SyncState(state)
                    }
                } finally {
                    println("Client disconnected")
                }
            }
        }
    }.start(wait = true)
}
```

### State & Actions

```kotlin
import io.flowdux.Action
import io.flowdux.State
import io.flowdux.remote.ClientSharedAction
import io.flowdux.remote.ServerSharedAction
import kotlinx.serialization.Serializable

// State
@Serializable
data class UserState(
    val name: String = "",
    val preferences: UserPreferences = UserPreferences(),
    val notifications: List<Notification> = emptyList(),
) : State

@Serializable
data class UserPreferences(
    val theme: String = "light",
    val language: String = "en",
    val notificationsEnabled: Boolean = true,
)

@Serializable
data class Notification(
    val id: String,
    val message: String,
    val read: Boolean = false,
)

// Actions
sealed interface UserAction : Action

@Serializable
sealed interface SharedUserAction : UserAction {
    // Client → Server
    @Serializable
    data class UpdatePreferences(val preferences: UserPreferences) : SharedUserAction, ServerSharedAction

    @Serializable
    data class MarkNotificationRead(val notificationId: String) : SharedUserAction, ServerSharedAction

    @Serializable
    data class ClearNotifications(val onlyRead: Boolean = false) : SharedUserAction, ServerSharedAction

    // Server → Client
    @Serializable
    data class SyncState(val state: UserState) : SharedUserAction, ClientSharedAction
}

// Server-only actions
sealed interface ServerUserAction : UserAction {
    data class AddNotification(val notification: Notification) : ServerUserAction
}
```

### Reducer

```kotlin
import io.flowdux.buildReducer

val userReducer = buildReducer<UserState, UserAction> {
    on<SharedUserAction.UpdatePreferences> { state, action ->
        state.copy(preferences = action.preferences)
    }

    on<SharedUserAction.MarkNotificationRead> { state, action ->
        state.copy(
            notifications = state.notifications.map { notification ->
                if (notification.id == action.notificationId) {
                    notification.copy(read = true)
                } else {
                    notification
                }
            }
        )
    }

    on<SharedUserAction.ClearNotifications> { state, action ->
        if (action.onlyRead) {
            state.copy(notifications = state.notifications.filter { !it.read })
        } else {
            state.copy(notifications = emptyList())
        }
    }

    on<ServerUserAction.AddNotification> { state, action ->
        state.copy(notifications = state.notifications + action.notification)
    }
}
```

### Client Middleware

클라이언트는 `SyncMiddleware`를 상속한 미들웨어를 작성해야 합니다:

```kotlin
import io.flowdux.ActionProcessorMap
import io.flowdux.remote.SyncMiddleware
import io.flowdux.remote.TypedClientConnection

// 로컬 전용 액션 (네트워크로 전송되지 않음)
sealed interface LocalUserAction : UserAction {
    data object Connect : LocalUserAction
    data object Disconnect : LocalUserAction
}

class UserRemoteMiddleware(
    connection: TypedClientConnection<UserAction>,
) : SyncMiddleware<UserState, UserAction>(
    connection = connection,
) {
    override val processors: ActionProcessorMap<UserState, UserAction> = buildProcessors {
        on<LocalUserAction.Connect> { _, _ ->
            startConnection()  // 내장: 연결 및 메시지 리스닝 시작
        }
        on<LocalUserAction.Disconnect> { _, _ ->
            stopConnection()   // 내장: 정상 종료
        }
    }
}
```

### Client Usage

```kotlin
import io.flowdux.createStore
import io.flowdux.remote.ktor.KtorWebSocketClientConnection
import io.flowdux.remote.serialization.typedJsonAs

suspend fun main() {
    val connection = KtorWebSocketClientConnection.create(
        host = "localhost",
        port = 8080,
        path = "/ws",
    ).typedJsonAs<SharedUserAction, UserAction>()

    val store = createStore(
        initialState = UserState(),
        reducer = clientUserReducer,
        middlewares = listOf(UserRemoteMiddleware(connection)),
    )

    // 연결
    store.dispatch(LocalUserAction.Connect)

    // 상태 관찰
    store.state.collect { state ->
        println("State updated: $state")
    }
}

// Client reducer - SyncState만 처리
val clientUserReducer = buildReducer<UserState, UserAction> {
    on<SharedUserAction.SyncState> { _, action ->
        action.state
    }
}
```

## Use Cases

### 1. 개인 대시보드

사용자별 맞춤 데이터를 표시하는 대시보드입니다.

```kotlin
@Serializable
data class DashboardState(
    val userId: String = "",
    val widgets: List<Widget> = emptyList(),
    val metrics: UserMetrics = UserMetrics(),
    val recentActivity: List<Activity> = emptyList(),
) : State

// 서버: 사용자 인증 후 Store 생성
webSocket("/dashboard") {
    val userId = authenticateUser(call) ?: return@webSocket close(...)

    val server = createSingleClientServer(
        initialState = DashboardState(userId = userId),
        reducer = dashboardReducer,
        connection = connection,
    )

    // 사용자별 데이터 로드
    val userData = userRepository.loadDashboardData(userId)
    server.dispatch(ServerAction.InitializeData(userData))

    server.serve { SharedAction.SyncDashboard(it) }
}
```

### 2. 실시간 알림 센터

사용자별 알림을 실시간으로 전달합니다.

```kotlin
@Serializable
data class NotificationCenterState(
    val notifications: List<Notification> = emptyList(),
    val unreadCount: Int = 0,
    val settings: NotificationSettings = NotificationSettings(),
) : State

// 서버: 알림 시스템과 연동
webSocket("/notifications") {
    val userId = authenticateUser(call) ?: return@webSocket

    val server = createSingleClientServer(
        initialState = NotificationCenterState(),
        reducer = notificationReducer,
        connection = connection,
    )

    // 알림 구독
    val notificationJob = launch {
        notificationService.subscribeToUser(userId).collect { notification ->
            server.dispatch(ServerAction.NewNotification(notification))
        }
    }

    try {
        server.serve { SharedAction.SyncNotifications(it) }
    } finally {
        notificationJob.cancel()
    }
}
```

### 3. 설정 관리 도구

사용자별 설정을 실시간으로 동기화합니다.

```kotlin
@Serializable
data class SettingsState(
    val profile: ProfileSettings = ProfileSettings(),
    val appearance: AppearanceSettings = AppearanceSettings(),
    val privacy: PrivacySettings = PrivacySettings(),
    val notifications: NotificationSettings = NotificationSettings(),
    val lastSaved: Long = 0,
) : State

// Processor: 설정 변경 시 DB 저장
val settingsProcessors = Middleware.ActionProcessorBuilder<SettingsState, SettingsAction>().apply {
    on<SharedAction.UpdateProfile> { state, action ->
        settingsRepository.saveProfile(state.userId, action.profile)
        emit(action)  // Reducer로 전달
    }

    on<SharedAction.UpdateAppearance> { state, action ->
        settingsRepository.saveAppearance(state.userId, action.appearance)
        emit(action)
    }
}.build()
```

### 4. 1:1 상담/코칭

상담사와 고객 간 1:1 세션을 관리합니다.

```kotlin
@Serializable
data class ConsultationState(
    val sessionId: String = "",
    val messages: List<Message> = emptyList(),
    val participants: Participants = Participants(),
    val status: SessionStatus = SessionStatus.WAITING,
) : State

// 고객 연결
webSocket("/consultation/client/{sessionId}") {
    val sessionId = call.parameters["sessionId"]!!
    val clientStore = createClientStore(sessionId, connection)
    clientStore.serveState { SharedAction.SyncSession(it) }
}

// 상담사 연결
webSocket("/consultation/advisor/{sessionId}") {
    val sessionId = call.parameters["sessionId"]!!
    val advisorStore = createAdvisorStore(sessionId, connection)
    advisorStore.serveState { SharedAction.SyncSession(it) }
}
```

### 5. 개인 작업 도구

개인 작업 관리 도구 (To-Do, 노트 등)입니다.

```kotlin
@Serializable
data class WorkspaceState(
    val tasks: List<Task> = emptyList(),
    val notes: List<Note> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val filter: FilterSettings = FilterSettings(),
) : State

// 서버: 사용자별 작업 공간
webSocket("/workspace") {
    val userId = authenticateUser(call) ?: return@webSocket

    val server = createSingleClientServer(
        initialState = WorkspaceState(),
        reducer = workspaceReducer,
        connection = connection,
        processors = workspaceProcessors(userId),  // DB 연동
    )

    // 초기 데이터 로드
    val savedData = workspaceRepository.load(userId)
    server.dispatch(ServerAction.LoadWorkspace(savedData))

    server.serve { SharedAction.SyncWorkspace(it) }
}
```

## 다른 패턴으로 전환

Single Client 패턴에서 다른 패턴으로 전환해야 하는 신호:

| 신호 | 전환 대상 |
|------|----------|
| "다른 사용자와 같은 데이터를 봐야 해요" | [Shared State](./pattern-shared-state.md) |
| "그룹/방별로 데이터를 분리해야 해요" | [Room](./pattern-room.md) |
| "일부 정보는 본인만 봐야 해요" | [Per-Client](./pattern-per-client.md) |

## Sample App

```bash
# 서버 실행
./gradlew :kotlin:sample-remote-simple:server:run

# 클라이언트 실행
./gradlew :kotlin:sample-remote-simple:client:run
```

## Related

- [Server Patterns Overview](./server-patterns.md) — 패턴 선택 가이드
- [Shared State Pattern](./pattern-shared-state.md) — 다중 클라이언트 상태 공유
- [Remote Guide](./remote.md) — 기본 설정 가이드
