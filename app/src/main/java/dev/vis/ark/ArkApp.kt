package dev.vis.ark

import android.app.Application
import android.os.Build
import android.os.StrictMode

class ArkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Debug builds log any disk or network access on the main thread, the usual cause of dropped frames.
        if (BuildConfig.DEBUG) StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().detectCustomSlowCalls().penaltyLog().build(),
        )
        // Only the app's own process (not GeckoView's content processes): start reading settings and saved tabs on
        // background threads now, so they're ready by the time the window is created instead of blocking it.
        val main = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() == packageName else !isGeckoChild()
        if (main) {
            getSharedPreferences("ark", MODE_PRIVATE) // loads asynchronously; Prefs.init picks up the same instance
            dev.vis.ark.browser.Browser.prefetch(this)
        }
    }

    /** Android 8.x: GeckoView's child processes are named "<package>:<name>". */
    private fun isGeckoChild(): Boolean = runCatching {
        java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000').contains(':')
    }.getOrDefault(false)
}
