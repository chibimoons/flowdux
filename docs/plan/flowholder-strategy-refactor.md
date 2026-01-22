# FlowHolderAction Strategy 리팩토링 계획서

## 개요

FlowHolderAction의 `cancelable` 속성을 `strategy` 속성으로 변경하고, 처리 로직을 Store에서 FlowHolderMiddleware로 분리한다.

## 배경

### 현재 문제점

1. **코드 중복**: `cancelable = true`가 `TakeLatest` 전략과 동일한 동작을 재구현
2. **관심사 혼재**: Store가 FlowHolderAction 처리 로직을 직접 포함
3. **취소 전파 한계**: 중첩된 FlowHolderAction에서 외부가 취소되어도 내부 flow가 계속 실행
4. **확장성 부족**: Debounce, Throttle 등 다른 전략 적용 불가

### 변경 후 이점

1. **코드 재사용**: ExecutionStrategy를 그대로 활용
2. **관심사 분리**: Store는 단순히 미들웨어 체인만 처리
3. **확장성**: 모든 ExecutionStrategy를 FlowHolderAction에 적용 가능
4. **일관성**: 미들웨어와 동일한 전략 시스템 사용

## 아키텍처 변경

### Before
```
┌─────────────────────────────────────────────────┐
│ Store                                           │
│  ├─ processAction()                             │
│  │   └─ middlewares.fold(...)                   │
│  ├─ processFlowHolderAction()  ← 직접 처리      │
│  │   ├─ CancelFlag                              │
│  │   ├─ activeFlags map                         │
│  │   └─ takeWhile(!cancelled)                   │
│  └─ reduceAction()                              │
└─────────────────────────────────────────────────┘
```

### After
```
┌─────────────────────────────────────────────────┐
│ Store (단순화)                                   │
│  ├─ processAction()                             │
│  │   └─ middlewares.fold(...)                   │
│  │       └─ FlowHolderMiddleware (마지막)       │
│  └─ reduceAction()                              │
└─────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────┐
│ FlowHolderMiddleware                            │
│  ├─ strategy.wrap()으로 동시성 제어              │
│  └─ transform {                                 │
│       FlowHolderAction → emitAll(process(...))  │
│                          (재귀 호출)            │
│       Action → emit (직접 reducer로)            │
│     }                                           │
└─────────────────────────────────────────────────┘
```

## API 변경

### FlowHolderAction

**Before:**
```kotlin
interface FlowHolderAction : Action {
    fun toFlowAction(): Flow<Action>
    val cancelable: Boolean get() = true
}
```

**After:**
```kotlin
interface FlowHolderAction : Action {
    fun toFlowAction(): Flow<Action>
    val strategy: ExecutionStrategy get() = TakeLatest()
}
```

### 마이그레이션 매핑

| Before | After |
|--------|-------|
| `cancelable = true` (기본값) | `strategy = TakeLatest()` (기본값) |
| `cancelable = false` | `strategy = Concurrent()` |
| - | `strategy = Debounce(300.ms)` (새 기능) |
| - | `strategy = Throttle(1000.ms)` (새 기능) |

## 구현 상세

### 1. Concurrent 전략 추가

```kotlin
// ExecutionStrategy.kt에 추가
class Concurrent : ExecutionStrategy {
    override val category = StrategyCategory.CONCURRENCY

    override fun <S, A, T : A> wrap(
        processor: suspend FlowCollector<A>.(S, T) -> Unit
    ): suspend FlowCollector<A>.(S, T) -> Unit = processor
}

fun concurrent() = Concurrent()
```

### 2. FlowHolderMiddleware 구현

```kotlin
class FlowHolderMiddleware<S : State, A : Action> : Middleware<S, A> {

    // 타입별 wrapped processor 캐시
    private val wrappedProcessors = mutableMapOf<KClass<*>, WrappedProcessor<S, A>>()

    override val processors: ActionProcessorMap<S, A> = emptyMap()

    override fun process(getState: () -> S, action: A): Flow<A> {
        if (action !is FlowHolderAction) return flowOf(action)

        val wrapped = getOrCreateWrappedProcessor(action)

        return wrapped(getState(), action).transform { innerAction ->
            if (innerAction is FlowHolderAction) {
                emitAll(process(getState, innerAction as A))  // 재귀 호출
            } else {
                emit(innerAction as A)  // 직접 reducer로
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getOrCreateWrappedProcessor(action: FlowHolderAction): WrappedProcessor<S, A> {
        return wrappedProcessors.getOrPut(action::class) {
            val baseProcessor: suspend FlowCollector<A>.(S, A) -> Unit = { _, a ->
                (a as FlowHolderAction).toFlowAction().collect { emit(it as A) }
            }
            action.strategy.wrap(baseProcessor)
        }
    }
}

private typealias WrappedProcessor<S, A> = suspend FlowCollector<A>.(S, A) -> Unit
```

**재귀 방식의 장점:**
- 기존 Store 내부 처리 방식과 동일한 동작
- 미들웨어 체인 재통과 오버헤드 없음
- 중첩된 FlowHolderAction도 동일한 strategy 시스템으로 처리

### 3. Store 변경

**삭제:**
```kotlin
// 삭제할 코드
private class CancelFlag { var cancelled = false }
private val activeFlags = mutableMapOf<KClass<*>, CancelFlag>()
private fun processFlowHolderAction(action: A): Flow<A> { ... }

// close()에서도 삭제
for (flag in activeFlags.values) { flag.cancelled = true }
activeFlags.clear()
```

**수정:**
```kotlin
// createStore 또는 Store 생성자에서
private val flowHolderMiddleware = FlowHolderMiddleware<S, A>()
private val allMiddlewares = middlewares + flowHolderMiddleware

// processAction에서 allMiddlewares 사용
```

### 4. FlowHolderAction 인터페이스 변경

```kotlin
interface FlowHolderAction : Action {
    fun toFlowAction(): Flow<Action>

    /**
     * Execution strategy for this FlowHolderAction.
     *
     * Default is TakeLatest(), which cancels previous executions
     * when a new action of the same type is dispatched.
     *
     * Use Concurrent() for parallel execution without cancellation.
     */
    val strategy: ExecutionStrategy get() = TakeLatest()
}
```

## 파일 변경 목록

### Kotlin

| 파일 | 변경 |
|-----|------|
| `Action.kt` | `cancelable` → `strategy` |
| `ExecutionStrategy.kt` | `Concurrent` 클래스 추가 |
| `FlowHolderMiddleware.kt` | 신규 생성 |
| `Store.kt` | FlowHolderAction 처리 로직 제거, FlowHolderMiddleware 추가 |
| `FlowHolderActionTest.kt` | 테스트 마이그레이션 |
| `TestFixtures.kt` | 테스트 fixture 수정 |

### Dart

| 파일 | 변경 |
|-----|------|
| `action.dart` | `cancelable` → `strategy` |
| `execution_strategy.dart` | `Concurrent` 클래스 추가 |
| `flowholder_middleware.dart` | 신규 생성 |
| `store.dart` | FlowHolderAction 처리 로직 제거 |
| `infinite_stream_test.dart` | 테스트 마이그레이션 |

## 사용자 마이그레이션 가이드

### Case 1: 기본값 사용 (변경 없음)

```kotlin
// Before
class MyStreamAction : FlowHolderAction {
    override fun toFlowAction() = flow { ... }
}

// After (동일 - 기본값이 TakeLatest)
class MyStreamAction : FlowHolderAction {
    override fun toFlowAction() = flow { ... }
}
```

### Case 2: cancelable = false

```kotlin
// Before
class MyConcurrentAction : FlowHolderAction {
    override val cancelable = false
    override fun toFlowAction() = flow { ... }
}

// After
class MyConcurrentAction : FlowHolderAction {
    override val strategy = concurrent()
    override fun toFlowAction() = flow { ... }
}
```

### Case 3: 새로운 전략 사용 (신규 기능)

```kotlin
// Debounce 적용
class MyDebouncedAction : FlowHolderAction {
    override val strategy = debounce(300.milliseconds)
    override fun toFlowAction() = flow { ... }
}

// Strategy Chaining
class MyResilientAction : FlowHolderAction {
    override val strategy = takeLatest().then(retry(3))
    override fun toFlowAction() = flow { ... }
}
```

## 액션 흐름 변경

### Before
```
dispatch(FlowHolderAction A)
    → Middleware chain
    → processFlowHolderAction() (Store 내부)
        → emit(ActionB) → Reducer (미들웨어 재통과 안 함)
        → emit(FlowHolderAction C) → 재귀 처리
```

### After
```
dispatch(FlowHolderAction A)
    → Middleware chain
    → FlowHolderMiddleware.process(A)
        → emit(ActionB: Action) → Reducer
        → emit(FlowHolderAction C) → FlowHolderMiddleware.process(C) (재귀)
                                      → emit(ActionD) → Reducer
```

**동작은 기존과 동일** - 중첩된 FlowHolderAction이 미들웨어 체인을 재통과하지 않음

## 테스트 계획

1. 기존 FlowHolderActionTest 모든 케이스 통과
2. Concurrent 전략 테스트
3. 다른 전략 (Debounce, Throttle) 적용 테스트
4. 중첩 FlowHolderAction 처리 테스트
5. Store.close() 시 정리 테스트

## Breaking Changes

| 변경 | 영향 | 마이그레이션 |
|-----|-----|------------|
| `cancelable` 제거 | `cancelable = false` 사용자 | `strategy = concurrent()` |

## 작업 순서

1. [ ] Kotlin: `Concurrent` 전략 추가
2. [ ] Kotlin: `FlowHolderAction.strategy` 속성 추가
3. [ ] Kotlin: `FlowHolderMiddleware` 구현
4. [ ] Kotlin: `Store`에서 기존 로직 제거 및 미들웨어 연결
5. [ ] Kotlin: 테스트 마이그레이션 및 검증
6. [ ] Dart: 동일하게 변경
7. [ ] README 업데이트
