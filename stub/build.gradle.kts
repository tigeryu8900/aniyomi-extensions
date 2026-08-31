plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)

    alias(kei.plugins.android.base)
    alias(kei.plugins.spotless)
}

android {
    namespace = "eu.kanade.tachiyomi.stub"
}

dependencies {
    compileOnly(libs.appcompat)
    compileOnly(libs.bundles.common)
    compileOnly(libs.webkit)
    compileOnly(libs.tachiyomi.lib.v16)
}
