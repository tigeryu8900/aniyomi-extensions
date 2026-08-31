package eu.kanade.tachiyomi.ui.base.delegate

import android.app.Activity

interface ThemingDelegate {
    fun applyAppTheme(activity: Activity)
}

class ThemingDelegateImpl : ThemingDelegate {
    override fun applyAppTheme(activity: Activity) = throw RuntimeException("Stub!")
}
