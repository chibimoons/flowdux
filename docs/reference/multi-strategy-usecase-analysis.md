# 단일 액션의 다중 전략 UseCase 분석

*하나의 액션이 여러 Execution Strategy를 가져야 하는 경우가 실제로 존재하는가?*

---

## 배경

redux-saga에서는 같은 액션을 여러 watcher가 독립적으로 처리할 수 있습니다:

```javascript
// redux-saga: 가능
yield all([
  takeLatest('SEARCH', searchWorker),
  throttle(1000, 'SEARCH', analyticsWorker),
])
```

반면 flowdux에서는 미들웨어당 하나의 액션에 하나의 프로세서만 등록 가능합니다:

```kotlin
// flowdux: DuplicateProcessorException 발생
on<SearchAction>(takeLatest()) { ... }
on<SearchAction>(throttle(1.seconds)) { ... }  // 불가!
```

이 제약이 실제 개발에서 문제가 되는지, 다중 전략이 필요한 UseCase가 존재하는지 분석합니다.

---

## UseCase 분석

### Case 1: 검색 + 애널리틱스

**시나리오:**
```
사용자가 검색어 입력
    │
    ├──► [takeLatest] API 검색 호출 (최신 결과만)
    │
    └──► [throttle] 애널리틱스 로깅 (초당 1회)
```

**요구사항:**
- 검색 API는 최신 요청만 유효 (이전 요청 취소)
- 애널리틱스는 rate-limit으로 과도한 로깅 방지

**해결책: 액션 분리**

```kotlin
on<SearchAction>(takeLatest()) { state, action ->
    // 애널리틱스 이벤트를 별도 액션으로 emit
    emit(AnalyticsEvent.SearchPerformed(action.query))

    val results = api.search(action.query)
    emit(SearchResults(results))
}

on<AnalyticsEvent>(throttle(1.seconds)) { state, action ->
    analytics.log(action)
}
```

**결론:** ❌ 다중 전략 불필요. 액션 분리로 해결.

---

### Case 2: 실시간 저장 + UI 피드백

**시나리오:**
```
TextChanged 액션
    │
    ├──► [debounce 2초] 서버에 저장 (타이핑 멈춘 후)
    │
    └──► [throttle 100ms] UI 글자수 업데이트 (빠른 피드백)
```

**요구사항:**
- 서버 저장은 사용자가 타이핑을 멈춘 후에만
- UI는 즉각 반응하되 과도한 렌더링 방지

**해결책: Reducer + Middleware 분리**

```kotlin
// Reducer (동기, 전략 개념 불필요)
val reducer = buildReducer<State, Action> {
    on<TextChanged> { state, action ->
        state.copy(
            text = action.text,
            charCount = action.text.length  // 즉시 UI 반영
        )
    }
}

// Middleware (비동기, debounce 적용)
on<TextChanged>(debounce(2.seconds)) { state, action ->
    api.saveDraft(action.text)
    emit(DraftSaved)
}
```

**결론:** ❌ 다중 전략 불필요. 동기/비동기 역할 분리로 해결.

---

### Case 3: 낙관적 업데이트 + 서버 동기화

**시나리오:**
```
LikeButton 클릭
    │
    ├──► [즉시] UI 낙관적 업데이트 (사용자 경험)
    │
    └──► [debounce] 서버 동기화 (연타 방지)
```

**요구사항:**
- UI는 클릭 즉시 반영 (낙관적 업데이트)
- 서버 호출은 연속 클릭 시 마지막만

**해결책: Reducer + Middleware 분리**

```kotlin
// Reducer: 즉시 낙관적 업데이트
val reducer = buildReducer<State, Action> {
    on<ToggleLike> { state, _ ->
        state.copy(
            isLiked = !state.isLiked,
            likeCount = if (state.isLiked) state.likeCount - 1 else state.likeCount + 1
        )
    }
    on<LikeSyncFailed> { state, _ ->
        state.copy(
            isLiked = !state.isLiked,  // 롤백
            likeCount = if (state.isLiked) state.likeCount - 1 else state.likeCount + 1
        )
    }
}

// Middleware: debounce로 서버 동기화
on<ToggleLike>(debounce(500.milliseconds)) { state, action ->
    try {
        api.syncLikeStatus(action.itemId, state.isLiked)
    } catch (e: Exception) {
        emit(LikeSyncFailed(action.itemId))
    }
}
```

**결론:** ❌ 다중 전략 불필요. Reducer(낙관적) + Middleware(동기화) 패턴.

---

### Case 4: 독립적인 취소 스코프가 필요한 경우

**시나리오:**
```
RefreshAction
    │
    ├──► [takeLatest] 사용자 목록 새로고침 (독립 취소)
    │
    └──► [takeLatest] 통계 새로고침 (독립 취소)
```

**요구사항:**
- 같은 RefreshAction이 두 개의 독립적인 API 호출 트리거
- 각 호출은 자체 취소 스코프 필요 (하나가 취소되어도 다른 것에 영향 없음)

**이 케이스는 경계선에 있음:**

flowdux에서 단일 핸들러로 처리하면:

```kotlin
on<RefreshAction>(takeLatest()) { state, action ->
    coroutineScope {
        launch {
            val users = api.fetchUsers()
            emit(UsersLoaded(users))
        }
        launch {
            val stats = api.fetchStats()
            emit(StatsLoaded(stats))
        }
    }
}
```

**문제:** 전체가 하나의 takeLatest 스코프. 새 RefreshAction이 오면 둘 다 취소됨.
부분적으로 "통계만 다시" 같은 세밀한 제어 불가.

**해결책 A: 액션 분리**

```kotlin
// 상위 액션이 하위 액션들을 트리거
on<RefreshAction> { state, action ->
    emit(RefreshUsers)
    emit(RefreshStats)
}

// 각각 독립적인 전략
on<RefreshUsers>(takeLatest()) { state, action ->
    val users = api.fetchUsers()
    emit(UsersLoaded(users))
}

on<RefreshStats>(takeLatest()) { state, action ->
    val stats = api.fetchStats()
    emit(StatsLoaded(stats))
}
```

**장점:** 독립적 취소 스코프, 명시적 흐름
**단점:** 액션 수 증가

**해결책 B: 미들웨어 분리**

```kotlin
class UserMiddleware : Middleware<AppState, AppAction> {
    override val processors = buildProcessors {
        on<RefreshAction>(takeLatest()) { state, action ->
            val users = api.fetchUsers()
            emit(UsersLoaded(users))
        }
    }
}

class StatsMiddleware : Middleware<AppState, AppAction> {
    override val processors = buildProcessors {
        on<RefreshAction>(takeLatest()) { state, action ->
            val stats = api.fetchStats()
            emit(StatsLoaded(stats))
        }
    }
}
```

**장점:** 같은 액션 타입, 독립적 전략
**단점:** 미들웨어 수 증가, 같은 액션이 여러 곳에서 처리되어 추적 어려움

**해결책 C: 전략 없이 내부에서 관리**

```kotlin
class RefreshMiddleware : Middleware<AppState, AppAction> {
    private var usersJob: Job? = null
    private var statsJob: Job? = null

    override val processors = buildProcessors {
        on<RefreshAction> { state, action ->
            // 수동으로 독립적 취소 관리
            usersJob?.cancel()
            usersJob = launch {
                val users = api.fetchUsers()
                emit(UsersLoaded(users))
            }

            statsJob?.cancel()
            statsJob = launch {
                val stats = api.fetchStats()
                emit(StatsLoaded(stats))
            }
        }
    }
}
```

**장점:** 완전한 제어
**단점:** 보일러플레이트 증가, 전략의 선언적 장점 상실

**결론:** ⚠️ 경계선 케이스. 액션 분리(해결책 A)가 가장 flowdux스러운 접근.

---

### Case 5: 같은 이벤트, 다른 시간 윈도우

**시나리오:**
```
ScrollAction
    │
    ├──► [throttle 100ms] 스크롤 위치 상태 업데이트 (부드러운 UI)
    │
    └──► [throttle 5초] 읽음 위치 서버 저장 (낮은 빈도)
```

**해결책: 액션 분리**

```kotlin
on<ScrollAction>(throttle(100.milliseconds)) { state, action ->
    emit(UpdateScrollPosition(action.position))
    emit(MaybeSaveReadPosition(action.position))
}

on<UpdateScrollPosition> { state, action ->
    // Reducer에서 처리하거나 여기서 상태 관련 로직
}

on<MaybeSaveReadPosition>(throttle(5.seconds)) { state, action ->
    api.saveReadPosition(action.position)
}
```

**결론:** ❌ 다중 전략 불필요. 액션 분리로 해결.

---

## 분석 결과 요약

| UseCase | 다중 전략 필요? | 권장 해결책 |
|---------|---------------|------------|
| 검색 + 애널리틱스 | ❌ | 액션 분리 (emit 체이닝) |
| 저장 + UI 피드백 | ❌ | Reducer(동기) + Middleware(비동기) |
| 낙관적 업데이트 | ❌ | Reducer + Middleware 역할 분리 |
| 독립 취소 스코프 | ⚠️ 경계선 | 액션 분리 또는 미들웨어 분리 |
| 다른 시간 윈도우 | ❌ | 액션 분리 |

---

## 핵심 인사이트

### 1. 대부분의 케이스는 설계 리팩토링으로 해결

"하나의 액션 → 다중 전략"이 필요해 보이면, 실제로는:
- 액션이 너무 많은 책임을 지고 있거나
- 동기/비동기 역할이 혼재되어 있다는 신호

### 2. Redux 아키텍처의 역할 분리

```
┌─────────────────────────────────────────────────────────────┐
│                         Action                               │
└─────────────────────────┬───────────────────────────────────┘
                          │
          ┌───────────────┴───────────────┐
          ▼                               ▼
┌──────────────────┐            ┌──────────────────┐
│     Reducer      │            │   Middleware     │
│   (동기 상태)     │            │  (비동기 효과)    │
│                  │            │                  │
│ • 즉시 실행      │            │ • 전략 적용 가능  │
│ • 순수 함수      │            │ • 사이드이펙트   │
│ • 전략 불필요    │            │ • 취소/조절 가능  │
└──────────────────┘            └──────────────────┘
```

동기적 상태 변경(UI 즉시 반영)은 Reducer에서,
비동기 사이드이펙트(API 호출 등)는 Middleware에서 처리하면
대부분의 "다중 전략" 요구사항이 자연스럽게 분리됨.

### 3. 진짜 다중 전략이 필요한 경우

같은 트리거로 **완전히 독립적인 취소 스코프**가 필요한 경우:

- **redux-saga**: 자연스럽게 지원 (독립 watcher 패턴)
- **flowdux**: 액션 분리 또는 미들웨어 분리로 해결

### 4. flowdux 제약의 장점

"하나의 액션 = 하나의 프로세서" 제약은:

| 장점 | 설명 |
|------|------|
| 추적 용이 | 액션 → 핸들러가 1:1 매핑, 디버깅 쉬움 |
| 명시적 흐름 | 암묵적 다중 처리 없음 |
| 설계 강제 | 책임 분리를 자연스럽게 유도 |
| 테스트 용이 | 하나의 액션에 대한 동작이 명확 |

### 5. redux-saga 유연성의 트레이드오프

| 장점 | 단점 |
|------|------|
| 유연한 구성 | 같은 액션이 여러 곳에서 처리되어 추적 어려움 |
| 기존 패턴 호환 | 암묵적 의존성 발생 가능 |
| 점진적 추가 용이 | 전체 흐름 파악이 어려워질 수 있음 |

---

## 권장 가이드라인

### flowdux에서 "다중 전략"이 필요해 보일 때

```
1. 정말 같은 액션이어야 하는가?
   │
   ├─ 아니오 → 액션 분리
   │           on<PrimaryAction> { emit(SecondaryAction) }
   │
   └─ 예 → 2. 동기/비동기가 섞여 있는가?
            │
            ├─ 예 → Reducer + Middleware 분리
            │
            └─ 아니오 → 3. 독립적 취소가 필요한가?
                        │
                        ├─ 예 → 액션 분리 또는 미들웨어 분리
                        │
                        └─ 아니오 → 단일 핸들러에서 처리
```

### 액션 분리 패턴

```kotlin
// 상위 액션
sealed interface UserAction : AppAction {
    data class Refresh(val userId: String) : UserAction
}

// 하위 액션 (내부용)
sealed interface InternalAction : AppAction {
    data class FetchProfile(val userId: String) : InternalAction
    data class FetchPosts(val userId: String) : InternalAction
}

// 미들웨어
on<UserAction.Refresh> { state, action ->
    emit(InternalAction.FetchProfile(action.userId))
    emit(InternalAction.FetchPosts(action.userId))
}

on<InternalAction.FetchProfile>(takeLatest()) { ... }
on<InternalAction.FetchPosts>(takeLatest()) { ... }
```

---

## 결론

**단일 액션에 다중 전략이 진정으로 필요한 케이스는 매우 드뭅니다.**

대부분의 경우:
1. 액션의 책임이 과도하게 큰 것이거나
2. 동기/비동기 역할이 혼재된 것이거나
3. 설계를 재고할 기회

flowdux의 "하나의 액션 = 하나의 프로세서" 제약은 이러한 설계 문제를 조기에 발견하게 해주는 **의도적인 제약**입니다.
