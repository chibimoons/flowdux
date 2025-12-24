# iOS App Setup

## Prerequisites

1. Xcode 15+
2. CocoaPods or SPM (optional)

## Build Shared Framework

```bash
cd sample-shared
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

## Setup Xcode Project

1. Create new Xcode project (iOS App with SwiftUI)
2. Add the framework:
   - Go to Project Settings → General → Frameworks
   - Add `shared.framework` from:
     `sample-shared/shared/build/bin/iosSimulatorArm64/debugFramework/`
3. Copy Swift files from `iosApp/` to your project

## Run

Build and run from Xcode on simulator or device.
