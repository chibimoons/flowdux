# Orbit MVI

*KMP용 타입 세이프 MVI 프레임워크 ("MVVM+" 지향)*

---

## 개요

| 항목 | 내용 |
|------|------|
| GitHub | [orbit-mvi/orbit-mvi](https://github.com/orbit-mvi/orbit-mvi) |
| 문서 | [orbit-mvi.org](https://orbit-mvi.org/) |
| 철학 | Redux/MVI 영감의 단방향 데이터 흐름, MVVM 확장 ("MVVM+") |
| 플랫폼 | Android, iOS, Desktop (KMP) |
| 의존성 | Kotlin Coroutines |

Orbit은 Kotlin Multiplatform을 위한 간단하고 타입 안전한 MVI 프레임워크입니다.
Android, iOS, Desktop 간 비즈니스 로직 공유를 가능하게 합니다.

---

## 핵심 아키텍처

### Container 패턴

```
┌─────────────────────────────────────────────────────────────────┐
│                        ContainerHost                             │
│                     (ViewModel 또는 일반 클래스)                   │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                       Container                            │  │
│  │                                                            │  │
│  │   ┌─────────┐    ┌─────────┐    ┌─────────────────────┐   │  │
│  │   │  State  │    │ Intent  │    │    Side Effects     │   │  │
│  │   │ (Flow)  │    │ (입력)  │    │ (one-off 이벤트)     │   │  │
│  │   └─────────┘    └─────────┘    └─────────────────────┘   │  │
│  │                                                            │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### MVI 구성 요소

```kotlin
// State: 불변 데이터 클래스
data class CounterState(
    val count: Int = 0,
    val isLoading: Boolean = false
)

// Side Effect: 일회성 이벤트 (Toast, 네비게이션 등)
sealed class CounterSideEffect {
    data class Toast(val message: String) : CounterSideEffect()
    object NavigateToDetails : CounterSideEffect()
}
```

---

## 기본 사용법

### ContainerHost 구현

```kotlin
class CounterViewModel : ViewModel(), ContainerHost<CounterState, CounterSideEffect> {

    // Container 생성
    override val container = container<CounterState, CounterSideEffect>(CounterState())

    // Intent 처리
    fun increment() = intent {
        reduce { state.copy(count = state.count + 1) }
    }

    fun loadData() = intent {
        reduce { state.copy(isLoading = true) }

        val result = api.fetchData()  // suspend 함수

        reduce {
            state.copy(
                isLoading = false,
                data = result
            )
        }

        // Side Effect 발생
        postSideEffect(CounterSideEffect.Toast("Data loaded!"))
    }
}
```

### UI에서 사용

```kotlin
@Composable
fun CounterScreen(viewModel: CounterViewModel) {
    val state by viewModel.container.stateFlow.collectAsState()

    // Side Effect 처리
    viewModel.container.sideEffectFlow.collectSideEffect { sideEffect ->
        when (sideEffect) {
            is CounterSideEffect.Toast -> showToast(sideEffect.message)
            CounterSideEffect.NavigateToDetails -> navigateToDetails()
        }
    }

    CounterContent(
        count = state.count,
        onIncrement = viewModel::increment
    )
}
```

---

## 데이터 흐름

```
User Action ───► Intent ───► Reducer ───► New State ───► UI Update
                    │
                    └───► Side Effect ───► One-off Event (Toast, Nav)
```

### 상세 흐름

```
1. 사용자가 버튼 클릭
         │
         ▼
2. intent { } 블록 실행
         │
         ├──► reduce { } : 상태 업데이트
         │         │
         │         ▼
         │    Container 내부에서 atomic하게 적용
         │         │
         │         ▼
         │    stateFlow로 새 상태 emit
         │
         └──► postSideEffect() : 일회성 이벤트
                   │
                   ▼
              sideEffectFlow로 emit (캐싱됨)
```

---

## 주요 기능

### 1. reduce (상태 업데이트)

```kotlin
fun updateName(name: String) = intent {
    reduce {
        state.copy(name = name)
    }
}
```

- **Atomic**: 모든 reduce는 순차적으로 실행
- **Immutable**: 항상 새 상태 객체 반환
- **Thread-safe**: 내부적으로 동기화됨

### 2. Side Effects

```kotlin
fun showError(message: String) = intent {
    postSideEffect(ShowToast(message))
}
```

- 상태에 포함되지 않는 일회성 이벤트
- 기본적으로 무제한 캐싱 (collector가 없을 때)
- 캐시 크기 설정 가능

### 3. repeatOnSubscription

```kotlin
override val container = container<State, SideEffect>(State()) {
    repeatOnSubscription {
        // collector가 있을 때만 실행
        observeDataSource().collect { data ->
            reduce { state.copy(data = data) }
        }
    }
}
```

### 4. Saved State (Android)

```kotlin
override val container = container<State, SideEffect>(
    initialState = State(),
    savedStateHandle = savedStateHandle,
    settings = Container.Settings(
        // 프로세스 종료 후에도 상태 복원
        stateSerializerClass = State::class
    )
)
```

---

## flowdux와 비교

| 측면 | Orbit MVI | flowdux |
|------|-----------|---------|
| **패러다임** | MVI (Container 중심) | Redux (Store/Middleware) |
| **상태 업데이트** | `reduce { }` 블록 | Reducer 함수 |
| **비동기 처리** | `intent { }` 내 suspend | Middleware processor |
| **Side Effects** | 전용 `sideEffectFlow` | Action으로 emit |
| **동시성 제어** | 기본 순차 실행 | Execution Strategy |
| **액션 취소** | 수동 관리 | takeLatest, debounce 등 |
| **글로벌 상태** | 각 Container 독립 | 단일 Store |
| **ViewModel 통합** | 네이티브 지원 | 별도 통합 필요 |

### 아키텍처 비교

```
┌─────────────────────────────────────────────────────────────────┐
│                         Orbit MVI                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   UI ──► ContainerHost ──► Container ──► State/SideEffect       │
│                │                                                 │
│                └── intent { reduce { }, postSideEffect() }       │
│                                                                  │
│   특징: ViewModel마다 독립적인 Container                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                           flowdux                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   UI ──► Store ──► Middleware ──► Reducer ──► State             │
│              │          │                                        │
│              │          └── processor { emit(Action) }           │
│              │                                                   │
│              └── 글로벌 단일 Store                                │
│                                                                  │
│   특징: 전역 상태, Middleware 체인, Execution Strategy           │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 장단점

### 장점

1. **간결한 API**: `intent { reduce { } }` 패턴으로 보일러플레이트 최소화
2. **Side Effect 분리**: 상태와 일회성 이벤트 명확히 분리
3. **ViewModel 친화적**: Android ViewModel과 자연스러운 통합
4. **테스트 용이**: `runContainerTest`로 쉬운 테스트
5. **Compose 지원**: `collectAsState`, `collectSideEffect` 확장

### 단점

1. **글로벌 상태 부재**: 각 Container가 독립적 (앱 전체 상태 관리 어려움)
2. **동시성 제어 제한**: 내장 takeLatest, debounce 없음
3. **Middleware 패턴 없음**: 횡단 관심사 처리 어려움
4. **액션 로깅**: 별도 구현 필요

---

## 언제 사용하면 좋은가?

### Orbit MVI가 적합한 경우

- 화면별 독립적인 상태 관리
- Android ViewModel 중심 아키텍처
- 간단한 MVI 패턴 도입
- Side Effect 처리가 많은 앱

### flowdux가 적합한 경우

- 앱 전체 글로벌 상태 필요
- 복잡한 동시성 제어 (takeLatest, debounce 등)
- Middleware로 횡단 관심사 처리
- 액션 로깅, 타임트래블 디버깅

---

## 참고 자료

- [Orbit MVI 공식 문서](https://orbit-mvi.org/)
- [GitHub Repository](https://github.com/orbit-mvi/orbit-mvi)
- [Orbit MVI and ContainerHost - Medium](https://medium.com/@engineer.marwatalaat/orbit-mvi-and-containerhost-acf78b64d2ac)
- [Orbit MVI Complete Guide - Medium](https://medium.com/@mikhaltchenkov/orbit-mvi-a-complete-guide-to-the-state-management-framework-39c28e05cdd3)
