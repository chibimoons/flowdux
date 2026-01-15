# Store 동기화 이슈 검토 보고서

## 1. 문제 제기

### 1.1 배경
`Store.kt`에서 리팩토링 과정 중 `reduceAction()` 함수를 extract 했는데, 이로 인해 동기화 문제가 발생할 수 있는지 검토가 필요했다.

### 1.2 기존 코드 구조

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class Store<S : State, A : Action>(
    initialState: S,
    private val reducer: Reducer<S, A>,
    private val middlewares: List<Middleware<S, A>>,
    private val errorProcessor: ErrorProcessor<A>,
    private val logger: StoreLogger<S, A>,
    private val scope: CoroutineScope,
) {
    private val actionFlow = Channel<A>()

    private val stateFlow = actionFlow
        .receiveAsFlow()
        .flatMapMerge { processAction(it) }
        .map { reduceAction(state.value, it) }  // ← 문제 지점?
        .stateIn(scope, SharingStarted.Eagerly, initialState)

    private val mutex = Mutex()

    private suspend fun reduceAction(currentState: S, action: A): S {
        return mutex.withLock {
            val newState = reducer.reduce(currentState, action)
            logger.onStateReduced(action, currentState, newState)
            newState
        }
    }
    // ...
}
```

### 1.3 우려 사항
`.map { reduceAction(state.value, it) }` 에서:
- `state.value` 읽기가 mutex 외부에서 발생
- `flatMapMerge`로 인해 여러 action이 동시에 처리될 수 있음
- 읽기와 쓰기가 분리되어 race condition 가능성?

---

## 2. 검증 과정

### 2.1 Flow 파이프라인 동작 방식 검증

**핵심 질문:** Flow에 `map(), map(), map()` 연산자가 순차적으로 있다면, 각각의 map들이 파이프라이닝처럼 동작하나? 아니면 flow의 데이터가 마지막 map()을 빠져나가야 다음 데이터가 flow를 타고 흐르나?

#### 테스트 코드 1: 기본 Flow 순차 실행

```kotlin
@Test
fun `stateIn with Dispatchers Default`(): Unit = runBlocking {
    val scope = CoroutineScope(Dispatchers.Default + Job())

    flow {
        listOf(1, 2, 3).forEach {
            println("[${Thread.currentThread().name}] emit: $it")
            emit(it)
        }
    }
        .map {
            println("[${Thread.currentThread().name}] map1 start: $it")
            delay(50)
            println("[${Thread.currentThread().name}] map1 end: $it → ${it * 2}")
            it * 2
        }
        .map {
            println("[${Thread.currentThread().name}] map2 start: $it")
            delay(50)
            println("[${Thread.currentThread().name}] map2 end: $it → ${it + 1}")
            it + 1
        }
        .stateIn(scope, SharingStarted.Eagerly, 0)
        // ...
}
```

#### 결과 분석
```
emit: 1 → map1 start: 1 → map1 end: 1 → map2 start: 2 → map2 end: 2 →
emit: 2 → map1 start: 2 → map1 end: 2 → map2 start: 4 → map2 end: 4 →
emit: 3 → ...
```

**결론:** Flow의 기본 연산자들은 **파이프라이닝이 아닌 순차 실행**. 하나의 값이 모든 연산자를 통과해야 다음 값이 처리됨.

---

### 2.2 flatMapMerge 이후 동작 검증

**질문:** `flatMapMerge`가 동시성을 도입하면 downstream의 map도 동시에 실행되나?

#### 테스트 코드 2: flatMapMerge + map

```kotlin
@Test
fun `flatMapMerge then map with stateIn`(): Unit = runBlocking {
    flow {
        listOf(1, 2, 3).forEach { emit(it) }
    }
        .flatMapMerge { value ->
            flow {
                delay(50) // simulate async work
                emit(value)
            }
        }
        .map {
            println("[${Thread.currentThread().name}] map1 start: $it")
            delay(30)
            println("[${Thread.currentThread().name}] map1 end: $it → ${it * 10}")
            it * 10
        }
        .map {
            println("[${Thread.currentThread().name}] map2 start: $it")
            delay(30)
            println("[${Thread.currentThread().name}] map2 end: $it → ${it * 10}")
            it * 10
        }
        .flowOn(Dispatchers.Default)
        .collect { ... }
}
```

#### 결과 분석
```
[DefaultDispatcher-worker-1] flatMapMerge processing: 1
[DefaultDispatcher-worker-3] flatMapMerge processing: 2  ← 동시 실행
[DefaultDispatcher-worker-4] flatMapMerge processing: 3  ← 동시 실행

[DefaultDispatcher-worker-3] map1 start: 2
[DefaultDispatcher-worker-3] map1 end: 2 → 20
[DefaultDispatcher-worker-3] map2 start: 20              ← 순차 실행
[DefaultDispatcher-worker-3] map2 end: 20 → 200
```

**결론:**
- `flatMapMerge` 내부의 inner flow들은 **동시에** 실행됨
- 그러나 downstream의 map 연산자들은 **동일한 코루틴에서 순차적으로** 실행됨
- 이는 Kotlin Flow의 **Context Preservation** 원칙 때문

---

### 2.3 flowOn으로 컨텍스트 변경 시 동작 검증

**질문:** `flowOn`으로 연산자 사이에 컨텍스트를 변경하면 어떻게 되나?

#### 테스트 코드 3: flowOn between operators

```kotlin
@Test
fun `flowOn between map operators`(): Unit = runBlocking {
    flow {
        listOf(1, 2, 3).forEach { emit(it) }
    }
        .flatMapMerge { value ->
            flow {
                delay(50)
                emit(value)
            }
        }
        .map {
            println("[${Thread.currentThread().name}] map1 start: $it")
            delay(30)
            println("[${Thread.currentThread().name}] map1 end: $it")
            it * 10
        }
        .flowOn(Dispatchers.Default)  // ← 컨텍스트 변경!
        .map {
            println("[${Thread.currentThread().name}] map2 start: $it")
            delay(30)
            println("[${Thread.currentThread().name}] map2 end: $it")
            it * 10
        }
        .collect { ... }
}
```

#### 결과 분석
```
[DefaultDispatcher-worker-3] map1 start: 3
[Test worker] map2 start: 20          ← map1이 끝나기 전에 map2 시작!
[DefaultDispatcher-worker-3] map1 end: 3 → 30
[Test worker] map2 end: 20 → 200
[DefaultDispatcher-worker-3] map1 start: 1
[Test worker] collect: 200
```

**결론:**
- `flowOn`은 내부적으로 **버퍼링 채널**을 사용하여 upstream과 downstream을 분리
- upstream(flowOn 이전)과 downstream(flowOn 이후)이 **병렬로 실행**될 수 있음
- 이 경우 동기화 문제가 발생할 수 있음

---

## 3. Kotlin Flow의 Context Preservation 원칙

### 3.1 공식 문서 내용

Kotlin Flow 공식 문서에 따르면:

> "Flow adheres to the general cooperative cancellation of coroutines. As usual, flow collection can be cancelled when the flow is suspended in a cancellable suspending function (like delay). The intermediate operators, like map, filter and others, are also executed in the collector's coroutine context."

### 3.2 핵심 원칙

1. **Context Preservation**: Flow의 중간 연산자들은 collector의 코루틴 컨텍스트에서 실행됨
2. **Sequential by default**: 기본적으로 모든 연산은 순차적으로 실행됨
3. **flowOn의 역할**: upstream의 컨텍스트만 변경하고, downstream에는 영향 없음

---

## 4. 현재 Store 구현 분석

### 4.1 현재 코드 흐름

```kotlin
private val stateFlow = actionFlow
    .receiveAsFlow()
    .flatMapMerge { processAction(it) }  // 1. 동시성 도입
    .map { reduceAction(state.value, it) }  // 2. 순차 실행 보장됨
    .stateIn(scope, SharingStarted.Eagerly, initialState)  // 3. StateFlow 변환
```

### 4.2 동기화 안전성 분석

| 구간 | 동시성 | 안전성 |
|------|--------|--------|
| `flatMapMerge` 내부 | 동시 실행 | Inner flow들만 동시 |
| `flatMapMerge` → `map` | 순차 실행 | Context preservation 보장 |
| `map` → `stateIn` | 순차 실행 | 단일 코루틴에서 실행 |

### 4.3 결론

**현재 Store 구현은 동기화 문제가 없다.**

이유:
1. `flowOn`이 사용되지 않아 모든 downstream 연산이 **동일 코루틴 컨텍스트**에서 실행됨
2. Flow의 Context Preservation 원칙에 의해 `map` 연산자는 **순차적으로** 실행됨
3. `stateIn`에 값이 할당되기 전에 이전 값의 모든 처리가 완료됨

---

## 5. Mutex 필요성 검토

### 5.1 현재 코드의 Mutex

```kotlin
private val mutex = Mutex()

private suspend fun reduceAction(currentState: S, action: A): S {
    return mutex.withLock {
        val newState = reducer.reduce(currentState, action)
        logger.onStateReduced(action, currentState, newState)
        newState
    }
}
```

### 5.2 분석 결과

**현재 구조에서 mutex는 불필요하다.**

이유:
- Flow의 순차 실행 보장으로 인해 `reduceAction`은 동시에 호출되지 않음
- `state.value` 읽기와 `reducer.reduce()` 실행이 자연스럽게 순차적으로 이루어짐

### 5.3 권장 사항

Mutex 제거 가능하나, 다음 경우 유지를 권장:
1. 향후 `flowOn` 사용 가능성이 있는 경우
2. 방어적 프로그래밍 관점에서 안전장치로 유지
3. 코드 리뷰어에게 의도를 명확히 전달하기 위해

---

## 6. 주의 사항

### 6.1 flowOn 사용 시 주의

만약 향후 다음과 같이 코드를 변경하면 **동기화 문제 발생 가능**:

```kotlin
// ⚠️ 위험한 패턴
private val stateFlow = actionFlow
    .receiveAsFlow()
    .flatMapMerge { processAction(it) }
    .flowOn(Dispatchers.Default)  // ← 이것을 추가하면
    .map { reduceAction(state.value, it) }  // ← 병렬 실행 가능!
    .stateIn(scope, SharingStarted.Eagerly, initialState)
```

### 6.2 안전한 대안: scan 연산자

동기화를 더 명확하게 보장하려면 `scan` 연산자 사용을 고려:

```kotlin
private val stateFlow = actionFlow
    .receiveAsFlow()
    .flatMapMerge { processAction(it) }
    .scan(initialState) { currentState, action ->
        reducer.reduce(currentState, action)
    }
    .stateIn(scope, SharingStarted.Eagerly, initialState)
```

`scan`은 이전 상태를 명시적으로 전달받으므로 `state.value` 읽기 없이 안전하게 상태 갱신 가능.

---

## 7. 결론 요약

| 항목 | 결론 |
|------|------|
| 현재 구현의 동기화 문제 | **없음** |
| Mutex 필요성 | **불필요** (단, 방어적 유지 권장) |
| flowOn 사용 시 | **주의 필요** (병렬 실행 가능) |
| 권장 개선 사항 | `scan` 연산자 사용 고려 |

---

## 8. 참고 자료

- [Kotlin Flow 공식 문서](https://kotlinlang.org/docs/flow.html)
- [Flow Context Preservation](https://kotlinlang.org/docs/flow.html#flow-context)
- [StateFlow and SharedFlow](https://kotlinlang.org/docs/stateflow-and-sharedflow.html)

---

*문서 작성일: 2026-01-14*
