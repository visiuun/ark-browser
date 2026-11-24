package dev.vis.ark

import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Timing marks for benchmarking, silent unless turned on with `adb shell setprop log.tag.ArkPerf DEBUG`.
 * Each mark logs milliseconds since the process started, so startup phases line up across runs.
 */
object Perf {
    private const val TAG = "ArkPerf"
    @JvmField val on: Boolean = runCatching { Log.isLoggable(TAG, Log.DEBUG) }.getOrDefault(false)
    private val start = Process.getStartUptimeMillis()

    fun mark(what: String) {
        if (on) Log.d(TAG, "$what ${SystemClock.uptimeMillis() - start}")
    }
}
