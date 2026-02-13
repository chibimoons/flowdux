plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

group = "io.flowdux.sample.nodemediator"
version = "1.0.0"

dependencies {
    implementation(project(":kotlin:flowdux"))
    implementation(project(":kotlin:flowdux-remote-core"))
    api(project(":kotlin:flowdux-remote-serialization"))
    api(project(":kotlin:flowdux-remote-node-mediator"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
