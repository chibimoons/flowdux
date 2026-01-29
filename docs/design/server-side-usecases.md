# FlowDux Server-Side Use Cases

## Overview

FlowDux는 클라이언트 상태 관리를 위해 설계되었지만, 특정 조건에서 서버에서도 유용하게 활용될 수 있다.

### 서버에서 FlowDux가 적합한 조건

1. **복잡한 상태**: 서버가 단순 릴레이가 아닌 복잡한 mutable 상태를 관리
2. **실시간 동기화**: 상태 변화를 여러 클라이언트에 즉시 브로드캐스트
3. **예측 가능한 상태 전이**: 상태 변화가 명확한 규칙을 따름
4. **이벤트 소싱 친화적**: 액션 히스토리가 비즈니스 가치를 가짐

### 서버에서 FlowDux가 불필요한 경우

- 단순 메시지 릴레이 (채팅 서버)
- Stateless API (REST/GraphQL)
- 상태가 DB에만 존재하는 경우
- 요청-응답 패턴만 사용하는 경우

---

## Use Case Categories

### 1. Gaming & Entertainment

#### 1.1 Multiplayer Game Server
실시간 멀티플레이어 게임에서 게임 상태를 중앙에서 관리.

```kotlin
data class GameState(
    val players: Map<PlayerId, PlayerData>,
    val world: WorldState,
    val turn: Int,
    val phase: GamePhase,
)

sealed interface GameAction : Action {
    data class PlayerMove(val playerId: PlayerId, val direction: Direction) : GameAction, SharedAction
    data class Attack(val attackerId: PlayerId, val targetId: PlayerId) : GameAction, SharedAction
    data class UseItem(val playerId: PlayerId, val itemId: ItemId) : GameAction, SharedAction
    data class ChatMessage(val playerId: PlayerId, val message: String) : GameAction, SharedAction
}
```

**적합 이유:**
- 모든 플레이어에게 일관된 게임 상태 필요
- 액션의 순서가 게임 결과에 영향
- 치트 방지를 위한 서버 권위 모델

#### 1.2 Turn-based Board Games
체스, 바둑, 카드 게임 등 턴제 게임.

```kotlin
data class ChessState(
    val board: Board,
    val currentTurn: Color,
    val moveHistory: List<Move>,
    val capturedPieces: Map<Color, List<Piece>>,
    val gameStatus: GameStatus,  // PLAYING, CHECK, CHECKMATE, DRAW
)

sealed interface ChessAction : Action {
    data class MovePiece(val from: Position, val to: Position) : ChessAction, SharedAction
    data class OfferDraw(val playerId: PlayerId) : ChessAction, SharedAction
    data class Resign(val playerId: PlayerId) : ChessAction, SharedAction
}
```

#### 1.3 Live Quiz / Trivia
실시간 퀴즈 게임 (Kahoot 스타일).

```kotlin
data class QuizState(
    val currentQuestion: Question?,
    val questionIndex: Int,
    val participants: Map<UserId, ParticipantData>,
    val scores: Map<UserId, Int>,
    val phase: QuizPhase,  // WAITING, QUESTION, ANSWERING, RESULTS, FINAL
    val timeRemaining: Duration,
)

sealed interface QuizAction : Action {
    object StartQuiz : QuizAction
    object NextQuestion : QuizAction
    data class SubmitAnswer(val userId: UserId, val answer: Int) : QuizAction, SharedAction
    object ShowResults : QuizAction
}
```

#### 1.4 Live Streaming Interaction
라이브 스트리밍 중 시청자 상호작용 (투표, 이벤트).

```kotlin
data class StreamState(
    val isLive: Boolean,
    val viewerCount: Int,
    val activePoll: Poll?,
    val chatMode: ChatMode,  // NORMAL, SLOW, SUBSCRIBERS_ONLY, EMOTE_ONLY
    val recentEvents: List<StreamEvent>,  // donations, subs, raids
)

sealed interface StreamAction : Action {
    data class StartPoll(val question: String, val options: List<String>) : StreamAction
    data class Vote(val userId: UserId, val optionIndex: Int) : StreamAction, SharedAction
    data class SetChatMode(val mode: ChatMode) : StreamAction
    data class Donation(val userId: UserId, val amount: Int, val message: String) : StreamAction
}
```

---

### 2. Collaboration Tools

#### 2.1 Real-time Document Editor
Google Docs 스타일 협업 문서 편집.

```kotlin
data class DocumentState(
    val content: List<Block>,
    val cursors: Map<UserId, CursorPosition>,
    val selections: Map<UserId, Selection>,
    val comments: List<Comment>,
    val version: Long,
)

sealed interface DocAction : Action {
    data class InsertText(val userId: UserId, val position: Int, val text: String) : DocAction, SharedAction
    data class DeleteText(val userId: UserId, val range: IntRange) : DocAction, SharedAction
    data class FormatText(val range: IntRange, val format: TextFormat) : DocAction, SharedAction
    data class MoveCursor(val userId: UserId, val position: Int) : DocAction, SharedAction
    data class AddComment(val userId: UserId, val range: IntRange, val text: String) : DocAction, SharedAction
}
```

**고려사항:**
- OT(Operational Transformation) 또는 CRDT 알고리즘 필요
- 충돌 해결 로직이 복잡

#### 2.2 Design Tool (Figma-like)
실시간 협업 디자인 툴.

```kotlin
data class CanvasState(
    val elements: Map<ElementId, DesignElement>,
    val layers: List<LayerId>,
    val cursors: Map<UserId, Cursor>,
    val selections: Map<UserId, Set<ElementId>>,
    val zoom: Map<UserId, ZoomLevel>,
)

sealed interface CanvasAction : Action {
    data class AddElement(val element: DesignElement) : CanvasAction, SharedAction
    data class MoveElement(val elementId: ElementId, val position: Position) : CanvasAction, SharedAction
    data class ResizeElement(val elementId: ElementId, val size: Size) : CanvasAction, SharedAction
    data class DeleteElement(val elementId: ElementId) : CanvasAction, SharedAction
    data class SelectElement(val userId: UserId, val elementId: ElementId) : CanvasAction, SharedAction
    data class UpdateCursor(val userId: UserId, val position: Position) : CanvasAction, SharedAction
}
```

#### 2.3 Whiteboard
실시간 화이트보드 (Miro-like).

```kotlin
data class WhiteboardState(
    val strokes: List<Stroke>,
    val stickyNotes: Map<NoteId, StickyNote>,
    val connectors: List<Connector>,
    val participants: Map<UserId, ParticipantInfo>,
)

sealed interface WhiteboardAction : Action {
    data class Draw(val userId: UserId, val stroke: Stroke) : WhiteboardAction, SharedAction
    data class AddStickyNote(val note: StickyNote) : WhiteboardAction, SharedAction
    data class MoveNote(val noteId: NoteId, val position: Position) : WhiteboardAction, SharedAction
    data class EditNoteText(val noteId: NoteId, val text: String) : WhiteboardAction, SharedAction
}
```

#### 2.4 Collaborative Code Editor
VS Code Live Share 스타일.

```kotlin
data class CodeSessionState(
    val files: Map<FilePath, FileContent>,
    val cursors: Map<UserId, FileCursor>,
    val terminals: Map<TerminalId, TerminalState>,
    val debugSessions: Map<SessionId, DebugState>,
    val participants: List<Participant>,
)

sealed interface CodeAction : Action {
    data class EditFile(val path: FilePath, val edit: TextEdit) : CodeAction, SharedAction
    data class OpenFile(val userId: UserId, val path: FilePath) : CodeAction, SharedAction
    data class TerminalInput(val terminalId: TerminalId, val input: String) : CodeAction, SharedAction
    data class SetBreakpoint(val path: FilePath, val line: Int) : CodeAction, SharedAction
}
```

---

### 3. Business & Commerce

#### 3.1 Real-time Auction
실시간 경매 시스템.

```kotlin
data class AuctionState(
    val item: AuctionItem,
    val currentBid: Long,
    val highestBidder: UserId?,
    val bidHistory: List<Bid>,
    val startTime: Instant,
    val endTime: Instant,
    val status: AuctionStatus,  // UPCOMING, ACTIVE, ENDED, CANCELLED
    val watcherCount: Int,
)

sealed interface AuctionAction : Action {
    data class PlaceBid(val userId: UserId, val amount: Long) : AuctionAction, SharedAction
    data class AutoBid(val userId: UserId, val maxAmount: Long) : AuctionAction, SharedAction
    object ExtendTime : AuctionAction  // 마지막 순간 입찰 시 연장
    data class AuctionEnded(val winner: UserId?, val finalPrice: Long) : AuctionAction
}
```

**적합 이유:**
- 모든 참여자에게 동일한 가격 정보 필요
- 입찰 순서가 중요
- 실시간 업데이트 필수

#### 3.2 Stock/Crypto Trading Dashboard
실시간 트레이딩 대시보드.

```kotlin
data class TradingState(
    val portfolio: Map<Symbol, Position>,
    val watchlist: List<Symbol>,
    val prices: Map<Symbol, PriceData>,
    val openOrders: List<Order>,
    val recentTrades: List<Trade>,
    val alerts: List<PriceAlert>,
)

sealed interface TradingAction : Action {
    data class PriceUpdate(val symbol: Symbol, val price: PriceData) : TradingAction
    data class PlaceOrder(val order: Order) : TradingAction, SharedAction
    data class CancelOrder(val orderId: OrderId) : TradingAction, SharedAction
    data class OrderFilled(val orderId: OrderId, val trade: Trade) : TradingAction
    data class SetAlert(val symbol: Symbol, val condition: AlertCondition) : TradingAction, SharedAction
}
```

#### 3.3 Order Management (Restaurant/Delivery)
주문 관리 시스템 (주방 디스플레이, 배달 추적).

```kotlin
data class KitchenState(
    val pendingOrders: List<Order>,
    val preparingOrders: Map<OrderId, PreparationStatus>,
    val readyOrders: List<OrderId>,
    val stations: Map<StationId, StationStatus>,
)

sealed interface KitchenAction : Action {
    data class NewOrder(val order: Order) : KitchenAction
    data class StartPreparing(val orderId: OrderId, val stationId: StationId) : KitchenAction, SharedAction
    data class ItemReady(val orderId: OrderId, val itemId: ItemId) : KitchenAction, SharedAction
    data class OrderReady(val orderId: OrderId) : KitchenAction, SharedAction
    data class OrderPickedUp(val orderId: OrderId) : KitchenAction
}
```

#### 3.4 Live Event Ticketing
실시간 좌석 선택 및 예매.

```kotlin
data class VenueState(
    val seats: Map<SeatId, SeatStatus>,  // AVAILABLE, HELD, SOLD
    val heldSeats: Map<SeatId, HoldInfo>,  // userId, expiresAt
    val seatPrices: Map<SectionId, Long>,
)

sealed interface TicketAction : Action {
    data class HoldSeat(val userId: UserId, val seatId: SeatId) : TicketAction, SharedAction
    data class ReleaseSeat(val seatId: SeatId) : TicketAction
    data class PurchaseSeat(val userId: UserId, val seatId: SeatId) : TicketAction, SharedAction
    data class HoldExpired(val seatId: SeatId) : TicketAction
}
```

---

### 4. IoT & Monitoring

#### 4.1 Smart Home Hub
스마트홈 중앙 제어.

```kotlin
data class HomeState(
    val devices: Map<DeviceId, DeviceState>,
    val rooms: Map<RoomId, RoomState>,
    val scenes: Map<SceneId, Scene>,
    val automations: List<Automation>,
    val energyUsage: EnergyData,
)

sealed interface HomeAction : Action {
    data class DeviceStateChanged(val deviceId: DeviceId, val state: DeviceState) : HomeAction
    data class SetDevice(val deviceId: DeviceId, val state: DeviceState) : HomeAction, SharedAction
    data class ActivateScene(val sceneId: SceneId) : HomeAction, SharedAction
    data class TriggerAutomation(val automationId: AutomationId) : HomeAction
}
```

#### 4.2 Industrial Monitoring Dashboard
산업 설비 모니터링.

```kotlin
data class PlantState(
    val machines: Map<MachineId, MachineStatus>,
    val sensors: Map<SensorId, SensorReading>,
    val alerts: List<Alert>,
    val productionMetrics: ProductionData,
)

sealed interface PlantAction : Action {
    data class SensorUpdate(val sensorId: SensorId, val reading: SensorReading) : PlantAction
    data class AlertTriggered(val alert: Alert) : PlantAction
    data class AcknowledgeAlert(val alertId: AlertId, val userId: UserId) : PlantAction, SharedAction
    data class MachineCommand(val machineId: MachineId, val command: Command) : PlantAction, SharedAction
}
```

#### 4.3 Fleet Management
차량/배송 추적.

```kotlin
data class FleetState(
    val vehicles: Map<VehicleId, VehicleStatus>,
    val activeRoutes: Map<RouteId, RouteProgress>,
    val deliveries: Map<DeliveryId, DeliveryStatus>,
)

sealed interface FleetAction : Action {
    data class LocationUpdate(val vehicleId: VehicleId, val location: Location) : FleetAction
    data class DeliveryCompleted(val deliveryId: DeliveryId) : FleetAction, SharedAction
    data class AssignRoute(val vehicleId: VehicleId, val routeId: RouteId) : FleetAction, SharedAction
    data class ReportIssue(val vehicleId: VehicleId, val issue: Issue) : FleetAction, SharedAction
}
```

---

### 5. Communication & Social

#### 5.1 Video Conference State
화상 회의 상태 관리 (Zoom-like).

```kotlin
data class MeetingState(
    val participants: Map<UserId, ParticipantState>,
    val screenShare: ScreenShareInfo?,
    val recording: RecordingStatus,
    val chat: List<ChatMessage>,
    val reactions: List<Reaction>,
    val breakoutRooms: Map<RoomId, List<UserId>>,
)

sealed interface MeetingAction : Action {
    data class ParticipantJoined(val userId: UserId) : MeetingAction
    data class ParticipantLeft(val userId: UserId) : MeetingAction
    data class ToggleMute(val userId: UserId) : MeetingAction, SharedAction
    data class ToggleVideo(val userId: UserId) : MeetingAction, SharedAction
    data class StartScreenShare(val userId: UserId) : MeetingAction, SharedAction
    data class SendReaction(val userId: UserId, val reaction: String) : MeetingAction, SharedAction
    data class RaiseHand(val userId: UserId) : MeetingAction, SharedAction
}
```

#### 5.2 Customer Support Queue
고객 지원 대기열 관리.

```kotlin
data class SupportState(
    val waitingQueue: List<Ticket>,
    val activeChats: Map<TicketId, ChatSession>,
    val agents: Map<AgentId, AgentStatus>,
    val metrics: SupportMetrics,
)

sealed interface SupportAction : Action {
    data class NewTicket(val ticket: Ticket) : SupportAction
    data class AssignToAgent(val ticketId: TicketId, val agentId: AgentId) : SupportAction, SharedAction
    data class SendMessage(val ticketId: TicketId, val message: Message) : SupportAction, SharedAction
    data class ResolveTicket(val ticketId: TicketId, val resolution: Resolution) : SupportAction, SharedAction
    data class TransferTicket(val ticketId: TicketId, val toAgentId: AgentId) : SupportAction, SharedAction
}
```

#### 5.3 Collaborative Playlist
공유 음악 재생목록 (Spotify Group Session).

```kotlin
data class PlaylistState(
    val queue: List<Track>,
    val currentTrack: Track?,
    val playbackPosition: Duration,
    val isPlaying: Boolean,
    val participants: Map<UserId, ParticipantInfo>,
    val votes: Map<TrackId, Set<UserId>>,  // 다음 곡 투표
)

sealed interface PlaylistAction : Action {
    data class AddTrack(val userId: UserId, val track: Track) : PlaylistAction, SharedAction
    data class VoteSkip(val userId: UserId) : PlaylistAction, SharedAction
    data class VoteForNext(val userId: UserId, val trackId: TrackId) : PlaylistAction, SharedAction
    object Play : PlaylistAction, SharedAction
    object Pause : PlaylistAction, SharedAction
    object NextTrack : PlaylistAction
}
```

---

### 6. Workflow & Process

#### 6.1 Approval Workflow
결재/승인 워크플로우.

```kotlin
data class WorkflowState(
    val currentStage: Stage,
    val document: Document,
    val approvals: Map<Stage, ApprovalDecision>,
    val comments: List<Comment>,
    val history: List<StateTransition>,
)

sealed interface WorkflowAction : Action {
    data class Submit(val userId: UserId) : WorkflowAction, SharedAction
    data class Approve(val userId: UserId, val comment: String?) : WorkflowAction, SharedAction
    data class Reject(val userId: UserId, val reason: String) : WorkflowAction, SharedAction
    data class RequestRevision(val userId: UserId, val feedback: String) : WorkflowAction, SharedAction
    data class Delegate(val fromUserId: UserId, val toUserId: UserId) : WorkflowAction, SharedAction
}
```

#### 6.2 Kanban Board
실시간 칸반 보드.

```kotlin
data class BoardState(
    val columns: List<Column>,
    val cards: Map<CardId, Card>,
    val cardPositions: Map<CardId, CardPosition>,  // columnId, order
    val labels: List<Label>,
    val members: Map<UserId, MemberInfo>,
)

sealed interface BoardAction : Action {
    data class AddCard(val columnId: ColumnId, val card: Card) : BoardAction, SharedAction
    data class MoveCard(val cardId: CardId, val toColumn: ColumnId, val position: Int) : BoardAction, SharedAction
    data class AssignMember(val cardId: CardId, val userId: UserId) : BoardAction, SharedAction
    data class AddLabel(val cardId: CardId, val labelId: LabelId) : BoardAction, SharedAction
    data class UpdateCard(val cardId: CardId, val updates: CardUpdates) : BoardAction, SharedAction
}
```

---

### 7. Additional Use Cases

#### 7.1 Live Sports Scoreboard
실시간 스포츠 경기 스코어보드.

```kotlin
data class MatchState(
    val homeTeam: TeamInfo,
    val awayTeam: TeamInfo,
    val score: Score,
    val period: Int,
    val timeRemaining: Duration,
    val events: List<MatchEvent>,  // goals, fouls, substitutions
    val statistics: MatchStatistics,
    val status: MatchStatus,  // SCHEDULED, LIVE, HALFTIME, FINISHED
)

sealed interface MatchAction : Action {
    data class Goal(val team: TeamSide, val player: PlayerId, val assistBy: PlayerId?) : MatchAction
    data class Foul(val player: PlayerId, val type: FoulType) : MatchAction
    data class Substitution(val team: TeamSide, val playerOut: PlayerId, val playerIn: PlayerId) : MatchAction
    data class PeriodStart(val period: Int) : MatchAction
    data class PeriodEnd(val period: Int) : MatchAction
    data class TimeUpdate(val remaining: Duration) : MatchAction
    data class StatUpdate(val stats: MatchStatistics) : MatchAction
}
```

**적합 이유:**
- 수천~수백만 시청자에게 동일한 실시간 정보 필요
- 이벤트 순서가 중요 (골 → 어시스트 순서)
- 이벤트 히스토리가 기록으로 가치 있음

#### 7.2 Online Exam / Test System
실시간 온라인 시험 시스템.

```kotlin
data class ExamState(
    val examInfo: ExamInfo,
    val participants: Map<UserId, ParticipantStatus>,
    val questions: List<Question>,
    val answers: Map<UserId, Map<QuestionId, Answer>>,
    val timeRemaining: Duration,
    val phase: ExamPhase,  // WAITING, IN_PROGRESS, REVIEWING, FINISHED
    val proctorAlerts: List<ProctorAlert>,
)

sealed interface ExamAction : Action {
    object StartExam : ExamAction
    data class SubmitAnswer(val userId: UserId, val questionId: QuestionId, val answer: Answer) : ExamAction, SharedAction
    data class FlagQuestion(val userId: UserId, val questionId: QuestionId) : ExamAction, SharedAction
    data class ProctorAlert(val userId: UserId, val alertType: AlertType) : ExamAction  // 부정행위 감지
    data class SubmitExam(val userId: UserId) : ExamAction, SharedAction
    object EndExam : ExamAction
    data class TimeSync(val remaining: Duration) : ExamAction
}
```

**적합 이유:**
- 모든 응시자에게 동일한 시간 동기화 필요
- 답안 제출 순서/시간 기록 중요
- 실시간 감독(proctoring) 상태 관리

#### 7.3 Crowdfunding Campaign
크라우드펀딩 실시간 현황.

```kotlin
data class CampaignState(
    val campaign: CampaignInfo,
    val currentAmount: Long,
    val backerCount: Int,
    val recentBackers: List<BackerInfo>,
    val rewards: Map<RewardId, RewardStatus>,  // 남은 수량 등
    val milestones: List<Milestone>,
    val timeRemaining: Duration,
    val status: CampaignStatus,  // ACTIVE, FUNDED, FAILED, COMPLETED
)

sealed interface CampaignAction : Action {
    data class Pledge(val userId: UserId, val amount: Long, val rewardId: RewardId?) : CampaignAction, SharedAction
    data class CancelPledge(val userId: UserId, val pledgeId: PledgeId) : CampaignAction, SharedAction
    data class MilestoneReached(val milestone: Milestone) : CampaignAction
    data class RewardSoldOut(val rewardId: RewardId) : CampaignAction
    data class CampaignUpdate(val update: UpdatePost) : CampaignAction
    object CampaignFunded : CampaignAction
    object CampaignEnded : CampaignAction
}
```

**적합 이유:**
- 실시간 펀딩 현황 업데이트로 FOMO 유발
- 리워드 수량 실시간 동기화 필요
- 마일스톤 달성 이벤트 브로드캐스트

#### 7.4 Live Commerce
실시간 라이브 커머스 (쇼핑 방송).

```kotlin
data class LiveCommerceState(
    val stream: StreamInfo,
    val currentProduct: Product?,
    val products: List<Product>,
    val stock: Map<ProductId, Int>,
    val flashSale: FlashSaleInfo?,
    val viewerCount: Int,
    val recentPurchases: List<PurchaseEvent>,
    val chat: List<ChatMessage>,
    val likes: Int,
)

sealed interface LiveCommerceAction : Action {
    data class ShowProduct(val productId: ProductId) : LiveCommerceAction
    data class StartFlashSale(val productId: ProductId, val discountPercent: Int, val duration: Duration) : LiveCommerceAction
    data class Purchase(val userId: UserId, val productId: ProductId, val quantity: Int) : LiveCommerceAction, SharedAction
    data class StockUpdate(val productId: ProductId, val remaining: Int) : LiveCommerceAction
    data class SendChat(val userId: UserId, val message: String) : LiveCommerceAction, SharedAction
    data class Like(val userId: UserId) : LiveCommerceAction, SharedAction
    object FlashSaleEnded : LiveCommerceAction
}
```

**적합 이유:**
- 재고 실시간 동기화 (품절 방지)
- 플래시 세일 타이머 동기화
- 구매 이벤트 브로드캐스트 (사회적 증거)

#### 7.5 Multiplayer Escape Room / Puzzle
멀티플레이어 방탈출/퍼즐 게임.

```kotlin
data class EscapeRoomState(
    val room: RoomInfo,
    val players: Map<PlayerId, PlayerState>,
    val puzzles: Map<PuzzleId, PuzzleState>,
    val inventory: Map<PlayerId, List<Item>>,
    val sharedInventory: List<Item>,
    val hints: List<HintUsed>,
    val timeRemaining: Duration,
    val unlockedAreas: Set<AreaId>,
    val status: GameStatus,  // IN_PROGRESS, ESCAPED, FAILED
)

sealed interface EscapeAction : Action {
    data class ExaminePuzzle(val playerId: PlayerId, val puzzleId: PuzzleId) : EscapeAction, SharedAction
    data class AttemptSolution(val playerId: PlayerId, val puzzleId: PuzzleId, val solution: String) : EscapeAction, SharedAction
    data class PuzzleSolved(val puzzleId: PuzzleId) : EscapeAction
    data class PickupItem(val playerId: PlayerId, val itemId: ItemId) : EscapeAction, SharedAction
    data class UseItem(val playerId: PlayerId, val itemId: ItemId, val targetId: String) : EscapeAction, SharedAction
    data class ShareItem(val fromPlayer: PlayerId, val toPlayer: PlayerId, val itemId: ItemId) : EscapeAction, SharedAction
    data class RequestHint(val playerId: PlayerId) : EscapeAction, SharedAction
    data class AreaUnlocked(val areaId: AreaId) : EscapeAction
    object Escaped : EscapeAction
    object TimesUp : EscapeAction
}
```

**적합 이유:**
- 협동 퍼즐 풀이를 위한 상태 공유 필수
- 아이템/인벤토리 동기화
- 퍼즐 진행 상황 실시간 업데이트

---

## Decision Matrix

| Use Case | 상태 복잡도 | 실시간 필요 | 다중 클라이언트 | 이벤트 소싱 가치 | FlowDux 적합도 |
|----------|------------|------------|----------------|-----------------|---------------|
| 단순 채팅 | Low | Yes | Yes | Low | ❌ |
| 멀티플레이어 게임 | High | Critical | Yes | High | ✅✅✅ |
| 턴제 보드게임 | Medium | Yes | Yes | High | ✅✅✅ |
| 실시간 문서 편집 | High | Critical | Yes | High | ✅✅ |
| 디자인 툴 | High | Critical | Yes | Medium | ✅✅ |
| 실시간 경매 | Medium | Critical | Yes | High | ✅✅✅ |
| 트레이딩 대시보드 | Medium | Critical | No | Medium | ✅ |
| 주문 관리 | Medium | Yes | Yes | High | ✅✅ |
| 좌석 예매 | Medium | Yes | Yes | Medium | ✅✅ |
| 스마트홈 | Medium | Yes | Yes | Low | ✅ |
| 화상 회의 | Medium | Critical | Yes | Low | ✅✅ |
| 칸반 보드 | Medium | Yes | Yes | Medium | ✅✅ |
| 스포츠 스코어보드 | Medium | Critical | Yes | High | ✅✅✅ |
| 온라인 시험 | Medium | Critical | Yes | High | ✅✅✅ |
| 크라우드펀딩 | Medium | Yes | Yes | Medium | ✅✅ |
| 라이브 커머스 | Medium | Critical | Yes | Medium | ✅✅✅ |
| 방탈출/퍼즐 | High | Yes | Yes | Medium | ✅✅✅ |

**범례:**
- ✅✅✅ : 매우 적합
- ✅✅ : 적합
- ✅ : 사용 가능
- ❌ : 불필요

---

## Implementation Considerations

### 1. Scalability
- 단일 서버 FlowDux Store는 수평 확장이 어려움
- 대규모 서비스는 Room/Session 단위로 Store 분리 필요
- 예: 게임 서버는 매치별로 별도 Store

### 2. Persistence
- FlowDux 자체는 in-memory
- 상태 영속화가 필요하면 별도 미들웨어로 DB 동기화
- 이벤트 소싱 패턴과 잘 어울림

### 3. Conflict Resolution
- 협업 툴은 동시 편집 충돌 해결 필요
- OT/CRDT 알고리즘은 FlowDux 외부에서 처리
- Reducer에서 충돌 감지 및 해결 로직 구현

### 4. Authentication & Authorization
- SharedAction에 사용자 정보 포함
- 서버 미들웨어에서 권한 검증
- 무단 액션 필터링

```kotlin
class AuthMiddleware : Middleware<S, A> {
    override fun process(getState: () -> S, action: A): Flow<A> = flow {
        if (action is SharedAction) {
            if (!isAuthorized(action)) {
                // 권한 없음 - 액션 무시 또는 에러 응답
                return@flow
            }
        }
        emit(action)
    }
}
```

---

## Conclusion

FlowDux의 서버 사용은 **"서버가 상태 머신 역할을 하는가?"** 라는 질문에 달려있다.

**적합한 경우:**
- 복잡한 상태 전이 규칙이 있는 도메인
- 여러 클라이언트 간 상태 동기화가 핵심인 서비스
- 액션 히스토리가 비즈니스 가치를 가지는 경우

**부적합한 경우:**
- 단순 메시지 릴레이
- Stateless 서비스
- CRUD 중심 API
