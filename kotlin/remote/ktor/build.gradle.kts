plugins {
    alias(libs.plugins.kotlinMultiplatform)
    `maven-publish`
}

group = "io.flowdux"
version = "1.0.0"

// JitPack only publishes JVM artifacts to avoid variant resolution issues for JVM/Android consumers
val isJitPack = System.getenv("JITPACK") == "true"

kotlin {
    jvm()

    if (!isJitPack) {
        // iOS
        iosX64()
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kotlin:flowdux-remote-client"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core.multiplatform)
            implementation(libs.ktor.client.websockets.multiplatform)
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
                name.set("Flowdux Remote Ktor")
                description.set("Ktor WebSocket implementation for Flowdux remote modules")
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
