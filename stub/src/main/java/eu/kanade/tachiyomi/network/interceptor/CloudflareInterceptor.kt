package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import eu.kanade.tachiyomi.network.AndroidCookieJar
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class CloudflareInterceptor(
    context: Context,
    cookieManager: AndroidCookieJar,
    defaultUserAgentProvider: () -> String,
) : WebViewInterceptor(context, defaultUserAgentProvider) {
    override fun shouldIntercept(response: Response): Boolean = throw RuntimeException("Stub!")

    override fun intercept(chain: Interceptor.Chain, request: Request, response: Response): Response = throw RuntimeException("Stub!")
}
