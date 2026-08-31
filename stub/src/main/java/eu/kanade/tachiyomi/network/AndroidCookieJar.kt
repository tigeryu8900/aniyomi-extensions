package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class AndroidCookieJar : CookieJar {

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>): Unit = throw RuntimeException("Stub!")

    override fun loadForRequest(url: HttpUrl): List<Cookie> = throw RuntimeException("Stub!")

    fun get(url: HttpUrl): List<Cookie> = throw RuntimeException("Stub!")

    fun remove(url: HttpUrl, cookieNames: List<String>? = null, maxAge: Int = -1): Int = throw RuntimeException("Stub!")

    fun removeAll(): Unit = throw RuntimeException("Stub!")
}
