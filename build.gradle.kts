plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinCompose) apply false
    alias(libs.plugins.mavenPublish) apply false
    alias(libs.plugins.kover)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
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

spotless {
    kotlin {
        target("kotlin/**/*.kt")
        targetExclude("**/build/**")
        ktlint().editorConfigOverride(
            mapOf(
                "ktlint_standard_no-wildcard-imports" to "disabled",
                "ktlint_standard_filename" to "disabled",
                "ktlint_standard_backing-property-naming" to "disabled",
                "ktlint_standard_function-naming" to "disabled",
            ),
        )
    }
    kotlinGradle {
        target("*.gradle.kts", "build-logic/**/*.gradle.kts", "kotlin/**/*.gradle.kts")
        targetExclude("**/build/**")
        ktlint()
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("detekt.yml"))
    baseline = file("detekt-baseline.xml")
    parallel = true
    source.setFrom(files("kotlin/"))
}
