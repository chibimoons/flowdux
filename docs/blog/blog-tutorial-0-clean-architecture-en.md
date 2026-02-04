# FlowDux Tutorial Part 0: Clean Architecture Overview

*In the Age of AI-Generated Code, Architecture Matters More Than Ever*

---

## Series Overview

This series builds a complete Todo app, layer by layer:

| Part | Topic | Focus |
|------|-------|-------|
| **0** | Clean Architecture Overview | Big picture, requirements, direction (this post) |
| **1** | Domain Layer | Entity, UseCase + Tests |
| **2** | Repository Layer | Repository + DataSource interfaces |
| **3** | Presentation Layer | FlowDux + Tests |
| **4** | UI Layer (Android) | Jetpack Compose + Compose Tests |
| **5** | DataSource Implementation | Room DB |
| **6** | Adding Backend | RemoteDataSource + Offline-First |
| **7** | iOS Expansion | SwiftUI + iOS DataSource |
| **8+** | Adding Features | How new features flow through both platforms |

---

## Why Clean Architecture in the AI Era?

AI can generate code faster than ever. But without structure:
- Generated code ends up in random places
- Dependencies become tangled
- Testing becomes impossible
- Platform expansion becomes a rewrite

**Clean Architecture provides:**
- Clear boundaries for where code belongs
- Enforced dependency rules
- Testable layers
- Platform-agnostic business logic

---

## Our App: TaskFlow

### Requirements

A simple but complete Todo application:

1. **Create** tasks with title, description, priority, due date
2. **Read** task list with filtering and sorting
3. **Update** task details and completion status
4. **Delete** tasks with confirmation

### Evolution Plan

```
Phase 1: Local Only (Android)
    └── All data stored in local database

Phase 2: Add Backend
    └── Sync with server, offline-first strategy

Phase 3: iOS Expansion
    └── Reuse Domain, Repository, Presentation
    └── Write only: SwiftUI + iOS DataSource
```

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     Platform-Specific (Android/iOS)                      │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  UI Layer                                                        │    │
│  │  Android: Jetpack Compose  /  iOS: SwiftUI                       │    │
│  └─────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
                                    │ observes state
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        Shared (Pure Kotlin - KMP)                        │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  Presentation Layer (FlowDux)                                    │    │
│  │  State, Action, Reducer, Middleware                              │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                                    │ calls UseCase                       │
│                                    ▼                                     │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  Domain Layer                                                    │    │
│  │  Entity (Business Rules) + UseCase (User Flow)                   │    │
│  │  Service Interfaces                                              │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                                    │ uses interface                      │
│                                    ▼                                     │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  Repository Layer (Pure Kotlin)                                  │    │
│  │  Orchestrates DataSources, caching strategy                      │    │
│  └─────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
                                    │ delegates to
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     Platform-Specific (Android/iOS)                      │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  DataSource Layer                                                │    │
│  │  Android: Room  /  iOS: SQLDelight                               │    │
│  │  Remote: Ktor + External SaaS                                    │    │
│  └─────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Layer Responsibilities

### Domain Layer (Pure Kotlin)
- **Entity**: Business rules, validation, domain operations
- **UseCase**: User flow orchestration (dialog → action → toast)
- **Ports (interfaces)**: `ITaskRepository`, `IDialogService`, `IToastService`

### Repository Layer (Pure Kotlin)
- **Repository implementation**: Implements domain's `ITaskRepository` port
- **DataSource interfaces (ports)**: `ILocalDataSource`, `IRemoteDataSource`
- Orchestrates DataSources, handles caching and sync strategy

### Presentation Layer (Pure Kotlin + FlowDux)
- **State**: UI state representation
- **Action**: User intents and internal events
- **Reducer**: Pure state transitions
- **Middleware**: Side effects, calls UseCase

### DataSource Layer (Platform-Specific)
- Actual database implementation (Room, SQLDelight)
- Network calls (Ktor)
- Platform APIs

### UI Layer (Platform-Specific)
- Jetpack Compose (Android)
- SwiftUI (iOS)
- Observes Presentation state, dispatches actions

---

## Module Structure

```
project/
├── core/
│   ├── domain/           # :core:domain (no dependencies)
│   ├── repository/       # :core:repository (depends on domain)
│   └── presentation/     # :core:presentation (depends on domain)
│
├── androidApp/           # Room DataSource + Jetpack Compose
│
└── iosApp/               # SQLDelight DataSource + SwiftUI
```

### Dependency Rules (Compile-Time Enforced)

```
:core:domain       → (nothing)
:core:repository   → :core:domain
:core:presentation → :core:domain (NOT repository!)
:androidApp        → all core modules
:iosApp            → all core modules
```

**Key Point**: `presentation` cannot depend on `repository`. They only share `domain`.

### Where Does Each Code Live?

| Code | Location |
|------|----------|
| Entity, UseCase, **Ports** (IRepository, IDialogService...) | `:core:domain` |
| Repository **impl**, DataSource **interfaces** | `:core:repository` |
| State, Action, Reducer, Middleware | `:core:presentation` |
| DataSource **impl** (Room) + Compose UI | `:androidApp` |
| DataSource **impl** (SQLDelight) + SwiftUI | `:iosApp` |

---

## Client Domain ≠ Backend Domain

A common misconception: "Just reuse the backend's domain model on the client."

**Backend Domain focuses on:**
- Data integrity and validation
- Business rule enforcement
- Transactions and consistency
- Authorization and security

**Client Domain focuses on:**
- User flow orchestration (dialog → action → feedback)
- Client-specific business rules and validation
- Domain operations (complete, reopen, prioritize)
- Defining service contracts (Ports)

### Example: Deleting a Task

**Backend:**
```
DELETE /tasks/{id}
→ Check authorization
→ Validate business rules
→ Delete from database
→ Return 204 No Content
```

**Client UseCase Flow:**

```
┌─────────────────────────────────────────────────────────────────┐
│                       UseCase.deleteTask()                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐       │
│  │ 1. Validate  │───▶│ 2. Confirm   │───▶│ 3. Execute   │       │
│  │              │    │              │    │              │       │
│  │ Check rules  │    │ IDialogSvc   │    │ IRepository  │       │
│  │ from Entity  │    │ .showDialog()│    │ .delete()    │       │
│  └──────────────┘    └──────────────┘    └──────┬───────┘       │
│         │                   │                   │               │
│         ▼                   ▼                   ▼               │
│   [Fail → toast]    [Cancel → return]    ┌──────────────┐       │
│                                          │ 4. Feedback  │       │
│                                          │              │       │
│                                          │ IToastSvc    │       │
│                                          │ .showToast() │       │
│                                          └──────────────┘       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**Sequence Diagram:**

```
┌──────┐     ┌──────────┐     ┌─────────┐     ┌────────────┐     ┌─────────────┐
│  UI  │     │  Store   │     │ UseCase │     │ Repository │     │Port Services│
└──┬───┘     └────┬─────┘     └────┬────┘     └─────┬──────┘     └──────┬──────┘
   │              │                │                │                   │
   │ DeleteTask   │                │                │                   │
   │─────────────▶│                │                │                   │
   │              │ deleteTask()   │                │                   │
   │              │───────────────▶│                │                   │
   │              │                │                │                   │
   │              │                │ check rules    │                   │
   │              │                │───────┐        │                   │
   │              │                │       │        │                   │
   │              │                │◀──────┘        │                   │
   │              │                │                │                   │
   │              │                │ showConfirmDialog()                │
   │              │                │───────────────────────────────────▶│
   │              │                │                │                   │
   │◀ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ [Dialog appears] ─ ─ ─ ─│
   │              │                │                │                   │
   │ [User confirms]               │◀──────────────────────────────────│
   │              │                │                │                   │
   │              │                │ delete()       │                   │
   │              │                │───────────────▶│                   │
   │              │                │                │                   │
   │              │                │     done       │                   │
   │              │                │◀───────────────│                   │
   │              │                │                │                   │
   │              │                │ showToast()    │                   │
   │              │                │───────────────────────────────────▶│
   │              │                │                │                   │
   │◀ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─[Toast shows]─ ─ ─ ─ ─ │
   │              │                │                │                   │
   │              │◀════════════════════════════════│                   │
   │              │  [Repository Flow emits update] │                   │
   │              │                │                │                   │
   │◀─────────────│                │                │                   │
   │  [Reducer updates state → UI refreshes]        │                   │
   │              │                │                │                   │
```

The client's UseCase handles the **complete user journey**, not just the API call.

### How Can a UseCase Trigger UI Without Depending on UI Frameworks?

> "Wait, if UseCase shows dialogs and toasts, doesn't it depend on the UI framework?"

**No.** UseCase doesn't call UI frameworks directly. It calls **Ports (interfaces)** like `IDialogService`, `IToastService`.

```
┌─────────────────────────────────────────────────────────┐
│  :core:domain                                           │
│                                                         │
│  ┌─────────────┐     ┌─────────────────────────────┐   │
│  │  UseCase    │────▶│  IDialogService (Port)      │   │
│  │             │────▶│  IToastService (Port)       │   │
│  └─────────────┘     └─────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
                                   ▲
                                   │ implements
                                   │
┌─────────────────────────────────────────────────────────┐
│  :androidApp                                            │
│                                                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │  DialogService : IDialogService                 │   │
│  │  ToastService : IToastService                   │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

- **Port declared in**: `:core:domain`
- **Port implemented in**: `:androidApp`, `:iosApp`
- **UseCase knows**: Only the interface (Port)
- **UseCase doesn't know**: Compose, SwiftUI, or any UI framework

This is classic **Dependency Inversion**. The domain defines what it needs, and the platform provides the implementation. Some may debate whether this is "pure" Clean Architecture, but for client apps where user flow is the core business logic, this approach keeps the domain testable while expressing complete user scenarios.

---

## Key Design Principles

### 1. UseCase = User Flow

UseCase is not a CRUD wrapper. It orchestrates the complete user scenario:

```
User taps "Delete"
    → UseCase checks business rules
    → UseCase shows confirmation dialog (via IDialogService port)
    → User confirms
    → UseCase deletes via Repository (via ITaskRepository port)
    → UseCase shows success toast (via IToastService port)
    → Repository Flow emits updated list
    → UI automatically updates
```

Note: UseCase never calls UI frameworks directly—it calls **Ports** (see "How Can a UseCase Trigger UI" section above).

### 2. Entity = Business Rules

Entity contains domain logic, not just data:

```
Task
├── isOverdue (computed)
├── urgencyLevel (computed)
├── validate() → ValidationResult
├── complete() → Task
└── reopen() → Task
```

### 3. Middleware is Thin

Middleware just calls UseCase - no business logic:

```kotlin
on<DeleteTask> { _, action ->
    taskUseCase.deleteTask(action.taskId)
    // That's it!
}
```

*"But where do loading/error/success states become Actions?"* — We'll cover this in **Part 3: Presentation Layer**.

### 4. Dependencies Point Inward

```
UI → Presentation → Domain ← Repository ← DataSource
                      ↑
              (everything depends on Domain)
```

---

## What We'll Build

By the end of this series, you'll have:

```
✓ Testable Domain layer (aim for high coverage)
✓ Repository with offline-first sync strategy
✓ FlowDux state management with tests
✓ Android app with Compose UI tests
✓ iOS app sharing most of the code
✓ Pattern for adding new features to both platforms
```

---

## Code Sharing Reality

| Layer | Android | iOS | Shared |
|-------|---------|-----|--------|
| Domain | - | - | 100% |
| Repository | - | - | 100% |
| Presentation | - | - | 100% |
| DataSource | Room | SQLDelight | Interface only |
| UI | Compose | SwiftUI | - |

**Result**: Expect ~70–85% code shared, depending on features and UI complexity

---

## Next Steps

In **Part 1**, we'll implement the Domain layer:
- `Task` entity with business rules
- `TaskUseCase` with user flow methods
- Comprehensive unit tests

Let's start building!

---

*This is Part 0 of the FlowDux Tutorial Series.*

**Series:**
- **0. Clean Architecture Overview** (this post)
- 1. Domain Layer (Entity, UseCase + Tests)
- 2. Repository Layer
- 3. Presentation Layer (FlowDux + Tests)
- 4. UI Layer (Android + Compose Tests)
- 5. DataSource Implementation (Room)
- 6. Adding Backend (Offline-First)
- 7. iOS Expansion
- 8+. Adding Features

---

**Tags:** #Kotlin #KotlinMultiplatform #FlowDux #CleanArchitecture #KMP #Android #iOS
