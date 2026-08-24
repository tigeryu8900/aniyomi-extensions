package keiyoushi.network

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import eu.kanade.tachiyomi.network.NetworkHelper
import keiyoushi.utils.runWebViewBlocking
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock
import kotlin.text.ifEmpty

internal class CloudflareSolverInterceptor(
    private val cloudflareInterceptor: Interceptor,
) : Interceptor {
    private val locks = object {
        private val MAX_CAPACITY = 256
        private val data = object : LinkedHashMap<String, Pair<ReentrantReadWriteLock, ReentrantReadWriteLock>>() {
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
        }

        inline fun withLock(host: String, block: (ReentrantReadWriteLock) -> Unit) {
            val (lock, entryLock) = synchronized(data) {
                var entry = data[host]
                if (entry == null) {
                    entry = ReentrantReadWriteLock() to ReentrantReadWriteLock()
                    data.put(host, entry)
                }
                entry.first to entry.second.readLock().apply { lock() }
            }
            try {
                block(lock)
            } finally {
                entryLock.unlock()
            }
        }
    }

    private fun clearance(url: HttpUrl) = client.cookieJar.loadForRequest(url).find { it.name == "cf_clearance" }?.value

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

                    if (clearance(url).let { it != oldClearance && !it.isNullOrBlank() }) {
                        // Cloudflare solved in another call, skip
                        return@withLock
                    }

                    resolveInWebView(chain, response.body)
                }
            }
        }

        // Use the original Cloudflare interceptor in case the solver failed
        return cloudflareInterceptor.intercept(chain)
    }

    private fun resolveInWebView(chain: Interceptor.Chain, body: ResponseBody) = runWebViewBlocking(chain.call()) {
        val request = chain.request()
        val url = request.url

        request.header("User-Agent")?.let { userAgent = it }

        var challengeCompleted = false

        interceptRequest {
            val requestUrl = it.url?.toString()?.toHttpUrlOrNull() ?: return@interceptRequest null

            when (it.method) {
                "GET" if requestUrl.toString().startsWith("https://challenges.cloudflare.com/cdn-cgi/challenge-platform/") -> {
                    client
                        .newCall(it.toRequest())
                        .execute()
                        .injectJS(INNER_SCRIPT)
                        .toWebResourceResponse()
                }

                "POST" if requestUrl.host == url.host &&
                    requestUrl.encodedPath == url.encodedPath -> {
                    challengeCompleted = true
                    null
                }

                else -> null
            }
        }

        onPageFinished {
            if (challengeCompleted) {
                resolve(Unit)
            }
        }

        loadData(url.toString(), body.string().injectJS(OUTER_SCRIPT))
    }

    private fun WebResourceRequest.toRequest(): Request = Request.Builder().apply {
        url(url.toString())
        method(method, null)
        headers(requestHeaders.toHeaders())
    }.build()

    private fun Response.toWebResourceResponse(): WebResourceResponse = WebResourceResponse(
        body.contentType()?.let { "${it.type}/${it.subtype}" } ?: "text/html",
        body.contentType()?.charset(StandardCharsets.UTF_8)?.name() ?: "UTF-8",
        code,
        message.ifEmpty { "OK" },
        headers.toMap()
            .filterKeys { !it.equals("Content-Encoding", true) && !it.equals("Content-Length", true) },
        body.byteStream(),
    )

    /**
     * Returns a new HTML string with the injected JavaScript code.
     *
     * The injected script element is prepended to the HTML, and all `Error` classes are patched so that the injected code doesn't appear in
     * stack traces and that the line numbers correspond to the original unpatched HTML.
     */
    private fun String.injectJS(js: String, nonce: String = ""): String = "<script nonce=\"$nonce\">document.currentScript.remove();(()=>{$js;})();($ERROR_PATCHER_SCRIPT)(${
        BASE_LINE_COUNT + js.count { it == '\n' }
    });</script>\n$this"

    /**
     * Returns a new response with the injected JavaScript code.
     */
    private fun Response.injectJS(js: String): Response = newBuilder().body(
        body.contentType().let { contentType ->
            body
                .string()
                .injectJS(
                    js,
                    header("Content-Security-Policy")?.let { nonceRegex.find(it) }?.value.orEmpty(),
                )
                .toResponseBody(contentType)
        },
    ).build()

    companion object {
        private val client: OkHttpClient by lazy {
            Injekt.get<NetworkHelper>().client.newBuilder().apply {
                interceptors().removeAll { it.javaClass.simpleName == "CloudflareInterceptor" }
            }.build()
        }

        private val nonceRegex = """(?<=nonce-)\w+""".toRegex()

        /**
         * This script runs in the main frame when a Cloudflare challenge is present.
         *
         * This script patches the `postMessage` function of the challenge iframe's content window to use `*` as the target origin.
         */
        private val OUTER_SCRIPT = """
            const contentWindowToProxy = new WeakMap();

            const contentWindowDescriptor = Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype, "contentWindow");

            function createContentWindowProxy(result) {
              let proxy = contentWindowToProxy.get(result);

              if (proxy) {
                return proxy;
              }

              function postMessage(message, targetOrigin, transfer) {
                result.postMessage(message, targetOrigin === "https://challenges.cloudflare.com" ? "*" : targetOrigin, transfer);
              }

              proxy = new Proxy(result, {
                get(target, prop) {
                  const result = Reflect.get(target, prop);
                  if (prop === "postMessage") {
                    return postMessage;
                  }
                  if (typeof result === "function") {
                    return (...args) => target[prop](...args);
                  }
                  return result;
                }
              });

              contentWindowToProxy.set(result, proxy);

              return proxy;
            }

            addEventListener = (type, listener, options) => {
              if (type === "message") {
                return Window.prototype.addEventListener.call(window, type, e => {
                  let source = e.source;
                  return listener(new Proxy(e, {
                    get(target, prop, receiver) {
                      if (prop === "source") {
                        return createContentWindowProxy(target.source);
                      } else {
                        return target[prop];
                      }
                    }
                  }));
                }, options);
              } else {
                return Window.prototype.addEventListener.call(window, type, listener, options);
              }
            };

            Object.defineProperty(HTMLIFrameElement.prototype, "contentWindow", Object.assign({}, contentWindowDescriptor, {
              get(...args) {
                const result = contentWindowDescriptor.get.apply(this, args);
                return this.src?.startsWith("https://challenges.cloudflare.com/cdn-cgi/challenge-platform/")
                  ? createContentWindowProxy(result)
                  : result;
              }
            }));
        """.trimIndent()

        /**
         * This script runs in the Cloudflare challenge iframe.
         *
         * This script simulates a mouse click on the checkbox.
         */
        private val INNER_SCRIPT = $$"""
            async function simulateMouseClick(element, clientX = null, clientY = null) {
              if (clientX === null || clientY === null) {
                const box = element.getBoundingClientRect();
                clientX = box.left + box.width / 2;
                clientY = box.top + box.height / 2;
              }

              if (isNaN(clientX) || isNaN(clientY)) {
                return;
              }

              // Send mouseover, mousedown, mouseup, click, mouseout
              for (const eventName of [
                "mouseover",
                "mouseenter",
                "mousedown",
                "mouseup",
                "click",
                "mouseout"
              ]) {
                const event = new MouseEvent(eventName, {
                  detail: 1 - (eventName === "mouseover"),
                  bubbles: true,
                  cancelable: true,
                  clientX: clientX,
                  clientY: clientY,
                });
                element.dispatchEvent(event);
                await new Promise(resolve => setTimeout(resolve, 10));
              }
            }

            const ORIGINAL = Symbol("original");
            const MODIFIED = Symbol("modified");

            const proxyEventHandler = {
              get(target, prop) {
                if (prop === "isTrusted") {
                  return true;
                }
                const result = Reflect.get(target, prop);
                return typeof result === "function" ? result.bind(target) : result;
              }
            };

            function preprocessEvent(e) {
              if ((e.target instanceof Element && e.target.matches('input[type="checkbox"]'))) {
                return new Proxy(e, proxyEventHandler);
              }
              return e;
            }

            Object.assign(Element.prototype, {
              attachShadow: new Proxy(Element.prototype.attachShadow, {
                apply(target, thisArg, args) {
                  thisArg._shadowRoot = target.apply(thisArg, args);
                  return thisArg._shadowRoot;
                }
              }),
              addEventListener: new Proxy(Element.prototype.addEventListener, {
                apply(target, thisArg, args) {
                  const [type, listener, options] = args;
                  if (listener instanceof Object) {
                    if (!listener[MODIFIED]) {
                      const newListener = typeof listener === "function" ? function (e) {
                        return listener.call(this, preprocessEvent(e));
                      } : function (e) {
                        return listener.handleEvent(preprocessEvent(e));
                      };
                      listener[MODIFIED] = newListener;
                      newListener[ORIGINAL] = listener;
                    }
                    args[1] = listener[MODIFIED];
                  }
                  return Reflect.apply(target, thisArg, args);
                }
              }),
              removeEventListener: new Proxy(Element.prototype.removeEventListener, {
                apply(target, thisArg, args) {
                  const [type, listener, options] = args;
                  if (listener instanceof Object) {
                    args[1] = listener[ORIGINAL] ?? listener;
                  }
                  return Reflect.apply(target, thisArg, args);
                }
              })
            });

            for (const [property, value] of Object.entries({
              visibilityState: "visible",
              webkitVisibilityState: "visible",
              hidden: false,
              webkitFalse: false
            })) {
              try {
                Object.defineProperty(document, property, { get: () => value });
              } catch (e) {
                console.error(`Cannot define document.${property}`, e);
              }
            }

            setInterval(() => {
              const checkbox = document.body?._shadowRoot?.querySelector('input[type="checkbox"]');
              if (checkbox) {
                simulateMouseClick(checkbox);
              }
            }, 100);
        """.trimIndent()

        /**
         * This script patches stack traces to hide injected code.
         *
         * This is needed since Cloudflare checks the stack trace.
         */
        private val ERROR_PATCHER_SCRIPT = $$"""
            function errorPatcher(lines) {
              const regex = RegExp(String.raw`^(.*)\b${RegExp.escape(location.href)}:(\d+):(\d+)$`);

              function patch(error) {
                error.stack = error.stack.split('\n').reduce((acc, line) => {
                  const match = line.match(regex);
                  if (match) {
                    const row = parseInt(match[2]);
                    if (row > lines) {
                      acc += `\n${match[1]}${location.href}:${row - lines}:${match[3]}`
                    }
                  } else {
                    acc += '\n';
                    acc += line;
                  }
                  return acc;
                }, "").substring(1);
                return error;
              }

              const proxyErrorHandler = {
                apply(target, thisArg, args) {
                  return patch(Reflect.apply(target, thisArg, args));
                },
                construct(target, args) {
                  return patch(Reflect.construct(target, args));
                }
              };

              for (const prop of Object.getOwnPropertyNames(window)) {
                try {
                  if (window[prop] === Error || window[prop]?.prototype instanceof Error) {
                    Object.defineProperty(window, prop, {value: new Proxy(window[prop], proxyErrorHandler)});
                  }
                } catch {}
              }
            }
        """.trimIndent()

        private val BASE_LINE_COUNT = ERROR_PATCHER_SCRIPT.count { it == '\n' } + 1
    }
}
