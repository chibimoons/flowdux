plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("flowdux.publish-conventions")
}

mavenPublishing {
    coordinates("io.github.chibimoons", "flowdux-remote-ktor", providers.gradleProperty("flowdux.version").get())
    pom {
        name.set("Flowdux Remote Ktor")
        description.set("Ktor WebSocket implementation for Flowdux remote modules")
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
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kotlin:flowdux-remote-client"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core.multiplatform)
            implementation(libs.ktor.client.websockets.multiplatform)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio.multiplatform)
            api(project(":kotlin:flowdux-remote-server"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.server.core)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.websockets)
        }
        if (!isJitPack) {
            iosMain.dependencies {
                implementation(libs.ktor.client.darwin)
            }
            jsMain.dependencies {
                implementation(libs.ktor.client.js)
            }
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
