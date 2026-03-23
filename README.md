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
- Real-time remote state synchronization over WebSocket (flowdux-remote)
- Type-safe shared action contracts (`ServerSharedAction` / `ClientSharedAction`)
- Automatic serialization with `kotlinx.serialization`

## Installation

### Kotlin Multiplatform (Maven Central)

Add the dependency to your KMP project's `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.chibimoons:flowdux:1.18.0")
        }
    }
}
```

Gradle automatically resolves the correct artifact for each target (JVM, iOS, JS, WASM).

#### Single-platform (JVM / Android)

If your project is not KMP, add the dependency directly:

```kotlin
dependencies {
    implementation("io.github.chibimoons:flowdux:1.18.0")
}
```

#### Remote Modules (WebSocket)

For real-time client-server state sync, add the relevant modules:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            // Shared action markers (ServerSharedAction, ClientSharedAction)
            implementation("io.github.chibimoons:flowdux-remote-core:1.18.0")
            // Client middleware (SyncMiddleware)
            implementation("io.github.chibimoons:flowdux-remote-client:1.18.0")
            // Server middleware (SingleClientSyncMiddleware, MultiClientSyncMiddleware)
            implementation("io.github.chibimoons:flowdux-remote-server:1.18.0")
            // kotlinx.serialization codecs (ActionCodec, MessageCodec)
            implementation("io.github.chibimoons:flowdux-remote-serialization:1.18.0")
            // Ktor WebSocket transport (JVM, iOS, JS — WASM not supported)
            implementation("io.github.chibimoons:flowdux-remote-ktor:1.18.0")
        }
    }
}
```

### Kotlin (JitPack — legacy)

<details>
<summary>JitPack is still supported but Maven Central is recommended</summary>

Add JitPack repository to your `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

```kotlin
dependencies {
    implementation("com.github.chibimoons:flowdux:1.10.0")
}
```

</details>

### Dart

Add to your `pubspec.yaml`:

```yaml
dependencies:
  flowdux: ^0.3.2
```

### Flutter

Add to your `pubspec.yaml`:

```yaml
dependencies:
  flowdux: ^0.3.2
  flowdux_flutter: ^0.3.2
```

## Quick Start (Kotlin)

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

## Documentation

| Topic | Description |
|-------|-------------|
| [Architecture](docs/guide/architecture.md) | Store pipeline, middleware chain, action flow |
| [Execution Strategies](docs/guide/execution-strategies.md) | takeLatest, debounce, throttle, retry, groups, chaining |
| [Remote (WebSocket)](docs/guide/remote.md) | Client-server state sync, authentication, server patterns |
| [Time Travel](docs/guide/timetravel.md) | Undo/redo, state history, branching |
| [Sample Apps](docs/guide/samples.md) | How to run each sample |
| [Dart/Flutter](dart/flowdux/README.md) | Dart API, Flutter widgets, stream integration |

## Version Compatibility

### Kotlin Version

FlowDux 1.17.0 is built with **Kotlin 2.2.10**. For best compatibility, use the same Kotlin version in your project:

```kotlin
plugins {
    kotlin("multiplatform") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"  // if using remote modules
}
```

Using a different Kotlin version may cause compilation errors, especially on iOS and JS targets.

### kotlinx.serialization

When using `flowdux-remote` modules with `kotlinx.serialization`, use version **1.7.3** or compatible:

```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
```

### flowdux-remote-ktor Platform Support

| Platform | Status |
|----------|--------|
| JVM | ✅ |
| iOS | ✅ |
| JS | ✅ |
| WASM | ❌ (Ktor client not available) |

## Platform Support

### Kotlin Multiplatform

| Platform | Status | Sample |
|----------|--------|--------|
| JVM | ✅ | `kotlin/samples/jvm` |
| JVM (Remote/WebSocket) | ✅ | `kotlin/samples/remote-chat` |
| Android | ✅ | `kotlin/samples/android`, `kotlin/samples/shared/androidApp` |
| iOS | ✅ | `kotlin/samples/shared/iosApp` |
| JavaScript | ✅ | `kotlin/samples/web` |
| WebAssembly | ✅ | `kotlin/samples/wasm` |

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
