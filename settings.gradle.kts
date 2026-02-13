pluginManagement {
    includeBuild("build-logic")
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

include(":kotlin:flowdux-remote-auth")
project(":kotlin:flowdux-remote-auth").projectDir = file("kotlin/remote/auth")

include(":kotlin:flowdux-remote-multiplexer")
project(":kotlin:flowdux-remote-multiplexer").projectDir = file("kotlin/remote/multiplexer")

include(":kotlin:flowdux-remote-node-mediator")
project(":kotlin:flowdux-remote-node-mediator").projectDir = file("kotlin/remote/node-mediator")

// ── Samples: flowdux ──
include(":kotlin:sample-jvm")
project(":kotlin:sample-jvm").projectDir = file("kotlin/samples/flowdux/jvm")

include(":kotlin:sample-android")
project(":kotlin:sample-android").projectDir = file("kotlin/samples/flowdux/android")

include(":kotlin:sample-web")
project(":kotlin:sample-web").projectDir = file("kotlin/samples/flowdux/web")

include(":kotlin:sample-wasm")
project(":kotlin:sample-wasm").projectDir = file("kotlin/samples/flowdux/wasm")

include(":kotlin:sample-kmm:shared")
project(":kotlin:sample-kmm").projectDir = file("kotlin/samples/flowdux/kmm")
project(":kotlin:sample-kmm:shared").projectDir = file("kotlin/samples/flowdux/kmm/shared")

include(":kotlin:sample-kmm:androidApp")
project(":kotlin:sample-kmm:androidApp").projectDir = file("kotlin/samples/flowdux/kmm/androidApp")

// ── Samples: flowdux-remote ──
include(":kotlin:sample-remote:shared")
project(":kotlin:sample-remote").projectDir = file("kotlin/samples/flowdux-remote")
project(":kotlin:sample-remote:shared").projectDir = file("kotlin/samples/flowdux-remote/shared")

include(":kotlin:sample-remote-simple:server")
project(":kotlin:sample-remote-simple").projectDir = file("kotlin/samples/flowdux-remote/simple")
project(":kotlin:sample-remote-simple:server").projectDir = file("kotlin/samples/flowdux-remote/simple/server")

include(":kotlin:sample-remote-simple:client")
project(":kotlin:sample-remote-simple:client").projectDir = file("kotlin/samples/flowdux-remote/simple/client")

include(":kotlin:sample-remote-multi:server")
project(":kotlin:sample-remote-multi").projectDir = file("kotlin/samples/flowdux-remote/multi-client")
project(":kotlin:sample-remote-multi:server").projectDir = file("kotlin/samples/flowdux-remote/multi-client/server")

include(":kotlin:sample-remote-multi:client")
project(":kotlin:sample-remote-multi:client").projectDir = file("kotlin/samples/flowdux-remote/multi-client/client")

include(":kotlin:sample-remote-multiroom:server")
project(":kotlin:sample-remote-multiroom").projectDir = file("kotlin/samples/flowdux-remote/multi-room")
project(":kotlin:sample-remote-multiroom:server").projectDir = file("kotlin/samples/flowdux-remote/multi-room/server")

include(":kotlin:sample-remote-multiroom:client")
project(":kotlin:sample-remote-multiroom:client").projectDir = file("kotlin/samples/flowdux-remote/multi-room/client")

include(":kotlin:sample-remote-scaling:server")
project(":kotlin:sample-remote-scaling").projectDir = file("kotlin/samples/flowdux-remote/scaling")
project(":kotlin:sample-remote-scaling:server").projectDir = file("kotlin/samples/flowdux-remote/scaling/server")

include(":kotlin:sample-remote-poker:shared")
project(":kotlin:sample-remote-poker").projectDir = file("kotlin/samples/flowdux-remote/poker")
project(":kotlin:sample-remote-poker:shared").projectDir = file("kotlin/samples/flowdux-remote/poker/shared")

include(":kotlin:sample-remote-poker:server")
project(":kotlin:sample-remote-poker:server").projectDir = file("kotlin/samples/flowdux-remote/poker/server")

include(":kotlin:sample-remote-poker:client")
project(":kotlin:sample-remote-poker:client").projectDir = file("kotlin/samples/flowdux-remote/poker/client")

include(":kotlin:sample-remote-multiplexer:shared")
project(":kotlin:sample-remote-multiplexer").projectDir = file("kotlin/samples/flowdux-remote/multiplexer")
project(":kotlin:sample-remote-multiplexer:shared").projectDir = file("kotlin/samples/flowdux-remote/multiplexer/shared")

include(":kotlin:sample-remote-multiplexer:server")
project(":kotlin:sample-remote-multiplexer:server").projectDir = file("kotlin/samples/flowdux-remote/multiplexer/server")

include(":kotlin:sample-remote-multiplexer:client")
project(":kotlin:sample-remote-multiplexer:client").projectDir = file("kotlin/samples/flowdux-remote/multiplexer/client")

include(":kotlin:sample-remote-node-mediator:shared")
project(":kotlin:sample-remote-node-mediator").projectDir = file("kotlin/samples/flowdux-remote/node-mediator")
project(":kotlin:sample-remote-node-mediator:shared").projectDir = file("kotlin/samples/flowdux-remote/node-mediator/shared")

include(":kotlin:sample-remote-node-mediator:central")
project(":kotlin:sample-remote-node-mediator:central").projectDir = file("kotlin/samples/flowdux-remote/node-mediator/central")

include(":kotlin:sample-remote-node-mediator:node")
project(":kotlin:sample-remote-node-mediator:node").projectDir = file("kotlin/samples/flowdux-remote/node-mediator/node")

include(":kotlin:sample-remote-node-mediator:client")
project(":kotlin:sample-remote-node-mediator:client").projectDir = file("kotlin/samples/flowdux-remote/node-mediator/client")

// ── Samples: flowdux-remote (auth) ──
include(":kotlin:sample-remote-auth:server")
project(":kotlin:sample-remote-auth").projectDir = file("kotlin/samples/flowdux-remote/auth")
project(":kotlin:sample-remote-auth:server").projectDir = file("kotlin/samples/flowdux-remote/auth/server")

include(":kotlin:sample-remote-auth:client")
project(":kotlin:sample-remote-auth:client").projectDir = file("kotlin/samples/flowdux-remote/auth/client")

// ── Benchmark ──
include(":kotlin:flowdux-benchmark")
project(":kotlin:flowdux-benchmark").projectDir = file("kotlin/benchmark")
