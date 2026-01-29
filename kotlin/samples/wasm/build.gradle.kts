plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalWasmDsl::class)
    wasmJs {
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
        val wasmJsMain by getting {
            dependencies {
                implementation(project(":kotlin:flowdux"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}
