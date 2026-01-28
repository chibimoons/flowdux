plugins {
    alias(libs.plugins.kotlinMultiplatform)
    `maven-publish`
}

group = "io.flowdux"
version = "1.8.2"

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kotlin:flowdux"))
            implementation(project(":kotlin:flowdux-remote-core"))
            implementation(libs.kotlinx.coroutines.core)
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
                name.set("Flowdux Remote Server")
                description.set("Server-side components for Flowdux remote state management")
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
