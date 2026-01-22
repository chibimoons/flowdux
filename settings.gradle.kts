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
include(":kotlin:sample-jvm")
include(":kotlin:sample-android")
include(":kotlin:sample-shared:shared")
include(":kotlin:sample-shared:androidApp")
include(":kotlin:sample-web")
include(":kotlin:sample-wasm")
