package com.nuvio.tv.ui.screens.player

import android.os.SystemClock

/**
 * The piece requests the chunk session really has running (downloads and read-ahead together), and their running
 * total in request-milliseconds, so the requests behind any stretch of bytes can be averaged.
 */
internal class RequestMeter(private val clock: () -> Long = { SystemClock.elapsedRealtime() }) {
    private var running = 0
    private var sinceMs = 0L
    private var totalMs = 0L
    private var lastEndedMs = -1L

    @Synchronized
    fun started() {
        settle()
        running++
    }

    @Synchronized
    fun ended() {
        settle()
        running = (running - 1).coerceAtLeast(0)
        lastEndedMs = sinceMs
    }

    @Synchronized
    fun running(): Int = running

    /**
     * True once when no request has run for [idleMs]: the pooled connections may have been closed by the server
     * meanwhile. The next caller then counts from now again.
     */
    @Synchronized
    fun takeStale(idleMs: Long): Boolean {
        val now = clock()
        if (running > 0 || lastEndedMs < 0L || now - lastEndedMs < idleMs) return false
        lastEndedMs = now
        return true
    }

    /** Request-milliseconds so far. */
    @Synchronized
    fun requestMs(): Long {
        settle()
        return totalMs
    }

    @Synchronized
    fun reset() {
        totalMs = 0L
        sinceMs = clock()
        lastEndedMs = -1L
    }

    private fun settle() {
        val now = clock()
        if (running > 0) totalMs += running * (now - sinceMs).coerceAtLeast(0L)
        sinceMs = now
    }
}
