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
include(":flowdux")
// Exclude other modules for testing environment constraints
// include(":flowdux-timetravel")
// include(":sample-jvm")
// include(":sample-android")
// include(":sample-shared:shared")
// include(":sample-shared:androidApp")
// include(":sample-web")
// include(":sample-wasm")
