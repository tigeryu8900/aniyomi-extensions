package keiyoushi.utils

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.ViewGroup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.TypeReference
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.fullType
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicReference

/**
 * The activity currently on screen, for the code that needs a real window rather than a context.
 *
 * The Cloudflare bypass is the caller: its WebView only renders a challenge widget, routes touch and
 * reports focus once it is attached to one, and the interceptor it runs from has nothing but the
 * application context. Held weakly and re-checked on read, so a finished activity is never returned.
 *
 * Adapted from https://github.com/unseensnick/Reikai/blob/14d3d54/core/common/src/main/kotlin/eu/kanade/tachiyomi/util/system/ForegroundActivity.kt
 */
object ForegroundActivity : Application.ActivityLifecycleCallbacks {
    private typealias CallbacksTypeRef = TypeReference<Application.ActivityLifecycleCallbacks>
    private typealias ActivityRef = AtomicReference<Activity?>

    private val last: ActivityRef = with(Injekt.registrar) {
        synchronized(this) {
            if (!hasFactory(fullType<Pair<CallbacksTypeRef, ActivityRef>>())) {
                addSingleton<Pair<CallbacksTypeRef, ActivityRef>>(
                    fullType<Application.ActivityLifecycleCallbacks>() to ActivityRef(null),
                )
                get<Application>().registerActivityLifecycleCallbacks(this@ForegroundActivity)
            }
        }
        get<Pair<CallbacksTypeRef, ActivityRef>>().second
    }

    val current: Activity? get() = last.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    val viewGroup: ViewGroup? get() = current?.window?.decorView as? ViewGroup

    override fun onActivityPaused(activity: Activity) {
        last.compareAndSet(activity, null)
    }

    override fun onActivityResumed(activity: Activity) {
        last.set(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
