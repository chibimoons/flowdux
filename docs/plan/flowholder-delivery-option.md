# FlowHolderAction delivery 옵션 추가 계획서

## 1. 개요

### 1.1 목표
`FlowHolderAction`에서 방출된 액션이 **리듀서에 직접 전달(emit)**될지, **dispatch()를 통해 미들웨어 파이프라인을 포함한 전체 흐름을 거칠지(dispatch)** 선택할 수 있는 옵션을 추가한다.

### 1.2 배경
현재 `FlowHolderAction`에서 방출된 액션은 항상 `dispatch()`를 통해 전체 파이프라인을 거친다.
그러나 대부분의 경우 방출된 액션은 단순 상태 업데이트용이므로, 미들웨어를 거칠 필요가 없다.
불필요한 미들웨어 순회를 피하고, 의도를 명시적으로 표현할 수 있도록 `delivery` 옵션을 도입한다.

### 1.3 적용 대상
- Kotlin (`flowdux`)
- Dart (`flowdux`)

---

## 2. API 설계

### 2.1 FlowActionDelivery enum

**Kotlin:**
```kotlin
enum class FlowActionDelivery {
    /** 방출된 액션을 리듀서에 직접 전달 (미들웨어 우회) */
    Emit,
    /** 방출된 액션을 dispatch()로 전달 (미들웨어 파이프라인 포함 전체 흐름) */
    Dispatch,
}
```

**Dart:**
```dart
enum FlowActionDelivery {
  /// 방출된 액션을 리듀서에 직접 전달 (미들웨어 우회)
  emit,
  /// 방출된 액션을 dispatch()로 전달 (미들웨어 파이프라인 포함 전체 흐름)
  dispatch,
}
```

### 2.2 FlowHolderAction 변경

**Kotlin:**
```kotlin
interface FlowHolderAction : Action {
    fun toFlowAction(): Flow<Action>
    val strategy: ExecutionStrategy get() = TakeLatest()

    /** 방출된 액션의 전달 방식. 기본값: Emit */
    val delivery: FlowActionDelivery get() = FlowActionDelivery.Emit
}
```

**Dart:**
```dart
abstract mixin class FlowHolderAction implements Action {
  Stream<Action> toStreamAction();
  ExecutionStrategy get strategy => TakeLatestStrategy();

  /// 방출된 액션의 전달 방식. 기본값: [FlowActionDelivery.emit]
  FlowActionDelivery get delivery => FlowActionDelivery.emit;
}
```

### 2.3 delivery 옵션 동작 비교

| 값 | 동작 | 사용 시나리오 |
|----|------|--------------|
| `emit` (기본값) | 방출된 액션이 미들웨어를 거치지 않고 리듀서에 직접 전달 | 단순 상태 업데이트, 내부 액션 체이닝 |
| `dispatch` | 방출된 액션이 `dispatch()`를 통해 미들웨어 파이프라인 포함 전체 흐름을 거침 | 미들웨어 처리가 필요한 액션 방출, 사이드 이펙트 트리거 |

---

## 3. Store 처리 로직 변경

### 3.1 Kotlin

**변경 전:**
```kotlin
// FlowHolderAction 방출 액션을 항상 dispatch()로 전달
scope.launch {
    flowAction.toFlowAction().collect { emittedAction ->
        dispatch(emittedAction)
    }
}
```

**변경 후:**
```kotlin
scope.launch {
    flowAction.toFlowAction().collect { emittedAction ->
        when (flowAction.delivery) {
            FlowActionDelivery.Emit -> processAction(emittedAction)
            FlowActionDelivery.Dispatch -> dispatch(emittedAction)
        }
    }
}
```

### 3.2 Dart

**변경 전:**
```dart
action.toStreamAction().listen(
  (emittedAction) => dispatch(emittedAction as A),
);
```

**변경 후:**
```dart
final flowAction = action as FlowHolderAction;
flowAction.toStreamAction().listen(
  (emittedAction) {
    switch (flowAction.delivery) {
      case FlowActionDelivery.emit:
        _processAction(emittedAction as A);
      case FlowActionDelivery.dispatch:
        dispatch(emittedAction as A);
    }
  },
);
```

---

## 4. 사용 예시

### 4.1 기본값 (emit) - 리듀서 직접 전달

```dart
// 방출된 액션이 리듀서에 직접 전달 (미들웨어 우회)
class FetchAndProcessAction with FlowHolderAction {
  @override
  Stream<Action> toStreamAction() async* {
    yield LoadingAction();
    final data = await fetchData();
    yield DataLoadedAction(data);
  }
  // delivery 기본값 = FlowActionDelivery.emit
}
```

```kotlin
class FetchAndProcessAction : FlowHolderAction {
    override fun toFlowAction() = flow {
        emit(LoadingAction)
        val data = fetchData()
        emit(DataLoadedAction(data))
    }
    // delivery 기본값 = FlowActionDelivery.Emit
}
```

### 4.2 dispatch 모드 - 미들웨어 파이프라인 통과

```dart
class TriggerSideEffectsAction with FlowHolderAction {
  @override
  FlowActionDelivery get delivery => FlowActionDelivery.dispatch;

  @override
  Stream<Action> toStreamAction() async* {
    yield AnalyticsTrackAction('started');  // 미들웨어에서 처리
    yield FetchDataAction();                // 미들웨어에서 API 호출 트리거
  }
}
```

```kotlin
class TriggerSideEffectsAction : FlowHolderAction {
    override val delivery = FlowActionDelivery.Dispatch

    override fun toFlowAction() = flow {
        emit(AnalyticsTrackAction("started"))  // 미들웨어에서 처리
        emit(FetchDataAction())                // 미들웨어에서 API 호출 트리거
    }
}
```

---

## 5. 변경 대상 파일

### 5.1 Kotlin

| 파일 | 변경 내용 |
|------|----------|
| `kotlin/flowdux/src/commonMain/kotlin/io/flowdux/Action.kt` | `FlowActionDelivery` enum 추가, `FlowHolderAction`에 `delivery` 프로퍼티 추가 |
| `kotlin/flowdux/src/commonMain/kotlin/io/flowdux/Store.kt` | `delivery`에 따른 분기 처리 |
| `kotlin/flowdux/src/commonTest/kotlin/io/flowdux/FlowHolderActionTest.kt` | `delivery` 옵션 테스트 추가 |

### 5.2 Dart

| 파일 | 변경 내용 |
|------|----------|
| `dart/flowdux/lib/src/action.dart` | `FlowActionDelivery` enum 추가, `FlowHolderAction`에 `delivery` 프로퍼티 추가 |
| `dart/flowdux/lib/src/store.dart` | `delivery`에 따른 분기 처리 |
| `dart/flowdux/test/store_test.dart` | `delivery` 옵션 테스트 추가 |

---

## 6. 테스트 계획

### 6.1 기본 동작 테스트
- [ ] `delivery` 미지정 시 기본값 `emit` 확인
- [ ] `emit` 모드에서 방출된 액션이 미들웨어를 거치지 않고 리듀서에 직접 전달되는지 확인
- [ ] `dispatch` 모드에서 방출된 액션이 미들웨어를 거쳐 처리되는지 확인

### 6.2 기존 동작 호환성 테스트
- [ ] 기존 `FlowHolderAction`(delivery 미지정)이 `emit` 모드로 동작하는지 확인
- [ ] `strategy` 옵션과 `delivery` 옵션이 독립적으로 동작하는지 확인

### 6.3 엣지 케이스 테스트
- [ ] 중첩된 `FlowHolderAction`에서 각각 다른 `delivery` 옵션 사용 시 동작 확인
- [ ] `dispatch` 모드에서 방출된 `FlowHolderAction`이 다시 올바르게 처리되는지 확인

---

## 7. 주의사항

- **기본값 변경**: 기존 동작은 `dispatch`였으나, 기본값을 `emit`으로 변경한다. 이는 **breaking change**이므로 마이그레이션 가이드가 필요하다.
- **Kotlin과 Dart 동일 동작 보장**: 양쪽 플랫폼에서 동일한 기본값과 동작을 유지한다.
