pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "flowdux-root"

// ── Core ──
include(":kotlin:flowdux")

// ── Time Travel ──
include(":kotlin:flowdux-timetravel")
project(":kotlin:flowdux-timetravel").projectDir = file("kotlin/timetravel")

// ── Remote ──
include(":kotlin:flowdux-remote-core")
project(":kotlin:flowdux-remote-core").projectDir = file("kotlin/remote/core")

include(":kotlin:flowdux-remote-client")
project(":kotlin:flowdux-remote-client").projectDir = file("kotlin/remote/client")

include(":kotlin:flowdux-remote-server")
project(":kotlin:flowdux-remote-server").projectDir = file("kotlin/remote/server")

include(":kotlin:flowdux-remote-ktor")
project(":kotlin:flowdux-remote-ktor").projectDir = file("kotlin/remote/ktor")

include(":kotlin:flowdux-remote-serialization")
project(":kotlin:flowdux-remote-serialization").projectDir = file("kotlin/remote/serialization")

// ── Samples ──
include(":kotlin:sample-jvm")
project(":kotlin:sample-jvm").projectDir = file("kotlin/samples/jvm")

include(":kotlin:sample-android")
project(":kotlin:sample-android").projectDir = file("kotlin/samples/android")

include(":kotlin:sample-web")
project(":kotlin:sample-web").projectDir = file("kotlin/samples/web")

include(":kotlin:sample-wasm")
project(":kotlin:sample-wasm").projectDir = file("kotlin/samples/wasm")

include(":kotlin:sample-shared:shared")
project(":kotlin:sample-shared").projectDir = file("kotlin/samples/shared")
project(":kotlin:sample-shared:shared").projectDir = file("kotlin/samples/shared/shared")

include(":kotlin:sample-shared:androidApp")
project(":kotlin:sample-shared:androidApp").projectDir = file("kotlin/samples/shared/androidApp")

include(":kotlin:sample-remote-chat:shared")
project(":kotlin:sample-remote-chat").projectDir = file("kotlin/samples/remote-chat")
project(":kotlin:sample-remote-chat:shared").projectDir = file("kotlin/samples/remote-chat/shared")

include(":kotlin:sample-remote-chat:server")
project(":kotlin:sample-remote-chat:server").projectDir = file("kotlin/samples/remote-chat/server")

include(":kotlin:sample-remote-chat:client")
project(":kotlin:sample-remote-chat:client").projectDir = file("kotlin/samples/remote-chat/client")

// ── Benchmark ──
include(":kotlin:flowdux-benchmark")
project(":kotlin:flowdux-benchmark").projectDir = file("kotlin/benchmark")
