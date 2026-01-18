# Decompose

*KMP용 컴포넌트 + 라이프사이클 + 네비게이션 프레임워크*

---

## 개요

| 항목 | 내용 |
|------|------|
| GitHub | [arkivanov/Decompose](https://github.com/arkivanov/Decompose) |
| 문서 | [arkivanov.github.io/Decompose](https://arkivanov.github.io/Decompose/) |
| 저자 | Arkadii Ivanov (Bumble, GDE) |
| 철학 | BLoC 스타일 컴포넌트, 플러그블 UI |
| 플랫폼 | Android, iOS, Desktop, Web (KMP) |
| 의존성 | Kotlin Coroutines (선택) |

Decompose는 라이프사이클 인식 비즈니스 로직 컴포넌트(BLoC)와
네비게이션(라우팅)을 제공하는 KMP 프레임워크입니다.

---

## 핵심 개념

### Component

UI 독립적인 비즈니스 로직 단위:

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Component                                    │
│                                                                      │
│   • 라이프사이클 인식                                               │
│   • 플랫폼 독립적 비즈니스 로직                                     │
│   • 상태 + 이벤트 관리                                              │
│   • 자식 Component 관리 (네비게이션)                                │
│                                                                      │
│   ┌─────────────┐    ┌─────────────┐    ┌─────────────┐            │
│   │    State    │    │   Events    │    │  Children   │            │
│   │  (Value)    │    │ (one-off)   │    │ (Navigation)│            │
│   └─────────────┘    └─────────────┘    └─────────────┘            │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### ComponentContext

Component에 필요한 모든 것을 제공:

```kotlin
interface ComponentContext {
    val lifecycle: Lifecycle
    val stateKeeper: StateKeeper
    val instanceKeeper: InstanceKeeper
    val backHandler: BackHandler
}
```

---

## 기본 사용법

### 1. Component 정의

```kotlin
class CounterComponent(
    componentContext: ComponentContext
) : ComponentContext by componentContext {

    private val _state = MutableValue(CounterState())
    val state: Value<CounterState> = _state

    fun increment() {
        _state.value = _state.value.copy(count = _state.value.count + 1)
    }

    fun decrement() {
        _state.value = _state.value.copy(count = _state.value.count - 1)
    }

    data class CounterState(val count: Int = 0)
}
```

### 2. Root Component

```kotlin
class RootComponent(
    componentContext: ComponentContext
) : ComponentContext by componentContext {

    private val navigation = StackNavigation<Config>()

    val childStack: Value<ChildStack<Config, Child>> =
        childStack(
            source = navigation,
            initialConfiguration = Config.Home,
            handleBackButton = true,
            childFactory = ::createChild
        )

    private fun createChild(
        config: Config,
        componentContext: ComponentContext
    ): Child = when (config) {
        Config.Home -> Child.Home(HomeComponent(componentContext))
        Config.Details -> Child.Details(DetailsComponent(componentContext))
    }

    fun navigateToDetails() {
        navigation.push(Config.Details)
    }

    fun navigateBack() {
        navigation.pop()
    }

    sealed interface Config : Parcelable {
        @Parcelize object Home : Config
        @Parcelize object Details : Config
    }

    sealed interface Child {
        data class Home(val component: HomeComponent) : Child
        data class Details(val component: DetailsComponent) : Child
    }
}
```

### 3. Compose UI 연결

```kotlin
@Composable
fun RootContent(component: RootComponent) {
    val childStack by component.childStack.subscribeAsState()

    Children(stack = childStack) { child ->
        when (val instance = child.instance) {
            is Child.Home -> HomeContent(instance.component)
            is Child.Details -> DetailsContent(instance.component)
        }
    }
}

@Composable
fun CounterContent(component: CounterComponent) {
    val state by component.state.subscribeAsState()

    Column {
        Text("Count: ${state.count}")
        Button(onClick = component::increment) { Text("+") }
        Button(onClick = component::decrement) { Text("-") }
    }
}
```

---

## 네비게이션

### Stack Navigation

화면 스택 관리:

```kotlin
private val navigation = StackNavigation<Config>()

// 화면 추가
navigation.push(Config.Details)

// 뒤로가기
navigation.pop()

// 스택 교체
navigation.replaceAll(Config.Home)

// 특정 화면까지 팝
navigation.popTo(index = 0)
```

### Slot Navigation

단일 자식 관리:

```kotlin
private val dialogNavigation = SlotNavigation<DialogConfig>()

val dialogSlot: Value<ChildSlot<DialogConfig, Dialog>> =
    childSlot(
        source = dialogNavigation,
        childFactory = ::createDialog
    )

// 다이얼로그 표시
dialogNavigation.activate(DialogConfig.Confirm)

// 다이얼로그 닫기
dialogNavigation.dismiss()
```

### Pages Navigation

페이저/탭 관리:

```kotlin
private val pagesNavigation = PagesNavigation<PageConfig>()

val pages: Value<ChildPages<PageConfig, Page>> =
    childPages(
        source = pagesNavigation,
        initialPages = { Pages(items = listOf(Page1, Page2, Page3)) }
    )

// 페이지 선택
pagesNavigation.select(index = 1)
```

---

## 라이프사이클

### Lifecycle 상태

```
┌─────────────────────────────────────────────────────────────────────┐
│                      Decompose Lifecycle                             │
│                                                                      │
│   CREATED ────► STARTED ────► RESUMED                               │
│       │             │             │                                  │
│       │             │             ▼                                  │
│       │             │         (활성 상태)                            │
│       │             │             │                                  │
│       │             ◄─────────────┘                                  │
│       │                                                              │
│       ◄── STOPPED ◄── DESTROYED                                     │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Lifecycle 콜백

```kotlin
class MyComponent(
    componentContext: ComponentContext
) : ComponentContext by componentContext {

    init {
        lifecycle.doOnCreate { /* Component 생성 시 */ }
        lifecycle.doOnStart { /* 화면에 표시될 때 */ }
        lifecycle.doOnResume { /* 포그라운드 */ }
        lifecycle.doOnPause { /* 백그라운드 */ }
        lifecycle.doOnStop { /* 화면에서 사라질 때 */ }
        lifecycle.doOnDestroy { /* Component 파괴 시 */ }
    }
}
```

---

## MVIKotlin 통합

같은 저자의 MVIKotlin과 완벽 호환:

```kotlin
class CounterComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory
) : ComponentContext by componentContext {

    private val store = CounterStoreFactory(storeFactory).create()

    val state: StateFlow<CounterState> = store.stateFlow

    fun onIntent(intent: CounterIntent) {
        store.accept(intent)
    }

    init {
        // 라이프사이클과 Store 연동
        lifecycle.doOnDestroy { store.dispose() }
    }
}
```

---

## flowdux와 비교

| 측면 | Decompose | flowdux |
|------|-----------|---------|
| **범위** | 네비게이션 + 라이프사이클 + 상태 | 상태 관리만 |
| **상태 관리** | Value/StateFlow (또는 MVIKotlin) | Store + Middleware |
| **네비게이션** | Stack/Slot/Pages | 없음 (별도 라이브러리) |
| **라이프사이클** | 완전 지원 | 없음 |
| **동시성** | 수동 | Execution Strategy |
| **Compose 통합** | `subscribeAsState()` | `collectAsState()` |

### 조합 가능성

Decompose는 상태 관리 라이브러리와 조합하여 사용:

```
┌─────────────────────────────────────────────────────────────────┐
│                    Decompose + flowdux 조합                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   class MyComponent(                                             │
│       componentContext: ComponentContext,                        │
│       private val store: Store<AppState, AppAction>             │
│   ) : ComponentContext by componentContext {                     │
│                                                                  │
│       val state: StateFlow<AppState> = store.state              │
│                                                                  │
│       fun dispatch(action: AppAction) {                          │
│           store.dispatch(action)                                 │
│       }                                                          │
│   }                                                              │
│                                                                  │
│   특징: Decompose로 네비게이션/라이프사이클,                      │
│         flowdux로 상태 관리 + 동시성                              │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 장단점

### 장점

1. **완전한 솔루션**: 네비게이션 + 라이프사이클 + 상태
2. **플러그블 UI**: Compose, SwiftUI, React 등 모두 지원
3. **타입 안전 네비게이션**: sealed class로 화면 정의
4. **상태 보존**: 프로세스 종료에도 상태 복원
5. **MVIKotlin 통합**: 같은 저자의 라이브러리

### 단점

1. **학습 곡선**: Component, Navigation 개념 학습 필요
2. **보일러플레이트**: 설정 코드 많음
3. **상태 관리 별도**: 자체 상태 관리는 단순 (MVIKotlin 조합 권장)

---

## 언제 사용하면 좋은가?

### Decompose가 적합한 경우

- KMP에서 네비게이션 필요
- 라이프사이클 관리 중요
- MVIKotlin과 함께 사용
- 다양한 UI 프레임워크 지원 필요

### flowdux와 조합이 적합한 경우

- Decompose로 네비게이션/라이프사이클
- flowdux로 상태 관리 + 동시성 전략
- Strategy Group으로 액션 간 조율

---

## 참고 자료

- [Decompose 공식 문서](https://arkivanov.github.io/Decompose/)
- [GitHub Repository](https://github.com/arkivanov/Decompose)
- [Component Overview](https://arkivanov.github.io/Decompose/component/overview/)
- [Navigation Overview](https://arkivanov.github.io/Decompose/navigation/overview/)
- [DIY Compose Multiplatform Navigation - ProAndroidDev](https://proandroiddev.com/diy-compose-multiplatform-navigation-with-decompose-94ac8126e6b5)
