plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

group = "io.flowdux"
version = "1.0.0"

application {
    mainClass.set("io.flowdux.benchmark.MainKt")
}

dependencies {
    implementation(project(":kotlin:flowdux"))
    implementation(project(":kotlin:flowdux-remote-core"))
    implementation(libs.kotlinx.coroutines.core)
}

kotlin {
    jvmToolchain(17)
}
