package keiyoushi.network

import okhttp3.Interceptor
import okhttp3.Response

private val CHROME_REGEX = Regex("""Chrome/(\d+)""")
private val EDGE_REGEX = Regex("""Edg[^/]*/(\d+)""")
private val OPERA_REGEX = Regex("""OPR/(\d+)""")

fun clientHints(userAgent: String?): Map<String, String> {
    userAgent ?: return emptyMap()
    val (name, version, chromiumVersion) = when {
        userAgent.contains("Firefox/") && !userAgent.contains("Chrome") -> return emptyMap()

        userAgent.contains("Safari/") &&
            !userAgent.contains("Chrome") &&
            !userAgent.contains("Chromium") -> return emptyMap()

        userAgent.contains("Edg/") || userAgent.contains("EdgA/") || userAgent.contains("EdgiOS/") -> {
            val edgeVersion = EDGE_REGEX.find(userAgent)?.groupValues?.get(1) ?: "134"
            val chromiumVersion = CHROME_REGEX.find(userAgent)?.groupValues?.get(1) ?: edgeVersion
            Triple("Microsoft Edge", edgeVersion, chromiumVersion)
        }

        userAgent.contains("OPR/") -> {
            val operaVersion = OPERA_REGEX.find(userAgent)?.groupValues?.get(1) ?: "118"
            val chromiumVersion = CHROME_REGEX.find(userAgent)?.groupValues?.get(1) ?: "134"
            Triple("Opera", operaVersion, chromiumVersion)
        }

        userAgent.contains("Chrome/") -> {
            val chromeVersion = CHROME_REGEX.find(userAgent)?.groupValues?.get(1) ?: "134"
            Triple("Google Chrome", chromeVersion, chromeVersion)
        }

        else -> return emptyMap()
    }

    val isMobile = userAgent.contains("Mobile") ||
        userAgent.contains("Android") ||
        userAgent.contains("iPhone") ||
        userAgent.contains("iPad")

    val platform = when {
        userAgent.contains("Windows") -> "\"Windows\""
        userAgent.contains("Android") -> "\"Android\""
        userAgent.contains("iPhone") || userAgent.contains("iPad") -> "\"iOS\""
        userAgent.contains("Macintosh") || userAgent.contains("Mac OS X") -> "\"macOS\""
        userAgent.contains("Linux") -> "\"Linux\""
        else -> "\"Windows\""
    }

    return mapOf(
        "Sec-CH-UA" to "\"$name\";v=\"$version\", \"Chromium\";v=\"$chromiumVersion\", \"Not A(Brand\";v=\"24\"",
        "Sec-CH-UA-Mobile" to if (isMobile) "?1" else "?0",
        "Sec-CH-UA-Platform" to platform,
    )
}

object ClientHintsInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val userAgent = request.header("User-Agent")

        return chain.proceed(
            request
                .newBuilder()
                .apply {
                    clientHints(userAgent).forEach { (key, value) ->
                        addHeader(key, value)
                    }
                }
                .build(),
        )
    }
}
