package eu.kanade.tachiyomi.network

import android.content.Context
import okhttp3.OkHttpClient

class NetworkHelper(context: Context) {
    val cookieJar = AndroidCookieJar()
    val client: OkHttpClient = OkHttpClient()
}
