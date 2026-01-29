plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

group = "io.flowdux.sample"
version = "1.0.0"

application {
    mainClass.set("io.flowdux.sample.MainKt")
}

dependencies {
    implementation(project(":kotlin:flowdux"))
    implementation(project(":kotlin:flowdux-timetravel"))
    implementation(libs.kotlinx.coroutines.core)
}
