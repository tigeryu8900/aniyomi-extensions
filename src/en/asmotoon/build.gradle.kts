import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Asmodeus Scans"
    // TACH -->
    versionCode = 4
    // <-- TACH
    contentWarning = ContentWarning.SAFE
    libVersion = "1.4"
    theme = "keyoapp"

    source {
        lang = "en"
        baseUrl = "https://asmotoon.com"
    }
}

// TACH -->
dependencies {
    implementation(project(":lib:waybackmachineinterceptor"))
}
// <-- TACH
