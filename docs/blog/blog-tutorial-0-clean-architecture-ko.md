# FlowDux 실전 튜토리얼 0편: 클린 아키텍처 개요

*AI가 코드를 쓰는 시대, 아키텍처는 더 중요해졌다*

---

## 시리즈 소개

이 시리즈는 완전한 Todo 앱을, 레이어별로 만들어갑니다:

| 편 | 주제 | 핵심 |
|---|------|------|
| **0편** | 클린 아키텍처 개요 | 큰 그림, 요구사항, 방향 (현재 글) |
| **1편** | Domain Layer | Entity, UseCase + 테스트 |
| **2편** | Repository Layer | Repository + DataSource 인터페이스 |
| **3편** | Presentation Layer | FlowDux + 테스트 |
| **4편** | UI Layer (Android) | Jetpack Compose + Compose 테스트 |
| **5편** | DataSource 구현 | Room DB |
| **6편** | 백엔드 추가 | RemoteDataSource + 오프라인 우선 |
| **7편** | iOS 확장 | SwiftUI + iOS DataSource |
| **8편~** | 피처 추가 | 새 기능이 양 플랫폼에서 어떻게 작업되는지 |

---

## AI 시대에 왜 클린 아키텍처인가?

AI가 그 어느 때보다 빠르게 코드를 생성합니다. 하지만 구조 없이는:
- 생성된 코드가 엉뚱한 곳에 들어감
- 의존성이 꼬임
- 테스트가 불가능해짐
- 플랫폼 확장이 새로 작성하는 것과 같아짐

**클린 아키텍처가 제공하는 것:**
- 코드가 어디에 있어야 하는지 명확한 경계
- 강제되는 의존성 규칙
- 테스트 가능한 레이어
- 플랫폼에 독립적인 비즈니스 로직

---

## 우리의 앱: TaskFlow

### 요구사항

간단하지만 완전한 Todo 애플리케이션:

1. **생성**: 제목, 설명, 우선순위, 마감일을 가진 할 일 생성
2. **조회**: 필터링과 정렬이 가능한 할 일 목록
3. **수정**: 할 일 상세 및 완료 상태 변경
4. **삭제**: 확인 후 할 일 삭제

### 진화 계획

```
1단계: 로컬만 (Android)
    └── 모든 데이터를 로컬 데이터베이스에 저장

2단계: 백엔드 추가
    └── 서버와 동기화, 오프라인 우선 전략

3단계: iOS 확장
    └── Domain, Repository, Presentation 재사용
    └── 작성할 것: SwiftUI + iOS DataSource만
```

---

## 아키텍처 개요

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      플랫폼 종속 (Android/iOS)                            │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  UI Layer                                                        │    │
│  │  Android: Jetpack Compose  /  iOS: SwiftUI                       │    │
│  └─────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
                                    │ 상태 관찰
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        공유 (순수 Kotlin - KMP)                           │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  Presentation Layer (FlowDux)                                    │    │
│  │  State, Action, Reducer, Middleware                              │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                                    │ UseCase 호출                        │
│                                    ▼                                     │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  Domain Layer                                                    │    │
│  │  Entity (비즈니스 규칙) + UseCase (사용자 플로우)                   │    │
│  │  Service Interfaces                                              │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                                    │ 인터페이스 사용                       │
│                                    ▼                                     │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  Repository Layer (순수 Kotlin)                                  │    │
│  │  DataSource 조율, 캐싱 전략                                       │    │
│  └─────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
                                    │ 위임
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                      플랫폼 종속 (Android/iOS)                            │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  DataSource Layer                                                │    │
│  │  Android: Room  /  iOS: SQLDelight                               │    │
│  │  Remote: Ktor + 외부 SaaS                                        │    │
│  └─────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 레이어 역할

### Domain Layer (순수 Kotlin)
- **Entity**: 비즈니스 규칙, 유효성 검사, 도메인 연산
- **UseCase**: 사용자 플로우 조율 (다이얼로그 → 액션 → 토스트)
- **Ports (인터페이스)**: `ITaskRepository`, `IDialogService`, `IToastService`

### Repository Layer (순수 Kotlin)
- **Repository 구현**: Domain의 `ITaskRepository` port 구현
- **DataSource 인터페이스 (ports)**: `ILocalDataSource`, `IRemoteDataSource`
- DataSource 조율, 캐싱과 동기화 전략 처리

### Presentation Layer (순수 Kotlin + FlowDux)
- **State**: UI 상태 표현
- **Action**: 사용자 의도와 내부 이벤트
- **Reducer**: 순수 상태 전이
- **Middleware**: 부수 효과, UseCase 호출

### DataSource Layer (플랫폼 종속)
- 실제 데이터베이스 구현 (Room, SQLDelight)
- 네트워크 호출 (Ktor)
- 플랫폼 API

### UI Layer (플랫폼 종속)
- Jetpack Compose (Android)
- SwiftUI (iOS)
- Presentation 상태 관찰, 액션 발행

---

## 모듈 구조

```
project/
├── core/
│   ├── domain/           # :core:domain (의존성 없음)
│   ├── repository/       # :core:repository (domain 의존)
│   └── presentation/     # :core:presentation (domain 의존)
│
├── androidApp/           # Room DataSource + Jetpack Compose
│
└── iosApp/               # SQLDelight DataSource + SwiftUI
```

### 의존성 규칙 (컴파일 타임에 강제)

```
:core:domain       → (없음)
:core:repository   → :core:domain
:core:presentation → :core:domain (repository 아님!)
:androidApp        → 모든 core 모듈
:iosApp            → 모든 core 모듈
```

**핵심**: `presentation`은 `repository`에 의존할 수 없습니다. 오직 `domain`만 공유합니다.

### 각 코드는 어디에?

| 코드 | 위치 |
|------|------|
| Entity, UseCase, **Ports** (IRepository, IDialogService...) | `:core:domain` |
| Repository **구현**, DataSource **인터페이스** | `:core:repository` |
| State, Action, Reducer, Middleware | `:core:presentation` |
| DataSource **구현** (Room) + Compose UI | `:androidApp` |
| DataSource **구현** (SQLDelight) + SwiftUI | `:iosApp` |

---

## 클라이언트 도메인 ≠ 백엔드 도메인

흔한 오해: "백엔드의 도메인 모델을 클라이언트에서 그대로 쓰면 되지 않나?"

**백엔드 도메인이 집중하는 것:**
- 데이터 무결성과 유효성 검증
- 비즈니스 규칙 강제
- 트랜잭션과 일관성
- 인가와 보안

**클라이언트 도메인이 집중하는 것:**
- 사용자 플로우 조율 (다이얼로그 → 액션 → 피드백)
- 클라이언트 특화 비즈니스 규칙과 유효성 검증
- 도메인 연산 (완료, 다시 열기, 우선순위 지정)
- 서비스 계약 정의 (Port)

### 예시: 할 일 삭제

**백엔드:**
```
DELETE /tasks/{id}
→ 인가 확인
→ 비즈니스 규칙 검증
→ 데이터베이스에서 삭제
→ 204 No Content 반환
```

**클라이언트 UseCase 플로우:**

```
┌─────────────────────────────────────────────────────────────────┐
│                       UseCase.deleteTask()                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐       │
│  │ 1. 검증      │───▶│ 2. 확인      │───▶│ 3. 실행      │       │
│  │              │    │              │    │              │       │
│  │ Entity에서   │    │ IDialogSvc   │    │ IRepository  │       │
│  │ 규칙 확인    │    │ .showDialog()│    │ .delete()    │       │
│  └──────────────┘    └──────────────┘    └──────┬───────┘       │
│         │                   │                   │               │
│         ▼                   ▼                   ▼               │
│  [실패 → 토스트]    [취소 → 반환]         ┌──────────────┐       │
│                                          │ 4. 피드백    │       │
│                                          │              │       │
│                                          │ IToastSvc    │       │
│                                          │ .showToast() │       │
│                                          └──────────────┘       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**시퀀스 다이어그램:**

```
┌──────┐     ┌──────────┐     ┌─────────┐     ┌────────────┐     ┌─────────────┐
│  UI  │     │Middleware│     │ UseCase │     │ Repository │     │Port Services│
└──┬───┘     └────┬─────┘     └────┬────┘     └─────┬──────┘     └──────┬──────┘
   │              │                │                │                   │
   │ DeleteTask   │                │                │                   │
   │─────────────▶│                │                │                   │
   │              │ deleteTask()   │                │                   │
   │              │───────────────▶│                │                   │
   │              │                │                │                   │
   │              │                │ 규칙 확인      │                   │
   │              │                │───────┐        │                   │
   │              │                │       │        │                   │
   │              │                │◀──────┘        │                   │
   │              │                │                │                   │
   │              │                │ showConfirmDialog()                │
   │              │                │───────────────────────────────────▶│
   │              │                │                │                   │
   │◀ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ [다이얼로그 표시] ─ ─ ─ │
   │              │                │                │                   │
   │ [사용자 확인]                 │◀──────────────────────────────────│
   │              │                │                │                   │
   │              │                │ delete()       │                   │
   │              │                │───────────────▶│                   │
   │              │                │                │                   │
   │              │                │     완료       │                   │
   │              │                │◀───────────────│                   │
   │              │                │                │                   │
   │              │                │ showToast()    │                   │
   │              │                │───────────────────────────────────▶│
   │              │                │                │                   │
   │◀ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─[토스트 표시]─ ─ ─ ─ ─ │
   │              │                │                │                   │
   │◀═══════════════════════════════════════════════│                   │
   │         [Flow가 업데이트된 할 일 목록 방출]      │                   │
   │              │                │                │                   │
```

클라이언트의 UseCase는 API 호출만이 아닌 **완전한 사용자 여정**을 다룹니다.

### UseCase가 UI 프레임워크에 의존하지 않고 UI를 트리거하는 방법은?

> "잠깐, UseCase가 다이얼로그와 토스트를 표시하면 UI 프레임워크에 의존하는 거 아니야?"

**아닙니다.** UseCase는 UI 프레임워크를 직접 호출하지 않습니다. `IDialogService`, `IToastService` 같은 **Port(인터페이스)**를 호출합니다.

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
                                   │ 구현
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

- **Port 선언 위치**: `:core:domain`
- **Port 구현 위치**: `:androidApp`, `:iosApp`
- **UseCase가 아는 것**: 인터페이스(Port)만
- **UseCase가 모르는 것**: Compose, SwiftUI, 어떤 UI 프레임워크도

이것은 전형적인 **의존성 역전(Dependency Inversion)**입니다. 도메인이 필요한 것을 정의하고, 플랫폼이 구현을 제공합니다. 이것이 "순수한" 클린 아키텍처인지 논쟁할 수 있지만, 사용자 플로우가 핵심 비즈니스 로직인 클라이언트 앱에서는 이 접근 방식이 도메인을 테스트 가능하게 유지하면서 완전한 사용자 시나리오를 표현할 수 있게 합니다.

---

## 핵심 설계 원칙

### 1. UseCase = 사용자 플로우

UseCase는 CRUD 래퍼가 아닙니다. 완전한 사용자 시나리오를 조율합니다:

```
사용자가 "삭제" 탭
    → UseCase가 비즈니스 규칙 확인
    → UseCase가 확인 다이얼로그 표시 (IDialogService port 통해)
    → 사용자가 확인
    → UseCase가 Repository로 삭제 (ITaskRepository port 통해)
    → UseCase가 성공 토스트 표시 (IToastService port 통해)
    → Repository Flow가 업데이트된 목록 방출
    → UI가 자동으로 업데이트
```

참고: UseCase는 UI 프레임워크를 직접 호출하지 않습니다—**Port**를 호출합니다 (위의 "UseCase가 UI 프레임워크에 의존하지 않고 UI를 트리거하는 방법은?" 섹션 참조).

### 2. Entity = 비즈니스 규칙

Entity는 데이터만이 아닌 도메인 로직을 담습니다:

```
Task
├── isOverdue (계산됨)
├── urgencyLevel (계산됨)
├── validate() → ValidationResult
├── complete() → Task
└── reopen() → Task
```

### 3. Middleware는 얇게

Middleware는 UseCase만 호출합니다 - 비즈니스 로직 없음:

```kotlin
on<DeleteTask> { _, action ->
    taskUseCase.deleteTask(action.taskId)
    // 끝!
}
```

*"그럼 로딩/에러/성공 상태는 어디서 Action으로 바뀌지?"* — **3편: Presentation Layer**에서 다룹니다.

### 4. 의존성은 안쪽을 향한다

```
UI → Presentation → Domain ← Repository ← DataSource
                      ↑
              (모든 것이 Domain에 의존)
```

---

## 우리가 만들 것

이 시리즈가 끝나면 갖게 될 것:

```
✓ 테스트 가능한 Domain 레이어 (높은 커버리지 목표)
✓ 오프라인 우선 동기화 전략을 가진 Repository
✓ 테스트가 있는 FlowDux 상태 관리
✓ Compose UI 테스트가 있는 Android 앱
✓ 대부분의 코드를 공유하는 iOS 앱
✓ 양 플랫폼에 새 기능을 추가하는 패턴
```

---

## 코드 공유 현실

| 레이어 | Android | iOS | 공유 |
|-------|---------|-----|------|
| Domain | - | - | 100% |
| Repository | - | - | 100% |
| Presentation | - | - | 100% |
| DataSource | Room | SQLDelight | 인터페이스만 |
| UI | Compose | SwiftUI | - |

**결과**: 기능과 UI 복잡도에 따라 ~70–85% 코드 공유 기대

---

## 다음 단계

**1편**에서는 Domain 레이어를 구현합니다:
- 비즈니스 규칙을 담은 `Task` 엔티티
- 사용자 플로우 메서드를 가진 `TaskUseCase`
- 포괄적인 단위 테스트

시작해봅시다!

---

*이 글은 FlowDux 실전 튜토리얼 시리즈의 0편입니다.*

**시리즈 목록:**
- **0편. 클린 아키텍처 개요** (현재 글)
- 1편. Domain Layer (Entity, UseCase + 테스트)
- 2편. Repository Layer
- 3편. Presentation Layer (FlowDux + 테스트)
- 4편. UI Layer (Android + Compose 테스트)
- 5편. DataSource 구현 (Room)
- 6편. 백엔드 추가 (오프라인 우선)
- 7편. iOS 확장
- 8편~. 피처 추가

---

**태그:** #Kotlin #KotlinMultiplatform #FlowDux #클린아키텍처 #KMP #Android #iOS
