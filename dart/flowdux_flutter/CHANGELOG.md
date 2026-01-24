# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.4] - 2026-01-24

### Changed

- Improve README documentation with comprehensive examples
- Fix flowdux link to point to pub.dev instead of relative path
- Fix license badge (MIT -> Apache 2.0)
- Update installation section with both flowdux and flowdux_flutter dependencies

## [0.2.3] - 2026-01-24

### Changed

- Update flowdux dependency to ^0.2.3 (includes distinct state emission fix)

## [0.2.2] - 2026-01-23

### Changed

- Update flowdux dependency to ^0.2.2 (includes middleware blocking fix)

## [0.2.1] - 2026-01-23

### Changed

- Fix LICENSE file format to standard Apache 2.0 template for pub.dev recognition
- Update flowdux dependency to ^0.2.1

## [0.2.0] - 2026-01-23

### Changed

- Fix LICENSE format for pub.dev OSI license recognition
- Update flowdux dependency to ^0.2.0

## [0.1.0] - 2024-01-21

### Added

- `StoreProvider` - InheritedWidget for providing store to widget tree
  - `StoreProvider.of<S, A>()` - Get store from context (throws if not found)
  - `StoreProvider.maybeOf<S, A>()` - Get store from context (returns null if not found)
  - Context extensions: `context.store<S, A>()` and `context.dispatch<S, A>(action)`

- `StoreBuilder` - Widget that rebuilds when store state changes
  - Optional `store` parameter for direct store injection
  - Optional `selector` for optimized rebuilds

- `StoreSelector` - Optimized widget that only rebuilds when selected value changes
  - Type-safe selector function
  - Efficient comparison to prevent unnecessary rebuilds

- `StoreConsumer` - Combined builder and listener widget
  - Access to both store and state in builder
  - Optional `listener` for side effects

- `StoreListener` - Widget for side effects without rebuilding
  - `listenWhen` predicate for conditional listening
  - Callbacks are scheduled after frame to avoid build-time navigation issues

### Notes
- Requires Flutter >= 3.10.0
- Requires Dart SDK >= 3.0.0
- Depends on flowdux ^0.1.0
