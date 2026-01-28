plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

group = "io.flowdux.sample.chat"
version = "1.0.0"

application {
    mainClass.set("io.flowdux.sample.chat.server.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

dependencies {
    implementation(project(":kotlin:sample-remote-chat:shared"))
    implementation(project(":kotlin:flowdux"))
    implementation(project(":kotlin:flowdux-remote-core"))
    implementation(project(":kotlin:flowdux-remote-server"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
}
