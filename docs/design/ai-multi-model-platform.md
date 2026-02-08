# AI 멀티모델 협업 플랫폼 설계 문서

> Status: Draft
> Created: 2026-02-02
> Based on: [flowdux-remote Scaling Design](./flowdux-remote-scaling.md)

## 목차

1. [서비스 요구사항](#1-서비스-요구사항)
2. [AI 협업 모드 정의](#2-ai-협업-모드-정의)
3. [아키텍처 설계](#3-아키텍처-설계)
4. [State 설계](#4-state-설계)
5. [Action 설계](#5-action-설계)
6. [서버 구성: Per-User Store](#6-서버-구성-per-user-store)
7. [AI Provider Layer](#7-ai-provider-layer)
8. [협업 오케스트레이터](#8-협업-오케스트레이터)
9. [렌더링 중립 전략](#9-렌더링-중립-전략)
10. [데이터 흐름](#10-데이터-흐름)
11. [flowdux-remote 기능 매핑](#11-flowdux-remote-기능-매핑)
12. [클라이언트 전략: Compose Multiplatform](#12-클라이언트-전략-compose-multiplatform)

---

## 1. 서비스 요구사항

### 1.1 서비스 개요

현존하는 LLM들을 래핑하여 하나의 플랫폼에서 다양한 AI를 선택·비교·협업시킬 수 있는 크로스플랫폼 서비스.

### 1.2 핵심 요구사항

```
A. 인증/세션 ─────────────────────────────────────────────
   ─ 로그인 상태가 플랫폼 간 공유됨
   ─ 한 계정으로 여러 디바이스 동시 접속
   ─ 디바이스별 렌더링은 다르지만 데이터는 동일

B. AI 모델 관리 ──────────────────────────────────────────
   ─ 사용 가능한 AI 목록 (Claude, GPT, Gemini, Llama, Mistral, ...)
   ─ 구독 등급별 사용 가능 모델/쿼터
   ─ 모델별 설정 (temperature, system prompt, max tokens, ...)

C. 대화 관리 ────────────────────────────────────────────
   ─ 대화 생성/삭제/목록
   ─ 대화 히스토리 (메시지 스트리밍)
   ─ 대화 내 모델 전환 (같은 대화에서 Claude → GPT 전환)

D. AI 협업 ──────────────────────────────────────────────
   ─ 한 대화에 여러 AI 참여
   ─ 협업 모드: 토론, 릴레이, 병렬 비교, 검증, 합성
   ─ 사용자가 AI 간 역할 배정 가능

E. 렌더링 ──────────────────────────────────────────────
   ─ 서버는 데이터만 전달, 표현 방식을 강제하지 않음
   ─ 같은 State에서 2D 채팅 / 3D 공간 / 칸반 등 다양한 뷰 가능
   ─ 리치 콘텐츠: 코드, 수식, 이미지, 차트 등
```

### 1.3 요구사항 → flowdux-remote 매핑 (요약)

| 요구사항 | flowdux-remote 매핑 |
|----------|---------------------|
| 크로스 플랫폼 동기화 | Per-User `RemoteServer` — 같은 유저의 모든 디바이스가 같은 Store에 연결 |
| 플랫폼별 표현 자유 | `ContentBlock` sealed interface — 서버는 구조화된 데이터만 전달 |
| 구독 모델 | State에 `Subscription` 포함, Processor에서 쿼터 체크 |
| AI 협업 | Processor 내 오케스트레이터 — 협업 모드별 AI Provider 호출 패턴 |
| 스트리밍 | `StreamChunk` / `StreamComplete` — `ClientSharedAction`으로 실시간 전달 |

---

## 2. AI 협업 모드 정의

이 서비스의 핵심 차별점. 5가지 협업 모드를 정의한다.

### 2.1 토론 (Debate)

```
사용자: "React vs Vue, 각각 옹호해봐"

  사용자 ───질문──────────────────────────────────►
                                                    │
  Claude (React 옹호) ◄──── 역할 배정 ─────────────┤
  GPT (Vue 옹호)      ◄──── 역할 배정 ─────────────┘

  Turn 1: Claude ──► "React의 생태계는..."
  Turn 2: GPT    ──► "Vue의 학습 곡선은..."
  Turn 3: Claude ──► "하지만 React Hooks..."
  Turn 4: GPT    ──► "Composition API로..."

  또는 자유 토론 (동시 응답)
```

**오케스트레이션:**
- 턴제: AI A 응답 완료 → AI B에게 이전 응답 포함하여 호출
- 자유: 동시에 호출, 각각 이전 전체 컨텍스트 포함

### 2.2 릴레이 (Relay)

```
사용자: "Claude가 코드 짜고, GPT가 리뷰해줘"

  사용자 ───요청───► Claude ──코드 생성──► GPT ──코드 리뷰──► 사용자
                       │                    │
                       ▼                    ▼
                   Message 1            Message 2
                   (코드)               (리뷰 결과)
```

**오케스트레이션:**
- 파이프라인 순서대로 순차 호출
- 이전 AI의 출력을 다음 AI의 컨텍스트에 포함

### 2.3 병렬 비교 (Parallel)

```
사용자: "이 버그 원인이 뭘까?"

  사용자 ───질문───┬──► Claude ──► 응답 A
                   ├──► GPT    ──► 응답 B
                   └──► Gemini ──► 응답 C

  3개 응답이 동시에 스트리밍 → 클라이언트가 나란히 표시
```

**오케스트레이션:**
- 모든 AI에게 동일한 프롬프트로 동시 호출
- 각 AI의 스트리밍이 독립적으로 클라이언트에 전달

### 2.4 검증 (Verify)

```
사용자: "이거 맞아?"

  사용자 ───질문───► Claude (Primary) ──답변──► Gemini (Verifier)
                                                    │
                                                    ▼
                                              검증 결과
                                              "Claude의 답변에서 X는 정확하지만
                                               Y는 다음과 같이 수정이 필요합니다..."
```

**오케스트레이션:**
- Primary AI 호출 → 응답 완료 → Verifier AI에게 원본 질문 + Primary 응답 전달

### 2.5 합성 (Synthesis)

```
사용자: "이 문제에 대한 종합 의견을 만들어줘"

  사용자 ───질문───┬──► Claude  ──► 개별 의견 A
                   ├──► GPT     ──► 개별 의견 B
                   └──► Gemini  ──► 개별 의견 C
                                        │
                                        ▼
                              Synthesizer AI (Claude or GPT)
                                        │
                                        ▼
                                  종합 정리 의견
```

**오케스트레이션:**
- Phase 1: 모든 contributor AI에게 동시 호출 (Parallel과 동일)
- Phase 2: 모든 개별 응답 완료 후, Synthesizer AI에게 전체 응답 + "종합해줘" 요청

---

## 3. 아키텍처 설계

### 3.1 전체 구조

```
┌─────────────────────────────────────────────────────────────────┐
│  Clients (렌더링 자유)                                           │
│                                                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐       │
│  │ Web      │  │ iOS      │  │ Android  │  │ Desktop  │       │
│  │ (React)  │  │ (SwiftUI)│  │ (Compose)│  │ (KMP)    │       │
│  │          │  │          │  │          │  │          │       │
│  │ 2D Chat  │  │ Card UI  │  │ 2D Chat  │  │ 3D Space │       │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘       │
│       │              │              │              │             │
│       └──────────────┴──────┬───────┴──────────────┘             │
│                             │                                    │
│                        WebSocket (flowdux-remote)                │
└─────────────────────────────┼────────────────────────────────────┘
                              │
┌─────────────────────────────┼────────────────────────────────────┐
│  Server                     │                                    │
│                             ▼                                    │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  UserSessionManager                                       │   │
│  │  ┌────────────────────────────────────────────────────┐   │   │
│  │  │ RemoteServer (per-user)                             │   │   │
│  │  │ Store<UserSessionState, SessionAction>              │   │   │
│  │  │                                                     │   │   │
│  │  │ Processors:                                         │   │   │
│  │  │   ├── SendMessage → AI Provider Router              │   │   │
│  │  │   ├── StartCollaboration → 협업 오케스트레이터       │   │   │
│  │  │   └── SwitchModel → 모델 전환                       │   │   │
│  │  └────────────────────────────────────────────────────┘   │   │
│  └──────────────────────────────────────────────────────────┘   │
│                              │                                   │
│                              ▼                                   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  AI Provider Layer                                        │   │
│  │  ┌────────────┐ ┌────────────┐ ┌────────────┐            │   │
│  │  │ Anthropic   │ │ OpenAI     │ │ Google     │ ...        │   │
│  │  │ (Claude)    │ │ (GPT)      │ │ (Gemini)   │            │   │
│  │  └────────────┘ └────────────┘ └────────────┘            │   │
│  └──────────────────────────────────────────────────────────┘   │
│                              │                                   │
│                              ▼                                   │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  Persistence Layer                                        │   │
│  │  ├── UserRepository (프로필, 구독)                         │   │
│  │  ├── ConversationRepository (대화 히스토리)                 │   │
│  │  └── UsageRepository (쿼터, 사용량)                        │   │
│  └──────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────┘
```

### 3.2 Per-User Store 패턴

[Scaling Design Part 3 §14](./flowdux-remote-scaling.md#14-패턴별-use-case-구현-가능성)의 패턴을 조합:
- **Pattern A (Central Store)** 기반이되, **사용자별 독립 Store**
- 같은 유저의 여러 디바이스는 같은 Store에 연결 (크로스 플랫폼 동기화)
- 다른 유저의 Store는 완전히 격리

```
alice (iPhone)  ──ws──┐
                      ├──► RemoteServer("alice")  ──► Store<UserSessionState>
alice (MacBook) ──ws──┤
alice (Web)     ──ws──┘

bob (Android)   ──ws──────► RemoteServer("bob")   ──► Store<UserSessionState>

→ alice의 어느 디바이스에서 메시지를 보내든 3개 디바이스 모두 동기화
→ bob은 alice의 상태를 전혀 모름
```

### 3.3 스케일링 전략

[Scaling Design Part 1](./flowdux-remote-scaling.md#6-전략-비교-및-도입-로드맵) 적용:

```
~1,000 유저:
  단일 서버. UserSessionManager가 in-memory로 per-user RemoteServer 관리.

~10,000 유저:
  Phase 1 적용. 활성 유저만 메모리에 유지, 비활성은 영속화 후 해제.

~100,000 유저:
  Phase 2 적용 (Pub/Sub Backplane).
  여러 서버에 유저를 분산. Consistent hashing으로 같은 유저는 같은 서버로.

~1M+ 유저:
  Phase 3 적용 (Gateway 분리).
  Gateway는 연결만 관리, Logic Server가 UserSessionManager 운영.
```

---

## 4. State 설계

### 4.1 최상위 State

```kotlin
data class UserSessionState(
    val user: UserProfile,
    val subscription: Subscription,
    val conversations: Map<String, Conversation>,
    val activeConversationId: String?,
    val availableModels: List<AIModel>,
) : State
```

### 4.2 사용자 / 구독

```kotlin
data class UserProfile(
    val id: String,
    val name: String,
    val avatarUrl: String?,
)

data class Subscription(
    val tier: SubscriptionTier,             // FREE, PRO, UNLIMITED
    val quotaRemaining: Map<String, Int>,   // 모델별 남은 쿼터
)

enum class SubscriptionTier {
    FREE,       // 기본 모델만, 일일 제한
    PRO,        // 주요 모델, 넉넉한 쿼터
    UNLIMITED,  // 전체 모델, 무제한
}

data class AIModel(
    val id: String,                         // "claude-opus-4-5", "gpt-4o", ...
    val displayName: String,
    val provider: String,                   // "anthropic", "openai", "google", ...
    val capabilities: Set<ModelCapability>, // TEXT, CODE, IMAGE, TOOL_USE, ...
    val requiredTier: SubscriptionTier,
)
```

### 4.3 대화

```kotlin
data class Conversation(
    val id: String,
    val title: String,
    val messages: List<Message>,
    val participants: List<Participant>,     // 사용자 + AI들
    val collaborationMode: CollaborationMode?,
    val createdAt: Long,
)

data class Message(
    val id: String,
    val participantId: String,              // 누가 보냈는지 (user or AI id)
    val content: List<ContentBlock>,        // 렌더링 중립적 콘텐츠
    val timestamp: Long,
    val replyTo: String?,                   // 협업 시 어떤 메시지에 대한 응답인지
    val metadata: Map<String, String>,      // 모델명, token 수, latency 등
)
```

### 4.4 참여자

```kotlin
sealed interface Participant {
    val id: String
    val displayName: String

    data class Human(
        override val id: String,
        override val displayName: String,
    ) : Participant

    data class AI(
        override val id: String,
        override val displayName: String,
        val modelId: String,                // "claude-opus-4-5", "gpt-4o", ...
        val provider: String,               // "anthropic", "openai", ...
        val role: String?,                  // 협업 시 역할 ("reviewer", "advocate", ...)
        val systemPrompt: String?,
    ) : Participant
}
```

### 4.5 협업 모드

```kotlin
sealed interface CollaborationMode {
    data class Debate(
        val topic: String,
        val turnBased: Boolean,
    ) : CollaborationMode

    data class Relay(
        val pipeline: List<String>,         // AI participant id 순서
    ) : CollaborationMode

    data object Parallel : CollaborationMode

    data class Verify(
        val primaryAiId: String,
        val verifierAiId: String,
    ) : CollaborationMode

    data class Synthesis(
        val contributorIds: List<String>,
        val synthesizerAiId: String,
    ) : CollaborationMode
}
```

### 4.6 스트리밍

```kotlin
data class StreamingState(
    val participantId: String,
    val chunks: List<ContentBlock>,         // 지금까지 받은 청크
    val isComplete: Boolean,
)
```

---

## 5. Action 설계

### 5.1 Client → Server (ServerSharedAction)

```kotlin
sealed interface SessionAction : Action {

    // ── 대화 관리 ──
    data class CreateConversation(val title: String)
        : SessionAction, ServerSharedAction

    data class DeleteConversation(val convId: String)
        : SessionAction, ServerSharedAction

    data class SelectConversation(val convId: String)
        : SessionAction, ServerSharedAction

    // ── 메시지 전송 ──
    data class SendMessage(
        val convId: String,
        val text: String,
        val targetModelId: String?,         // null이면 현재 활성 AI
    ) : SessionAction, ServerSharedAction

    // ── AI 참여자 관리 ──
    data class AddAIToConversation(
        val convId: String,
        val modelId: String,
        val role: String?,
        val systemPrompt: String?,
    ) : SessionAction, ServerSharedAction

    data class RemoveAIFromConversation(
        val convId: String,
        val participantId: String,
    ) : SessionAction, ServerSharedAction

    // ── 협업 ──
    data class StartCollaboration(
        val convId: String,
        val mode: CollaborationMode,
    ) : SessionAction, ServerSharedAction

    data class StopCollaboration(val convId: String)
        : SessionAction, ServerSharedAction

    data class StopStreaming(val convId: String)
        : SessionAction, ServerSharedAction
```

### 5.2 Server → Client (ClientSharedAction)

```kotlin
    // ── 상태 동기화 ──
    data class SyncState(val state: UserSessionState)
        : SessionAction, ClientSharedAction

    // ── 스트리밍 ──
    data class StreamChunk(
        val convId: String,
        val participantId: String,
        val chunk: ContentBlock,
    ) : SessionAction, ClientSharedAction

    data class StreamComplete(
        val convId: String,
        val participantId: String,
        val fullMessage: Message,
    ) : SessionAction, ClientSharedAction

    // ── 부분 업데이트 ──
    data class ConversationUpdated(val conversation: Conversation)
        : SessionAction, ClientSharedAction

    data class QuotaUpdated(val quotaRemaining: Map<String, Int>)
        : SessionAction, ClientSharedAction

    data class Error(val code: String, val message: String)
        : SessionAction, ClientSharedAction
```

### 5.3 Server Internal

```kotlin
    // ── 서버 내부 (wire에 노출되지 않음) ──
    data class AIResponseReceived(
        val convId: String,
        val participantId: String,
        val message: Message,
    ) : SessionAction

    data class CollaborationNext(
        val convId: String,
        val nextParticipantId: String,
        val context: List<Message>,
    ) : SessionAction
}
```

### 5.4 Action 흐름 정리

```
Client → Server (ServerSharedAction):
  SendMessage ──────────────────────────► Processor가 AI 호출
  StartCollaboration ───────────────────► 오케스트레이터 시작
  AddAIToConversation ──────────────────► Reducer가 참여자 추가
  StopStreaming ────────────────────────► AI 호출 취소

Server → Client (ClientSharedAction):
  SyncState ◄────────────────────────── 전체 상태 동기화
  StreamChunk ◄─────────────────────── AI 응답 스트리밍 청크
  StreamComplete ◄──────────────────── AI 응답 완료 + 최종 메시지
  ConversationUpdated ◄────────────── 대화 부분 업데이트
  Error ◄──────────────────────────── 에러 알림

Server Internal:
  AIResponseReceived ──────────────── AI 전체 응답 수신 (Reducer용)
  CollaborationNext ───────────────── 릴레이/토론에서 다음 AI 턴
```

---

## 6. 서버 구성: Per-User Store

### 6.1 UserSessionManager

```kotlin
class UserSessionManager(
    private val aiProviders: AIProviderRegistry,
    private val userRepository: UserRepository,
    private val scope: CoroutineScope,
) {
    private val sessions = ConcurrentHashMap<String, RemoteServer<UserSessionState, SessionAction>>()

    suspend fun getOrCreateSession(
        userId: String,
    ): RemoteServer<UserSessionState, SessionAction> {
        return sessions.getOrPut(userId) {
            val user = userRepository.getUser(userId)
            val subscription = userRepository.getSubscription(userId)

            createRemoteServer(
                initialState = UserSessionState(
                    user = user,
                    subscription = subscription,
                    conversations = userRepository.getConversations(userId),
                    activeConversationId = null,
                    availableModels = aiProviders.modelsFor(subscription.tier),
                ),
                reducer = sessionReducer,
                processors = sessionProcessors(aiProviders),
                stateMapper = { state -> SessionAction.SyncState(state) },
                scope = scope,
            )
        }
    }

    /** 비활성 유저의 Store를 해제하여 메모리 절약 */
    suspend fun evictInactive(maxIdleMs: Long) {
        // 마지막 액션으로부터 maxIdleMs 이상 경과한 세션 해제
        // 대화 히스토리는 이미 영속화되어 있으므로 안전
    }
}
```

### 6.2 Ktor 엔드포인트

```kotlin
fun Application.configureRouting(sessionManager: UserSessionManager) {
    install(WebSockets)

    routing {
        authenticate {
            webSocket("/session") {
                val userId = call.principal<UserPrincipal>()!!.id
                val server = sessionManager.getOrCreateSession(userId)

                // 디바이스별 세션 ID (같은 유저, 다른 디바이스 구분)
                val deviceId = call.request.header("X-Device-Id")
                    ?: UUID.randomUUID().toString()
                val sessionId = "${userId}:${deviceId}"

                val connection = KtorWebSocketServerConnection(this)
                    .typedJson<SessionAction>() as TypedServerConnection<SessionAction>

                server.handleClient(sessionId, connection)
            }
        }
    }
}
```

### 6.3 크로스 플랫폼 동기화 원리

```
alice가 iPhone에서 메시지를 보냄:

  iPhone ──ws── server
                  │
                  ▼
  RemoteServer("alice")
  Store<UserSessionState>
    │
    ├── Processor: SendMessage 처리
    │   └── Anthropic API 호출 → StreamChunk 생성
    │
    ├── Reducer: 상태 업데이트 (새 메시지 추가)
    │
    └── stateMapper: SyncState(updatedState) → broadcast
                │
                ├──► iPhone (alice:iphone)  ── SyncState 수신
                ├──► MacBook (alice:macbook) ── SyncState 수신
                └──► Web (alice:web)        ── SyncState 수신

  3개 디바이스 모두 동일한 상태로 동기화.
  StreamChunk도 ClientSharedAction이므로 3개 디바이스 모두 실시간 스트리밍.
```

---

## 7. AI Provider Layer

### 7.1 Provider 추상화

```kotlin
interface AIProvider {
    val providerId: String                      // "anthropic", "openai", "google", ...
    val models: List<AIModel>

    fun stream(
        model: String,
        messages: List<ProviderMessage>,
        config: ModelConfig,
    ): Flow<ContentBlock>                       // 스트리밍 응답

    suspend fun complete(
        model: String,
        messages: List<ProviderMessage>,
        config: ModelConfig,
    ): List<ContentBlock>                       // 전체 응답 (비스트리밍)
}

data class ProviderMessage(
    val role: String,                           // "user", "assistant", "system"
    val content: List<ContentBlock>,
)

data class ModelConfig(
    val temperature: Float = 1.0f,
    val maxTokens: Int = 4096,
    val systemPrompt: String? = null,
)
```

### 7.2 Provider Registry

```kotlin
class AIProviderRegistry(
    private val providers: Map<String, AIProvider>,
) {
    fun providerFor(modelId: String): AIProvider {
        val providerId = modelId.toProviderId()     // "claude-*" → "anthropic"
        return providers[providerId]
            ?: throw IllegalArgumentException("Unknown provider for $modelId")
    }

    fun modelsFor(tier: SubscriptionTier): List<AIModel> {
        return providers.values
            .flatMap { it.models }
            .filter { it.requiredTier <= tier }
    }
}
```

### 7.3 Provider 구현 예시

```kotlin
class AnthropicProvider(private val apiKey: String) : AIProvider {
    override val providerId = "anthropic"
    override val models = listOf(
        AIModel("claude-opus-4-5", "Claude Opus 4.5", "anthropic", setOf(TEXT, CODE, IMAGE, TOOL_USE), PRO),
        AIModel("claude-sonnet-4", "Claude Sonnet 4", "anthropic", setOf(TEXT, CODE, TOOL_USE), FREE),
        AIModel("claude-haiku-3.5", "Claude Haiku 3.5", "anthropic", setOf(TEXT, CODE), FREE),
    )

    override fun stream(model: String, messages: List<ProviderMessage>, config: ModelConfig): Flow<ContentBlock> = flow {
        // Anthropic Messages API 스트리밍 호출
        // SSE 이벤트를 ContentBlock으로 변환하여 emit
    }
}

class OpenAIProvider(private val apiKey: String) : AIProvider {
    override val providerId = "openai"
    override val models = listOf(
        AIModel("gpt-4o", "GPT-4o", "openai", setOf(TEXT, CODE, IMAGE), PRO),
        AIModel("gpt-4o-mini", "GPT-4o Mini", "openai", setOf(TEXT, CODE), FREE),
        AIModel("o3", "o3", "openai", setOf(TEXT, CODE, REASONING), UNLIMITED),
    )
    // ...
}
```

---

## 8. 협업 오케스트레이터

### 8.1 Processor 구성

```kotlin
fun sessionProcessors(
    aiProviders: AIProviderRegistry,
) = Middleware.ActionProcessorBuilder<UserSessionState, SessionAction>().apply {

    // 단일 AI 메시지 전송
    on<SessionAction.SendMessage> { getState, action ->
        handleSendMessage(getState, action, aiProviders)
    }

    // 협업 시작
    on<SessionAction.StartCollaboration> { getState, action ->
        handleStartCollaboration(getState, action, aiProviders)
    }

    // 스트리밍 중단
    on<SessionAction.StopStreaming> { getState, action ->
        // 현재 진행 중인 AI 호출 취소
        emit(action) // Reducer에 전달하여 streaming state 정리
    }

}.build()
```

### 8.2 단일 메시지 처리

```kotlin
private suspend fun FlowCollector<SessionAction>.handleSendMessage(
    getState: () -> UserSessionState,
    action: SessionAction.SendMessage,
    aiProviders: AIProviderRegistry,
) {
    val state = getState()
    val conv = state.conversations[action.convId] ?: return

    // 사용자 메시지를 Reducer에 전달
    emit(SessionAction.AIResponseReceived(
        convId = action.convId,
        participantId = state.user.id,
        message = Message(
            id = uuid(),
            participantId = state.user.id,
            content = listOf(ContentBlock.Text(action.text)),
            timestamp = now(),
            replyTo = null,
            metadata = emptyMap(),
        ),
    ))

    // 대상 AI 결정
    val targetAI = conv.participants.filterIsInstance<Participant.AI>()
        .find { it.modelId == action.targetModelId }
        ?: conv.participants.filterIsInstance<Participant.AI>().firstOrNull()
        ?: return

    // AI 스트리밍 호출
    val provider = aiProviders.providerFor(targetAI.modelId)
    val chunks = mutableListOf<ContentBlock>()

    provider.stream(targetAI.modelId, conv.toProviderMessages(), targetAI.toConfig())
        .collect { chunk ->
            chunks.add(chunk)
            emit(SessionAction.StreamChunk(action.convId, targetAI.id, chunk))
        }

    // 완료
    val fullMessage = Message(
        id = uuid(),
        participantId = targetAI.id,
        content = chunks,
        timestamp = now(),
        replyTo = null,
        metadata = mapOf("model" to targetAI.modelId, "provider" to targetAI.provider),
    )
    emit(SessionAction.StreamComplete(action.convId, targetAI.id, fullMessage))
    emit(SessionAction.AIResponseReceived(action.convId, targetAI.id, fullMessage))
}
```

### 8.3 협업 모드별 오케스트레이션

```kotlin
private suspend fun FlowCollector<SessionAction>.handleStartCollaboration(
    getState: () -> UserSessionState,
    action: SessionAction.StartCollaboration,
    aiProviders: AIProviderRegistry,
) {
    val state = getState()
    val conv = state.conversations[action.convId] ?: return

    when (val mode = action.mode) {

        // ── 병렬 비교: 모든 AI에게 동시 호출 ──
        is CollaborationMode.Parallel -> {
            val aiParticipants = conv.participants.filterIsInstance<Participant.AI>()
            coroutineScope {
                aiParticipants.forEach { ai ->
                    launch {
                        streamFromAI(ai, conv, action.convId, aiProviders)
                    }
                }
            }
        }

        // ── 릴레이: 순차 호출, 이전 출력 → 다음 입력 ──
        is CollaborationMode.Relay -> {
            var context = conv.messages
            for (aiId in mode.pipeline) {
                val ai = conv.participants.find { it.id == aiId }
                    as? Participant.AI ?: continue
                val response = streamFromAI(ai, conv.copy(messages = context), action.convId, aiProviders)
                context = context + response
            }
        }

        // ── 토론: 턴제로 번갈아 호출 ──
        is CollaborationMode.Debate -> {
            val aiParticipants = conv.participants.filterIsInstance<Participant.AI>()
            var context = conv.messages
            val maxTurns = 6 // 설정 가능

            if (mode.turnBased) {
                repeat(maxTurns) { turn ->
                    val ai = aiParticipants[turn % aiParticipants.size]
                    val response = streamFromAI(ai, conv.copy(messages = context), action.convId, aiProviders)
                    context = context + response
                }
            } else {
                // 자유 토론: Parallel과 유사하지만 이전 컨텍스트 포함
                coroutineScope {
                    aiParticipants.forEach { ai ->
                        launch {
                            streamFromAI(ai, conv, action.convId, aiProviders)
                        }
                    }
                }
            }
        }

        // ── 검증: Primary → Verifier ──
        is CollaborationMode.Verify -> {
            val primary = conv.participants.find { it.id == mode.primaryAiId } as? Participant.AI ?: return
            val verifier = conv.participants.find { it.id == mode.verifierAiId } as? Participant.AI ?: return

            // Step 1: Primary 응답
            val primaryResponse = streamFromAI(primary, conv, action.convId, aiProviders)

            // Step 2: Verifier에게 원본 + Primary 응답 전달
            val verifyConv = conv.copy(messages = conv.messages + primaryResponse)
            streamFromAI(verifier, verifyConv, action.convId, aiProviders)
        }

        // ── 합성: 모든 AI → Synthesizer ──
        is CollaborationMode.Synthesis -> {
            // Phase 1: 개별 응답 수집
            val responses = mutableListOf<Message>()
            coroutineScope {
                mode.contributorIds.forEach { aiId ->
                    launch {
                        val ai = conv.participants.find { it.id == aiId } as? Participant.AI ?: return@launch
                        val response = streamFromAI(ai, conv, action.convId, aiProviders)
                        synchronized(responses) { responses.addAll(response) }
                    }
                }
            }

            // Phase 2: Synthesizer에게 모든 응답 전달
            val synthesizer = conv.participants.find { it.id == mode.synthesizerAiId }
                as? Participant.AI ?: return
            val synthConv = conv.copy(messages = conv.messages + responses)
            streamFromAI(synthesizer, synthConv, action.convId, aiProviders)
        }
    }
}

/** AI 스트리밍 호출 헬퍼. 청크를 emit하고 최종 메시지를 반환. */
private suspend fun FlowCollector<SessionAction>.streamFromAI(
    ai: Participant.AI,
    conv: Conversation,
    convId: String,
    aiProviders: AIProviderRegistry,
): List<Message> {
    val provider = aiProviders.providerFor(ai.modelId)
    val chunks = mutableListOf<ContentBlock>()

    provider.stream(ai.modelId, conv.toProviderMessages(), ai.toConfig())
        .collect { chunk ->
            chunks.add(chunk)
            emit(SessionAction.StreamChunk(convId, ai.id, chunk))
        }

    val msg = Message(
        id = uuid(),
        participantId = ai.id,
        content = chunks,
        timestamp = now(),
        replyTo = null,
        metadata = mapOf("model" to ai.modelId, "provider" to ai.provider),
    )
    emit(SessionAction.StreamComplete(convId, ai.id, msg))
    emit(SessionAction.AIResponseReceived(convId, ai.id, msg))
    return listOf(msg)
}
```

---

## 9. 렌더링 중립 전략

### 9.1 ContentBlock sealed interface

서버는 **구조화된 콘텐츠 블록**만 전달한다. 렌더링 방법은 전적으로 클라이언트가 결정한다.

```kotlin
sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class Code(val code: String, val language: String) : ContentBlock
    data class Image(val url: String, val alt: String) : ContentBlock
    data class Math(val latex: String) : ContentBlock
    data class Chart(val type: String, val data: JsonElement) : ContentBlock
    data class Thinking(val text: String) : ContentBlock
    data class ToolUse(
        val name: String,
        val input: JsonElement,
        val output: JsonElement?,
    ) : ContentBlock
    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
    ) : ContentBlock
    data class Reference(
        val title: String,
        val url: String,
        val snippet: String?,
    ) : ContentBlock
    // 확장 가능 — 새 블록 타입은 Scaling Design Part 2 Case 3과 동일하게 처리
}
```

### 9.2 플랫폼별 렌더링 예시

```
서버가 보내는 것 (동일):

  Message(content = [
    ContentBlock.Text("다음은 피보나치 함수입니다:"),
    ContentBlock.Code("fun fib(n: Int)...", language = "kotlin"),
    ContentBlock.Math("O(2^n)"),
    ContentBlock.Chart(type = "line", data = {...}),
  ])


각 클라이언트가 렌더링하는 방식:

  ┌───────────────────────────────────────────────────────┐
  │  Web (2D Chat)                                        │
  │                                                       │
  │  Text    → <p> 태그                                   │
  │  Code    → Prism.js / Monaco 코드 하이라이팅           │
  │  Math    → KaTeX 렌더링                               │
  │  Chart   → Chart.js / D3.js                           │
  └───────────────────────────────────────────────────────┘

  ┌───────────────────────────────────────────────────────┐
  │  iOS (Card UI)                                        │
  │                                                       │
  │  Text    → UILabel / SwiftUI Text                     │
  │  Code    → 접기 가능한 코드 카드 (Highlightr)          │
  │  Math    → WKWebView + MathJax                        │
  │  Chart   → Swift Charts                               │
  └───────────────────────────────────────────────────────┘

  ┌───────────────────────────────────────────────────────┐
  │  Desktop (3D Space)                                   │
  │                                                       │
  │  Text    → 3D 말풍선                                  │
  │  Code    → 떠다니는 코드 패널                          │
  │  Math    → 3D 수식 오브젝트                           │
  │  Chart   → 3D 인터랙티브 차트                          │
  └───────────────────────────────────────────────────────┘

  모르는 ContentBlock → fallback (JSON raw 또는 텍스트화)
```

### 9.3 확장성 — Versioning 관계

새 `ContentBlock` 타입 추가는 [Scaling Design Part 2 §10 Case 3](./flowdux-remote-scaling.md#10-케이스별-배포-전략)과 동일한 패턴:

```
신규: ContentBlock.Mermaid(val diagram: String)

구버전 클라이언트:
  ─ ignoreUnknownKeys = true → ContentBlock 자체는 역직렬화 성공
  ─ 모르는 서브타입 → lenient decode로 스킵 또는 fallback 렌더링

신버전 클라이언트:
  ─ Mermaid 렌더러로 표시

→ 강업 없이 자연 확장 가능 (Scaling Design Part 2 인프라 필수)
```

---

## 10. 데이터 흐름

### 10.1 단일 AI 메시지

```
① alice (iPhone)
   dispatch(SendMessage("conv-1", "피보나치 함수 짜줘", "claude-opus-4-5"))
      │
      ▼ ServerSharedAction → WebSocket
      │
② Server: RemoteServer("alice")
   MultiClientSingleClientSyncMiddleware
      │
      ├── ServerSharedAction이므로 Processor로 라우팅
      │
      ▼
③ Processor: handleSendMessage()
      │
      ├── emit(AIResponseReceived)  → Reducer: 사용자 메시지를 State에 추가
      │
      ├── AnthropicProvider.stream("claude-opus-4-5", ...)
      │      │
      │      ▼ (SSE 스트리밍)
      │
      ├── emit(StreamChunk(Text("다음은...")))  ──► ClientSharedAction → broadcast
      ├── emit(StreamChunk(Code("fun fib..."))) ──► ClientSharedAction → broadcast
      ├── emit(StreamChunk(Math("O(2^n)")))     ──► ClientSharedAction → broadcast
      │
      ├── emit(StreamComplete(fullMessage))      ──► ClientSharedAction → broadcast
      └── emit(AIResponseReceived(fullMessage))  ──► Reducer: AI 메시지를 State에 추가
                                                           │
                                                           ▼
                                                     SyncState → broadcast
      │
      ▼
④ 모든 디바이스 수신:
   iPhone  ── StreamChunk × N → 실시간 렌더링
   MacBook ── StreamChunk × N → 실시간 렌더링
   Web     ── StreamChunk × N → 실시간 렌더링
```

### 10.2 병렬 비교 협업

```
① alice: StartCollaboration("conv-1", Parallel)
        + SendMessage("conv-1", "이 코드 리뷰해줘", null)
      │
      ▼
② Processor: handleStartCollaboration()
      │
      ├── coroutineScope {
      │     launch { streamFromAI(claude, ...) }  ──► StreamChunk(claude, ...)
      │     launch { streamFromAI(gpt, ...) }     ──► StreamChunk(gpt, ...)
      │     launch { streamFromAI(gemini, ...) }   ──► StreamChunk(gemini, ...)
      │   }
      │
      ▼ 3개 AI의 StreamChunk가 독립적으로 broadcast
      │
③ 클라이언트: participantId로 구분하여 렌더링
      │
      ├── Web:     3컬럼 레이아웃
      ├── iPhone:  탭 전환
      └── Desktop: 3D 공간에 3개 패널
```

### 10.3 릴레이 협업

```
① alice: StartCollaboration("conv-1", Relay(["claude-1", "gpt-1"]))

② Processor:
      │
      ├── streamFromAI(claude, conv)
      │     ├── StreamChunk(claude, Code("fun fib..."))
      │     └── StreamComplete(claude, fullCodeMessage)
      │
      ├── context = conv.messages + codeMessage
      │
      └── streamFromAI(gpt, conv.copy(messages = context))
            ├── StreamChunk(gpt, Text("코드 리뷰 결과:"))
            └── StreamComplete(gpt, fullReviewMessage)

③ 클라이언트: claude 응답 완료 → gpt 응답 시작 순서로 표시
```

---

## 11. flowdux-remote 기능 매핑

### 11.1 사용하는 패턴

| 기능 | flowdux-remote 패턴 | Scaling Design 참조 |
|------|---------------------|---------------------|
| 크로스 플랫폼 동기화 | Per-User `RemoteServer` + `stateMapper` broadcast | Part 3 §14.1 (Pattern A) |
| 디바이스별 세션 | 같은 `RemoteServer`에 여러 `handleClient()` | Part 3 §13 |
| AI 스트리밍 | Processor에서 `StreamChunk` emit → `ClientSharedAction` broadcast | Part 1 §1.3 |
| 협업 오케스트레이션 | Processor 내 `coroutineScope` + `launch` | Part 1 §1.3 |
| 구독/쿼터 | State에 포함, Processor에서 체크 | — |

### 11.2 필요한 라이브러리 기능

| 기능 | 필요 이유 | Scaling Design 참조 |
|------|----------|---------------------|
| `ignoreUnknownKeys` | 새 `ContentBlock` 타입 추가 시 구버전 클라이언트 호환 | Part 2 §11.2 |
| Lenient decode | 새 `SessionAction` 추가 시 구버전 클라이언트 연결 유지 | Part 2 §11.1 |
| `close()` 메서드 | 구독 만료 시 서버에서 연결 종료 | Part 2 §11.3 |
| Wire protocol `"v"` | 향후 wire format 변경 대비 | Part 2 §11.4 |

### 11.3 향후 확장 시 활용할 패턴

| 시나리오 | 활용 패턴 | Scaling Design 참조 |
|----------|----------|---------------------|
| 유저 간 대화 공유 | Room Store (공유 대화방) | Part 1 §3 / Part 3 §14.2 (Pattern B) |
| 대화 + 알림 동시 수신 | Channel Multiplexing | Part 4 §20 |
| 10만+ 동시 접속 | Pub/Sub Backplane | Part 1 §4 |
| Zero-downtime 배포 | Gateway 분리 | Part 1 §5 |

### 11.4 이 서비스에서 활용하지 않는 패턴

| 패턴 | 미사용 이유 |
|------|------------|
| `SessionAwareAction` / `sessionStateMapper` | 같은 유저의 디바이스 간에는 **동일한 상태**를 공유하므로 세션별 다른 뷰 불필요 |
| Room 파티셔닝 | 초기 버전에서는 유저 간 공유 기능 없음 (향후 확장 시 활용) |

---

## 12. 클라이언트 전략: Compose Multiplatform

### 12.1 현재 프로젝트 현황

현재 flowdux 샘플 프로젝트의 클라이언트 UI 현황:

| 샘플 | 위치 | UI 방식 | Compose 사용 |
|------|------|---------|-------------|
| Android | `samples/flowdux/android/` | Compose | O |
| KMM Android | `samples/flowdux/kmm/androidApp/` | Compose | O |
| Web (JS) | `samples/flowdux/web/` | DOM 직접 조작 | X |
| WASM | `samples/flowdux/wasm/` | DOM 직접 조작 | X |
| JVM | `samples/flowdux/jvm/` | CLI (콘솔) | X |
| Remote Multi-Room | `samples/flowdux-remote/multi-room/` | CLI (콘솔) | X |

버전 카탈로그(`gradle/libs.versions.toml`) 상태:
- `kotlinCompose` 플러그인 선언됨: `org.jetbrains.kotlin.plugin.compose` (Kotlin 2.2.10)
- Android용 Compose BOM, Material3, Activity Compose 등 의존성 선언됨
- **Compose Multiplatform 플러그인(`org.jetbrains.compose`) 미선언**
- Web/WASM 타겟에 Compose 의존성 없음

Web/WASM 샘플은 `kotlinx.browser.document`를 사용한 순수 DOM 조작:

```kotlin
// 현재 web/wasm 샘플의 렌더링 방식
val countDisplay = document.getElementById("count")!!
countDisplay.textContent = "Count: $count"

val incrementButton = document.getElementById("increment")!!
incrementButton.addEventListener("click", { store.dispatch(Increment) })
```

Android 샘플만 Compose를 사용:

```kotlin
// 현재 Android 샘플 — Compose UI
setContent {
    CounterScreen(store)
}
```

### 12.2 렌더링 방식 비교: Canvas vs DOM

Compose Multiplatform for Web은 **Canvas/Skia 렌더링** 방식을 사용한다. 기존 웹 프레임워크의 DOM 렌더링과 근본적으로 다르다.

```
DOM 렌더링 (React, Vue, 현재 web/wasm 샘플):

  Kotlin/JS ──► DOM API ──► HTML 요소 ──► 브라우저 렌더링 엔진
                             │
                             ├── <div>, <p>, <button>, ...
                             ├── CSS 스타일
                             └── 브라우저 레이아웃/페인트


Canvas 렌더링 (Compose Multiplatform/Wasm):

  Kotlin/Wasm ──► Skia ──► Canvas 2D ──► 픽셀 직접 그리기
                   │
                   ├── 자체 레이아웃 엔진
                   ├── 자체 텍스트 렌더링
                   └── 자체 이벤트 처리
```

| 항목 | Canvas/Skia (Compose) | DOM (React/Vue/현재 샘플) |
|------|----------------------|--------------------------|
| 렌더링 원리 | Skia로 Canvas에 픽셀 직접 그리기 | 브라우저 DOM 트리 조작 |
| 플랫폼 일관성 | 모든 플랫폼 동일한 픽셀 출력 | 브라우저 엔진별 차이 |
| SEO | 불가 (Canvas 내부 콘텐츠 인덱싱 안 됨) | 가능 (HTML 구조 크롤링) |
| 접근성 | 별도 구현 필요 (시맨틱 트리 매핑) | 네이티브 지원 (ARIA, 스크린 리더) |
| 브라우저 DevTools | 제한적 (Canvas 내부 검사 불가) | 완전 지원 (요소 검사, CSS 디버깅) |
| 텍스트 선택/검색 | 별도 구현 필요 | 네이티브 지원 |
| 코드 공유 | KMP 전 플랫폼 동일 코드 | 웹 전용, 별도 구현 필요 |
| 60fps 애니메이션 | Skia 하드웨어 가속, 안정적 | DOM reflow/repaint 비용 |
| 생태계 | Compose 컴포넌트 | 웹 생태계 전체 (npm, CSS 프레임워크) |

### 12.3 성능 특성

#### Wasm 런타임 성능

```
Kotlin/Wasm vs Kotlin/JS 벤치마크 (Kotlin 2.0+ 기준):

  연산 성능:    Wasm ≈ 2~3x 빠름 (네이티브에 근접)
  GC:          Wasm GC 제안 기반, 효율적 메모리 관리
  호출 오버헤드: JS interop 없이 직접 실행
```

#### 초기 로드 특성

```
┌─────────────────────────────────────────────────────────┐
│  초기 로드 시간 (Cold Start)                              │
│                                                         │
│  DOM (Kotlin/JS):                                       │
│    JS 번들 다운로드 → 파싱 → 실행                         │
│    ≈ 50~150ms (소규모 앱)                                │
│                                                         │
│  Compose/Wasm:                                          │
│    .wasm 다운로드 → 컴파일 → 인스턴스화 → 첫 프레임        │
│    ≈ 250~500ms (Skia + Compose 런타임 포함)              │
│                                                         │
│  차이 원인:                                              │
│    ─ Skia 렌더링 엔진 포함 (≈ 2~4MB)                     │
│    ─ Compose 런타임 + UI 프레임워크                       │
│    ─ Wasm 모듈 컴파일 시간                               │
└─────────────────────────────────────────────────────────┘
```

#### 번들 사이즈

| 구성 | 대략적 크기 |
|------|-----------|
| Kotlin/JS (DOM 직접 조작) | 200~500KB |
| Kotlin/Wasm + Compose | 5~10MB (Skia 포함) |
| 참고: Skia 바이너리 | ~2~4MB (gzip 후) |

초기 로드와 번들 사이즈에서 손해가 있지만, 로드 이후 **런타임 렌더링 성능**에서 이점이 있다:

```
런타임 렌더링 (로드 이후):

  DOM: 상태 변경 → Virtual DOM diff → DOM 업데이트 → 브라우저 레이아웃/페인트
       (reflow 비용, 대량 요소 시 성능 저하)

  Canvas/Skia: 상태 변경 → Compose recomposition → Skia 직접 렌더
               (reflow 없음, 일관된 프레임 레이트)

  채팅 앱에서의 실질적 차이:
  ─ 수백 개 메시지 스크롤: Canvas가 안정적 60fps 유지
  ─ 코드 블록 + 수식 + 차트 혼합 렌더링: Canvas가 유리
  ─ 단순 텍스트 몇 줄: 차이 무의미
```

### 12.4 이 서비스에 적합한 이유

이 AI 멀티모델 협업 플랫폼에 Compose Multiplatform/Wasm이 적합한 이유:

**1. SPA 성격 — SEO 불필요**

```
이 서비스의 특성:
  ─ 로그인 필수 (인증 벽 뒤의 콘텐츠)
  ─ 실시간 채팅/협업 (동적 콘텐츠)
  ─ 검색 엔진에 노출될 필요 없음
  ─ 앱 스토어/직접 접속으로 유입

→ Canvas 렌더링의 최대 약점인 SEO 불가가 문제되지 않음
```

**2. KMP 코드 공유 극대화**

```
현재 (DOM 방식):

  ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
  │ Android     │     │ iOS         │     │ Web         │
  │ Compose UI  │     │ SwiftUI     │     │ DOM 조작     │
  │ (Kotlin)    │     │ (Swift)     │     │ (Kotlin/JS) │
  └──────┬──────┘     └──────┬──────┘     └──────┬──────┘
         └──────────────┬────┘                    │
                 KMP 공유 로직               별도 UI 구현
                 (State, Action)            (DOM API)


Compose Multiplatform 적용 후:

  ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
  │ Android     │     │ iOS         │     │ Web         │
  │ Compose     │     │ Compose     │     │ Compose     │
  │             │     │             │     │ (Wasm)      │
  └──────┬──────┘     └──────┬──────┘     └──────┬──────┘
         └──────────────┬────┴────────────────────┘
                 KMP 공유 로직 + UI
                 (State, Action, Composable)

→ UI 코드까지 공유 가능. 플랫폼별로 별도 UI를 작성하지 않아도 됨.
```

**3. ContentBlock → Compose 자연스러운 매핑**

[§9 렌더링 중립 전략](#9-렌더링-중립-전략)에서 정의한 `ContentBlock` sealed interface는 Compose의 `@Composable` 함수와 1:1로 매핑된다:

```
ContentBlock sealed interface         @Composable 함수
──────────────────────────────        ────────────────────
ContentBlock.Text        ────────►   Text()
ContentBlock.Code        ────────►   CodeBlock()
ContentBlock.Image       ────────►   AsyncImage() / Image()
ContentBlock.Math        ────────►   MathView()
ContentBlock.Chart       ────────►   ChartView()
ContentBlock.Thinking    ────────►   ThinkingBlock()
ContentBlock.Table       ────────►   TableView()
ContentBlock.ToolUse     ────────►   ToolUseCard()
ContentBlock.Reference   ────────►   ReferenceLink()

→ sealed interface의 when 분기가 Compose의 선언적 UI와 자연스럽게 매핑
```

**4. flowdux Store와의 통합**

```
flowdux Store                    Compose
─────────────                    ───────
store.state (StateFlow)  ──►    collectAsState()
store.dispatch(action)   ──►    onClick = { dispatch(action) }

→ StateFlow 기반 flowdux는 Compose의 상태 관리와 직접 호환
→ 별도 어댑터 없이 Store.state를 Composable에서 구독 가능
```

### 12.5 ContentBlock → Compose 렌더링 매핑

[§9](#9-렌더링-중립-전략)의 `ContentBlock` sealed interface를 Compose Composable로 매핑하는 설계:

```kotlin
/** ContentBlock을 Composable로 렌더링하는 최상위 함수 */
@Composable
fun ContentBlockView(block: ContentBlock) {
    when (block) {
        is ContentBlock.Text      -> TextBlock(block)
        is ContentBlock.Code      -> CodeBlock(block)
        is ContentBlock.Image     -> ImageBlock(block)
        is ContentBlock.Math      -> MathBlock(block)
        is ContentBlock.Chart     -> ChartBlock(block)
        is ContentBlock.Thinking  -> ThinkingBlock(block)
        is ContentBlock.ToolUse   -> ToolUseBlock(block)
        is ContentBlock.Table     -> TableBlock(block)
        is ContentBlock.Reference -> ReferenceBlock(block)
    }
}

/** 메시지 하나를 렌더링 */
@Composable
fun MessageView(message: Message, participant: Participant) {
    Row {
        Avatar(participant)
        Column {
            ParticipantName(participant)
            message.content.forEach { block ->
                ContentBlockView(block)
            }
        }
    }
}

/** 대화 전체를 렌더링 */
@Composable
fun ConversationView(store: Store<UserSessionState, SessionAction>) {
    val state by store.state.collectAsState()
    val conv = state.conversations[state.activeConversationId] ?: return

    LazyColumn {
        items(conv.messages) { message ->
            val participant = conv.participants.find { it.id == message.participantId }
                ?: return@items
            MessageView(message, participant)
        }
    }
}
```

#### 블록별 Composable 예시

```kotlin
@Composable
fun TextBlock(block: ContentBlock.Text) {
    // Markdown 파싱 + 리치 텍스트 렌더링
    MarkdownText(block.text)
}

@Composable
fun CodeBlock(block: ContentBlock.Code) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column {
            // 언어 태그 + 복사 버튼
            Row {
                Text(block.language, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { clipboard.setText(block.code) }) {
                    Icon(Icons.Default.ContentCopy, "Copy")
                }
            }
            // 구문 강조 코드
            SyntaxHighlightedCode(block.code, block.language)
        }
    }
}

@Composable
fun ThinkingBlock(block: ContentBlock.Thinking) {
    // 접기/펼치기 가능한 thinking 블록
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
        onClick = { expanded = !expanded },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Icon(Icons.Default.Psychology, "Thinking")
                Text("Thinking...")
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    "Toggle",
                )
            }
            AnimatedVisibility(expanded) {
                Text(block.text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
```

#### 병렬 비교 모드 렌더링

[§10.2 병렬 비교 협업](#102-병렬-비교-협업)의 3개 AI 동시 스트리밍을 Compose로 렌더링:

```kotlin
@Composable
fun ParallelComparisonView(
    state: UserSessionState,
    streamingStates: Map<String, StreamingState>,
) {
    val conv = state.conversations[state.activeConversationId] ?: return
    val aiParticipants = conv.participants.filterIsInstance<Participant.AI>()

    Row(Modifier.fillMaxWidth()) {
        aiParticipants.forEach { ai ->
            Column(
                modifier = Modifier.weight(1f).padding(4.dp),
            ) {
                // AI 이름 헤더
                Text(ai.displayName, style = MaterialTheme.typography.titleSmall)

                // 스트리밍 중인 청크 렌더링
                val streaming = streamingStates[ai.id]
                if (streaming != null) {
                    streaming.chunks.forEach { block ->
                        ContentBlockView(block)
                    }
                    if (!streaming.isComplete) {
                        StreamingIndicator()
                    }
                }
            }
        }
    }
}
```

### 12.6 플랫폼별 클라이언트 전략

```
┌────────────────────────────────────────────────────────────────┐
│  Compose Multiplatform 공유 레이어                               │
│                                                                │
│  ┌────────────────────────────────────────────────────────┐    │
│  │  commonMain                                             │    │
│  │  ├── ContentBlockView() — 모든 블록 렌더링               │    │
│  │  ├── ConversationView() — 대화 화면                     │    │
│  │  ├── MessageView() — 메시지 렌더링                      │    │
│  │  ├── ParallelComparisonView() — 병렬 비교 뷰            │    │
│  │  └── Theme, Typography, ColorScheme                    │    │
│  └────────────────────────────────────────────────────────┘    │
│       │              │              │              │            │
│       ▼              ▼              ▼              ▼            │
│  ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐      │
│  │ Android │   │  iOS    │   │  Web    │   │ Desktop │      │
│  │ (JVM)   │   │(Native) │   │ (Wasm)  │   │ (JVM)   │      │
│  └─────────┘   └─────────┘   └─────────┘   └─────────┘      │
└────────────────────────────────────────────────────────────────┘
```

#### Android (Compose)

| 항목 | 내용 |
|------|------|
| 렌더링 엔진 | Compose + Android Canvas (Skia) |
| 배포 | Google Play Store |
| 플랫폼 특화 | 알림(FCM), 백그라운드 연결 유지, Material You 테마 |
| 코드 블록 | 공유 `SyntaxHighlightedCode` Composable 사용 |
| 수식 렌더링 | `expect`/`actual`로 플랫폼 렌더러 분리 가능 |

#### iOS (Compose Multiplatform)

| 항목 | 내용 |
|------|------|
| 렌더링 엔진 | Compose + Skia (iOS) |
| 배포 | App Store |
| 플랫폼 특화 | APNs 알림, iOS 특화 제스처, Cupertino 느낌 커스터마이징 |
| 대안 | SwiftUI 네이티브 UI + KMP 공유 로직 (코드 공유율 ↓, 네이티브 경험 ↑) |

```
iOS 전략 선택:

  옵션 A: Compose Multiplatform (코드 공유 극대화)
  ────────────────────────────────────────────
  장점: commonMain UI 100% 재사용
  단점: iOS 네이티브 look & feel과 미세한 차이

  옵션 B: SwiftUI 네이티브 (네이티브 경험 극대화)
  ────────────────────────────────────────────
  장점: 완벽한 iOS 네이티브 UX
  단점: UI 코드 별도 작성, ContentBlock 렌더러 Swift로 재구현

→ 추천: 옵션 A로 시작, iOS 특화 UX 필요 시 부분적으로 SwiftUI 도입
```

#### Web (Compose for Wasm)

| 항목 | 내용 |
|------|------|
| 렌더링 엔진 | Compose + Skia (WebAssembly, Canvas 2D) |
| 타겟 | `wasmJs` (Kotlin/Wasm) |
| 배포 | 정적 호스팅 (CDN) |
| 플랫폼 특화 | 브라우저 알림 API, URL 라우팅, 키보드 단축키 |

```kotlin
// build.gradle.kts — Compose for Wasm 설정 (향후)
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)    // 추가 필요
    alias(libs.plugins.kotlinCompose)
}

kotlin {
    wasmJs {
        browser()
    }
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(compose.material3)
            implementation(compose.foundation)
            implementation(libs.flowdux)
            implementation(libs.flowdux.remote.client)
        }
    }
}
```

#### Desktop (Compose for Desktop)

| 항목 | 내용 |
|------|------|
| 렌더링 엔진 | Compose + Skia (JVM, 하드웨어 가속) |
| 타겟 | macOS, Windows, Linux |
| 배포 | 네이티브 인스톨러 (dmg, msi, deb) |
| 플랫폼 특화 | 시스템 트레이, 멀티 윈도우, 파일 시스템 접근 |

### 12.7 SEO/접근성 보완

Canvas 렌더링의 약점인 SEO와 접근성에 대한 보완 전략:

#### SEO

```
이 서비스의 페이지 분류:

  ┌─────────────────────────────────────────────────────┐
  │  SEO 필요 (공개 페이지)         │  SEO 불필요 (앱)    │
  │                                │                    │
  │  ─ 랜딩 페이지                  │  ─ 채팅 화면        │
  │  ─ 가격 페이지                  │  ─ 설정 화면        │
  │  ─ 블로그/문서                  │  ─ 협업 화면        │
  │  ─ 로그인/회원가입               │  ─ 대시보드         │
  │                                │                    │
  │  → 별도 구현 (SSR/SSG)          │  → Compose/Wasm    │
  └─────────────────────────────────────────────────────┘

SEO 필요 페이지 전략:
  ─ Next.js / Astro 등 SSG 프레임워크로 별도 구현
  ─ 또는 서버 사이드 렌더링된 정적 HTML
  ─ 로그인 후 Compose/Wasm 앱으로 리다이렉트

→ 앱 영역과 마케팅 영역을 분리. 각각 최적의 기술 사용.
```

#### 접근성 (Accessibility)

Compose Multiplatform의 접근성 현황과 대안:

```
Compose의 접근성 지원:

  Android/Desktop:
  ─ Compose semantics API 지원
  ─ Modifier.semantics { contentDescription = "..." }
  ─ TalkBack (Android), 스크린 리더 (Desktop) 호환

  Web (Wasm/Canvas):
  ─ Canvas 내부 콘텐츠는 스크린 리더가 접근 불가 (기본)
  ─ Compose for Web은 시맨틱 트리를 숨겨진 DOM 요소로 매핑 (실험적)
  ─ 아직 성숙하지 않은 영역

  보완 전략:
  ─ Compose semantics API를 모든 인터랙티브 요소에 적용
  ─ 키보드 내비게이션 구현 (Modifier.focusable(), FocusRequester)
  ─ 접근성이 중요한 공개 페이지는 DOM 기반 별도 구현 (§12.7 SEO와 동일)
```

### 12.8 마이그레이션 경로

현재 DOM 기반 웹 샘플에서 Compose Multiplatform/Wasm으로 전환하는 단계별 경로:

```
Phase 1: 기반 설정
──────────────────────────────────────────────────────
  □ libs.versions.toml에 Compose Multiplatform 플러그인 추가
    ─ compose-multiplatform 플러그인 버전 선언
    ─ composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "..." }
  □ 공유 UI 모듈 생성
    ─ samples/flowdux/compose-shared/ (commonMain)
    ─ ContentBlockView 등 공유 Composable 작성
  □ Android 샘플을 공유 UI 모듈 사용으로 전환


Phase 2: Web/Wasm Compose 샘플 추가
──────────────────────────────────────────────────────
  □ samples/flowdux/compose-web/ 생성
    ─ wasmJs 타겟, Compose Multiplatform 적용
    ─ 공유 UI 모듈의 Composable 사용
  □ flowdux Store ↔ Compose 통합 검증
    ─ store.state.collectAsState() 동작 확인
    ─ 실시간 스트리밍 렌더링 성능 확인
  □ 기존 DOM 샘플은 유지 (참조 구현으로)


Phase 3: Remote Chat 샘플 전환
──────────────────────────────────────────────────────
  □ samples/flowdux-remote/compose-web/ 생성
    ─ 기존 web/client/ DOM 기반 → Compose/Wasm
    ─ ContentBlock 렌더러 Composable 구현
    ─ 병렬 비교, 스트리밍 등 AI 협업 UI 구현
  □ iOS / Desktop 타겟 추가 (같은 Composable 재사용)


Phase 4: 프로덕션 최적화
──────────────────────────────────────────────────────
  □ 번들 사이즈 최적화
    ─ Wasm 바이너리 압축 (Brotli)
    ─ 사용하지 않는 Compose 컴포넌트 트리쉐이킹
  □ 초기 로드 최적화
    ─ 스플래시 화면 (HTML/CSS) → Wasm 로드 완료 후 전환
    ─ Service Worker 캐싱
  □ 접근성 구현
    ─ semantics API 적용
    ─ 키보드 내비게이션
  □ 랜딩 페이지 별도 구현 (SSG)
```
