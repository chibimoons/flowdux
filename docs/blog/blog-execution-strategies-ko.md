# 선언적 Execution Strategy: Kotlin에서 동시성 액션 제어하기

*flowdux가 Redux 스타일 미들웨어에 takeLatest, debounce, throttle을 도입한 방법*

---

## 모든 개발자가 겪는 동시성 문제

모바일 개발자라면 이런 상황을 경험해봤을 것입니다:

```kotlin
// 사용자가 검색창에 빠르게 타이핑
searchBox.onTextChanged { query ->
    viewModel.search(query)  // 키 입력마다 호출
}
```

사용자가 "hello"를 입력하면 어떻게 될까요?

```
요청: "h"     → 3번째로 응답 도착 → "h" 결과 표시
요청: "he"    → 1번째로 응답 도착 → "he" 결과 표시
요청: "hel"   → 5번째로 응답 도착 → "hel" 결과 표시   ← 최종 상태!
요청: "hell"  → 4번째로 응답 도착 → "hell" 결과 표시
요청: "hello" → 2번째로 응답 도착 → "hello" 결과 표시
```

결과가 깜빡거리고, 최종 상태는 "hello"가 아닌 "hel"을 보여줍니다. 이것이 **레이스 컨디션 문제**입니다 — 가장 마지막 입력이 아니라, 가장 느린 응답이 이깁니다.

---

## 기존 해결책은 장황하다

### RxJava/RxKotlin

```kotlin
searchSubject
    .debounce(300, TimeUnit.MILLISECONDS)
    .switchMap { query ->
        api.search(query).toObservable()
    }
    .subscribe { results -> /* UI 업데이트 */ }
```

### Kotlin Coroutines (수동 관리)

```kotlin
private var searchJob: Job? = null

fun search(query: String) {
    searchJob?.cancel()
    searchJob = viewModelScope.launch {
        delay(300)  // debounce
        val results = api.search(query)
        _state.value = results
    }
}
```

### 문제점

1. **로직이 분산됨** — 취소 로직이 비즈니스 로직과 섞임
2. **보일러플레이트** — 모든 비동기 작업에 같은 패턴 반복
3. **에러 발생 쉬움** — 정리 로직을 빼먹기 쉽고, 테스트하기 어려움
4. **선언적이지 않음** — "무엇"이 "어떻게"에 묻힘

---

## flowdux의 해결책: 선언적 Execution Strategy

flowdux는 **Execution Strategy**를 도입했습니다 — 동시성을 자동으로 처리하는 미들웨어 프로세서용 선언적 래퍼입니다.

```kotlin
class SearchMiddleware : Middleware<AppState, AppAction> {
    override val processors = buildProcessors {
        // 선언만 하면 됨: "검색에 takeLatest 사용"
        on<SearchAction>(takeLatest()) { state, action ->
            val results = api.search(action.query)
            emit(SearchResults(results))
        }
    }
}
```

끝입니다. Job 추적도, 취소 로직도, 레이스 컨디션도 없습니다.

---

## 4가지 전략

### 1. takeLatest()

**동작:** 새 액션이 도착하면 이전 처리를 취소합니다. 가장 최근 결과만 emit됩니다.

```kotlin
on<SearchAction>(takeLatest()) { state, action ->
    val results = api.search(action.query)  // 이전 호출은 취소됨
    emit(SearchResults(results))
}
```

**타임라인:**
```
액션:   Search("h") ──────────────────────────────> (취소됨)
액션:        Search("he") ────────────────────────> (취소됨)
액션:             Search("hel") ──────────────────> (취소됨)
액션:                  Search("hello") ───────────> SearchResults ✓
```

**사용 사례:**
- 검색 자동완성
- API 새로고침 / Pull-to-refresh
- 실시간 필터링
- 최신 결과만 중요한 모든 작업

> **주의: 취소는 협조적(Cooperative)입니다**
>
> Kotlin 코루틴 취소는 중단 지점(suspension point)에서만 작동합니다. 프로세서에 블로킹 호출(레거시 SDK, 블로킹 IO)이 있으면 취소가 중단시키지 못합니다:
> ```kotlin
> // ✅ 정상 작동 - suspend 함수가 취소 지점 제공
> val results = api.search(query)
>
> // ❌ 취소 안 됨 - 블로킹 호출
> val results = legacySdk.blockingSearch(query)
> ```
> `withContext(Dispatchers.IO)`는 UI 블로킹을 방지하지만, 그 자체로 블로킹 호출을 취소 가능하게 만들지는 않습니다. 취소 가능한 suspend API를 사용하거나, 레거시 코드를 연동할 때는 `suspendCancellableCoroutine`을 사용하세요.

---

### 2. takeLeading()

**동작:** 하나가 처리 중일 때 새 액션을 무시합니다. 첫 번째 액션만 실행됩니다.

```kotlin
on<SubmitFormAction>(takeLeading()) { state, action ->
    val result = api.submitForm(action.data)  // 이후 호출은 무시됨
    emit(FormSubmitted(result))
}
```

**타임라인:**
```
액션:   SubmitForm ─────────────────────> FormSubmitted ✓
액션:        SubmitForm (무시됨)
액션:             SubmitForm (무시됨)
```

**사용 사례:**
- 폼 제출 (중복 제출 방지)
- 결제 처리
- 삭제 확인
- 동시에 실행되면 안 되는 모든 작업

---

### 3. debounce(duration)

**동작:** 실행을 지연합니다. 지연이 완료되기 전에 새 액션이 도착하면 타이머가 재시작됩니다.

```kotlin
on<TextChangedAction>(debounce(300.milliseconds)) { state, action ->
    api.saveAsDraft(action.text)  // 사용자가 타이핑을 멈춘 후에만 저장
    emit(DraftSaved)
}
```

**타임라인:**
```
액션:   TextChanged("h") ──[대기]──> (리셋)
액션:        TextChanged("he") ──[대기]──> (리셋)
액션:             TextChanged("hel") ──[대기]──> (리셋)
액션:                  TextChanged("hello") ──[300ms]──> DraftSaved ✓
```

**사용 사례:**
- 자동 저장
- 검색 제안 (takeLatest와 조합)
- 입력 유효성 검사
- 리사이즈/스크롤 핸들러

---

### 4. throttle(duration)

**동작:** 첫 번째 액션을 즉시 실행하고, 시간 윈도우가 지날 때까지 이후 액션을 무시합니다.

```kotlin
on<ScrollAction>(throttle(1000.milliseconds)) { state, action ->
    analytics.logScrollPosition(action.position)  // 초당 최대 1회
    emit(action)
}
```

**타임라인:**
```
액션:   Scroll(100) ─────> 기록됨 ✓
액션:        Scroll(200) (무시됨)
액션:             Scroll(300) (무시됨)
          ─────[1000ms 경과]─────
액션:                            Scroll(400) ─────> 기록됨 ✓
```

**사용 사례:**
- 애널리틱스 이벤트
- Rate-limited API 호출
- 진행 상태 업데이트
- 쓰로틀링이 필요한 고빈도 이벤트

---

## Strategy Group: 액션 간 조율

flowdux가 진정으로 빛나는 부분입니다. 때로는 서로 다른 액션 타입이 같은 전략 상태를 공유해야 합니다.

### 문제 상황

```kotlin
// 사용자가 검색 후 새로고침
store.dispatch(SearchAction("hello"))  // 진행 중...
store.dispatch(RefreshAction)          // 검색을 취소해야 함!
```

별도의 전략으로는 이들이 서로를 알지 못합니다.

### 해결책: Strategy Group

```kotlin
override val processors = buildProcessors {
    // 두 액션이 동일한 takeLatest 인스턴스를 공유
    group(takeLatest()) {
        on<SearchAction> { state, action ->
            val results = api.search(action.query)
            emit(SearchResults(results))
        }
        on<RefreshAction> { state, action ->
            val results = api.refresh()
            emit(SearchResults(results))  // 진행 중인 검색을 취소!
        }
    }
}
```

**타임라인:**
```
액션:   SearchAction("hello") ────────────> (RefreshAction에 의해 취소됨)
액션:             RefreshAction ──────────> SearchResults ✓
```

### 실전 Group 패턴

**패턴 1: 다중 입력 소스**

```kotlin
group(debounce(300.milliseconds)) {
    on<TextChanged> { state, action -> emit(ValidateInput(action.text)) }
    on<FilterChanged> { state, action -> emit(ApplyFilter(action.filter)) }
    on<SortChanged> { state, action -> emit(ApplySort(action.sort)) }
}
// 어떤 입력이든 모든 액션의 debounce 타이머를 리셋
```

**패턴 2: 상호 배타적 작업**

```kotlin
group(takeLeading()) {
    on<CreateItem> { state, action -> /* ... */ }
    on<UpdateItem> { state, action -> /* ... */ }
    on<DeleteItem> { state, action -> /* ... */ }
}
// 한 번에 하나의 mutation만, 나머지는 무시됨
```

---

## 내부 구현

이 마법은 어떻게 작동할까요? 각 전략은 프로세서를 감쌉니다:

```kotlin
sealed interface ExecutionStrategy {
    fun <S, A, T : A> wrap(
        processor: suspend FlowCollector<A>.(state: S, action: T) -> Unit
    ): suspend FlowCollector<A>.(state: S, action: T) -> Unit
}
```

예를 들어 `TakeLatest`:

```kotlin
class TakeLatest : ExecutionStrategy {
    private val mutex = Mutex()
    private var currentJob: Job? = null

    override fun <S, A, T : A> wrap(processor: ...) = { state, action ->
        val job = currentCoroutineContext()[Job]!!

        mutex.withLock {
            currentJob?.cancel()  // 이전 것 취소
            currentJob = job
        }

        try {
            processor(state, action)  // 실행
        } finally {
            mutex.withLock {
                if (currentJob === job) {
                    currentJob = null
                }
            }
        }
    }
}
```

**핵심 구현 상세:**

1. **스레드 안전을 위한 Mutex** — 전략 상태가 보호됨
2. **Job 추적** — 현재 코루틴의 Job이 캡처되고 관리됨
3. **finally에서 정리** — 취소 시에도 리소스가 해제됨
4. **인스턴스 기반 그룹화** — `group()`을 사용해 액션 타입 간 전략 공유

---

## 비교: flowdux vs 다른 솔루션

| 기능 | flowdux | Redux-Saga | MobX | 수동 Coroutines |
|------|---------|------------|------|-----------------|
| takeLatest | `takeLatest()` | `takeLatest(pattern, saga)` | N/A | 수동 Job 추적 |
| takeLeading | `takeLeading()` | `takeLeading(pattern, saga)` | N/A | 수동 플래그 |
| debounce | `debounce(duration)` | `debounce(ms, pattern, saga)` | N/A (별도 구현 필요) | `delay()` + cancel |
| throttle | `throttle(duration)` | `throttle(ms, pattern, saga)` | `reaction`/`autorun` delay | 수동 타임스탬프 |
| Strategy Group | `group(strategy) { }` | N/A | N/A | N/A |
| 타입 안전성 | 완전한 Kotlin 타입 | 런타임 문자열 | 완전함 | 완전함 |
| 멀티플랫폼 | KMP (JVM, iOS, JS, WASM) | JS만 | 주로 JS | KMP |

**flowdux의 Strategy Group** — flowdux의 `group { }` DSL은 각 핸들러를 분리한 채로 여러 액션 타입이 하나의 전략 인스턴스를 공유할 수 있게 합니다. Redux-saga도 배열 패턴(예: `takeLatest([A, B], worker)`)으로 유사한 조율이 가능하지만, 보통 단일 worker 함수 내에서 액션 타입 분기가 필요합니다.

*참고: MobX는 액션 파이프라인이 아닌 reaction 기반 모델이어서 직접 비교가 어렵습니다. `reaction`/`autorun`의 "delay" 옵션이 throttle과 유사한 동작을 제공합니다.*

---

## 전략 테스트하기

Execution Strategy는 완전히 테스트 가능합니다:

```kotlin
@Test
fun `takeLatest는 이전 검색을 취소한다`() = runTest {
    val store = createStore(
        initialState = SearchState(),
        reducer = searchReducer,
        middlewares = listOf(SearchMiddleware()),
        scope = backgroundScope
    )

    store.state.test {
        awaitItem()  // 초기 상태

        // 빠르게 검색 디스패치
        store.dispatch(SearchAction("a"))
        store.dispatch(SearchAction("ab"))
        store.dispatch(SearchAction("abc"))

        // 마지막 검색만 완료됨
        val result = awaitItem()
        assertEquals(listOf("abc-result"), result.results)

        // 중간 결과 없음
        expectNoEvents()
    }
}
```

---

## 모범 사례

### 1. 적절한 전략 선택하기

| 상황 | 전략 |
|------|------|
| 사용자가 아직 타이핑 중 | `debounce` |
| 최신 결과만 중요함 | `takeLatest` |
| 중복 액션 방지 | `takeLeading` |
| 고빈도 이벤트 제한 | `throttle` |

### 2. 액션 간 조율은 Group 사용하기

```kotlin
// 좋음: 관련 액션이 group을 통해 전략 공유
group(takeLatest()) {
    on<SearchAction> { ... }
    on<RefreshAction> { ... }  // 진행 중인 SearchAction 취소
}

// 독립적: 별도 전략 = 조율 없음
on<SearchAction>(takeLatest()) { ... }   // 별도 인스턴스
on<RefreshAction>(takeLatest()) { ... }  // 별도 인스턴스 - SearchAction 취소 안 함
```

### 3. 관련 액션 그룹화하기

```kotlin
// 좋음: 관련 액션이 전략을 공유
group(takeLatest()) {
    on<LoadUser> { ... }
    on<RefreshUser> { ... }
    on<UpdateUser> { ... }
}

// 나쁨: 관련 없는 액션이 그룹화됨
group(takeLatest()) {
    on<LoadUser> { ... }
    on<SendMessage> { ... }  // 왜 이것들이 서로를 취소해야 하는가?
}
```

### 4. 필요시 전략 조합하기

```kotlin
// 입력은 debounce하고, API 호출은 takeLatest
on<SearchInputChanged>(debounce(300.milliseconds)) { state, action ->
    emit(SearchAction(action.query))  // takeLatest 검색을 트리거
}

on<SearchAction>(takeLatest()) { state, action ->
    val results = api.search(action.query)
    emit(SearchResults(results))
}
```

---

## 결론

Execution Strategy는 상태 관리에서 동시성을 다루는 방식을 혁신합니다:

- **선언적** — 구현이 아닌 의도를 표현
- **조합 가능** — 전략이 그룹을 통해 함께 작동
- **타입 안전** — 완전한 Kotlin 타입 시스템 지원
- **테스트 가능** — 표준 코루틴 테스트 방식 그대로 사용
- **멀티플랫폼** — JVM, iOS, JS, WASM에서 같은 코드 사용

보일러플레이트 취소 로직 작성을 멈추세요. `group { }`으로 서로 다른 액션 타입을 같은 동시성 규칙으로 묶으세요. 미들웨어 선언이 앱의 동작을 이야기하게 하세요.

---

*flowdux는 Kotlin Multiplatform을 위한 경량 Redux 스타일 상태 관리 라이브러리입니다. [GitHub](https://github.com/chibimoons/flowdux)에서 확인하세요.*

```kotlin
implementation("com.github.chibimoons:flowdux:1.6.1")
```

---

**태그:** #Kotlin #KotlinMultiplatform #상태관리 #Redux #Coroutines #동시성 #아키텍처 #모바일개발
