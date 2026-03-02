plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("flowdux.publish-conventions")
}

mavenPublishing {
    coordinates("io.github.chibimoons", "flowdux-timetravel", providers.gradleProperty("flowdux.version").get())
    pom {
        name.set("Flowdux Time Travel")
        description.set("Time travel debugging extension for Flowdux")
    }
}

// JitPack only publishes JVM artifacts to avoid variant resolution issues for JVM/Android consumers
val isJitPack = providers.environmentVariable("JITPACK").map { it == "true" }.getOrElse(false)

kotlin {
    jvm()

    if (!isJitPack) {
        // iOS
        iosX64()
        iosArm64()
        iosSimulatorArm64()

        // JavaScript
        js(IR) {
            browser()
            nodejs()
        }

        // WebAssembly
        @OptIn(org.jetbrains.kotlin.gradle.targets.js.dsl.ExperimentalWasmDsl::class)
        wasmJs {
            browser()
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kotlin:flowdux"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.turbine)
        }
    }

    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
                }
            }
        }
    }
}
