package com.nuvio.tv.core.performance

import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

/**
 * PowerManager.getThermalHeadroom() shared by every caller in the process, asked at most once per [MIN_INTERVAL_MS].
 *
 * On boxes whose thermal HAL cannot serve it (Xiaomi MiTV-AFMU0, Ugoos AM9) every call makes system_server's
 * TemperatureWatcher retry against the HAL and log a stack trace per attempt, for several seconds after each call.
 * Callers get the cached answer between reads. After two failed reads in a row the next one waits
 * [FAILURE_BACKOFF_MS], and a box that has not answered once in [FAILURES_BEFORE_DEAD] reads is not asked again in
 * this process. Asked more often than about once a second the API answers NaN, which the interval avoids.
 */
object ThermalHeadroom {
    private const val TAG = "ThermalHeadroom"
    const val FAILURES_BEFORE_DEAD = 3
    const val MIN_INTERVAL_MS = 10_000L
    const val FAILURE_BACKOFF_MS = 60_000L

    @Volatile
    var halDead: Boolean = false
        private set

    @Volatile
    private var consecutiveFailures: Int = 0

    private var answeredOnce: Boolean = false
    private var lastAskAtMs: Long? = null
    private var lastAnswer: Float = Float.NaN

    fun read(pm: PowerManager?, forecastSeconds: Int): Float {
        if (pm == null || halDead || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return Float.NaN
        return readThrottled(SystemClock.elapsedRealtime()) { pm.getThermalHeadroom(forecastSeconds) }
    }

    @Synchronized
    internal fun readThrottled(nowMs: Long, ask: () -> Float): Float {
        if (halDead) return Float.NaN
        val last = lastAskAtMs
        val wait = if (consecutiveFailures >= 2) FAILURE_BACKOFF_MS else MIN_INTERVAL_MS
        if (last != null && nowMs - last in 0 until wait) return lastAnswer
        lastAskAtMs = nowMs
        lastAnswer = runCatching(ask).getOrDefault(Float.NaN)
        record(lastAnswer)
        return lastAnswer
    }

    internal fun record(headroom: Float) {
        if (!headroom.isNaN() && headroom >= 0f) {
            consecutiveFailures = 0
            answeredOnce = true
            return
        }
        consecutiveFailures += 1
        if (!answeredOnce && consecutiveFailures >= FAILURES_BEFORE_DEAD && !halDead) {
            halDead = true
            Log.i(TAG, "thermal headroom unusable ($consecutiveFailures consecutive failures): not asking again in this process")
        }
    }

    @Synchronized
    internal fun resetForTest() {
        halDead = false
        consecutiveFailures = 0
        answeredOnce = false
        lastAskAtMs = null
        lastAnswer = Float.NaN
    }
}
