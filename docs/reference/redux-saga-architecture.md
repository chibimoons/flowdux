# Redux-Saga Architecture Reference

*redux-saga의 내부 동작 원리와 실행 모델*

---

## 1. 핵심 개념: Generator + Effects

Redux-Saga는 **ES6 Generator 함수**와 **Effect 객체**를 기반으로 동작합니다.

```javascript
function* mySaga() {
  const action = yield take('FETCH_USER')    // Effect 객체 yield
  const user = yield call(api.fetchUser, action.id)
  yield put({ type: 'USER_LOADED', user })
}
```

Generator가 yield하는 것은 **실제 실행이 아닌 "명령 객체(Effect)"** 입니다:

```javascript
// yield call(api.fetchUser, 1) 이 반환하는 것:
{
  '@@redux-saga/IO': true,
  type: 'CALL',
  payload: {
    fn: api.fetchUser,
    args: [1]
  }
}
```

이 설계의 장점:
- **테스트 용이**: Effect 객체를 `deepEqual`로 비교 가능
- **선언적**: "무엇을 할지"를 객체로 표현
- **제어 가능**: 미들웨어가 실행 시점과 방식을 결정

---

## 2. 전체 아키텍처 흐름도

```
┌─────────────────────────────────────────────────────────────────────┐
│                           Redux Store                                │
└─────────────────────────────────────────────────────────────────────┘
         │ dispatch(action)                        ▲ dispatch(action)
         ▼                                         │ (from put effect)
┌─────────────────────────────────────────────────────────────────────┐
│                        Saga Middleware                               │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                    Effect Executor                             │  │
│  │                                                                │  │
│  │   1. Generator.next() 호출                                    │  │
│  │   2. Effect 객체 수신                                         │  │
│  │   3. Effect 타입에 따라 실행:                                 │  │
│  │      - TAKE: 특정 액션 대기                                   │  │
│  │      - CALL: 함수 호출 (blocking)                             │  │
│  │      - PUT: 액션 dispatch                                     │  │
│  │      - FORK: 새 태스크 생성 (non-blocking)                    │  │
│  │   4. 결과를 Generator.next(result)로 전달                     │  │
│  │   5. 반복...                                                  │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                              │                                       │
│              ┌───────────────┼───────────────┐                      │
│              ▼               ▼               ▼                      │
│         ┌────────┐     ┌────────┐      ┌────────┐                   │
│         │ Saga 1 │     │ Saga 2 │      │ Saga 3 │  (forked tasks)   │
│         └────────┘     └────────┘      └────────┘                   │
└─────────────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────────────┐
│                           Reducer                                    │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. Effect Executor 상세 루프

미들웨어의 Effect Executor가 Generator를 구동하는 방식:

```
Generator 시작
     │
     ▼
┌─────────────────┐
│ next() 호출     │◄─────────────────────────────┐
└────────┬────────┘                              │
         │                                       │
         ▼                                       │
   ┌───────────┐     Yes                        │
   │ 종료됨?    │─────────► 완료                 │
   └─────┬─────┘                                │
         │ No                                    │
         ▼                                       │
┌─────────────────┐                              │
│ Effect 객체 수신 │                              │
└────────┬────────┘                              │
         │                                       │
         ▼                                       │
┌─────────────────────────────────────┐          │
│        Effect 타입 판별              │          │
└────────┬────────────────────────────┘          │
         │                                       │
    ┌────┴────┬────────┬────────┬─────┐         │
    ▼         ▼        ▼        ▼     ▼         │
 ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌────┐    │
 │ TAKE │ │ CALL │ │ PUT  │ │ FORK │ │ ...│    │
 └──┬───┘ └──┬───┘ └──┬───┘ └──┬───┘ └────┘    │
    │        │        │        │                │
    ▼        ▼        ▼        ▼                │
  액션     함수     Store    새 Task            │
  대기     실행    dispatch   생성              │
 (block)  (block) (non-block)(non-block)        │
    │        │        │        │                │
    └────────┴────────┴────────┘                │
                   │                             │
                   ▼                             │
         ┌─────────────────┐                     │
         │ next(result)    │─────────────────────┘
         └─────────────────┘
```

---

## 4. Effect 종류와 특성

### Blocking vs Non-Blocking

| Effect | 타입 | 동작 |
|--------|------|------|
| `take(pattern)` | Blocking | 특정 액션이 dispatch될 때까지 대기 |
| `call(fn, ...args)` | Blocking | 함수 호출, Promise 완료까지 대기 |
| `put(action)` | Non-blocking | Store에 액션 dispatch 후 즉시 반환 |
| `fork(fn, ...args)` | Non-blocking | 새 태스크를 백그라운드에서 시작 |
| `select(selector)` | Blocking | 현재 Store state 조회 |
| `cancel(task)` | Non-blocking | 태스크 취소 |
| `all([...effects])` | Blocking | 모든 Effect 병렬 실행, 전체 완료 대기 |
| `race({...effects})` | Blocking | 가장 먼저 완료되는 Effect만 반환 |

### 타임라인 시각화

```
시간 ──────────────────────────────────────────────────────►

[Blocking: take, call]

Saga:    yield take('A') ─────────[대기]─────────► yield call(api) ──[대기]──► 다음
                                     │                                  │
                                     └── 액션 도착까지 멈춤              └── 완료까지 멈춤


[Non-Blocking: put, fork]

Saga:    yield fork(task) ──► yield put(action) ──► 즉시 다음으로 진행
              │                      │
              │                      └── Store에 dispatch (바로 반환)
              │
              └── 백그라운드에서 task 실행 (부모는 안 기다림)
                        │
                        ▼
                  Forked Task: ─────────────────► 독립 실행
```

---

## 5. Watcher/Worker 패턴

redux-saga의 가장 일반적인 패턴:

```javascript
// Watcher: 액션을 감시하고 Worker를 실행
function* watchFetchUser() {
  yield takeEvery('FETCH_USER', fetchUserWorker)
}

// Worker: 실제 비즈니스 로직 수행
function* fetchUserWorker(action) {
  try {
    const user = yield call(api.fetchUser, action.userId)
    yield put({ type: 'FETCH_USER_SUCCESS', user })
  } catch (error) {
    yield put({ type: 'FETCH_USER_FAILURE', error })
  }
}
```

```
                    ┌─────────────────────────────┐
                    │      watchFetchUser         │
                    │   (Watcher - 무한 루프)      │
                    └──────────────┬──────────────┘
                                   │
                                   │ takeEvery('FETCH_USER', worker)
                                   │
         ┌─────────────────────────┼─────────────────────────┐
         │                         │                         │
         ▼                         ▼                         ▼
   FETCH_USER #1             FETCH_USER #2             FETCH_USER #3
         │                         │                         │
         ▼                         ▼                         ▼
   ┌───────────┐             ┌───────────┐             ┌───────────┐
   │  Worker   │             │  Worker   │             │  Worker   │
   │ (forked)  │             │ (forked)  │             │ (forked)  │
   └───────────┘             └───────────┘             └───────────┘
```

---

## 6. 동시성 헬퍼 내부 구현

### takeLatest

```javascript
// takeLatest의 실제 구현 (단순화)
function* takeLatest(pattern, saga) {
  let lastTask
  while (true) {
    const action = yield take(pattern)    // 액션 대기
    if (lastTask) {
      yield cancel(lastTask)              // 이전 태스크 취소
    }
    lastTask = yield fork(saga, action)   // 새 태스크 시작
  }
}
```

**실행 흐름:**

```
Action A ─────┐
              ▼
         ┌─────────┐
         │  take   │ ◄── 액션 대기
         └────┬────┘
              │ Action A 도착
              ▼
         ┌─────────┐
         │ cancel  │ ◄── 이전 task 취소 (있으면)
         └────┬────┘
              │
              ▼
         ┌─────────┐
         │  fork   │ ◄── 새 task 시작 (non-blocking)
         └────┬────┘
              │
              ▼
         다시 take로 ──────────────────┐
                                       │
              ┌────────────────────────┘
              ▼
Action B ─────┐
              ▼
         ┌─────────┐
         │  take   │
         └────┬────┘
              │ Action B 도착
              ▼
         ┌─────────┐
         │ cancel  │ ◄── Task A 취소!
         └────┬────┘
              │
              ▼
         ┌─────────┐
         │  fork   │ ◄── Task B 시작
         └─────────┘
```

### takeLeading

```javascript
function* takeLeading(pattern, saga) {
  while (true) {
    const action = yield take(pattern)
    yield call(saga, action)  // call = blocking, 완료까지 다음 take 안 함
  }
}
```

### throttle

```javascript
function* throttle(ms, pattern, saga) {
  const throttleChannel = yield actionChannel(pattern)
  while (true) {
    const action = yield take(throttleChannel)
    yield fork(saga, action)
    yield delay(ms)  // ms 동안 대기 (채널에 쌓인 액션은 무시됨)
  }
}
```

### debounce

```javascript
function* debounce(ms, pattern, saga) {
  while (true) {
    let action = yield take(pattern)
    while (true) {
      const { debounced, latestAction } = yield race({
        debounced: delay(ms),
        latestAction: take(pattern)
      })
      if (debounced) {
        yield fork(saga, action)
        break
      }
      action = latestAction  // 새 액션으로 교체, 다시 대기
    }
  }
}
```

---

## 7. Fork 모델: 부모-자식 관계

```
                    Root Saga
                        │
            ┌───────────┼───────────┐
            │           │           │
         fork(A)     fork(B)     fork(C)
            │           │           │
            ▼           ▼           ▼
         Task A      Task B      Task C
            │           │
         fork(A1)    fork(B1)
            │           │
            ▼           ▼
        Task A1     Task B1
```

### Fork 규칙

1. **완료 대기**: 부모는 모든 자식이 완료될 때까지 종료되지 않음
2. **에러 버블링**: 자식 에러 → 부모로 전파 → 전체 트리 취소
3. **취소 전파**: 부모 취소 → 모든 자식 취소

### Attached Fork vs Detached Fork (spawn)

| | `fork` (attached) | `spawn` (detached) |
|---|---|---|
| 부모와 연결 | 연결됨 | 분리됨 |
| 에러 전파 | 부모로 버블업 | 부모에 영향 없음 |
| 취소 전파 | 부모 취소 시 함께 취소 | 독립적 |
| 완료 대기 | 부모가 자식 완료 대기 | 대기 안 함 |

---

## 8. 채널 (Channel)

채널은 액션을 버퍼링하고 순서대로 처리할 수 있게 해줍니다:

```javascript
function* watchRequests() {
  const requestChannel = yield actionChannel('REQUEST')
  while (true) {
    const action = yield take(requestChannel)
    yield call(handleRequest, action)  // 하나씩 순차 처리
  }
}
```

```
액션 스트림:    R1 ─── R2 ─── R3 ─── R4 ─── R5
                │      │      │      │      │
                ▼      ▼      ▼      ▼      ▼
           ┌─────────────────────────────────────┐
           │        Action Channel (버퍼)         │
           │   [R1] [R2] [R3] [R4] [R5]          │
           └──────────────┬──────────────────────┘
                          │ take (하나씩)
                          ▼
                    ┌───────────┐
                    │  Worker   │ ─── R1 처리 완료 ─── R2 처리 ─── ...
                    └───────────┘
```

---

## 9. 패턴 매칭

`take`, `takeEvery`, `takeLatest` 등에서 사용하는 패턴:

```javascript
// String: 정확히 일치
take('INCREMENT')

// Array: 여러 타입 중 하나와 일치
take(['INCREMENT', 'DECREMENT'])

// Function: predicate 함수
take(action => action.type.startsWith('USER_'))

// Wildcard: 모든 액션
take('*')

// Channel: 특정 채널에서 take
take(myChannel)
```

---

## 10. 테스팅

Effect가 순수 객체이므로 테스트가 간단합니다:

```javascript
import { call, put } from 'redux-saga/effects'

function* fetchUser(action) {
  const user = yield call(api.fetchUser, action.userId)
  yield put({ type: 'FETCH_SUCCESS', user })
}

// 테스트
it('should fetch user', () => {
  const gen = fetchUser({ userId: 1 })

  // 첫 번째 yield: call effect
  expect(gen.next().value).toEqual(
    call(api.fetchUser, 1)
  )

  // 두 번째 yield: put effect (call 결과를 주입)
  const mockUser = { id: 1, name: 'John' }
  expect(gen.next(mockUser).value).toEqual(
    put({ type: 'FETCH_SUCCESS', user: mockUser })
  )

  // 완료
  expect(gen.next().done).toBe(true)
})
```

---

## 11. flowdux와의 비교

```
┌─────────────────────────────────────────────────────────────────────┐
│                         redux-saga                                   │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   Action ──► Middleware ──► Generator ──► Effect ──► Executor       │
│                                 │              │                     │
│                                 │              └── 순수 명령 객체     │
│                                 └── 함수 (pausable)                  │
│                                                                      │
│   특징: Generator 기반, Effect는 순수 객체, 테스트 용이              │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                           flowdux                                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│   Action ──► Middleware ──► Processor ──► Flow<Action> ──► collect  │
│                                 │              │                     │
│                                 │              └── 코루틴 emit       │
│                                 └── suspend 함수                     │
│                                                                      │
│   특징: Coroutine 기반, suspend/resume, structured concurrency       │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

| 측면 | redux-saga | flowdux |
|------|------------|---------|
| 언어/런타임 | JavaScript (ES6+) | Kotlin Multiplatform |
| 비동기 기반 | Generator + yield | Coroutine + suspend |
| Side Effect 표현 | Effect 객체 (순수 데이터) | suspend 함수 호출 |
| 취소 메커니즘 | `cancel(task)` 명시적 | Structured Concurrency |
| 액션 매칭 | 런타임 문자열/배열/함수 | 컴파일타임 KClass |
| 전략 공유 | 배열 패턴 `[A, B]` | `group { }` DSL |
| 같은 액션 다중 처리 | 가능 (독립 watcher) | 불가 (DuplicateProcessorException) |
| 테스트 방식 | Effect 객체 비교 | runTest + Turbine |
| 에러 처리 | try/catch + fork 버블링 | try/catch + SupervisorJob |

---

## References

- [Redux-Saga Official Documentation](https://redux-saga.js.org/)
- [Redux-Saga API Reference](https://redux-saga.js.org/docs/api/)
- [Redux-Saga Fork Model](https://redux-saga.js.org/docs/advanced/ForkModel/)
- [Redux-Saga Concurrency Patterns](https://redux-saga.js.org/docs/advanced/Concurrency/)
- [Understanding Redux-Saga and Generators](https://medium.com/@tanner.west/a-few-insights-for-better-understanding-redux-saga-and-javascript-generators-68efaef44c9e)
