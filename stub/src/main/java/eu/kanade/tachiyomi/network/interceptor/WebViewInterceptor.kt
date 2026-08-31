package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.DelicateCoroutinesApi
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.CountDownLatch

abstract class WebViewInterceptor(
    context: Context,
    defaultUserAgentProvider: () -> String,
) : Interceptor {
    abstract fun shouldIntercept(response: Response): Boolean

    abstract fun intercept(chain: Interceptor.Chain, request: Request, response: Response): Response

    @OptIn(DelicateCoroutinesApi::class)
    override fun intercept(chain: Interceptor.Chain): Response = throw RuntimeException("Stub!")

    fun parseHeaders(headers: Headers): Map<String, String> = throw RuntimeException("Stub!")

    fun CountDownLatch.awaitFor30Seconds(): Unit = throw RuntimeException("Stub!")

    fun createWebView(request: Request): WebView = throw RuntimeException("Stub!")
}
