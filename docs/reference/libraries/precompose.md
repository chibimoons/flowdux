# PreCompose

*Compose Multiplatform 네비게이션 + ViewModel + 상태 관리*

---

## 개요

| 항목 | 내용 |
|------|------|
| GitHub | [Tlaster/PreCompose](https://github.com/Tlaster/PreCompose) |
| 문서 | [tlaster.github.io/PreCompose](https://tlaster.github.io/PreCompose/) |
| 저자 | Tlaster |
| 철학 | Jetpack 컴포넌트의 KMP 버전 |
| 플랫폼 | Android, iOS, Desktop, Web (Compose Multiplatform) |
| 의존성 | Compose Multiplatform |

PreCompose는 Jetpack Navigation, ViewModel, Lifecycle을 Compose Multiplatform에서
사용할 수 있게 해주는 라이브러리입니다.

---

## 핵심 철학

### Jetpack과 동일한 API

```
┌─────────────────────────────────────────────────────────────────────┐
│                     PreCompose 철학                                  │
│                                                                      │
│   Jetpack에 익숙하다면, PreCompose도 익숙하다                        │
│                                                                      │
│   Jetpack             →        PreCompose                           │
│   ──────────────────────────────────────────                        │
│   Navigation              NavHost                                   │
│   ViewModel               ViewModel                                 │
│   Lifecycle               Lifecycle                                 │
│   SavedStateHandle        SavedStateHolder                          │
│                                                                      │
│   commonMain에서 한 번 작성, 모든 플랫폼에서 실행                    │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Navigation

### NavHost 설정

```kotlin
@Composable
fun App() {
    PreComposeApp {
        val navigator = rememberNavigator()

        NavHost(
            navigator = navigator,
            initialRoute = "/home"
        ) {
            scene("/home") {
                HomeScreen(
                    onNavigateToDetails = { id ->
                        navigator.navigate("/details/$id")
                    }
                )
            }

            scene("/details/{id}") { backStackEntry ->
                val id = backStackEntry.path<String>("id") ?: ""
                DetailsScreen(id = id)
            }
        }
    }
}
```

### Navigator 사용

```kotlin
// 화면 이동
navigator.navigate("/details/123")

// 뒤로가기
navigator.goBack()

// 스택 조작
navigator.popBackStack("/home", inclusive = false)
```

### 쿼리 파라미터

```kotlin
// 이동
navigator.navigate("/search?query=kotlin&page=1")

// 받기
scene("/search") { backStackEntry ->
    val query = backStackEntry.query<String>("query")
    val page = backStackEntry.query<Int>("page") ?: 1
}
```

---

## ViewModel

### 기본 사용법

```kotlin
class CounterViewModel : ViewModel() {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    fun increment() {
        _count.value++
    }
}

@Composable
fun CounterScreen() {
    val viewModel = viewModel { CounterViewModel() }
    val count by viewModel.count.collectAsState()

    Column {
        Text("Count: $count")
        Button(onClick = viewModel::increment) {
            Text("Increment")
        }
    }
}
```

### ViewModel 키

파라미터에 따라 다른 ViewModel 인스턴스:

```kotlin
@Composable
fun UserScreen(userId: String) {
    // userId가 바뀌면 새 ViewModel 생성
    val viewModel = viewModel(keys = listOf(userId)) {
        UserViewModel(userId)
    }
}
```

### SavedStateHolder

상태 저장/복원:

```kotlin
class MyViewModel(savedStateHolder: SavedStateHolder) : ViewModel() {
    var count by savedStateHolder.saveable { mutableStateOf(0) }

    fun increment() {
        count++
    }
}

@Composable
fun MyScreen() {
    val viewModel = viewModel { entry ->
        MyViewModel(entry.savedStateHolder)
    }
}
```

---

## Lifecycle

PreCompose는 자체 Lifecycle을 관리합니다:

```kotlin
@Composable
fun MyScreen() {
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleObserver {
            // 라이프사이클 이벤트 처리
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}
```

---

## Koin 통합

DI 프레임워크와 통합:

```kotlin
// Koin 모듈 정의
val viewModelModule = module {
    viewModelOf(::CounterViewModel)
    viewModelOf(::UserViewModel)
}

// 사용
@Composable
fun CounterScreen() {
    val viewModel = koinViewModel<CounterViewModel>()
}
```

---

## Molecule 통합

Molecule과 함께 사용:

```kotlin
class CounterPresenter {
    @Composable
    fun present(): CounterState {
        var count by remember { mutableStateOf(0) }

        return CounterState(
            count = count,
            onIncrement = { count++ }
        )
    }
}

@Composable
fun CounterScreen() {
    val presenter = remember { CounterPresenter() }
    val state = presenter.present()

    CounterContent(state)
}
```

---

## flowdux와 비교

| 측면 | PreCompose | flowdux |
|------|------------|---------|
| **범위** | 네비게이션 + ViewModel + Lifecycle | 상태 관리만 |
| **상태 관리** | ViewModel + StateFlow | Store + Middleware |
| **네비게이션** | NavHost (Jetpack 스타일) | 없음 |
| **라이프사이클** | 지원 | 없음 |
| **동시성** | 수동 | Execution Strategy |
| **플랫폼** | Compose Multiplatform | KMP |

### 조합 가능성

```
┌─────────────────────────────────────────────────────────────────┐
│                  PreCompose + flowdux 조합                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   class MyViewModel(                                             │
│       private val store: Store<AppState, AppAction>             │
│   ) : ViewModel() {                                              │
│                                                                  │
│       val state: StateFlow<AppState> = store.state              │
│                                                                  │
│       fun dispatch(action: AppAction) {                          │
│           store.dispatch(action)                                 │
│       }                                                          │
│   }                                                              │
│                                                                  │
│   @Composable                                                    │
│   fun MyScreen() {                                               │
│       val viewModel = viewModel { MyViewModel(appStore) }        │
│       val state by viewModel.state.collectAsState()              │
│       // ...                                                     │
│   }                                                              │
│                                                                  │
│   특징: PreCompose로 네비게이션/ViewModel,                        │
│         flowdux로 상태 관리 + 동시성                              │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 장단점

### 장점

1. **Jetpack 친숙도**: 기존 Android 개발자에게 익숙
2. **쉬운 마이그레이션**: Jetpack → PreCompose 전환 간단
3. **올인원**: 네비게이션 + ViewModel + Lifecycle
4. **Koin/Molecule 통합**: 기존 라이브러리와 호환
5. **빠른 시작**: 플랫폼별 코드 불필요

### 단점

1. **Compose 의존**: Compose Multiplatform 필수
2. **상태 관리 단순**: 복잡한 상태 관리는 별도 라이브러리
3. **동시성 전략 없음**: takeLatest 등 수동 구현
4. **글로벌 상태**: ViewModel 간 공유 복잡

---

## 언제 사용하면 좋은가?

### PreCompose가 적합한 경우

- Compose Multiplatform 프로젝트
- Jetpack Navigation 경험자
- 빠른 프로토타이핑
- 간단한 네비게이션 요구사항

### flowdux와 조합이 적합한 경우

- PreCompose로 네비게이션/ViewModel
- flowdux로 글로벌 상태 관리
- 동시성 전략 (takeLatest, debounce)
- 복잡한 상태 로직

---

## 참고 자료

- [PreCompose 공식 문서](https://tlaster.github.io/PreCompose/)
- [GitHub Repository](https://github.com/Tlaster/PreCompose)
- [ViewModel 문서](https://tlaster.github.io/PreCompose/component/view_model.html)
- [Navigation 문서](https://tlaster.github.io/PreCompose/component/navigation.html)
- [Building a Compose Multiplatform app - Medium](https://medium.com/@nitheeshag/building-a-compose-multiplatform-app-with-an-architectural-pattern-e31a85e82927)
