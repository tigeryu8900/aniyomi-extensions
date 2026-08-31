package eu.kanade.tachiyomi.ui.base.delegate

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

interface SecureActivityDelegate {
    fun registerSecureActivity(activity: AppCompatActivity)

    companion object {
        // SY -->
        const val LOCK_SUNDAY = 0x40
        const val LOCK_MONDAY = 0x20
        const val LOCK_TUESDAY = 0x10
        const val LOCK_WEDNESDAY = 0x8
        const val LOCK_THURSDAY = 0x4
        const val LOCK_FRIDAY = 0x2
        const val LOCK_SATURDAY = 0x1
        const val LOCK_ALL_DAYS = 0x7F
        // SY <--

        /**
         * Set to true if we need the first activity to authenticate.
         *
         * Always require unlock if app is killed.
         */
        var requireUnlock = true

        fun onApplicationStopped(): Unit = throw RuntimeException("Stub!")

        /**
         * Checks if unlock is needed when app comes foreground.
         */
        fun onApplicationStart(): Unit = throw RuntimeException("Stub!")

        fun unlock(): Unit = throw RuntimeException("Stub!")
    }
}

class SecureActivityDelegateImpl :
    SecureActivityDelegate,
    DefaultLifecycleObserver {
    override fun registerSecureActivity(activity: AppCompatActivity) = throw RuntimeException("Stub!")

    override fun onCreate(owner: LifecycleOwner) = throw RuntimeException("Stub!")

    override fun onResume(owner: LifecycleOwner) = throw RuntimeException("Stub!")
}
