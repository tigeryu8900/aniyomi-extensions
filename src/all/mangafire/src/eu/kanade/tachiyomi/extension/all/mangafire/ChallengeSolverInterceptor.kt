package eu.kanade.tachiyomi.extension.all.mangafire

import android.annotation.SuppressLint
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebViewBlocking
import kotlinx.serialization.Serializable
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock
import kotlin.getValue

class ChallengeSolverInterceptor(
    private val doSolve: () -> Boolean,
) : Interceptor {
    private val html by lazy { javaClass.getResource("/assets/solver.html")!!.readText() }

    private val lock = ReentrantReadWriteLock()

    private fun Interceptor.Chain.clearance() =
        cookieJar.loadForRequest(request().url).find { it.name == "waf_pass" }?.value

    @Serializable
    private data class ErrorResponse(
        val error: String?,
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        val request = chain.request()

        val oldClearance = lock.readLock().withLock {
            val response = chain.proceed(request)
            if (
                response.code != 403 ||
                response.peekBody(Long.MAX_VALUE).byteStream().parseAs<ErrorResponse>().error != "captcha_required"
            ) {
                return response
            }
            response.close()

            if (!doSolve()) {
                throw IOException("Shape-selecting captcha detected. Open in WebView to solve manually or turn on the setting to solve automatically.")
            }

            chain.clearance()
        }

        if (call.isCanceled()) {
            throw IOException("Canceled")
        }

        // We are solving the challenge in a WebView instead of directly in Kotlin because the solver depends on OpenCV, which is >100 MB
        // as a Kotlin dependency. Also, the OpenCV binaries would be in the storage of the extension app, making them inaccessible to the
        // reader app.
        // Using a WebView instead makes it possible to dynamically request OpenCV.js, keeping the app size small.
        val solved = lock.writeLock().withLock {
            if (call.isCanceled()) {
                throw IOException("Canceled")
            }

            if (chain.clearance().let { it != oldClearance && !it.isNullOrBlank() }) {
                // Captcha solved in another call, skip
                return@withLock true
            }

            runWebViewBlocking(call) {
                jsBridge("bridge") { resolve(it == "true") }
                loadData("https://mangafire.to/@waf/solver", html)
            }
        }

        if (!solved) {
            throw IOException("Failed to solve shape-selecting captcha. Open in WebView to solve manually.")
        }

        return chain.proceed(request)
    }
}
