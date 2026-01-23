plugins {
    alias(libs.plugins.kotlinMultiplatform)
    `maven-publish`
}

group = "io.flowdux"
version = "1.7.0"

kotlin {
    jvm()

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

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kotlin:flowdux"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.turbine)
            implementation(libs.junit.jupiter)
        }
    }

    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "17"
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
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

// Disable Gradle Module Metadata for JitPack to avoid variant resolution issues
// JitPack can't properly handle KMP's multi-platform variant metadata,
// causing JVM consumers to fail when trying to resolve JS/WASM/iOS variants.
// With module metadata disabled, consumers use POM files which don't have variant information.
tasks.withType<GenerateModuleMetadata> {
    enabled = !System.getenv("JITPACK").toBoolean()
}
