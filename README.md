# Flowdux

A lightweight Redux-style state management library for **Kotlin Multiplatform** and **Dart/Flutter** with Middleware support.

[![Kotlin](https://jitpack.io/v/chibimoons/flowdux.svg)](https://jitpack.io/#chibimoons/flowdux)
[![Dart](https://img.shields.io/pub/v/flowdux.svg)](https://pub.dev/packages/flowdux)
[![Flutter](https://img.shields.io/pub/v/flowdux_flutter.svg)](https://pub.dev/packages/flowdux_flutter)

## Features

- Redux-style state management with Reducer pattern
- Middleware support for side effects
- Execution strategies (takeLatest, takeLeading, sequential, debounce, throttle, retry)
- Strategy chaining and groups for flexible action coordination
- Error handling with ErrorProcessor
- Time travel debugging (undo/redo, state history)
- Built on Kotlin Coroutines and Flow / Dart Streams
- Kotlin Multiplatform support (JVM, iOS, JS, WASM)
- Dart/Flutter support with Flutter bindings

## Architecture

```mermaid
flowchart TB
    subgraph UI["UI / ViewModel"]
        dispatch["dispatch(Action)"]
        observe["state.collect { }"]
    end

    subgraph Store["Store"]
        channel["Channel〈Action〉"]

        subgraph processAction["processAction()"]
            subgraph middlewareChain["Middleware Chain"]
                userMiddleware["User Middlewares"]
                flowHolderMW["FlowHolderMiddleware"]
            end
            catchBlock[".catch { }"]
            errorProc["ErrorProcessor"]
        end

        reducer["Reducer"]
        stateFlow["StateFlow〈State〉"]
    end

    subgraph flowHolderMWDetail["FlowHolderMiddleware"]
        fhCheck{"FlowHolderAction?"}
        toFlow["toFlowAction()"]
        strategy["Apply Strategy"]
        passThrough["Pass Through"]
    end

    dispatch --> channel
    channel --> userMiddleware
    userMiddleware --> flowHolderMW
    flowHolderMW --> fhCheck
    fhCheck -->|Yes| strategy
    strategy --> toFlow
    toFlow -->|"emit(Action)"| fhCheck
    fhCheck -->|No| passThrough
    passThrough --> reducer
    middlewareChain -.->|Error| catchBlock
    catchBlock --> errorProc
    errorProc -.-> reducer
    reducer --> stateFlow
    stateFlow --> observe
```

| Component | Role |
|-----------|------|
| **Middleware** | Side effects (API calls, logging), action transformation |
| **FlowHolderMiddleware** | Processes FlowHolderAction with ExecutionStrategy (auto-added) |
| **ExecutionStrategy** | Control concurrent action processing (takeLatest, sequential, debounce, retry, etc.) |
| **FlowHolderAction** | Convert existing Flow to Action stream |
| **ErrorProcessor** | Catch errors and convert to Actions |
| **Reducer** | Pure function: (State, Action) → NewState |

### Action Flow

Actions can enter the pipeline in two ways:

**1. dispatch() - External Entry Point**

Called from UI or ViewModel to send actions into the Store:

```
dispatch(action) → Channel → Middleware Chain → Reducer → StateFlow
```

```kotlin
// From ViewModel or UI
store.dispatch(SearchAction("query"))
```

**2. emit() - Middleware Internal Emission**

Called within middleware processors to emit resulting actions:

```
emit(action) → (Remaining Middlewares) → Reducer → StateFlow
```

```kotlin
on<SearchAction> { state, action ->
    val results = api.search(action.query)
    emit(SearchResults(results))  // Goes to Reducer
}
```

**Key Differences:**

| | dispatch() | emit() |
|---|---|---|
| Called from | UI, ViewModel (external) | Middleware processor (internal) |
| Entry point | Channel (full pipeline) | Current position in Flow |
| Middleware | All middlewares process | Only remaining middlewares |

**Middleware Patterns:**

```kotlin
// Transform: Convert action to different action
on<FetchUser> { state, action ->
    val user = api.getUser(action.id)
    emit(UserLoaded(user))
}

// Pass through: Let action continue to Reducer
on<LogAction> { state, action ->
    logger.log(action)
    emit(action)
}

// Block: Don't emit to prevent Reducer processing
on<InvalidAction> { state, action ->
    // No emit - action stops here
}

// Multiple emissions
on<BatchAction> { state, action ->
    emit(StartLoading)
    val result = api.fetch()
    emit(DataLoaded(result))
    emit(StopLoading)
}
```

**Note:** Actions without a registered processor in middleware automatically pass through to the Reducer:

```kotlin
// Middleware only handles FetchUser
on<FetchUser> { state, action -> ... }

// Other actions (Increment, Reset, etc.) pass through directly to Reducer
store.dispatch(Increment)  // → Middleware (no processor) → Reducer
```

## Installation

### Kotlin (JitPack)

Add JitPack repository to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.chibimoons:flowdux:1.8.2")
}
```

### Dart

Add to your `pubspec.yaml`:

```yaml
dependencies:
  flowdux: ^0.2.3
```

### Flutter

Add to your `pubspec.yaml`:

```yaml
dependencies:
  flowdux: ^0.2.3
  flowdux_flutter: ^0.2.3
```

## Usage (Kotlin)

### Define State and Actions

```kotlin
data class CounterState(val count: Int = 0) : State

sealed class CounterAction : Action {
    object Increment : CounterAction()
    object Decrement : CounterAction()
    data class Add(val value: Int) : CounterAction()
}
```

### Create a Reducer

```kotlin
val counterReducer = buildReducer<CounterState, CounterAction> {
    on<CounterAction.Increment> { state, _ ->
        state.copy(count = state.count + 1)
    }
    on<CounterAction.Decrement> { state, _ ->
        state.copy(count = state.count - 1)
    }
    on<CounterAction.Add> { state, action ->
        state.copy(count = state.count + action.value)
    }
}
```

### Create a Middleware (Optional)

```kotlin
class LoggingMiddleware : Middleware<CounterState, CounterAction> {
    override val processors = buildProcessors {
        on<CounterAction.Increment> { state, action ->
            println("Incrementing from ${state.count}")
            emit(action)
        }
    }
}
```

### Create an ErrorProcessor

```kotlin
class CounterErrorProcessor : ErrorProcessor<CounterAction> {
    override fun process(throwable: Throwable): Flow<CounterAction> = flow {
        println("Error: ${throwable.message}")
    }
}
```

### Create and Use the Store

```kotlin
val store = createStore(
    initialState = CounterState(),
    reducer = counterReducer,
    middlewares = listOf(LoggingMiddleware()),
    errorProcessor = CounterErrorProcessor(),
    scope = viewModelScope
)

// Observe state
store.state.collect { state ->
    println("Current count: ${state.count}")
}

// Dispatch actions
store.dispatch(CounterAction.Increment)
store.dispatch(CounterAction.Add(10))
```

### Store Lifecycle

Always call `close()` when the store is no longer needed to release resources:

```kotlin
// In ViewModel
override fun onCleared() {
    store.close()
    super.onCleared()
}
```

**isClosed Property:**

Check `isClosed` before dispatching if there's a possibility the store may be closed:

```kotlin
if (!store.isClosed) {
    store.dispatch(action)
}
```

**Note:** Dispatching after `close()` is logged via `StoreLogger.onDispatchAfterClose()` and may indicate a bug in your application.

### FlowHolderAction (Wrap Existing Flow as Actions)

Use `FlowHolderAction` to wrap existing Flows (Repository, Socket) and convert them to Actions.
No side effects in the Action—just holds and transforms the Flow:

```kotlin
// FlowHolderAction wraps an existing Flow and converts to Flow<Action>
data class ObserveUser(
    private val userFlow: Flow<User>
) : UserAction, FlowHolderAction {
    override fun toFlowAction(): Flow<Action> =
        userFlow.map { user -> SetUser(user) }
}

// Usage: pass the Flow from Repository/Socket
val repositoryFlow = userRepository.getUser(123)  // Flow creation (cold)
store.dispatch(ObserveUser(repositoryFlow))       // Store collects it
// State updates: cached user -> fresh user from API
```

## Execution Strategies

FlowDux provides execution strategies to control how concurrent actions are processed in middleware.

| Category | Strategies | Purpose |
|----------|------------|---------|
| **Concurrency** | `takeLatest()`, `takeLeading()`, `sequential()` | How to handle concurrent executions |
| **Timing** | `debounce(duration)`, `throttle(duration)` | When to execute |
| **Resilience** | `retry(n)`, `retryWithBackoff(...)` | How to handle failures |

<details>
<summary><b>Concurrency Strategies</b></summary>

#### takeLatest()

Cancels previous processing when a new action arrives. Only the latest action's result is emitted.

```kotlin
on<AppAction.Search>(takeLatest()) { state, action ->
    val results = searchApi.search(action.query)
    emit(AppAction.SearchResults(results))
}
```

Use cases: Search, API refresh, pagination with pull-to-refresh

#### takeLeading()

Ignores new actions while one is still processing. Only the first action in a series executes.

```kotlin
on<AppAction.Submit>(takeLeading()) { state, action ->
    // Prevents duplicate submissions
    val result = api.submit(action.data)
    emit(AppAction.SubmitSuccess(result))
}
```

Use cases: Form submission, payment processing, preventing double-clicks

#### sequential()

Queues actions and processes them one at a time, preserving order. Unlike `takeLeading()` which ignores new actions, `sequential()` waits for the current action to complete before processing the next one.

```kotlin
on<AppAction.Save>(sequential()) { state, action ->
    // All save requests are processed in order
    api.save(action.data)
    emit(AppAction.SaveComplete(action.id))
}
```

Use cases: Sequential API calls, ordered form saves, FIFO task processing

</details>

<details>
<summary><b>Timing Strategies</b></summary>

#### debounce(duration)

Delays execution. If another action arrives before the delay completes, the previous action is canceled and the timer restarts.

```kotlin
on<AppAction.TextChanged>(debounce(500.milliseconds)) { state, action ->
    // Only saves after user stops typing for 500ms
    api.save(action.text)
    emit(AppAction.SaveComplete)
}
```

Use cases: Search autocomplete, autosave, input validation

#### throttle(duration)

Limits execution rate. Executes the first action immediately, then ignores subsequent actions until the time window passes.

```kotlin
on<AppAction.Scroll>(throttle(1000.milliseconds)) { state, action ->
    // Logs scroll position at most once per second
    analytics.logScroll(action.position)
    emit(action)
}
```

Use cases: Analytics events, scroll handling, rate limiting

</details>

<details>
<summary><b>Resilience Strategies</b></summary>

#### retry(maxAttempts)

Retries the processor execution on failure.

```kotlin
on<AppAction.FetchData>(retry(3)) { state, action ->
    // Retries up to 3 times on failure
    val data = api.fetchData(action.id)
    emit(AppAction.FetchSuccess(data))
}

// With custom retry condition
on<AppAction.FetchData>(retry(3) { e -> e is IOException }) { state, action ->
    // Only retries on IOException
}
```

#### retryWithBackoff(maxAttempts, initialDelay, ...)

Retries with exponential backoff delay between attempts.

```kotlin
on<AppAction.FetchData>(retryWithBackoff(
    maxAttempts = 5,
    initialDelay = 100.milliseconds,
    maxDelay = 10.seconds,
    factor = 2.0,      // Exponential multiplier
    jitter = 0.1       // Random jitter to prevent thundering herd
)) { state, action ->
    val data = api.fetchData(action.id)
    emit(AppAction.FetchSuccess(data))
}
```

Use cases: Network error recovery, transient server errors, rate limiting

</details>

### Strategy Groups

Use `group` to share a strategy instance across multiple action types. Actions within the same group will coordinate their execution (e.g., one action can cancel another):

```kotlin
override val processors = buildProcessors {
    // SearchAction and RefreshAction share the same takeLatest instance
    // Dispatching RefreshAction will cancel an in-progress SearchAction
    group(takeLatest()) {
        on<SearchAction> { state, action ->
            val results = searchApi.search(action.query)
            emit(SearchResults(results))
        }
        on<RefreshAction> { state, action ->
            val results = searchApi.refresh()
            emit(SearchResults(results))
        }
    }
}
```

### Strategy Chaining

Combine strategies from different categories using the `then` operator:

```kotlin
// Debounce input, then cancel previous search, then retry on failure
on<AppAction.Search>(
    debounce(300.milliseconds) then takeLatest() then retry(3)
) { state, action ->
    val results = searchApi.search(action.query)
    emit(AppAction.SearchResults(results))
}
```

**Rules:**
- Strategies from different categories can be chained
- Strategies from the same category cannot be chained (throws exception)
- Chained strategies work with `group()` as well

## Logging

Use `DebugStoreLogger` for development debugging:

```kotlin
val store = createStore(
    initialState = CounterState(),
    reducer = counterReducer,
    logger = DebugStoreLogger("MyStore")
)
```

> **Warning:** `DebugStoreLogger` prints the entire State and Action objects via `println()`. Do not use in production as it may expose sensitive information (tokens, passwords, personal data). Use `NoOpStoreLogger` (default) or implement a custom `StoreLogger` with proper filtering for production.

## Time Travel Debugging

Time travel debugging is available as a separate module:

```kotlin
// build.gradle.kts
implementation("com.github.chibimoons.flowdux:flowdux-timetravel:1.8.2")
```

```kotlin
import io.flowdux.timetravel.createTimeTravelStore

val store = createTimeTravelStore(
    initialState = CounterState(),
    reducer = counterReducer,
    middlewares = listOf(LoggingMiddleware()),
    maxHistorySize = 100        // Optional: limit history size (default: 100)
)

// Use like a regular store
store.dispatch(CounterAction.Increment)
store.dispatch(CounterAction.Add(10))

// Access history
store.history.forEach { snapshot ->
    println("Index: ${snapshot.index}, State: ${snapshot.currentState}, Action: ${snapshot.action}")
}

// Navigate through time
store.undo()           // Go to previous state
store.redo()           // Go to next state
store.jumpTo(0)        // Jump to initial state
store.reset()          // Alias for jumpTo(0)

// Check navigation availability
if (store.canUndo) store.undo()
if (store.canRedo) store.redo()

// Clear history (keeps current state as new initial)
store.clear()
```

**StateSnapshot Properties:**

| Property | Description |
|----------|-------------|
| `index` | Position in history (0 = initial) |
| `action` | Action that caused this state (null for initial) |
| `previousState` | State before the action (null for initial) |
| `currentState` | State after the action |
| `timestamp` | When the state change occurred |

**Restoring History:**

You can restore a previous session's history using a separate overload:

```kotlin
// Save history (e.g., to JSON)
val savedHistory = store.history

// Later, restore from saved history
val restoredStore = createTimeTravelStore(
    initialHistory = savedHistory,  // Restores state and history
    reducer = counterReducer
)
// restoredStore starts at the last state in savedHistory
```

**Branching Behavior:**

When dispatching from a past state (after `undo()` or `jumpTo()`), future history is discarded:

```kotlin
// History: [0] -> [1] -> [2] -> [3]
store.jumpTo(1)           // Now at state [1]
store.dispatch(NewAction) // History becomes: [0] -> [1] -> [new]
                          // States [2] and [3] are discarded
```

## Usage (Dart/Flutter)

### Define State and Actions

```dart
// State
class CounterState {
  final int count;
  CounterState(this.count);

  CounterState copyWith({int? count}) => CounterState(count ?? this.count);
}

// Actions
class IncrementAction implements Action {}
class DecrementAction implements Action {}
class AddAction implements Action {
  final int value;
  AddAction(this.value);
}
```

### Create a Reducer

```dart
final counterReducer = ReducerBuilder<CounterState, Action>()
  ..on<IncrementAction>((state, _) => state.copyWith(count: state.count + 1))
  ..on<DecrementAction>((state, _) => state.copyWith(count: state.count - 1))
  ..on<AddAction>((state, action) => state.copyWith(count: state.count + action.value));
```

### Create a Store

```dart
final store = createStore<CounterState, Action>(
  initialState: CounterState(0),
  reducer: counterReducer.build(),
);

// Dispatch actions
store.dispatch(IncrementAction());

// Listen to state changes
store.state.listen((state) => print('Count: ${state.count}'));
```

### Middleware with Execution Strategies

```dart
class SearchMiddleware extends Middleware<AppState, Action> {
  SearchMiddleware() {
    // takeLatest cancels previous search when new one arrives
    apply(takeLatest()).on<SearchAction>((state, action) async* {
      final results = await api.search(action.query);
      yield SearchResultsAction(results);
    });
  }
}
```

### FlowHolderAction

Use `FlowHolderAction` to wrap existing Streams (Repository, Socket) and convert them to Actions.
No side effects in the Action—just holds and transforms the Stream:

```dart
// FlowHolderAction wraps an existing Stream and converts to Stream<Action>
class ObserveUserAction with FlowHolderAction {
  final Stream<User> userStream;

  ObserveUserAction(this.userStream);

  @override
  Stream<Action> toStreamAction() {
    return userStream.map((user) => SetUserAction(user));
  }

  // Default: TakeLatest strategy (auto-cancels previous)
  // Override for concurrent execution:
  // @override
  // ExecutionStrategy get strategy => concurrent();
}

// Usage: pass the Stream from Repository/Socket
final repositoryStream = userRepository.getUser(123);  // Stream creation (cold)
store.dispatch(ObserveUserAction(repositoryStream));   // Store collects it
```

### Flutter Integration

```dart
// Provide store to widget tree
StoreProvider<AppState, Action>(
  store: store,
  child: MyApp(),
)

// Consume state in widgets
StoreConsumer<AppState, Action>(
  builder: (context, store, state) {
    return Text('Count: ${state.count}');
  },
)

// Or use selector for specific state
StoreSelector<AppState, Action, int>(
  selector: (state) => state.count,
  builder: (context, store, count) {
    return Text('Count: $count');
  },
)

// Listen to state changes for side effects (navigation, snackbar, etc.)
StoreListener<AppState, Action>(
  listenWhen: (previous, current) => current.navigateTo != null,
  listener: (context, store, state) {
    Navigator.of(context).pushNamed(state.navigateTo!);
  },
  child: MyWidget(),
)
```

### Run Dart Tests

```bash
cd dart/flowdux && dart test
```

### Run Flutter Example

```bash
cd dart/flowdux_flutter/example && flutter run
```

## Sample Apps

### Run JVM Console Sample

```bash
./gradlew :kotlin:sample-jvm:run
```

Output:
```
=== Flowdux Sample: Counter ===

State: count = 0
> Dispatching Increment
State: count = 1
...
> Dispatching ObserveCount - FlowHolderAction
  (Repository Flow emits: cache -> api)
State: count = 10 [cache]
State: count = 42 [api]
...

==================================================
=== Execution Strategy Examples ===
==================================================

> takeLatest: Rapid search (only latest completes)
  Dispatching Search('a'), Search('ab'), Search('abc') rapidly...
    [takeLatest] Searching for: a
    [takeLatest] Searching for: ab
    [takeLatest] Searching for: abc
    [takeLatest] Search completed: abc
  Result: Only 'abc' search completed!

> debounce: Wait 200ms after last input
  Dispatching FetchData rapidly...
    [debounce] Fetching data: 3
  Result: Only last FetchData executed after 200ms quiet period!

> takeLeading: Prevent double form submission
  Dispatching SubmitForm 3 times rapidly...
    [takeLeading] Processing form submission...
    [takeLeading] Form submitted!
  Result: Only first submission processed, others ignored!

> Strategy Group: LoadUser and RefreshUser share takeLatest
  Dispatching LoadUser, then RefreshUser (cancels LoadUser)...
    [group] Loading user: 123
    [group] Refreshing user...
    [group] User refreshed!
  Result: LoadUser was canceled, only RefreshUser completed!
```

### Build Android Sample

```bash
./gradlew :kotlin:sample-android:assembleDebug
```

APK location: `kotlin/sample-android/build/outputs/apk/debug/sample-android-debug.apk`

### Build KMM Sample (Android)

```bash
./gradlew :kotlin:sample-shared:androidApp:assembleDebug
```

APK location: `kotlin/sample-shared/androidApp/build/outputs/apk/debug/androidApp-debug.apk`

### Build KMM Sample (iOS)

**Prerequisites:** Xcode 15+ with command line tools

```bash
# Build shared framework
./gradlew :kotlin:sample-shared:shared:linkDebugFrameworkIosSimulatorArm64

# Build iOS app
xcodebuild -project kotlin/sample-shared/iosApp/iosApp.xcodeproj \
  -target iosApp -sdk iphonesimulator -arch arm64 build
```

App location: `kotlin/sample-shared/iosApp/build/Debug-iphonesimulator/iosApp.app`

### KMM Sample Structure

```
kotlin/sample-shared/
├── shared/           # Shared Kotlin code (commonMain)
│   └── CounterStore  # Shared business logic
├── androidApp/       # Android UI (Compose)
└── iosApp/           # iOS UI (SwiftUI) - see iosApp/README.md
```

### Run Web (JavaScript) Sample

```bash
./gradlew :kotlin:sample-web:jsBrowserDevelopmentRun
```

Opens browser at `http://localhost:8080` with an interactive Counter app.

### Run WebAssembly (WASM) Sample

```bash
./gradlew :kotlin:sample-wasm:wasmJsBrowserDevelopmentRun
```

Opens browser at `http://localhost:8080` with an interactive Counter app (WASM version).

## Platform Support

### Kotlin Multiplatform

| Platform | Status | Sample |
|----------|--------|--------|
| JVM | ✅ | `kotlin/sample-jvm` |
| Android | ✅ | `kotlin/sample-android`, `kotlin/sample-shared/androidApp` |
| iOS | ✅ | `kotlin/sample-shared/iosApp` |
| JavaScript | ✅ | `kotlin/sample-web` |
| WebAssembly | ✅ | `kotlin/sample-wasm` |

### Dart / Flutter

| Platform | Status | Sample |
|----------|--------|--------|
| Dart | ✅ | `dart/flowdux` |
| Flutter | ✅ | `dart/flowdux_flutter` |

## License

```
Copyright 2024 Flowdux Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
