plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    // Temporarily skip Android plugins for testing without Android SDK
    // alias(libs.plugins.kotlinAndroid) apply false
    // alias(libs.plugins.androidApplication) apply false
    // alias(libs.plugins.androidLibrary) apply false
}
