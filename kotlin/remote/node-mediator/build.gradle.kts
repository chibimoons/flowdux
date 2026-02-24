plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    id("flowdux.publish-conventions")
}

mavenPublishing {
    coordinates("io.github.chibimoons", "flowdux-remote-node-mediator", providers.gradleProperty("flowdux.version").get())
    pom {
        name.set("Flowdux Remote Node Mediator")
        description.set("Node mediator for horizontal scaling across multiple server nodes")
    }
}

// JitPack only publishes JVM artifacts to avoid variant resolution issues for JVM/Android consumers
val isJitPack = System.getenv("JITPACK") == "true"

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
            api(project(":kotlin:flowdux"))
            api(project(":kotlin:flowdux-remote-client"))
            api(project(":kotlin:flowdux-remote-server"))
            implementation(project(":kotlin:flowdux-remote-serialization"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
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
