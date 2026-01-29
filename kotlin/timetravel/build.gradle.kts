plugins {
    alias(libs.plugins.kotlinMultiplatform)
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

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                name.set("Flowdux Time Travel")
                description.set("Time travel debugging extension for Flowdux")
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

