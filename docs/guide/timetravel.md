# Time Travel Debugging

Time travel debugging is available as a separate module.

## Installation

```kotlin
// build.gradle.kts (KMP)
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.chibimoons:flowdux-timetravel:1.17.0")
        }
    }
}

// build.gradle.kts (JVM / Android)
dependencies {
    implementation("io.github.chibimoons:flowdux-timetravel:1.17.0")
}
```

## Usage

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

## StateSnapshot Properties

| Property | Description |
|----------|-------------|
| `index` | Position in history (0 = initial) |
| `action` | Action that caused this state (null for initial) |
| `previousState` | State before the action (null for initial) |
| `currentState` | State after the action |
| `timestamp` | When the state change occurred |

## Restoring History

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

## Branching Behavior

When dispatching from a past state (after `undo()` or `jumpTo()`), future history is discarded:

```kotlin
// History: [0] -> [1] -> [2] -> [3]
store.jumpTo(1)           // Now at state [1]
store.dispatch(NewAction) // History becomes: [0] -> [1] -> [new]
                          // States [2] and [3] are discarded
```
