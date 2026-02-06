plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

group = "io.flowdux.sample.poker"
version = "1.0.0"

application {
    mainClass.set("io.flowdux.sample.poker.server.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

dependencies {
    implementation(project(":kotlin:sample-remote-poker:shared"))
    implementation(project(":kotlin:flowdux"))
    implementation(project(":kotlin:flowdux-remote-core"))
    implementation(project(":kotlin:flowdux-remote-server"))
    implementation(project(":kotlin:flowdux-remote-ktor"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
}
