plugins {
    alias(libs.plugins.kotlinJvm)
}

group = "io.flowdux.sample.chat"
version = "1.0.0"

dependencies {
    implementation(project(":kotlin:flowdux"))
    implementation(project(":kotlin:flowdux-remote-core"))
    implementation(libs.kotlinx.coroutines.core)
}
