plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    `maven-publish`
}

group = "io.flowdux"
version = "1.8.2"

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
            implementation(project(":kotlin:flowdux"))
            implementation(project(":kotlin:flowdux-remote-core"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
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

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                name.set("Flowdux Remote Serialization")
                description.set("kotlinx.serialization-based ActionCodec for Flowdux remote state management")
                url.set("https://github.com/chibimoons/flowdux")

                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }
        }
    }
}
