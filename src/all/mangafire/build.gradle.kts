import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "MangaFire"
    // TACH -->
    versionCode = 33
    // <-- TACH
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    listOf("en", "es", "es-419", "fr", "ja", "pt", "pt-BR").forEach {
        source {
            lang = it
            baseUrl = "https://mangafire.to"
        }
    }

    deeplink {
        path("/title/..*")
    }
}
