package keiyoushi.network

import android.app.Application
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.isOutdated
import keiyoushi.utils.ForegroundActivity
import keiyoushi.utils.runWebViewBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.TypeReference
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.fullType
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.api.hasFactory
import java.io.IOException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor as OldCloudflareInterceptor

internal object CloudflareInterceptor : Interceptor {
    // Fallback JavaScript solver for when view group isn't available (i.e. app in background)
    private val iframeScript by lazy {
        javaClass
            .getResource("/assets/CloudflareSolverIframeScript.js")!!
            .readText()
            .replace("__SOLVER__", "__SOLVER_${(ULong.MIN_VALUE..ULong.MAX_VALUE).random()}__")
    }

    private val listenerScript = """
        addEventListener("message", ({data}) => {
            if (data?.source === "cloudflare-challenge") {
                mihon?.postMessage(data.event);
            }
        })
    """.trimIndent()

    private val networkHelper: NetworkHelper = Injekt.get()

    private typealias LocksData = LinkedHashMap<String, Pair<ReentrantReadWriteLock, ReentrantReadWriteLock>>

    private val locks = object {
        private val data by lazy {
            with(Injekt.registrar) {
                synchronized(this) {
                    if (!hasFactory<Pair<TypeReference<OldCloudflareInterceptor>, LocksData>>()) {
                        addSingleton<Pair<TypeReference<OldCloudflareInterceptor>, LocksData>>(
                            fullType<OldCloudflareInterceptor>() to object : LocksData() {
                                private val MAX_CAPACITY = 256

                                override fun removeEldestEntry(
                                    eldest: Map.Entry<String, Pair<ReentrantReadWriteLock, ReentrantReadWriteLock>>,
                                ): Boolean {
                                    if (size > MAX_CAPACITY) {
                                        eldest.value.second.writeLock().withLock {
                                            if (size > MAX_CAPACITY) {
                                                remove(eldest.key)
                                            }
                                        }
                                    }
                                    return false
                                }
                            },
                        )
                    }
                }
                get<Pair<TypeReference<OldCloudflareInterceptor>, LocksData>>().second
            }
        }

        inline fun <T> withLock(host: String, block: (ReentrantReadWriteLock) -> T): T {
            val (lock, entryLock) = synchronized(data) {
                var entry = data[host]
                if (entry == null) {
                    entry = ReentrantReadWriteLock() to ReentrantReadWriteLock()
                    data[host] = entry
                }
                entry.first to entry.second.readLock().apply { lock() }
            }
            try {
                return block(lock)
            } finally {
                entryLock.unlock()
            }
        }
    }

    private fun injectIframeScript(webview: WebView): ScriptHandler? = if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        WebViewCompat.addDocumentStartJavaScript(
            webview,
            iframeScript,
            mutableSetOf("https://challenges.cloudflare.com"),
        )
    } else {
        null
    }

    private fun clearance(url: HttpUrl): String? = networkHelper.cookieJar.loadForRequest(url).find { it.name == "cf_clearance" }?.value

    private fun isSolved(url: HttpUrl, oldClearance: String?): Boolean = clearance(url).let { it != oldClearance && !it.isNullOrBlank() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        val request = chain.request()
        val url = request.url

        locks.withLock(url.host) { lock ->
            val (response, oldClearance) = lock.readLock().withLock {
                if (call.isCanceled()) {
                    throw IOException("Canceled")
                }

                chain.proceed(request).apply {
                    if (header("cf-mitigated") != "challenge") {
                        return this
                    }
                } to clearance(url)
            }

            response.use { response ->
                if (call.isCanceled()) {
                    throw IOException("Canceled")
                }

                lock.writeLock().withLock {
                    if (call.isCanceled()) {
                        throw IOException("Canceled")
                    }

                    if (isSolved(url, oldClearance)) {
                        // Cloudflare solved in another call, skip
                        return@withLock
                    }

                    networkHelper.cookieJar.remove(url, listOf("cf_clearance"), 0)

                    if (!resolveInWebView(chain, response.body, oldClearance)) {
                        networkHelper.cookieJar.remove(url, listOf("cf_clearance"), 0)
                        throw IOException("Failed to bypass Cloudflare")
                    }
                }
            }
        }

        return chain.proceed(request)
    }

    private fun resolveInWebView(
        chain: Interceptor.Chain,
        body: ResponseBody,
        oldClearance: String?,
    ): Boolean {
        val request = chain.request()
        val url = request.url
        val html = body.string()

        var fail = false

        var iframeScriptHandler: ScriptHandler? = null
        var listenerScriptHandler: ScriptHandler? = null

        return runWebViewBlocking(chain.call(), cleanup = {
            if (fail && webView.isOutdated()) {
                Toast.makeText(
                    Injekt.get<Application>(),
                    "Please update the WebView app for better compatibility",
                    Toast.LENGTH_LONG,
                ).show()
            }

            if (WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)) {
                WebViewCompat.removeWebMessageListener(
                    webView,
                    WebViewCompat.getExecutionWorld(webView, "CloudflareInterceptor"),
                    "jsBridge",
                )
                iframeScriptHandler?.remove()
                listenerScriptHandler?.remove()
            }
        }) {
            request.header("User-Agent")?.let { userAgent = it }

            webView.apply {
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }

            if (ForegroundActivity.viewGroup == null) {
                // view group not available, using fallback JavaScript solver
                synchronized(webView) {
                    if (iframeScriptHandler == null) {
                        iframeScriptHandler = injectIframeScript(webView)
                    }
                }
            }

            // Inject fallback JavaScript solver
            fun injectIframeScript() {
                synchronized(webView) {
                    if (iframeScriptHandler == null) {
                        iframeScriptHandler = injectIframeScript(webView)
                    }
                }
                if (iframeScriptHandler != null) {
                    webView.loadDataWithBaseURL(url.toString(), html, "text/html", "UTF-8", null)
                } else {
                    // Feature not supported, abort
                    fail = true
                    resolve(false)
                }
            }

            var complete = false

            fun handleEvent(event: String) {
                when (event) {
                    "interactiveBegin" -> {
                        if (iframeScriptHandler != null) {
                            // Fallback solver is injected
                            thread {
                                // Fallback solver should complete within a short amount of time
                                Thread.sleep(5000)
                                if (!complete) {
                                    fail = true
                                    resolve(false)
                                }
                            }
                            return
                        }

                        // Get the current view group
                        val container = ForegroundActivity.viewGroup
                        if (container == null) {
                            injectIframeScript()
                            return
                        }

                        runOnMain {
                            val width = container.width.takeIf { it > 0 } ?: 1920
                            val height = container.height.takeIf { it > 0 } ?: 1080

                            // Set translationX to negative width.
                            // The WebView should be offscreen even when the orientation changes.
                            webView.translationX = -width.toFloat()

                            // Attach the WebView to the view group so we can send key events.
                            container.addView(webView, ViewGroup.LayoutParams(width, height))

                            // Send Tab and Space to check the checkbox, and fall back to JavaScript solver
                            // if dispatchKeyEvent fails.
                            // Use a separate thread to unblock the main thread.
                            thread {
                                if (!webView.dispatchKeyEvent(
                                        KeyEvent(
                                            KeyEvent.ACTION_DOWN,
                                            KeyEvent.KEYCODE_TAB,
                                        ),
                                    )
                                ) {
                                    injectIframeScript()
                                    return@thread
                                }
                                Thread.sleep(100)
                                if (!webView.dispatchKeyEvent(
                                        KeyEvent(
                                            KeyEvent.ACTION_UP,
                                            KeyEvent.KEYCODE_TAB,
                                        ),
                                    )
                                ) {
                                    injectIframeScript()
                                    return@thread
                                }
                                Thread.sleep(100)
                                if (!webView.dispatchKeyEvent(
                                        KeyEvent(
                                            KeyEvent.ACTION_DOWN,
                                            KeyEvent.KEYCODE_SPACE,
                                        ),
                                    )
                                ) {
                                    injectIframeScript()
                                    return@thread
                                }
                                Thread.sleep(100)
                                if (!webView.dispatchKeyEvent(
                                        KeyEvent(
                                            KeyEvent.ACTION_UP,
                                            KeyEvent.KEYCODE_SPACE,
                                        ),
                                    )
                                ) {
                                    injectIframeScript()
                                    return@thread
                                }

                                // Challenge should complete in a short amount of time
                                Thread.sleep(5000)
                                if (!complete) {
                                    fail = true
                                    resolve(false)
                                }
                            }
                        }
                    }
                    "complete" -> {
                        complete = true
                    }
                    "fail" -> {
                        // Challenge failed, abort
                        fail = true
                        resolve(false)
                    }
                }
            }

            if (WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)) {
                // Use an isolated world so the page cannot see our bridge
                val world = WebViewCompat.getExecutionWorld(webView, "mihon")
                val allowedOriginRules = mutableSetOf("${url.scheme}://${url.host}")

                WebViewCompat.addWebMessageListener(webView, "mihon", allowedOriginRules, world) {
                        _,
                        message,
                        _,
                        isMainFrame,
                        _,
                    ->
                    if (isMainFrame) {
                        message.data?.let { handleEvent(it) }
                    }
                }

                // Listen for message events
                listenerScriptHandler = WebViewCompat.addJavaScriptOnEvent(
                    webView,
                    listenerScript,
                    WebViewCompat.INJECTION_EVENT_DOCUMENT_START,
                    allowedOriginRules,
                    world,
                )
            } else {
                webView.addJavascriptInterface(
                    object {
                        @Suppress("unused")
                        @JavascriptInterface
                        fun postMessage(event: String) = handleEvent(event)
                    },
                    "mihon",
                )
            }

            onPageFinished {
                if (!fail && isSolved(url, oldClearance)) {
                    resolve(true)
                    return@onPageFinished
                }

                if (it == url.toString() &&
                    !WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)
                ) {
                    // Listen for message events
                    evaluateJs(listenerScript)
                }
            }

            loadData(url.toString(), html)
        }
    }
}
