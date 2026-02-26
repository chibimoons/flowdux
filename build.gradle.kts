plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinCompose) apply false
    alias(libs.plugins.mavenPublish) apply false
    alias(libs.plugins.kover)
}

dependencies {
    kover(project(":kotlin:flowdux"))
    kover(project(":kotlin:flowdux-timetravel"))
    kover(project(":kotlin:flowdux-remote-core"))
    kover(project(":kotlin:flowdux-remote-client"))
    kover(project(":kotlin:flowdux-remote-server"))
    kover(project(":kotlin:flowdux-remote-serialization"))
    kover(project(":kotlin:flowdux-remote-ktor"))
    kover(project(":kotlin:flowdux-remote-multiplexer"))
    kover(project(":kotlin:flowdux-remote-auth"))
    kover(project(":kotlin:flowdux-remote-node-mediator"))
}
