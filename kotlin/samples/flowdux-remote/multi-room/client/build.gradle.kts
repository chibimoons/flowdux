plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

group = "io.flowdux.sample.chat"
version = "1.0.0"

application {
    mainClass.set("io.flowdux.sample.chat.multiroomclient.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

dependencies {
    implementation(project(":kotlin:sample-remote:shared"))
    implementation(project(":kotlin:flowdux"))
    implementation(project(":kotlin:flowdux-remote-core"))
    implementation(project(":kotlin:flowdux-remote-client"))
    implementation(project(":kotlin:flowdux-remote-ktor"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
}
