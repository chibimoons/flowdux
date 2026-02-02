plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
            }
            binaries.executable()
        }
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":kotlin:flowdux"))
            implementation(project(":kotlin:flowdux-remote-core"))
            implementation(project(":kotlin:flowdux-remote-client"))
            implementation(project(":kotlin:flowdux-remote-serialization"))
            implementation(project(":kotlin:sample-remote:shared"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
