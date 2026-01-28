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
include(":kotlin:flowdux")
include(":kotlin:flowdux-timetravel")
include(":kotlin:flowdux-remote-core")
include(":kotlin:flowdux-remote-client")
include(":kotlin:flowdux-remote-server")
include(":kotlin:flowdux-remote-ktor")
include(":kotlin:sample-jvm")
include(":kotlin:sample-android")
include(":kotlin:sample-shared:shared")
include(":kotlin:sample-shared:androidApp")
include(":kotlin:sample-web")
include(":kotlin:sample-wasm")
include(":kotlin:sample-remote-chat:shared")
include(":kotlin:sample-remote-chat:server")
include(":kotlin:sample-remote-chat:client")
