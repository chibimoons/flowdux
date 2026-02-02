plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

group = "io.flowdux.sample.chat"
version = "1.0.0"

kotlin {
    jvm()
    js(IR) {
        browser()
        nodejs()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kotlin:flowdux"))
            implementation(project(":kotlin:flowdux-remote-core"))
            api(project(":kotlin:flowdux-remote-serialization"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
