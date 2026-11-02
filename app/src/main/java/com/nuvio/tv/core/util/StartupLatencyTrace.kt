package com.nuvio.tv.core.util

import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight startup-latency trace for task 2.13 (profile-select to main UI).
 * Logs elapsed-time marks under a single tag so one logcat filter yields the
 * full attribution chain:
 *
 *   adb logcat -s NUVIO_STARTUP_TRACE
 *
 * begin() resets the clock; mark() logs the total since begin and the step
 * since the previous mark. Marks fired outside a profile-switch run (e.g. a
 * routine foreground settings pull) log against the stale start time and are
 * recognisable by their large t values. Logging only; no behavioural effect.
 */
object StartupLatencyTrace {
    private const val TAG = "NUVIO_STARTUP_TRACE"

    @Volatile private var traceStartElapsedMs: Long = 0L
    @Volatile private var lastMarkElapsedMs: Long = 0L

    fun begin(name: String) {
        val now = SystemClock.elapsedRealtime()
        traceStartElapsedMs = now
        lastMarkElapsedMs = now
        Log.i(TAG, "[$name] t=0 ms (trace begin)")
    }

    fun mark(name: String) {
        val now = SystemClock.elapsedRealtime()
        if (traceStartElapsedMs == 0L) {
            begin(name)
            return
        }
        Log.i(TAG, "[$name] t=+${now - traceStartElapsedMs} ms (step +${now - lastMarkElapsedMs} ms)")
        lastMarkElapsedMs = now
    }

    private val coldNames = ConcurrentHashMap.newKeySet<String>()

    /** Logs [name] against the process start, so one cold launch reads as a single timeline. */
    fun cold(name: String, detail: String = "") {
        val t = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        Log.i(TAG, if (detail.isEmpty()) "[$name] cold=+$t ms" else "[$name] cold=+$t ms $detail")
    }

    fun coldOnce(name: String, detail: String = "") {
        if (coldNames.add(name)) cold(name, detail)
    }

    fun coldOnFirstDraw(view: View, name: String) {
        view.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                coldOnce(name)
                view.post { view.viewTreeObserver.removeOnDrawListener(this) }
            }
        })
    }
}
