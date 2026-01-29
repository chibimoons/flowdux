# Sample Apps

## Run JVM Console Sample

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

## Run Remote Chat Sample (WebSocket)

Start the server:
```bash
./gradlew :kotlin:sample-remote-chat:server:run
```

In a separate terminal, start the client:
```bash
./gradlew :kotlin:sample-remote-chat:client:run
```

Output:
```
=== Flowdux Remote Chat Demo ===

[System] Alice joined the room
[Alice] Hello everyone!
[System] Bob joined the room
[Bob] Hi Alice!

--- Final State ---
Users online: [Alice, Bob]
Message history:
  [Alice] Hello everyone!
  [Bob] Hi Alice!

=== Demo Complete ===
```

## Build Android Sample

```bash
./gradlew :kotlin:sample-android:assembleDebug
```

APK location: `kotlin/samples/android/build/outputs/apk/debug/sample-android-debug.apk`

## Build KMM Sample (Android)

```bash
./gradlew :kotlin:sample-shared:androidApp:assembleDebug
```

APK location: `kotlin/samples/shared/androidApp/build/outputs/apk/debug/androidApp-debug.apk`

## Build KMM Sample (iOS)

**Prerequisites:** Xcode 15+ with command line tools

```bash
# Build shared framework
./gradlew :kotlin:sample-shared:shared:linkDebugFrameworkIosSimulatorArm64

# Build iOS app
xcodebuild -project kotlin/samples/shared/iosApp/iosApp.xcodeproj \
  -target iosApp -sdk iphonesimulator -arch arm64 build
```

App location: `kotlin/samples/shared/iosApp/build/Debug-iphonesimulator/iosApp.app`

## KMM Sample Structure

```
kotlin/samples/shared/
├── shared/           # Shared Kotlin code (commonMain)
│   └── CounterStore  # Shared business logic
├── androidApp/       # Android UI (Compose)
└── iosApp/           # iOS UI (SwiftUI) - see iosApp/README.md
```

## Run Web (JavaScript) Sample

```bash
./gradlew :kotlin:sample-web:jsBrowserDevelopmentRun
```

Opens browser at `http://localhost:8080` with an interactive Counter app.

## Run WebAssembly (WASM) Sample

```bash
./gradlew :kotlin:sample-wasm:wasmJsBrowserDevelopmentRun
```

Opens browser at `http://localhost:8080` with an interactive Counter app (WASM version).
