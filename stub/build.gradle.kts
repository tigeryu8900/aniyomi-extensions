plugins {
    id("io.github.tjokinen.android-bcv-bridge") version "0.2.0"
    alias(libs.plugins.android.library)
    alias(libs.plugins.spotless)
}

dependencies {
    compileOnly(libs.appcompat)
    compileOnly(libs.bundles.common)
    compileOnly(libs.webkit)
    compileOnly(libs.tachiyomi.lib.v16)
}

android {
    namespace = "eu.kanade.tachiyomi.stub"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }
}
