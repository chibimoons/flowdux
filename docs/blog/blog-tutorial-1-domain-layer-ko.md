# FlowDux 튜토리얼 Part 1: Domain Layer

*비즈니스 로직이 사는 곳 — Entity, UseCase, 그리고 Port*

---

## 이번 파트에서 만들 것

이번 파트에서는 TaskFlow 앱의 **Domain Layer**를 구현합니다:

```
:core:domain/
├── entity/
│   ├── Task.kt
│   ├── Priority.kt
│   └── ValidationResult.kt
├── usecase/
│   └── TaskUseCase.kt
├── port/
│   ├── ITaskRepository.kt
│   ├── IDialogService.kt
│   └── IToastService.kt
└── test/
    ├── TaskTest.kt
    └── TaskUseCaseTest.kt
```

**핵심 원칙**: Domain은 **의존성이 없습니다**. Android도, iOS도, 프레임워크도 없습니다. 순수 Kotlin만 사용합니다.

---

## Entity: 단순한 데이터 그 이상

### 안티패턴: 빈혈 Entity

```kotlin
// 하지 마세요: 행위가 없는 데이터 클래스
data class Task(
    val id: String,
    val title: String,
    val description: String,
    val priority: Int,
    val dueDate: Long?,
    val isCompleted: Boolean
)

// 비즈니스 로직이 여기저기 흩어짐
fun isTaskOverdue(task: Task): Boolean {
    return task.dueDate?.let { it < System.currentTimeMillis() } ?: false
}
```

문제점:
- 비즈니스 규칙이 코드베이스 곳곳에 흩어짐
- 검증을 빼먹기 쉬움
- 일관된 테스트가 어려움
- 같은 규칙에 대한 해석이 달라질 수 있음

### 올바른 방법: 풍부한 Entity

```kotlin
// 이렇게 하세요: 비즈니스 로직이 내장된 Entity
data class Task private constructor(
    val id: TaskId,
    val title: Title,
    val description: Description,
    val priority: Priority,
    val dueDate: DueDate?,
    val isCompleted: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    // 계산된 속성 (시간 의존적 → 테스트 용이성을 위해 함수로)
    fun isOverdue(now: Instant = Instant.now()): Boolean =
        !isCompleted && dueDate?.isOverdue(now) == true

    fun urgencyLevel(today: LocalDate = LocalDate.now()): UrgencyLevel =
        calculateUrgency(today)

    // 도메인 연산
    fun complete(now: Instant = Instant.now()): Task = copy(
        isCompleted = true,
        updatedAt = now
    )

    fun reopen(now: Instant = Instant.now()): Task = copy(
        isCompleted = false,
        updatedAt = now
    )

    fun updateTitle(newTitle: Title, now: Instant = Instant.now()): Task = copy(
        title = newTitle,
        updatedAt = now
    )

    // 검증이 포함된 팩토리 메서드
    companion object {
        fun create(
            title: String,
            description: String = "",
            priority: Priority = Priority.MEDIUM,
            dueDate: DueDate? = null,
            now: Instant = Instant.now()
        ): Result<Task> {
            return runCatching {
                Task(
                    id = TaskId.generate(),
                    title = Title(title),
                    description = Description(description),
                    priority = priority,
                    dueDate = dueDate,
                    isCompleted = false,
                    createdAt = now,
                    updatedAt = now
                )
            }
        }
    }
}
```

---

## Value Object: 도메인 개념의 타입 안전성

### Value Object가 필요한 이유

```kotlin
// 원시 타입 집착 - 실수하기 쉬움
fun createTask(id: String, title: String, description: String)

// 실수로 인자 순서가 바뀜 - 컴파일은 됨!
createTask("우유 사기", "task-123", "저지방 우유로")
```

Value Object를 사용하면:

```kotlin
// 타입 안전 - 컴파일러가 실수를 잡아줌
fun createTask(id: TaskId, title: Title, description: Description)

// 컴파일 안 됨 - 타입이 맞지 않음
createTask(Title("우유 사기"), TaskId("task-123"), Description("..."))
```

### Value Object 구현

```kotlin
@JvmInline
value class TaskId(val value: String) {
    init {
        require(value.isNotBlank()) { "TaskId는 빈 값일 수 없습니다" }
    }

    companion object {
        fun generate(): TaskId = TaskId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class Title(val value: String) {
    init {
        require(value.isNotBlank()) { "제목은 빈 값일 수 없습니다" }
        require(value.length <= MAX_LENGTH) {
            "제목은 ${MAX_LENGTH}자를 초과할 수 없습니다"
        }
    }

    companion object {
        const val MAX_LENGTH = 100
    }
}

@JvmInline
value class Description(val value: String) {
    init {
        require(value.length <= MAX_LENGTH) {
            "설명은 ${MAX_LENGTH}자를 초과할 수 없습니다"
        }
    }

    companion object {
        const val MAX_LENGTH = 500
    }
}
```

### Priority Enum

```kotlin
enum class Priority(val level: Int) {
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    URGENT(4);

    fun isHigherThan(other: Priority): Boolean = level > other.level
}
```

### 비즈니스 로직이 있는 DueDate

```kotlin
@JvmInline
value class DueDate(val value: Instant) {
    fun isOverdue(now: Instant = Instant.now()): Boolean = value < now

    fun isToday(today: LocalDate = LocalDate.now()): Boolean {
        val dueDate = value.atZone(ZoneId.systemDefault()).toLocalDate()
        return today == dueDate
    }

    fun daysUntilDue(today: LocalDate = LocalDate.now()): Long {
        val dueDate = value.atZone(ZoneId.systemDefault()).toLocalDate()
        return ChronoUnit.DAYS.between(today, dueDate)
    }

    companion object {
        fun fromEpochMilli(epochMilli: Long): DueDate =
            DueDate(Instant.ofEpochMilli(epochMilli))

        fun today(now: LocalDate = LocalDate.now()): DueDate =
            DueDate(now.atStartOfDay(ZoneId.systemDefault()).toInstant())

        fun tomorrow(now: LocalDate = LocalDate.now()): DueDate =
            DueDate(now.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant())
    }
}
```

### UrgencyLevel 계산

```kotlin
enum class UrgencyLevel {
    NONE,       // 마감일 없음 또는 완료됨
    LOW,        // 7일 초과
    MEDIUM,     // 3-7일
    HIGH,       // 1-2일
    CRITICAL    // 오늘 또는 기한 초과
}

// Task entity 내부
private fun calculateUrgency(today: LocalDate): UrgencyLevel {
    if (isCompleted) return UrgencyLevel.NONE

    val dueDate = this.dueDate ?: return UrgencyLevel.NONE
    val daysLeft = dueDate.daysUntilDue(today)

    return when {
        daysLeft < 0 -> UrgencyLevel.CRITICAL  // 기한 초과
        daysLeft == 0L -> UrgencyLevel.CRITICAL // 오늘
        daysLeft <= 2 -> UrgencyLevel.HIGH
        daysLeft <= 7 -> UrgencyLevel.MEDIUM
        else -> UrgencyLevel.LOW
    }
}
```

---

## Port: Domain이 필요로 하는 인터페이스

Domain은 **무엇이 필요한지** 정의하고, 어떻게 구현되는지는 신경 쓰지 않습니다.

### ITaskRepository

```kotlin
interface ITaskRepository {
    /**
     * 모든 태스크를 Flow로 관찰합니다.
     * 데이터가 변경될 때마다 새 리스트를 방출합니다.
     */
    fun observeTasks(): Flow<List<Task>>

    /**
     * ID로 단일 태스크를 가져옵니다.
     */
    suspend fun getTask(id: TaskId): Task?

    /**
     * 태스크를 저장합니다 (추가 또는 수정).
     */
    suspend fun saveTask(task: Task)

    /**
     * ID로 태스크를 삭제합니다.
     */
    suspend fun deleteTask(id: TaskId)

    /**
     * 필터와 일치하는 태스크를 관찰합니다.
     */
    fun observeTasks(filter: TaskFilter): Flow<List<Task>>
}

data class TaskFilter(
    val showCompleted: Boolean = true,
    val priority: Priority? = null,
    val sortBy: SortBy = SortBy.CREATED_AT,
    val sortOrder: SortOrder = SortOrder.DESC
)

enum class SortBy { CREATED_AT, DUE_DATE, PRIORITY, TITLE }
enum class SortOrder { ASC, DESC }
```

### IDialogService

```kotlin
interface IDialogService {
    /**
     * 확인 다이얼로그를 표시합니다.
     * 사용자가 응답할 때까지 일시 중단됩니다.
     *
     * @return 확인하면 true, 취소하면 false
     */
    suspend fun showConfirmDialog(
        title: String,
        message: String,
        confirmText: String = "확인",
        cancelText: String = "취소"
    ): Boolean

    /**
     * 입력 다이얼로그를 표시합니다.
     * 사용자가 응답할 때까지 일시 중단됩니다.
     *
     * @return 입력된 텍스트, 취소 시 null
     */
    suspend fun showInputDialog(
        title: String,
        message: String,
        initialValue: String = "",
        hint: String = ""
    ): String?
}
```

### IToastService

```kotlin
interface IToastService {
    /**
     * 짧은 토스트 메시지를 표시합니다.
     */
    fun showToast(message: String)

    /**
     * 실행 취소 액션이 있는 토스트를 표시합니다.
     * @return 실행 취소가 클릭되면 true
     */
    suspend fun showUndoToast(
        message: String,
        undoLabel: String = "실행 취소",
        duration: Duration = 5.seconds
    ): Boolean
}
```

---

## UseCase: 사용자 흐름 조율

UseCase는 CRUD 래퍼가 아닙니다. **완전한 사용자 시나리오**를 조율합니다.

### TaskUseCase

```kotlin
class TaskUseCase(
    private val repository: ITaskRepository,
    private val dialogService: IDialogService,
    private val toastService: IToastService
) {
    /**
     * 선택적 필터링으로 모든 태스크를 관찰합니다.
     */
    fun observeTasks(filter: TaskFilter = TaskFilter()): Flow<List<Task>> {
        return repository.observeTasks(filter)
    }

    /**
     * 새 태스크를 생성합니다.
     *
     * 흐름:
     * 1. 입력 검증
     * 2. 태스크 생성
     * 3. 저장소에 저장
     * 4. 성공 토스트 표시
     */
    suspend fun createTask(
        title: String,
        description: String = "",
        priority: Priority = Priority.MEDIUM,
        dueDate: DueDate? = null
    ): Result<Task> {
        // 1. 검증과 함께 생성
        val taskResult = Task.create(
            title = title,
            description = description,
            priority = priority,
            dueDate = dueDate
        )

        val task = taskResult.getOrElse { error ->
            toastService.showToast("태스크 생성 실패: ${error.message}")
            return Result.failure(error)
        }

        // 2. 저장
        return runCatching {
            repository.saveTask(task)
            toastService.showToast("태스크가 생성되었습니다")
            task
        }.onFailure { error ->
            toastService.showToast("태스크 저장 실패: ${error.message}")
        }
    }

    /**
     * 확인 후 태스크를 삭제합니다.
     *
     * 흐름:
     * 1. 태스크 조회
     * 2. 확인 다이얼로그 표시
     * 3. 확인되면 삭제
     * 4. 실행 취소 토스트 표시
     * 5. 실행 취소 클릭 시 복원
     */
    suspend fun deleteTask(taskId: TaskId): Boolean {
        // 1. 태스크 조회
        val task = repository.getTask(taskId)
        if (task == null) {
            toastService.showToast("태스크를 찾을 수 없습니다")
            return false
        }

        // 2. 삭제 확인
        val confirmed = dialogService.showConfirmDialog(
            title = "태스크 삭제",
            message = "\"${task.title.value}\"을(를) 삭제하시겠습니까?",
            confirmText = "삭제",
            cancelText = "취소"
        )

        if (!confirmed) return false

        // 3. 삭제
        repository.deleteTask(taskId)

        // 4. 실행 취소 토스트 표시
        val undoClicked = toastService.showUndoToast(
            message = "태스크가 삭제되었습니다",
            undoLabel = "실행 취소"
        )

        // 5. 실행 취소 시 복원
        if (undoClicked) {
            repository.saveTask(task)
            toastService.showToast("태스크가 복원되었습니다")
            return false
        }

        return true
    }

    /**
     * 태스크 완료 상태를 토글합니다.
     *
     * 흐름:
     * 1. 태스크 조회
     * 2. 완료 상태 토글
     * 3. 저장
     * 4. 피드백 표시
     */
    suspend fun toggleComplete(taskId: TaskId): Result<Task> {
        val task = repository.getTask(taskId)
            ?: return Result.failure(IllegalArgumentException("태스크를 찾을 수 없습니다"))

        val updatedTask = if (task.isCompleted) {
            task.reopen()
        } else {
            task.complete()
        }

        return runCatching {
            repository.saveTask(updatedTask)

            val message = if (updatedTask.isCompleted) {
                "태스크가 완료되었습니다"
            } else {
                "태스크가 다시 열렸습니다"
            }
            toastService.showToast(message)

            updatedTask
        }
    }

    /**
     * 검증과 함께 태스크 제목을 업데이트합니다.
     */
    suspend fun updateTitle(taskId: TaskId, newTitle: String): Result<Task> {
        val task = repository.getTask(taskId)
            ?: return Result.failure(IllegalArgumentException("태스크를 찾을 수 없습니다"))

        val titleResult = runCatching { Title(newTitle) }
        val title = titleResult.getOrElse { error ->
            toastService.showToast(error.message ?: "잘못된 제목입니다")
            return Result.failure(error)
        }

        val updatedTask = task.updateTitle(title)

        return runCatching {
            repository.saveTask(updatedTask)
            toastService.showToast("태스크가 업데이트되었습니다")
            updatedTask
        }
    }

    /**
     * 확인 후 완료된 태스크를 일괄 삭제합니다.
     */
    suspend fun deleteCompletedTasks(): Int {
        val completedTasks = repository.observeTasks(
            TaskFilter(showCompleted = true)
        ).first().filter { it.isCompleted }

        if (completedTasks.isEmpty()) {
            toastService.showToast("삭제할 완료된 태스크가 없습니다")
            return 0
        }

        val confirmed = dialogService.showConfirmDialog(
            title = "완료된 태스크 삭제",
            message = "${completedTasks.size}개의 완료된 태스크를 삭제하시겠습니까?",
            confirmText = "모두 삭제",
            cancelText = "취소"
        )

        if (!confirmed) return 0

        completedTasks.forEach { task ->
            repository.deleteTask(task.id)
        }

        toastService.showToast("${completedTasks.size}개의 태스크가 삭제되었습니다")
        return completedTasks.size
    }
}
```

---

## Domain Layer 테스트

### Domain 테스트가 가장 중요한 이유

```
Domain Layer = 비즈니스 규칙 = 테스트가 가장 중요

- Entity 테스트에는 모킹 프레임워크가 필요 없음
- UseCase 테스트에는 간단한 인터페이스 모킹만 필요
- 빠른 실행 (I/O 없음, 프레임워크 없음)
- 비즈니스 로직에 대한 높은 신뢰도
```

### Entity 테스트

```kotlin
class TaskTest {

    @Test
    fun `유효한 입력으로 태스크 생성 성공`() {
        val result = Task.create(
            title = "장보기",
            description = "우유, 계란, 빵",
            priority = Priority.MEDIUM
        )

        assertTrue(result.isSuccess)
        val task = result.getOrThrow()
        assertEquals("장보기", task.title.value)
        assertEquals("우유, 계란, 빵", task.description.value)
        assertEquals(Priority.MEDIUM, task.priority)
        assertFalse(task.isCompleted)
    }

    @Test
    fun `빈 제목으로 태스크 생성 실패`() {
        val result = Task.create(title = "   ")

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("빈") == true)
    }

    @Test
    fun `최대 길이 초과 제목으로 태스크 생성 실패`() {
        val longTitle = "가".repeat(Title.MAX_LENGTH + 1)
        val result = Task.create(title = longTitle)

        assertTrue(result.isFailure)
    }

    @Test
    fun `태스크 완료 시 isCompleted가 true로 설정됨`() {
        val task = Task.create(title = "테스트").getOrThrow()

        val completed = task.complete()

        assertTrue(completed.isCompleted)
        assertEquals(task.id, completed.id) // 동일한 식별자
    }

    @Test
    fun `태스크 완료 시 updatedAt이 업데이트됨`() {
        val createdAt = Instant.parse("2024-01-15T10:00:00Z")
        val completedAt = Instant.parse("2024-01-15T11:00:00Z")
        val task = Task.create(title = "테스트", now = createdAt).getOrThrow()

        val completed = task.complete(now = completedAt)

        assertEquals(createdAt, completed.createdAt) // 변경 없음
        assertEquals(completedAt, completed.updatedAt) // 업데이트됨
    }

    @Test
    fun `완료된 태스크 다시 열기 시 isCompleted가 false로 설정됨`() {
        val task = Task.create(title = "테스트").getOrThrow().complete()

        val reopened = task.reopen()

        assertFalse(reopened.isCompleted)
    }

    @Test
    fun `지난 마감일에 대해 isOverdue가 true 반환`() {
        val checkTime = Instant.parse("2024-01-15T12:00:00Z")
        val yesterday = DueDate(Instant.parse("2024-01-14T12:00:00Z"))
        val task = Task.create(
            title = "기한 지난 태스크",
            dueDate = yesterday
        ).getOrThrow()

        assertTrue(task.isOverdue(now = checkTime))
    }

    @Test
    fun `완료된 태스크에 대해 isOverdue가 false 반환`() {
        val checkTime = Instant.parse("2024-01-15T12:00:00Z")
        val yesterday = DueDate(Instant.parse("2024-01-14T12:00:00Z"))
        val task = Task.create(
            title = "완료된 기한 지난 태스크",
            dueDate = yesterday
        ).getOrThrow().complete()

        assertFalse(task.isOverdue(now = checkTime)) // 완료된 태스크는 절대 "기한 초과"가 아님
    }

    @Test
    fun `오늘 마감인 태스크의 urgencyLevel은 CRITICAL`() {
        val today = LocalDate.parse("2024-01-15")
        val dueDate = DueDate.today(now = today)
        val task = Task.create(
            title = "오늘 마감",
            dueDate = dueDate
        ).getOrThrow()

        assertEquals(UrgencyLevel.CRITICAL, task.urgencyLevel(today = today))
    }

    @Test
    fun `완료된 태스크의 urgencyLevel은 NONE`() {
        val today = LocalDate.parse("2024-01-15")
        val dueDate = DueDate.today(now = today)
        val task = Task.create(
            title = "오늘 마감이지만 완료됨",
            dueDate = dueDate
        ).getOrThrow().complete()

        assertEquals(UrgencyLevel.NONE, task.urgencyLevel(today = today))
    }
}
```

### Value Object 테스트

```kotlin
class TitleTest {

    @Test
    fun `유효한 제목 생성`() {
        val title = Title("우유 사기")
        assertEquals("우유 사기", title.value)
    }

    @Test
    fun `빈 제목은 예외 발생`() {
        assertThrows<IllegalArgumentException> {
            Title("   ")
        }
    }

    @Test
    fun `최대 길이 제목은 유효`() {
        val maxTitle = "가".repeat(Title.MAX_LENGTH)
        val title = Title(maxTitle)
        assertEquals(Title.MAX_LENGTH, title.value.length)
    }

    @Test
    fun `최대 길이 초과 제목은 예외 발생`() {
        val tooLong = "가".repeat(Title.MAX_LENGTH + 1)
        assertThrows<IllegalArgumentException> {
            Title(tooLong)
        }
    }
}

class DueDateTest {

    @Test
    fun `지난 날짜에 대해 isOverdue가 true 반환`() {
        val now = Instant.parse("2024-01-15T12:00:00Z")
        val yesterday = DueDate(Instant.parse("2024-01-14T12:00:00Z"))

        assertTrue(yesterday.isOverdue(now))
    }

    @Test
    fun `미래 날짜에 대해 isOverdue가 false 반환`() {
        val now = Instant.parse("2024-01-15T12:00:00Z")
        val tomorrow = DueDate(Instant.parse("2024-01-16T12:00:00Z"))

        assertFalse(tomorrow.isOverdue(now))
    }

    @Test
    fun `daysUntilDue가 올바르게 계산됨`() {
        val today = LocalDate.parse("2024-01-15")
        val inThreeDays = DueDate(
            LocalDate.parse("2024-01-18")
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
        )

        assertEquals(3, inThreeDays.daysUntilDue(today))
    }

    @Test
    fun `isToday가 같은 날짜에 대해 true 반환`() {
        val today = LocalDate.parse("2024-01-15")
        val dueDate = DueDate.today(now = today)

        assertTrue(dueDate.isToday(today))
    }

    @Test
    fun `today 팩토리가 올바른 날짜 생성`() {
        val fixedDate = LocalDate.parse("2024-01-15")
        val dueDate = DueDate.today(now = fixedDate)

        assertTrue(dueDate.isToday(fixedDate))
        assertFalse(dueDate.isToday(fixedDate.plusDays(1)))
    }
}
```

### UseCase 테스트

```kotlin
class TaskUseCaseTest {

    // 간단한 테스트 더블 - 모킹 프레임워크 불필요
    private lateinit var repository: FakeTaskRepository
    private lateinit var dialogService: FakeDialogService
    private lateinit var toastService: FakeToastService
    private lateinit var useCase: TaskUseCase

    @BeforeEach
    fun setup() {
        repository = FakeTaskRepository()
        dialogService = FakeDialogService()
        toastService = FakeToastService()
        useCase = TaskUseCase(repository, dialogService, toastService)
    }

    @Test
    fun `createTask가 태스크를 저장하고 토스트를 표시함`() = runTest {
        val result = useCase.createTask(
            title = "새 태스크",
            priority = Priority.HIGH
        )

        assertTrue(result.isSuccess)
        val task = result.getOrThrow()

        // 저장 확인
        assertEquals(task, repository.getTask(task.id))

        // 토스트 표시 확인
        assertEquals("태스크가 생성되었습니다", toastService.lastToast)
    }

    @Test
    fun `잘못된 제목으로 createTask 시 에러 토스트 표시`() = runTest {
        val result = useCase.createTask(title = "")

        assertTrue(result.isFailure)
        assertTrue(toastService.lastToast?.contains("실패") == true)
    }

    @Test
    fun `deleteTask가 확인 다이얼로그를 표시함`() = runTest {
        // Given: 태스크가 존재함
        val task = Task.create(title = "삭제할 항목").getOrThrow()
        repository.saveTask(task)

        // And: 사용자가 확인할 것임
        dialogService.confirmResult = true

        // When
        useCase.deleteTask(task.id)

        // Then: 다이얼로그가 표시됨
        assertEquals("태스크 삭제", dialogService.lastDialogTitle)
    }

    @Test
    fun `사용자가 취소하면 deleteTask가 삭제하지 않음`() = runTest {
        val task = Task.create(title = "유지해주세요").getOrThrow()
        repository.saveTask(task)

        dialogService.confirmResult = false

        val deleted = useCase.deleteTask(task.id)

        assertFalse(deleted)
        assertNotNull(repository.getTask(task.id)) // 여전히 존재
    }

    @Test
    fun `실행 취소 클릭 시 deleteTask가 태스크를 복원함`() = runTest {
        val task = Task.create(title = "복원해주세요").getOrThrow()
        repository.saveTask(task)

        dialogService.confirmResult = true
        toastService.undoWillBeClicked = true

        val deleted = useCase.deleteTask(task.id)

        assertFalse(deleted)
        assertNotNull(repository.getTask(task.id)) // 복원됨
        assertEquals("태스크가 복원되었습니다", toastService.lastToast)
    }

    @Test
    fun `toggleComplete가 미완료 태스크를 완료함`() = runTest {
        val task = Task.create(title = "완료해주세요").getOrThrow()
        repository.saveTask(task)

        val result = useCase.toggleComplete(task.id)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isCompleted)
        assertEquals("태스크가 완료되었습니다", toastService.lastToast)
    }

    @Test
    fun `toggleComplete가 완료된 태스크를 다시 열음`() = runTest {
        val task = Task.create(title = "다시 열어주세요").getOrThrow().complete()
        repository.saveTask(task)

        val result = useCase.toggleComplete(task.id)

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow().isCompleted)
        assertEquals("태스크가 다시 열렸습니다", toastService.lastToast)
    }
}
```

### 테스트 더블 (Fake)

```kotlin
class FakeTaskRepository : ITaskRepository {
    private val tasks = mutableMapOf<TaskId, Task>()
    private val tasksFlow = MutableStateFlow<List<Task>>(emptyList())

    override fun observeTasks(): Flow<List<Task>> = tasksFlow

    override fun observeTasks(filter: TaskFilter): Flow<List<Task>> {
        return tasksFlow.map { tasks ->
            tasks.filter { task ->
                if (!filter.showCompleted && task.isCompleted) return@filter false
                if (filter.priority != null && task.priority != filter.priority) return@filter false
                true
            }.let { filtered ->
                when (filter.sortBy) {
                    SortBy.CREATED_AT -> filtered.sortedBy { it.createdAt }
                    SortBy.DUE_DATE -> filtered.sortedBy { it.dueDate?.value }
                    SortBy.PRIORITY -> filtered.sortedBy { it.priority.level }
                    SortBy.TITLE -> filtered.sortedBy { it.title.value }
                }.let { sorted ->
                    if (filter.sortOrder == SortOrder.DESC) sorted.reversed() else sorted
                }
            }
        }
    }

    override suspend fun getTask(id: TaskId): Task? = tasks[id]

    override suspend fun saveTask(task: Task) {
        tasks[task.id] = task
        tasksFlow.value = tasks.values.toList()
    }

    override suspend fun deleteTask(id: TaskId) {
        tasks.remove(id)
        tasksFlow.value = tasks.values.toList()
    }
}

class FakeDialogService : IDialogService {
    var confirmResult: Boolean = true
    var inputResult: String? = null
    var lastDialogTitle: String? = null

    override suspend fun showConfirmDialog(
        title: String,
        message: String,
        confirmText: String,
        cancelText: String
    ): Boolean {
        lastDialogTitle = title
        return confirmResult
    }

    override suspend fun showInputDialog(
        title: String,
        message: String,
        initialValue: String,
        hint: String
    ): String? {
        lastDialogTitle = title
        return inputResult
    }
}

class FakeToastService : IToastService {
    var lastToast: String? = null
    var undoWillBeClicked: Boolean = false

    override fun showToast(message: String) {
        lastToast = message
    }

    override suspend fun showUndoToast(
        message: String,
        undoLabel: String,
        duration: Duration
    ): Boolean {
        lastToast = message
        return undoWillBeClicked
    }
}
```

---

## 왜 이런 구조인가?

### 1. 생성 시점의 Entity 검증

```kotlin
// 잘못된 상태는 불가능
val task = Task.create(title = "") // Result.failure 반환

// Task 인스턴스가 있다면, 그것은 유효함
fun process(task: Task) {
    // 검증할 필요 없음 - 이미 유효함
}
```

### 2. Value Object가 버그를 방지

```kotlin
// 컴파일 타임 안전성
fun updateTask(id: TaskId, title: Title) // 매개변수를 혼동할 수 없음

// 경계에서의 런타임 검증
val title = Title(userInput) // 잘못되면 예외 발생
```

### 3. UseCase가 사용자 흐름을 포함

```kotlin
// 완전한 삭제 흐름이 한 곳에
suspend fun deleteTask(taskId: TaskId): Boolean {
    // 조회 → 확인 → 삭제 → 실행 취소 옵션 → 필요시 복원
}

// Middleware는 그냥 호출만
on<DeleteTask> { _, action ->
    taskUseCase.deleteTask(action.taskId)
}
```

### 4. Port가 테스트를 가능하게 함

```kotlin
// Domain이 필요한 것을 정의
interface IDialogService {
    suspend fun showConfirmDialog(...): Boolean
}

// 테스트는 Fake 사용
class FakeDialogService : IDialogService {
    var confirmResult = true
    override suspend fun showConfirmDialog(...) = confirmResult
}

// 프로덕션은 실제 구현 사용
class AndroidDialogService(private val context: Context) : IDialogService {
    override suspend fun showConfirmDialog(...): Boolean {
        return suspendCancellableCoroutine { cont ->
            AlertDialog.Builder(context)
                .setTitle(title)
                .setPositiveButton(confirmText) { _, _ -> cont.resume(true) }
                .setNegativeButton(cancelText) { _, _ -> cont.resume(false) }
                .show()
        }
    }
}
```

---

## 자주 묻는 질문

### "Todo 앱에 이건 과한 설계 아닌가요?"

실제 Todo 앱에는? 아마도요. 하지만 이 튜토리얼은 확장 가능한 패턴을 보여줍니다. 복잡한 흐름(다단계 폼, 오프라인 동기화, 실행 취소/다시 실행)이 있는 프로덕션 앱에서는 이 구조가 효과를 발휘합니다.

### "ViewModel/Middleware에 비즈니스 로직을 넣으면 안 되나요?"

- **테스트 용이성**: Domain 테스트는 빠르고 간단
- **재사용성**: 같은 로직이 Android, iOS, 웹에서 동작
- **명확성**: 비즈니스 규칙이 한 곳에
- **AI 친화성**: 생성된 코드를 위한 명확한 경계

### "모든 것에 Value Object가 정말 필요한가요?"

아니요. 다음 경우에 사용하세요:
- 검증이 중요한 경우 (Title, Email, Password)
- 타입 혼동 가능성이 있는 경우 (TaskId vs UserId)
- 도메인 개념에 행위가 있는 경우 (isOverdue가 있는 DueDate)

검증이나 혼동 위험이 없다면 단순 문자열이나 원시 타입도 괜찮습니다.

---

## 요약

**만든 것:**

| 컴포넌트 | 목적 |
|----------|------|
| `Task` entity | 비즈니스 규칙, 검증, 도메인 연산 |
| Value object | 타입 안전성, 경계에서의 검증 |
| `TaskUseCase` | 사용자 흐름 조율 |
| Port | 외부 의존성을 위한 인터페이스 |
| 테스트 더블 | 테스트를 위한 간단한 Fake |

**핵심 원칙:**

1. **Entity = 비즈니스 규칙** - 데이터만이 아닌 행위까지
2. **UseCase = 사용자 흐름** - CRUD가 아닌 완전한 시나리오
3. **Port = 계약** - Domain이 필요를 정의하고, 플랫폼이 제공
4. **Domain에서 테스트** - 빠르고, 간단하고, 높은 신뢰도

---

## 다음 단계

**Part 2: Repository Layer**에서 구현할 것:
- `ITaskRepository`를 구현하는 `TaskRepository`
- `ILocalDataSource`와 `IRemoteDataSource` 인터페이스
- 캐싱 및 동기화 전략
- Repository 테스트

Repository가 데이터 소스를 조율하는 동안 Domain은 순수하게 유지됩니다.

---

*이 글은 FlowDux 튜토리얼 시리즈의 Part 1입니다.*

**시리즈:**
- 0. Clean Architecture 개요
- **1. Domain Layer** (이 글)
- 2. Repository Layer
- 3. Presentation Layer (FlowDux + Tests)
- 4. UI Layer (Android + Compose Tests)
- 5. DataSource 구현 (Room)
- 6. Backend 추가 (Offline-First)
- 7. iOS 확장
- 8+. 기능 추가

---

**태그:** #Kotlin #KotlinMultiplatform #FlowDux #CleanArchitecture #DDD #Domain #UseCase #Testing
