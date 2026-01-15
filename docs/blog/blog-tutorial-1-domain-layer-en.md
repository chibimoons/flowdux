# FlowDux Tutorial Part 1: Domain Layer

*Where Business Logic Lives — Entity, UseCase, and Ports*

---

## What We'll Build

In this part, we implement the **Domain Layer** for our TaskFlow app:

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

**Key principle**: Domain has **zero dependencies**. No Android, no iOS, no frameworks. Pure Kotlin only.

---

## Entity: More Than Just Data

### The Anti-Pattern: Anemic Entity

```kotlin
// DON'T: Data class with no behavior
data class Task(
    val id: String,
    val title: String,
    val description: String,
    val priority: Int,
    val dueDate: Long?,
    val isCompleted: Boolean
)

// Business logic scattered elsewhere
fun isTaskOverdue(task: Task): Boolean {
    return task.dueDate?.let { it < System.currentTimeMillis() } ?: false
}
```

Problems:
- Business rules scattered across the codebase
- Easy to forget validation
- Hard to test consistently
- Different interpretations of the same rule

### The Right Way: Rich Entity

```kotlin
// DO: Entity with embedded business logic
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
    // Computed properties (time-dependent → functions for testability)
    fun isOverdue(now: Instant = Instant.now()): Boolean =
        !isCompleted && dueDate?.isOverdue(now) == true

    fun urgencyLevel(today: LocalDate = LocalDate.now()): UrgencyLevel =
        calculateUrgency(today)

    // Domain operations
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

    // Factory method with validation
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

## Value Objects: Type Safety for Domain Concepts

### Why Value Objects?

```kotlin
// Primitive obsession - easy to make mistakes
fun createTask(id: String, title: String, description: String)

// Accidentally swapped arguments - compiles fine!
createTask("Buy milk", "task-123", "Get 2% milk")
```

With value objects:

```kotlin
// Type-safe - compiler catches mistakes
fun createTask(id: TaskId, title: Title, description: Description)

// Won't compile - types don't match
createTask(Title("Buy milk"), TaskId("task-123"), Description("..."))
```

### Implementing Value Objects

```kotlin
@JvmInline
value class TaskId(val value: String) {
    init {
        require(value.isNotBlank()) { "TaskId cannot be blank" }
    }

    companion object {
        fun generate(): TaskId = TaskId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class Title(val value: String) {
    init {
        require(value.isNotBlank()) { "Title cannot be blank" }
        require(value.length <= MAX_LENGTH) {
            "Title cannot exceed $MAX_LENGTH characters"
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
            "Description cannot exceed $MAX_LENGTH characters"
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

### DueDate with Business Logic

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

### UrgencyLevel Calculation

```kotlin
enum class UrgencyLevel {
    NONE,       // No due date or completed
    LOW,        // > 7 days
    MEDIUM,     // 3-7 days
    HIGH,       // 1-2 days
    CRITICAL    // Today or overdue
}

// Inside Task entity
private fun calculateUrgency(today: LocalDate): UrgencyLevel {
    if (isCompleted) return UrgencyLevel.NONE

    val dueDate = this.dueDate ?: return UrgencyLevel.NONE
    val daysLeft = dueDate.daysUntilDue(today)

    return when {
        daysLeft < 0 -> UrgencyLevel.CRITICAL  // Overdue
        daysLeft == 0L -> UrgencyLevel.CRITICAL // Today
        daysLeft <= 2 -> UrgencyLevel.HIGH
        daysLeft <= 7 -> UrgencyLevel.MEDIUM
        else -> UrgencyLevel.LOW
    }
}
```

---

## Ports: Interfaces the Domain Needs

Domain defines **what it needs**, not how it's implemented.

### ITaskRepository

```kotlin
interface ITaskRepository {
    /**
     * Observe all tasks as a Flow.
     * Emits new list whenever data changes.
     */
    fun observeTasks(): Flow<List<Task>>

    /**
     * Get a single task by ID.
     */
    suspend fun getTask(id: TaskId): Task?

    /**
     * Save a task (insert or update).
     */
    suspend fun saveTask(task: Task)

    /**
     * Delete a task by ID.
     */
    suspend fun deleteTask(id: TaskId)

    /**
     * Observe tasks matching a filter.
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
     * Show a confirmation dialog.
     * Suspends until user responds.
     *
     * @return true if confirmed, false if cancelled
     */
    suspend fun showConfirmDialog(
        title: String,
        message: String,
        confirmText: String = "Confirm",
        cancelText: String = "Cancel"
    ): Boolean

    /**
     * Show an input dialog.
     * Suspends until user responds.
     *
     * @return entered text, or null if cancelled
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
     * Show a short toast message.
     */
    fun showToast(message: String)

    /**
     * Show a toast with an undo action.
     * @return true if undo was clicked
     */
    suspend fun showUndoToast(
        message: String,
        undoLabel: String = "Undo",
        duration: Duration = 5.seconds
    ): Boolean
}
```

---

## UseCase: Orchestrating User Flows

UseCase is not a CRUD wrapper. It orchestrates **complete user scenarios**.

### TaskUseCase

```kotlin
class TaskUseCase(
    private val repository: ITaskRepository,
    private val dialogService: IDialogService,
    private val toastService: IToastService
) {
    /**
     * Observe all tasks with optional filtering.
     */
    fun observeTasks(filter: TaskFilter = TaskFilter()): Flow<List<Task>> {
        return repository.observeTasks(filter)
    }

    /**
     * Create a new task.
     *
     * Flow:
     * 1. Validate input
     * 2. Create task
     * 3. Save to repository
     * 4. Show success toast
     */
    suspend fun createTask(
        title: String,
        description: String = "",
        priority: Priority = Priority.MEDIUM,
        dueDate: DueDate? = null
    ): Result<Task> {
        // 1. Create with validation
        val taskResult = Task.create(
            title = title,
            description = description,
            priority = priority,
            dueDate = dueDate
        )

        val task = taskResult.getOrElse { error ->
            toastService.showToast("Failed to create task: ${error.message}")
            return Result.failure(error)
        }

        // 2. Save
        return runCatching {
            repository.saveTask(task)
            toastService.showToast("Task created")
            task
        }.onFailure { error ->
            toastService.showToast("Failed to save task: ${error.message}")
        }
    }

    /**
     * Delete a task with confirmation.
     *
     * Flow:
     * 1. Fetch task
     * 2. Show confirmation dialog
     * 3. Delete if confirmed
     * 4. Show undo toast
     * 5. Restore if undo clicked
     */
    suspend fun deleteTask(taskId: TaskId): Boolean {
        // 1. Fetch task
        val task = repository.getTask(taskId)
        if (task == null) {
            toastService.showToast("Task not found")
            return false
        }

        // 2. Confirm deletion
        val confirmed = dialogService.showConfirmDialog(
            title = "Delete Task",
            message = "Are you sure you want to delete \"${task.title.value}\"?",
            confirmText = "Delete",
            cancelText = "Cancel"
        )

        if (!confirmed) return false

        // 3. Delete
        repository.deleteTask(taskId)

        // 4. Show undo toast
        val undoClicked = toastService.showUndoToast(
            message = "Task deleted",
            undoLabel = "Undo"
        )

        // 5. Restore if undo
        if (undoClicked) {
            repository.saveTask(task)
            toastService.showToast("Task restored")
            return false
        }

        return true
    }

    /**
     * Toggle task completion status.
     *
     * Flow:
     * 1. Fetch task
     * 2. Toggle completion
     * 3. Save
     * 4. Show feedback
     */
    suspend fun toggleComplete(taskId: TaskId): Result<Task> {
        val task = repository.getTask(taskId)
            ?: return Result.failure(IllegalArgumentException("Task not found"))

        val updatedTask = if (task.isCompleted) {
            task.reopen()
        } else {
            task.complete()
        }

        return runCatching {
            repository.saveTask(updatedTask)

            val message = if (updatedTask.isCompleted) {
                "Task completed"
            } else {
                "Task reopened"
            }
            toastService.showToast(message)

            updatedTask
        }
    }

    /**
     * Update task title with validation.
     */
    suspend fun updateTitle(taskId: TaskId, newTitle: String): Result<Task> {
        val task = repository.getTask(taskId)
            ?: return Result.failure(IllegalArgumentException("Task not found"))

        val titleResult = runCatching { Title(newTitle) }
        val title = titleResult.getOrElse { error ->
            toastService.showToast(error.message ?: "Invalid title")
            return Result.failure(error)
        }

        val updatedTask = task.updateTitle(title)

        return runCatching {
            repository.saveTask(updatedTask)
            toastService.showToast("Task updated")
            updatedTask
        }
    }

    /**
     * Bulk delete completed tasks with confirmation.
     */
    suspend fun deleteCompletedTasks(): Int {
        val completedTasks = repository.observeTasks(
            TaskFilter(showCompleted = true)
        ).first().filter { it.isCompleted }

        if (completedTasks.isEmpty()) {
            toastService.showToast("No completed tasks to delete")
            return 0
        }

        val confirmed = dialogService.showConfirmDialog(
            title = "Delete Completed Tasks",
            message = "Delete ${completedTasks.size} completed task(s)?",
            confirmText = "Delete All",
            cancelText = "Cancel"
        )

        if (!confirmed) return 0

        completedTasks.forEach { task ->
            repository.deleteTask(task.id)
        }

        toastService.showToast("${completedTasks.size} task(s) deleted")
        return completedTasks.size
    }
}
```

---

## Testing the Domain Layer

### Why Domain Tests Matter Most

```
Domain Layer = Business Rules = Most Important to Test

- No mocking frameworks needed for Entity tests
- Simple interface mocks for UseCase tests
- Fast execution (no I/O, no frameworks)
- High confidence in business logic
```

### Testing Entity

```kotlin
class TaskTest {

    @Test
    fun `create task with valid input succeeds`() {
        val result = Task.create(
            title = "Buy groceries",
            description = "Milk, eggs, bread",
            priority = Priority.MEDIUM
        )

        assertTrue(result.isSuccess)
        val task = result.getOrThrow()
        assertEquals("Buy groceries", task.title.value)
        assertEquals("Milk, eggs, bread", task.description.value)
        assertEquals(Priority.MEDIUM, task.priority)
        assertFalse(task.isCompleted)
    }

    @Test
    fun `create task with blank title fails`() {
        val result = Task.create(title = "   ")

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("blank") == true)
    }

    @Test
    fun `create task with title exceeding max length fails`() {
        val longTitle = "a".repeat(Title.MAX_LENGTH + 1)
        val result = Task.create(title = longTitle)

        assertTrue(result.isFailure)
    }

    @Test
    fun `complete task sets isCompleted to true`() {
        val task = Task.create(title = "Test").getOrThrow()

        val completed = task.complete()

        assertTrue(completed.isCompleted)
        assertEquals(task.id, completed.id) // Same identity
    }

    @Test
    fun `complete task updates updatedAt`() {
        val createdAt = Instant.parse("2024-01-15T10:00:00Z")
        val completedAt = Instant.parse("2024-01-15T11:00:00Z")
        val task = Task.create(title = "Test", now = createdAt).getOrThrow()

        val completed = task.complete(now = completedAt)

        assertEquals(createdAt, completed.createdAt) // Unchanged
        assertEquals(completedAt, completed.updatedAt) // Updated
    }

    @Test
    fun `reopen completed task sets isCompleted to false`() {
        val task = Task.create(title = "Test").getOrThrow().complete()

        val reopened = task.reopen()

        assertFalse(reopened.isCompleted)
    }

    @Test
    fun `isOverdue returns true for past due date`() {
        val checkTime = Instant.parse("2024-01-15T12:00:00Z")
        val yesterday = DueDate(Instant.parse("2024-01-14T12:00:00Z"))
        val task = Task.create(
            title = "Overdue task",
            dueDate = yesterday
        ).getOrThrow()

        assertTrue(task.isOverdue(now = checkTime))
    }

    @Test
    fun `isOverdue returns false for completed task`() {
        val checkTime = Instant.parse("2024-01-15T12:00:00Z")
        val yesterday = DueDate(Instant.parse("2024-01-14T12:00:00Z"))
        val task = Task.create(
            title = "Completed overdue task",
            dueDate = yesterday
        ).getOrThrow().complete()

        assertFalse(task.isOverdue(now = checkTime)) // Completed tasks are never "overdue"
    }

    @Test
    fun `urgencyLevel is CRITICAL for task due today`() {
        val today = LocalDate.parse("2024-01-15")
        val dueDate = DueDate.today(now = today)
        val task = Task.create(
            title = "Due today",
            dueDate = dueDate
        ).getOrThrow()

        assertEquals(UrgencyLevel.CRITICAL, task.urgencyLevel(today = today))
    }

    @Test
    fun `urgencyLevel is NONE for completed task`() {
        val today = LocalDate.parse("2024-01-15")
        val dueDate = DueDate.today(now = today)
        val task = Task.create(
            title = "Due today but completed",
            dueDate = dueDate
        ).getOrThrow().complete()

        assertEquals(UrgencyLevel.NONE, task.urgencyLevel(today = today))
    }
}
```

### Testing Value Objects

```kotlin
class TitleTest {

    @Test
    fun `valid title is created`() {
        val title = Title("Buy milk")
        assertEquals("Buy milk", title.value)
    }

    @Test
    fun `blank title throws exception`() {
        assertThrows<IllegalArgumentException> {
            Title("   ")
        }
    }

    @Test
    fun `title at max length is valid`() {
        val maxTitle = "a".repeat(Title.MAX_LENGTH)
        val title = Title(maxTitle)
        assertEquals(Title.MAX_LENGTH, title.value.length)
    }

    @Test
    fun `title exceeding max length throws exception`() {
        val tooLong = "a".repeat(Title.MAX_LENGTH + 1)
        assertThrows<IllegalArgumentException> {
            Title(tooLong)
        }
    }
}

class DueDateTest {

    @Test
    fun `isOverdue returns true for past date`() {
        val now = Instant.parse("2024-01-15T12:00:00Z")
        val yesterday = DueDate(Instant.parse("2024-01-14T12:00:00Z"))

        assertTrue(yesterday.isOverdue(now))
    }

    @Test
    fun `isOverdue returns false for future date`() {
        val now = Instant.parse("2024-01-15T12:00:00Z")
        val tomorrow = DueDate(Instant.parse("2024-01-16T12:00:00Z"))

        assertFalse(tomorrow.isOverdue(now))
    }

    @Test
    fun `daysUntilDue calculates correctly`() {
        val today = LocalDate.parse("2024-01-15")
        val inThreeDays = DueDate(
            LocalDate.parse("2024-01-18")
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
        )

        assertEquals(3, inThreeDays.daysUntilDue(today))
    }

    @Test
    fun `isToday returns true for same date`() {
        val today = LocalDate.parse("2024-01-15")
        val dueDate = DueDate.today(now = today)

        assertTrue(dueDate.isToday(today))
    }

    @Test
    fun `today factory creates correct date`() {
        val fixedDate = LocalDate.parse("2024-01-15")
        val dueDate = DueDate.today(now = fixedDate)

        assertTrue(dueDate.isToday(fixedDate))
        assertFalse(dueDate.isToday(fixedDate.plusDays(1)))
    }
}
```

### Testing UseCase

```kotlin
class TaskUseCaseTest {

    // Simple test doubles - no mocking framework needed
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
    fun `createTask saves task and shows toast`() = runTest {
        val result = useCase.createTask(
            title = "New task",
            priority = Priority.HIGH
        )

        assertTrue(result.isSuccess)
        val task = result.getOrThrow()

        // Verify saved
        assertEquals(task, repository.getTask(task.id))

        // Verify toast shown
        assertEquals("Task created", toastService.lastToast)
    }

    @Test
    fun `createTask with invalid title shows error toast`() = runTest {
        val result = useCase.createTask(title = "")

        assertTrue(result.isFailure)
        assertTrue(toastService.lastToast?.contains("Failed") == true)
    }

    @Test
    fun `deleteTask shows confirmation dialog`() = runTest {
        // Given: a task exists
        val task = Task.create(title = "To delete").getOrThrow()
        repository.saveTask(task)

        // And: user will confirm
        dialogService.confirmResult = true

        // When
        useCase.deleteTask(task.id)

        // Then: dialog was shown
        assertEquals("Delete Task", dialogService.lastDialogTitle)
    }

    @Test
    fun `deleteTask does not delete when user cancels`() = runTest {
        val task = Task.create(title = "Keep me").getOrThrow()
        repository.saveTask(task)

        dialogService.confirmResult = false

        val deleted = useCase.deleteTask(task.id)

        assertFalse(deleted)
        assertNotNull(repository.getTask(task.id)) // Still exists
    }

    @Test
    fun `deleteTask restores task when undo clicked`() = runTest {
        val task = Task.create(title = "Undo me").getOrThrow()
        repository.saveTask(task)

        dialogService.confirmResult = true
        toastService.undoWillBeClicked = true

        val deleted = useCase.deleteTask(task.id)

        assertFalse(deleted)
        assertNotNull(repository.getTask(task.id)) // Restored
        assertEquals("Task restored", toastService.lastToast)
    }

    @Test
    fun `toggleComplete completes incomplete task`() = runTest {
        val task = Task.create(title = "Complete me").getOrThrow()
        repository.saveTask(task)

        val result = useCase.toggleComplete(task.id)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isCompleted)
        assertEquals("Task completed", toastService.lastToast)
    }

    @Test
    fun `toggleComplete reopens completed task`() = runTest {
        val task = Task.create(title = "Reopen me").getOrThrow().complete()
        repository.saveTask(task)

        val result = useCase.toggleComplete(task.id)

        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow().isCompleted)
        assertEquals("Task reopened", toastService.lastToast)
    }
}
```

### Test Doubles (Fakes)

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

## Why This Structure?

### 1. Entity Validation at Construction

```kotlin
// Invalid states are impossible
val task = Task.create(title = "") // Returns Result.failure

// If you have a Task instance, it's valid
fun process(task: Task) {
    // No need to validate - it's already valid
}
```

### 2. Value Objects Prevent Bugs

```kotlin
// Compile-time safety
fun updateTask(id: TaskId, title: Title) // Can't mix up parameters

// Runtime validation at boundaries
val title = Title(userInput) // Throws if invalid
```

### 3. UseCase Contains User Flow

```kotlin
// The complete delete flow in one place
suspend fun deleteTask(taskId: TaskId): Boolean {
    // fetch → confirm → delete → undo option → restore if needed
}

// Middleware just calls it
on<DeleteTask> { _, action ->
    taskUseCase.deleteTask(action.taskId)
}
```

### 4. Ports Enable Testing

```kotlin
// Domain defines what it needs
interface IDialogService {
    suspend fun showConfirmDialog(...): Boolean
}

// Tests use fakes
class FakeDialogService : IDialogService {
    var confirmResult = true
    override suspend fun showConfirmDialog(...) = confirmResult
}

// Production uses real implementation
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

## Common Questions

### "Isn't this over-engineering for a Todo app?"

For a real todo app? Maybe. But this tutorial demonstrates patterns that scale. In production apps with complex flows (multi-step forms, offline sync, undo/redo), this structure pays off.

### "Why not just put business logic in ViewModel/Middleware?"

- **Testability**: Domain tests are fast and simple
- **Reusability**: Same logic works on Android, iOS, web
- **Clarity**: Business rules are in one place
- **AI-friendliness**: Clear boundaries for generated code

### "Do I really need value objects for everything?"

No. Use them where:
- Validation is important (Title, Email, Password)
- Type confusion is possible (TaskId vs UserId)
- Domain concepts have behavior (DueDate with isOverdue)

Simple strings or primitives are fine when there's no validation or confusion risk.

---

## Summary

**What we built:**

| Component | Purpose |
|-----------|---------|
| `Task` entity | Business rules, validation, domain operations |
| Value objects | Type safety, validation at boundaries |
| `TaskUseCase` | User flow orchestration |
| Ports | Interfaces for external dependencies |
| Test doubles | Simple fakes for testing |

**Key principles:**

1. **Entity = Business Rules** - Not just data, but behavior
2. **UseCase = User Flow** - Complete scenarios, not CRUD
3. **Ports = Contracts** - Domain defines needs, platforms provide
4. **Test at Domain** - Fast, simple, high-confidence tests

---

## Next Steps

In **Part 2: Repository Layer**, we'll implement:
- `TaskRepository` implementing `ITaskRepository`
- `ILocalDataSource` and `IRemoteDataSource` interfaces
- Caching and sync strategies
- Repository tests

The Repository will orchestrate data sources while the Domain remains pure.

---

*This is Part 1 of the FlowDux Tutorial Series.*

**Series:**
- 0. Clean Architecture Overview
- **1. Domain Layer** (this post)
- 2. Repository Layer
- 3. Presentation Layer (FlowDux + Tests)
- 4. UI Layer (Android + Compose Tests)
- 5. DataSource Implementation (Room)
- 6. Adding Backend (Offline-First)
- 7. iOS Expansion
- 8+. Adding Features

---

**Tags:** #Kotlin #KotlinMultiplatform #FlowDux #CleanArchitecture #DDD #Domain #UseCase #Testing
