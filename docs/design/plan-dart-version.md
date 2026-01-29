# FlowDux Dart 버전 구현 계획서

## 1. 개요

### 1.1 목표
FlowDux의 핵심 기능을 Dart로 포팅하여 Flutter 생태계에서 사용할 수 있도록 한다.

### 1.2 대상 플랫폼
- Flutter (iOS, Android, Web, Desktop)
- Dart CLI/Server

### 1.3 핵심 기능 범위
- Store (State, Action, Reducer)
- Middleware (Processor 패턴)
- Execution Strategies (takeLatest, takeLeading, sequential, debounce, throttle, retry)
- Strategy Chaining (then 연산자)
- Strategy Grouping

---

## 2. 기술 매핑

### 2.1 언어/라이브러리 대응

| Kotlin | Dart |
|--------|------|
| `Flow<T>` | `Stream<T>` |
| `StateFlow<T>` | `BehaviorSubject<T>` (rxdart) 또는 커스텀 구현 |
| `suspend fun` | `Future<T>` / `async-await` |
| `FlowCollector.emit()` | `StreamController.add()` 또는 `yield` |
| `Mutex` | `AsyncLock` (Completer 기반 자체 구현) |
| `CoroutineScope` | 없음 (Dart는 자동 관리) |
| `Job` | `StreamSubscription` |
| `delay()` | `Future.delayed()` |
| `kotlinx.serialization` | `json_serializable` / `freezed` |

### 2.2 Dart 싱글스레드와 동기화

Dart는 싱글스레드 이벤트 루프 모델이지만, **async 작업의 인터리빙** 때문에 동기화 전략이 여전히 필요합니다.

```dart
// 문제: async 작업이 인터리빙됨
dispatch(FetchA());  // await 지점에서 양보
dispatch(FetchB());  // await 지점에서 양보
// → 두 작업이 동시에 "진행 중" 상태, 순서 보장 안됨

// 해결: AsyncLock으로 순차 실행 보장
await _lock.synchronized(() async {
  // 한 번에 하나의 작업만 실행
});
```

**Kotlin과의 차이:**
| 항목 | Kotlin | Dart |
|------|--------|------|
| Mutex 목적 | 스레드 간 동시 접근 방지 | async 인터리빙 방지 |
| 구현 방식 | 스레드 안전 Mutex | Completer 기반 AsyncLock |
| 외부 의존성 | kotlinx.coroutines | **불필요** (자체 구현) |

### 2.3 주요 차이점

| 항목 | Kotlin | Dart |
|------|--------|------|
| Null Safety | `?` 연산자 | `?` 연산자 (유사) |
| 제네릭 | Reified generics 가능 | Type erasure |
| 확장 함수 | `fun T.ext()` | `extension on T` |
| sealed class | `sealed class` | `sealed class` (Dart 3.0+) |
| 연산자 오버로딩 | `operator fun` | `operator` |

---

## 3. 아키텍처 설계

### 3.1 디렉토리 구조

```
dart/
├── flowdux/                      # 코어 라이브러리
│   ├── lib/
│   │   ├── flowdux.dart          # 라이브러리 엔트리
│   │   └── src/
│   │       ├── store.dart
│   │       ├── action.dart              # Action, FlowHolderAction
│   │       ├── reducer.dart
│   │       ├── middleware.dart
│   │       ├── processor.dart
│   │       ├── error_processor.dart
│   │       ├── util/
│   │       │   └── async_lock.dart   # Completer 기반 비동기 락
│   │       └── strategy/
│   │           ├── execution_strategy.dart
│   │           ├── strategy_category.dart
│   │           ├── take_latest.dart
│   │           ├── take_leading.dart
│   │           ├── sequential.dart
│   │           ├── debounce.dart
│   │           ├── throttle.dart
│   │           ├── retry.dart
│   │           └── chained_strategy.dart
│   ├── test/
│   │   ├── store_test.dart
│   │   ├── middleware_test.dart
│   │   └── strategy/
│   │       ├── concurrency_strategy_test.dart
│   │       ├── timing_strategy_test.dart
│   │       └── resilience_strategy_test.dart
│   ├── pubspec.yaml
│   ├── README.md
│   └── CHANGELOG.md
│
├── flowdux_flutter/              # Flutter 통합 (선택적)
│   ├── lib/
│   │   └── src/
│   │       ├── store_provider.dart
│   │       ├── store_builder.dart
│   │       └── store_consumer.dart
│   └── pubspec.yaml
│
└── example/                      # 예제 앱
    └── counter/
        └── ...
```

### 3.2 의존성

```yaml
# pubspec.yaml
name: flowdux
description: A predictable state management library with execution strategies

dependencies:
  rxdart: ^0.27.0        # BehaviorSubject, Stream 확장
  meta: ^1.9.0           # @protected, @sealed 등
  # 참고: synchronized 패키지 불필요 - AsyncLock 자체 구현

dev_dependencies:
  test: ^1.24.0
  fake_async: ^1.3.0     # 시간 기반 테스트
  mocktail: ^1.0.0       # Mocking
```

> **Note:** Dart는 싱글스레드이므로 스레드 안전 Mutex가 필요 없습니다.
> async 인터리빙 방지를 위한 `AsyncLock`을 Completer로 자체 구현합니다.

---

## 4. 핵심 API 설계

### 4.1 Store

```dart
/// FlowDux Store
class Store<S, A> {
  Store({
    required S initialState,
    required Reducer<S, A> reducer,
    List<Middleware<S, A>> middlewares = const [],
    ErrorProcessor<A>? errorProcessor,
  });

  /// 현재 상태 스트림
  Stream<S> get state;

  /// 현재 상태값 (동기)
  S get currentState;

  /// 액션 디스패치
  void dispatch(A action);

  /// 스토어 종료
  Future<void> close();
}

/// 팩토리 함수
Store<S, A> createStore<S, A>({
  required S initialState,
  required Reducer<S, A> reducer,
  List<Middleware<S, A>> middlewares = const [],
  ErrorProcessor<A>? errorProcessor,
});
```

### 4.2 Reducer

```dart
/// 리듀서 타입 정의
typedef Reducer<S, A> = S Function(S state, A action);
```

### 4.3 Action & FlowHolderAction

```dart
/// 기본 액션 인터페이스 (마커)
abstract class Action {}

/// 여러 액션을 포함하는 액션
/// Store에서 자동으로 Stream을 구독하여 방출된 액션들을 디스패치
abstract class FlowHolderAction implements Action {
  Stream<Action> toStreamAction();
}
```

**사용 예:**
```dart
// 여러 액션을 순차 방출
class BatchAction implements FlowHolderAction {
  final List<Action> actions;
  BatchAction(this.actions);

  @override
  Stream<Action> toStreamAction() => Stream.fromIterable(actions);
}

// 비동기 액션 체인
class FetchAndProcessAction implements FlowHolderAction {
  @override
  Stream<Action> toStreamAction() async* {
    yield LoadingAction();
    final data = await fetchData();
    yield DataLoadedAction(data);
    yield ProcessingAction();
    final result = await processData(data);
    yield ProcessedAction(result);
  }
}

store.dispatch(BatchAction([ActionA(), ActionB(), ActionC()]));
// → ActionA, ActionB, ActionC 순서대로 디스패치됨
```

### 4.5 Middleware & Processor

```dart
/// 미들웨어 추상 클래스
abstract class Middleware<S, A> {
  List<Processor<S, A>> get processors;

  /// Processor 빌더 DSL
  ProcessorListBuilder<S, A> buildProcessors(
    void Function(ProcessorListBuilder<S, A>) builder
  );
}

/// 프로세서 정의
class Processor<S, A> {
  final bool Function(A action) matcher;
  final ExecutionStrategy strategy;
  final Stream<A> Function(S state, A action) process;
}

/// 프로세서 빌더
class ProcessorListBuilder<S, A> {
  /// 특정 액션 타입에 대한 프로세서 등록
  void on<T extends A>(
    ExecutionStrategy strategy,
    Stream<A> Function(S state, T action) processor,
  );

  /// 그룹 (공유 전략)
  void group(
    ExecutionStrategy strategy,
    void Function(ProcessorListBuilder<S, A>) builder,
  );
}
```

### 4.6 Execution Strategy

```dart
/// 전략 카테고리
enum StrategyCategory {
  concurrency,  // takeLatest, takeLeading, sequential
  timing,       // debounce, throttle
  resilience,   // retry, retryWithBackoff
  chained,      // 체이닝된 전략
}

/// 실행 전략 인터페이스
abstract class ExecutionStrategy {
  StrategyCategory get category;

  /// 프로세서를 래핑
  Stream<A> Function(S state, A action) wrap<S, A>(
    Stream<A> Function(S state, A action) processor,
  );
}

/// 전략 체이닝 연산자
extension StrategyChaining on ExecutionStrategy {
  ExecutionStrategy then(ExecutionStrategy other);
}
```

### 4.7 Concurrency Strategies

```dart
/// 마지막 액션만 처리 (이전 취소)
ExecutionStrategy takeLatest();

/// 첫 액션만 처리 (이후 무시)
ExecutionStrategy takeLeading();

/// 순차 처리 (FIFO)
ExecutionStrategy sequential();
```

### 4.8 Timing Strategies

```dart
/// 디바운스
ExecutionStrategy debounce(Duration duration);

/// 쓰로틀
ExecutionStrategy throttle(Duration duration);
```

### 4.9 Resilience Strategies

```dart
/// 재시도
ExecutionStrategy retry(
  int maxAttempts, {
  bool Function(Object error)? retryIf,
});

/// 지수 백오프 재시도
ExecutionStrategy retryWithBackoff(
  int maxAttempts,
  Duration initialDelay, {
  double factor = 2.0,
  Duration? maxDelay,
  bool Function(Object error)? retryIf,
});
```

### 4.10 Error Processor

```dart
/// 에러 처리기
abstract class ErrorProcessor<A> {
  Stream<A> process(Object error, StackTrace stackTrace);
}
```

---

## 5. 구현 세부사항

### 5.1 Store dispatch with FlowHolderAction

```dart
class Store<S, A extends Action> {
  final _stateController = BehaviorSubject<S>();
  final _actionController = StreamController<A>.broadcast();
  final Reducer<S, A> _reducer;
  final List<StreamSubscription> _subscriptions = [];

  Store({
    required S initialState,
    required Reducer<S, A> reducer,
    List<Middleware<S, A>> middlewares = const [],
    ErrorProcessor<A>? errorProcessor,
  }) : _reducer = reducer {
    _stateController.add(initialState);
    _setupActionProcessing(middlewares, errorProcessor);
  }

  void dispatch(A action) {
    // FlowHolderAction 처리: Stream의 각 액션을 개별 디스패치
    if (action is FlowHolderAction) {
      _subscriptions.add(
        action.toStreamAction().listen(
          (emittedAction) => dispatch(emittedAction as A),
          onError: (error) => _handleError(error),
        ),
      );
      return;
    }

    // 일반 액션 처리
    _actionController.add(action);
  }

  void _processAction(A action) {
    final newState = _reducer(_stateController.value, action);
    _stateController.add(newState);
  }

  Future<void> close() async {
    for (final sub in _subscriptions) {
      await sub.cancel();
    }
    await _actionController.close();
    await _stateController.close();
  }
}
```

**핵심 포인트:**
- `FlowHolderAction`이면 `toStreamAction()`을 구독
- 방출된 각 액션을 재귀적으로 `dispatch()` 호출
- 중첩된 `FlowHolderAction`도 자동 처리

### 5.2 TakeLatest 구현

```dart
class TakeLatestStrategy implements ExecutionStrategy {
  @override
  StrategyCategory get category => StrategyCategory.concurrency;

  StreamSubscription? _currentSubscription;

  @override
  Stream<A> Function(S state, A action) wrap<S, A>(
    Stream<A> Function(S state, A action) processor,
  ) {
    return (state, action) async* {
      // 이전 작업 취소
      await _currentSubscription?.cancel();

      final controller = StreamController<A>();
      _currentSubscription = processor(state, action).listen(
        controller.add,
        onError: controller.addError,
        onDone: controller.close,
      );

      yield* controller.stream;
    };
  }
}

ExecutionStrategy takeLatest() => TakeLatestStrategy();
```

### 5.3 AsyncLock 유틸리티 (자체 구현)

```dart
/// Completer 기반 비동기 락
/// Dart 싱글스레드에서 async 인터리빙을 방지
class AsyncLock {
  Completer<void>? _completer;

  /// 락을 획득하고 함수 실행 후 해제
  Future<T> synchronized<T>(Future<T> Function() fn) async {
    // 이전 작업 완료 대기
    while (_completer != null) {
      await _completer!.future;
    }

    // 락 획득
    _completer = Completer<void>();
    try {
      return await fn();
    } finally {
      // 락 해제
      final c = _completer!;
      _completer = null;
      c.complete();
    }
  }
}
```

### 5.4 Sequential 구현

```dart
class SequentialStrategy implements ExecutionStrategy {
  @override
  StrategyCategory get category => StrategyCategory.concurrency;

  final _lock = AsyncLock();  // 자체 구현 AsyncLock 사용

  @override
  Stream<A> Function(S state, A action) wrap<S, A>(
    Stream<A> Function(S state, A action) processor,
  ) {
    return (state, action) async* {
      await _lock.synchronized(() async {
        await for (final result in processor(state, action)) {
          yield result;
        }
      });
    };
  }
}
```

### 5.5 Debounce 구현

```dart
class DebounceStrategy implements ExecutionStrategy {
  final Duration duration;
  Timer? _timer;

  DebounceStrategy(this.duration);

  @override
  StrategyCategory get category => StrategyCategory.timing;

  @override
  Stream<A> Function(S state, A action) wrap<S, A>(
    Stream<A> Function(S state, A action) processor,
  ) {
    return (state, action) async* {
      final completer = Completer<void>();

      _timer?.cancel();
      _timer = Timer(duration, () => completer.complete());

      await completer.future;
      yield* processor(state, action);
    };
  }
}
```

### 5.6 Strategy Chaining 구현

```dart
class ChainedStrategy implements ExecutionStrategy {
  final List<ExecutionStrategy> strategies;

  ChainedStrategy(this.strategies);

  @override
  StrategyCategory get category => StrategyCategory.chained;

  @override
  Stream<A> Function(S state, A action) wrap<S, A>(
    Stream<A> Function(S state, A action) processor,
  ) {
    return strategies.fold(
      processor,
      (wrapped, strategy) => strategy.wrap(wrapped),
    );
  }
}

extension StrategyChaining on ExecutionStrategy {
  ExecutionStrategy then(ExecutionStrategy other) {
    // 카테고리 검증
    _validateChaining(this, other);

    final strategies = <ExecutionStrategy>[];
    if (this is ChainedStrategy) {
      strategies.addAll((this as ChainedStrategy).strategies);
    } else {
      strategies.add(this);
    }
    strategies.add(other);

    return ChainedStrategy(strategies);
  }
}
```

---

## 6. 구현 단계

### Phase 1: Core Store (1주차)

**작업 내용:**
- [ ] 프로젝트 구조 생성 (`dart/flowdux/`)
- [ ] `Action`, `FlowHolderAction` 인터페이스 정의
- [ ] `Store` 클래스 구현
- [ ] `Reducer` 타입 정의
- [ ] 기본 `dispatch` → `reduce` → `emit` 흐름
- [ ] `FlowHolderAction` 처리 로직 (Stream 구독 → 재귀 dispatch)
- [ ] `close()` 리소스 정리
- [ ] 단위 테스트

**완료 조건:**
- Store 생성, dispatch, state 구독 동작
- FlowHolderAction 방출 액션들 순차 처리
- 테스트 통과

### Phase 2: Middleware System (1주차)

**작업 내용:**
- [ ] `Middleware` 추상 클래스
- [ ] `Processor` 클래스
- [ ] `ProcessorListBuilder` DSL
- [ ] `on<T>()` 타입 매칭
- [ ] `ErrorProcessor` 구현
- [ ] 단위 테스트

**완료 조건:**
- Middleware가 액션 가로채기 동작
- 에러 처리 동작
- 테스트 통과

### Phase 3: Concurrency Strategies (1주차)

**작업 내용:**
- [ ] `ExecutionStrategy` 인터페이스
- [ ] `StrategyCategory` enum
- [ ] `takeLatest()` 구현
- [ ] `takeLeading()` 구현
- [ ] `sequential()` 구현
- [ ] 단위 테스트

**완료 조건:**
- 각 전략의 동작 검증
- 취소/무시/순차 처리 테스트 통과

### Phase 4: Timing Strategies (0.5주차)

**작업 내용:**
- [ ] `debounce()` 구현
- [ ] `throttle()` 구현
- [ ] `fake_async` 활용 시간 테스트
- [ ] 단위 테스트

**완료 조건:**
- 디바운스/쓰로틀 동작 검증
- 테스트 통과

### Phase 5: Resilience Strategies (0.5주차)

**작업 내용:**
- [ ] `retry()` 구현
- [ ] `retryWithBackoff()` 구현
- [ ] `retryIf` 조건부 재시도
- [ ] `CancellationException` 처리
- [ ] 단위 테스트

**완료 조건:**
- 재시도 동작 검증
- 지수 백오프 타이밍 검증
- 테스트 통과

### Phase 6: Strategy Chaining & Grouping (0.5주차)

**작업 내용:**
- [ ] `then()` 연산자 구현
- [ ] 카테고리 중복 검증
- [ ] `group()` 빌더 구현
- [ ] 체이닝 테스트
- [ ] 그룹 테스트

**완료 조건:**
- `debounce().then(takeLatest())` 동작
- 동일 카테고리 체이닝 시 예외 발생
- 테스트 통과

### Phase 7: Flutter Integration (선택적, 1주차)

**작업 내용:**
- [ ] `flowdux_flutter` 패키지 생성
- [ ] `StoreProvider` (InheritedWidget)
- [ ] `StoreBuilder` (StreamBuilder 래퍼)
- [ ] `StoreConsumer` (Provider + Builder)
- [ ] Flutter 예제 앱

**완료 조건:**
- Flutter 위젯에서 Store 사용 가능
- 예제 앱 동작

### Phase 8: Documentation & Publishing (0.5주차)

**작업 내용:**
- [ ] README.md 작성
- [ ] API 문서 (dartdoc)
- [ ] CHANGELOG.md
- [ ] 예제 코드
- [ ] pub.dev 배포 준비

**완료 조건:**
- 문서 완성
- `dart pub publish --dry-run` 통과

---

## 7. 테스트 전략

### 7.1 단위 테스트

```dart
// fake_async 활용 시간 테스트
test('debounce delays execution', () {
  fakeAsync((async) {
    final store = createStore(...);
    final strategy = debounce(Duration(milliseconds: 100));

    store.dispatch(SearchAction('a'));
    store.dispatch(SearchAction('ab'));
    store.dispatch(SearchAction('abc'));

    async.elapse(Duration(milliseconds: 150));

    // 'abc'만 처리됨
    expect(executedQueries, ['abc']);
  });
});
```

### 7.2 통합 테스트

```dart
test('middleware processes actions correctly', () async {
  final store = createStore(
    initialState: CounterState(0),
    reducer: counterReducer,
    middlewares: [CounterMiddleware()],
  );

  store.dispatch(IncrementAction());

  await expectLater(
    store.state,
    emitsInOrder([
      CounterState(0),  // initial
      CounterState(1),  // after increment
    ]),
  );
});
```

---

## 8. Kotlin 버전과의 차이점

| 항목 | Kotlin | Dart |
|------|--------|------|
| Scope 전달 | `scope: CoroutineScope` | 불필요 (자동 관리) |
| 취소 처리 | `CancellationException` | `StreamSubscription.cancel()` |
| 테스트 시간 제어 | `runTest`, `advanceTimeBy` | `fakeAsync`, `async.elapse` |
| Reified Generics | 지원 | 미지원 (타입 체크 방식 다름) |
| Sealed Class | `sealed class` | `sealed class` (Dart 3.0+) |

---

## 9. 패키지 배포 정보

### 9.1 pub.dev 메타데이터

```yaml
name: flowdux
version: 0.1.0
description: >
  A predictable state management library with execution strategies.
  Supports takeLatest, takeLeading, debounce, throttle, retry, and strategy chaining.
homepage: https://github.com/chibimoons/flowdux
repository: https://github.com/chibimoons/flowdux
issue_tracker: https://github.com/chibimoons/flowdux/issues
documentation: https://github.com/chibimoons/flowdux/tree/main/dart/flowdux

environment:
  sdk: '>=3.0.0 <4.0.0'

topics:
  - state-management
  - redux
  - flutter
  - reactive
```

### 9.2 라이선스
MIT License (Kotlin 버전과 동일)

---

## 10. 일정 요약

| Phase | 기간 | 내용 |
|-------|------|------|
| 1 | 1주 | Core Store |
| 2 | 1주 | Middleware System |
| 3 | 1주 | Concurrency Strategies |
| 4 | 0.5주 | Timing Strategies |
| 5 | 0.5주 | Resilience Strategies |
| 6 | 0.5주 | Strategy Chaining & Grouping |
| 7 | 1주 | Flutter Integration (선택) |
| 8 | 0.5주 | Documentation & Publishing |
| **총계** | **5~6주** | |

---

## 11. 리스크 및 고려사항

| 리스크 | 영향 | 대응 |
|--------|------|------|
| Stream 취소 복잡성 | 메모리 누수 | `StreamSubscription` 철저히 관리 |
| rxdart 의존성 | 패키지 크기 증가 | 필요 최소 기능만 사용 |
| Dart 3.0 sealed class | 하위 호환성 | SDK >=3.0.0 명시 |
| 타입 추론 한계 | API 사용성 저하 | 명시적 타입 파라미터 요구 |

---

## 12. 체크리스트

### Phase 1 완료
- [ ] `Action`, `FlowHolderAction` 구현
- [ ] `Store` 클래스 구현
- [ ] `FlowHolderAction` 처리 동작
- [ ] 기본 테스트 통과

### Phase 2 완료
- [ ] `Middleware` 시스템 구현
- [ ] `Processor` 빌더 동작

### Phase 3 완료
- [ ] `takeLatest`, `takeLeading`, `sequential` 구현
- [ ] 동시성 테스트 통과

### Phase 4 완료
- [ ] `debounce`, `throttle` 구현
- [ ] 타이밍 테스트 통과

### Phase 5 완료
- [ ] `retry`, `retryWithBackoff` 구현
- [ ] 재시도 테스트 통과

### Phase 6 완료
- [ ] `then()` 체이닝 구현
- [ ] `group()` 빌더 구현

### Phase 7 완료 (선택)
- [ ] Flutter 위젯 구현
- [ ] 예제 앱 동작

### Phase 8 완료
- [ ] 문서 작성
- [ ] pub.dev 배포 준비
