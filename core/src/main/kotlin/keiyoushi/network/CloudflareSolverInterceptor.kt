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
    private fun String.injectJS(js: String, nonce: String = ""): String = "<script nonce=\"$nonce\">(()=>{$js;})();</script>\n$this"

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
         * This script is injected in both the page and the iframe. It patches stack traces and creates helper functions.
         */
        private val COMMON_SCRIPT = $$"""
            document.currentScript.remove();

            addEventListener("message", e => console.log(`${location.origin}: ${JSON.stringify(e.data)}`));

            const stackLineRegex = new RegExp(String.raw`(?<=^.*\b${RegExp.escape(location.href)}:)\d+(?=(?::\d+)?$)`);
            const stackLineGlobalRegexString = String.raw`(?<=\b${RegExp.escape(location.href)}:)\d+`;

            function patchStackLine(str) {
              const match = str.match(stackLineRegex);
              if (match) {
                const newLine = parseInt(match[0]) - LINES;
                return newLine > 0 ? {
                  str: str.replaceAll(new RegExp(stackLineGlobalRegexString, "g"), line => parseInt(line) - LINES),
                  line: newLine
                } : null;
              }
              return { str };
            }

            function stackReduce(sites, site) {
              const str = patchStackLine(site.toString)?.str;
              if (str) {
                sites.push(str);
              }
              return sites;
            }

            const objectToProxy = new WeakMap();
            const proxyToObject = new WeakMap();

            function createProxy(target, handler) {
              if (objectToProxy.has(target)) {
                return objectToProxy.get(target);
              }
              const proxy = new Proxy(target, handler);
              objectToProxy.set(target, proxy);
              proxyToObject.set(proxy, target);
              return proxy;
            }

            function toProxy(obj) {
              return objectToProxy.has(obj) ? objectToProxy.get(obj) : obj;
            }

            function toObject(proxy) {
              return proxyToObject.has(proxy) ? proxyToObject.get(proxy) : proxy;
            }

            const redirectFunctionHandler = {
              apply(target, thisArg, args) {
                return Reflect.apply(toObject(target), toObject(thisArg), args.map(toObject));
              }
            };

            function getRedirectPropertyHandler(redirects) {
              return {
                defineProperty(target, prop, descriptor) {
                  return Reflect.defineProperty(redirects[prop] ?? target, prop, descriptor);
                },
                deleteProperty(target, prop) {
                  return Reflect.deleteProperty(redirects[prop] ?? target, prop);
                },
                get(target, prop, receiver) {
                  return toProxy(Reflect.get(redirects[prop] ?? target, prop, toObject(receiver)));
                },
                getOwnPropertyDescriptor(target, prop) {
                  return Reflect.getOwnPropertyDescriptor(redirects[prop] ?? target, prop);
                },
                has(target, prop, receiver) {
                  return Reflect.has(redirects[prop] ?? target, prop, toObject(receiver));
                },
                ownKeys(target) {
                  const result = Reflect.ownKeys(target).filter(key => !(key in redirects));
                  for (let prop in redirects) {
                    if (prop in redirects[prop]) {
                      result.push(prop);
                    }
                  }
                  return result;
                },
                set(target, prop, value) {
                  return Reflect.set(redirects[prop] ?? target, prop, toObject(value));
                }
              };
            }

            let CallSite;
            Error.prepareStackTrace = (_, sites) => (CallSite = sites[0].constructor);
            new Error().stack;
            delete Error.prepareStackTrace;

            if (CallSite) {
              // V8, i.e. Chrome

              const stackLines = new WeakMap();

              function WrappedCallSite(site, patch) {
                stackLines.set(this, Object.assign({
                  site: site,
                  line: site.getLineNumber()
                }, patch));
              }

              WrappedCallSite.prototype = Object
                .getOwnPropertyNames(CallSite.prototype)
                .reduce((prototype, prop) => prop in prototype ? prototype : Object.assign(prototype, {
                [prop]() {
                  return stackLines.get(this).site[prop](...arguments);
                }
              }), {
                constructor: WrappedCallSite,
                getLineNumber() {
                  return stackLines.get(this).line;
                },
                toString() {
                  return stackLines.get(this).str;
                }
              });

              function callSitesReduce(sites, site) {
                const patch = patchStackLine(site.toString());
                if (patch?.str) {
                  sites.push(new WrappedCallSite(site, patch));
                }
                return sites;
              }

              function prepareStackTrace(error, callSites) {
                return callSites.reduce(
                  (acc, cur) => acc + "\n    at " + cur,
                  `${error.name}: ${error.message}`
                );
              }

              const prepareStackTraceObj = {};
              Error.prepareStackTrace = (error, callSites) => (
                prepareStackTraceObj.prepareStackTrace ?? prepareStackTrace
              )(error, callSites.reduce(callSitesReduce, []));
              window.Error = Error.prototype.constructor = createProxy(Error, getRedirectPropertyHandler({
                prepareStackTrace: prepareStackTraceObj
              }));
            } else if (Object.hasOwn(Error.prototype, "stack")) {
              // Gecko, i.e. FireFox

              const stackDescriptor = Object.getOwnPropertyDescriptor(Error.prototype, "stack");
              const stacks = WeakMap();

              Object.defineProperty(Error.prototype, "stack", Object.assign({}, stackDescriptor, {
                get: createProxy(stackDescriptor.get, {
                  apply(target, thisArg) {
                    thisArg = toObject(thisArg);
                    if (stacks.has(thisArg)) {
                      return stacks.get(thisArg);
                    } else {
                      const value = target.call(thisArg).split('\n').reduce(stackReduce, []).join('\n');
                      stacks.set(thisArg, value);
                      return value;
                    }
                  }
                }),
                set: createProxy(stackDescriptor.set, {
                  apply(target, thisArg, [value]) {
                    stacks.set(toObject(thisArg), toObject(value));
                    return true;
                  }
                })
              }));
            } else {
              // Others, i.e. Safari

              const proxyErrorHandler = {
                apply(target, thisArg, args) {
                  const result = Reflect.apply(target, toObject(thisArg), args.map(toObject));
                  result.stack = result.stack.split('\n').reduce(stackReduce, []).join('\n');
                  return result;
                },
                construct(target, args) {
                  const result = Reflect.construct(target, args.map(toObject));
                  result.stack = result.stack.split('\n').reduce(stackReduce, []).join('\n');
                  return result;
                }
              };

              for (const prop of Object.getOwnPropertyNames(window)) {
                try {
                  if (window[prop] === Error || window[prop]?.prototype instanceof Error) {
                    const proxy = createProxy(window[prop], proxyErrorHandler);
                    Object.defineProperty(window[prop].prototype, "constructor", {value: proxy});
                    Object.defineProperty(window, prop, {value: proxy});
                  }
                } catch {}
              }
            }
        """.trimIndent()

        private fun String.addCommonScript(): String = "LINES = ${
            count { it == '\n' } + COMMON_SCRIPT.count { it == '\n' } + 1
        };$COMMON_SCRIPT;$this"

        /**
         * This script runs in the main frame when a Cloudflare challenge is present.
         *
         * This script patches the `postMessage` function of the challenge iframe's content window to use `*` as the target origin.
         */
        private val OUTER_SCRIPT = """
            function isContentWindow(window) {
              try {
                window.document;
                return false;
              } catch (e) {
                return e?.name === "SecurityError";
              }
            }

            function createContentWindowProxy(result) {
              if (objectToProxy.has(result)) {
                return objectToProxy.get(result);
              }

              if (!isContentWindow(result)) {
                return result;
              }

              const functionHandler = {
                apply(target, thisArg, args) {
                  return Reflect.apply(result, thisArg, args);
                }
              };

              const descriptors = Object.getOwnPropertyDescriptors(result);

              const functionsObj = {};

              Object.defineProperties(functionsObj, {
                focus: Object.assign(descriptors.focus, {
                  value: createProxy(descriptors.focus.value, functionHandler)
                }),
                blur: Object.assign(descriptors.blur, {
                  value: createProxy(descriptors.blur.value, functionHandler)
                }),
                close: Object.assign(descriptors.close, {
                  value: createProxy(descriptors.close.value, functionHandler)
                }),
                postMessage: {
                  value: createProxy(descriptors.postMessage.value, {
                    apply(target, thisArg, args) {
                      args = args.map(toObject);
                      const [message, targetOrigin, transfer] = args;
                      if (targetOrigin === "https://challenges.cloudflare.com") {
                        args[1] = "*";
                      }
                      return Reflect.apply(target, toObject(thisArg), args);
                    }
                  })
                }
              });

              const proxy = createProxy(result, getRedirectPropertyHandler({
                focus: functionsObj,
                blur: functionsObj,
                close: functionsObj,
                postMessage: functionsObj
              }));

              objectToProxy.set(result, proxy);
              proxyToObject.set(proxy, result);

              return proxy;
            }

            const sourcePropertyDescriptor = Object.getOwnPropertyDescriptor(MessageEvent.prototype, "source");

            Object.defineProperty(MessageEvent.prototype, "source", Object.assign(sourcePropertyDescriptor, {
              get: createProxy(sourcePropertyDescriptor.get, {
                apply(target, thisArg) {
                  return createContentWindowProxy(target.call(toObject(thisArg)));
                }
              })
            }));

            const contentWindowDescriptor = Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype, "contentWindow");

            Object.defineProperty(HTMLIFrameElement.prototype, "contentWindow", Object.assign(contentWindowDescriptor, {
              get: createProxy(contentWindowDescriptor.get, {
                apply(target, thisArg) {
                  return createContentWindowProxy(target.call(toObject(thisArg)));
                }
              })
            }));
        """.trimIndent().addCommonScript()

        /**
         * This script runs in the Cloudflare challenge iframe.
         *
         * This script simulates a mouse click on the checkbox.
         */
        private val INNER_SCRIPT = """
            function fixIllegalInvocation(obj) {
              try {
                while (obj && obj !== Object.prototype) {
                  const descriptors = Object.getOwnPropertyDescriptors(obj);
                  for (const prop of Object.getOwnPropertyNames(descriptors).concat(Object.getOwnPropertySymbols(descriptors))) {
                    if (prop === "constructor") {
                      continue;
                    }
                    const descriptor = descriptors[prop];
                    if (!descriptor.configurable) {
                      continue;
                    }
                    if (descriptor.get) {
                      descriptor.get = createProxy(descriptor.get, redirectFunctionHandler);
                    }
                    if (descriptor.set) {
                      descriptor.set = createProxy(descriptor.set, redirectFunctionHandler);
                    }
                    if (typeof descriptor.value === "function") {
                      descriptor.value = createProxy(descriptor.value, redirectFunctionHandler);
                    }
                    try {
                      Object.definePropery(obj, prop, descriptor);
                    } catch {}
                  }
                  obj = Object.getPrototypeOf(obj);
                }
              } catch {}
            }

            const shadows = new WeakMap();

            function getCheckBox() {
              return shadows.get(document.body)?.querySelector('input[type="checkbox"]');
            }

            Element.prototype.attachShadow = createProxy(Element.prototype.attachShadow, {
              apply(target, thisArg, args) {
                thisArg = toObject(thisArg);
                const result = target.apply(thisArg, args.map(toObject));
                shadows.set(thisArg, result);
                return result;
              }
            });

            const isTrustedPropertyDescriptor = Object.getOwnPropertyDescriptor(new Event(""), "isTrusted");
            const isTrustedObj = {};

            Object.defineProperty(isTrustedObj, "isTrusted", Object.assign(isTrustedPropertyDescriptor, {
              get: createProxy(isTrustedPropertyDescriptor.get, {
                apply() {
                  return true;
                }
              }),
            }));

            const proxyEventHandler = getRedirectPropertyHandler({ isTrusted: isTrustedObj });

            const patchedEvents = new WeakMap();
            const original = new WeakMap();
            const modified = new WeakMap();

            Object.assign(EventTarget.prototype, {
              addEventListener: createProxy(EventTarget.prototype.addEventListener, {
                apply(target, thisArg, args) {
                  thisArg = toObject(thisArg);
                  args = args.map(toObject)
                  const [type, listener, options] = args;
                  if (listener instanceof Object) {
                    if (!modified.has(listener)) {
                      const newListener = typeof listener === "function" ? function (e) {
                        return listener.call(this, patchedEvents.has(e) ? patchedEvents.get(e) : e);
                      } : function (e) {
                        return listener.handleEvent(patchedEvents.has(e) ? patchedEvents.get(e) : e);
                      };
                      modified.set(listener, newListener);
                      original.set(newListener, listener);
                    }
                    args[1] = modified.get(listener);
                  }
                  return Reflect.apply(target, thisArg, args);
                }
              }),
              removeEventListener: createProxy(EventTarget.prototype.removeEventListener, {
                apply(target, thisArg, args) {
                  thisArg = toObject(thisArg);
                  args = args.map(toObject);
                  const [type, listener, options] = args;
                  if (listener instanceof Object) {
                    args[1] = original.get(listener) ?? listener;
                  }
                  return Reflect.apply(target, thisArg, args);
                }
              })
            });

            const eventDescriptor = Object.getOwnPropertyDescriptor(window, "event");

            Object.defineProperty(window, "event", Object.assign(eventDescriptor, {
              get: createProxy(eventDescriptor.get, {
                apply(target, thisArg) {
                  return toProxy(target.call(thisArg));
                }
              }),
              set: createProxy(eventDescriptor.set, {
                apply(target, thisArg, [value]) {
                  return target.call(thisArg, toObject(value));
                }
              })
            }));

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
                patchedEvents.set(event, createProxy(event, proxyEventHandler));
                element.dispatchEvent(event);
                await new Promise(resolve => setTimeout(resolve, 10));
              }
            }

            fixIllegalInvocation(MouseEvent.prototype);

            setInterval(() => {
              const checkbox = getCheckBox();
              if (checkbox) {
                simulateMouseClick(checkbox);
              }
            }, 100);
        """.trimIndent().addCommonScript()
    }
}
