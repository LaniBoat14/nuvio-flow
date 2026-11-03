package com.nuvio.tv.ui.screens.player

import java.util.TreeSet

/**
 * A chunk session's "too many requests" spell. While it lasts no piece request starts. When the wait is over one
 * request, the piece nearest the player, tries alone; the others follow only once a request is answered again.
 * Refusals of requests sent before the latest counted refusal belong to the same burst and count once.
 */
internal class ParallelRangeRateGate(
    private val clock: () -> Long,
    private val jitter: (Long) -> Long = { bound -> (Math.random() * bound).toLong() }
) {
    companion object {
        const val FIRST_WAIT_MS = 2_000L
        const val MAX_WAIT_MS = 15_000L
        const val JITTER_MS = 250L
        /** A spell this long with no request answered fails the waiting pieces, so the player can give up cleanly. */
        const val GIVE_UP_MS = 120_000L
        private const val NONE = Long.MIN_VALUE

        /** The wait after the [refusals]th refusal in a row: the server's Retry-After, else 2 s doubling, at most 15 s. */
        fun waitMs(refusals: Int, retryAfterMs: Long?): Long {
            if (retryAfterMs != null) return retryAfterMs.coerceIn(0L, MAX_WAIT_MS)
            val shift = (refusals - 1).coerceIn(0, 4)
            return (FIRST_WAIT_MS shl shift).coerceAtMost(MAX_WAIT_MS)
        }
    }

    private var spellStartMs = NONE
    private var lastRefusalMs = NONE
    private var blockedUntilMs = NONE
    private var probe: Long? = null
    private val waiting = TreeSet<Long>()

    @Volatile
    var refusals = 0
        private set

    fun now(): Long = clock()

    @get:Synchronized
    val blocking: Boolean get() = spellStartMs != NONE

    /** Ms the spell has lasted, 0 when there is none. */
    @Synchronized
    fun spellMs(): Long = if (spellStartMs == NONE) 0L else (clock() - spellStartMs).coerceAtLeast(0L)

    /** Ms until the next request may try, 0 when it may try now. */
    @Synchronized
    fun waitLeftMs(): Long = if (spellStartMs == NONE) 0L else (blockedUntilMs - clock()).coerceAtLeast(0L)

    /**
     * Piece [index]'s request sent at [startedAtMs] was refused. Returns the refusal's number in this spell when it is
     * a new one (it starts or lengthens the wait), 0 when it belongs to a burst already counted.
     */
    @Synchronized
    fun refused(index: Long, startedAtMs: Long, retryAfterMs: Long?): Int {
        val now = clock()
        if (probe == index) probe = null
        val counted = spellStartMs == NONE || lastRefusalMs == NONE || startedAtMs > lastRefusalMs
        if (counted) {
            if (spellStartMs == NONE) spellStartMs = now
            lastRefusalMs = now
            refusals++
            blockedUntilMs = maxOf(blockedUntilMs, now + waitMs(refusals, retryAfterMs) + jitter(JITTER_MS))
        } else if (retryAfterMs != null) {
            blockedUntilMs = maxOf(blockedUntilMs, now + retryAfterMs.coerceIn(0L, MAX_WAIT_MS))
        }
        return if (counted) refusals else 0
    }

    /** May piece [index] send its request now? While it may not, it is counted as waiting until [leave]. */
    @Synchronized
    fun tryStart(index: Long): Boolean {
        if (spellStartMs == NONE) {
            waiting.remove(index)
            return true
        }
        waiting.add(index)
        if (clock() < blockedUntilMs) return false
        val current = probe
        if (current != null && current != index) return false
        if (waiting.first() != index) return false
        probe = index
        waiting.remove(index)
        return true
    }

    @Synchronized
    fun waitingCount(): Int = waiting.size

    /** Piece [index] stopped waiting without sending. */
    @Synchronized
    fun leave(index: Long) {
        waiting.remove(index)
    }

    /** Piece [index]'s request ended without a refusal; a probe that failed otherwise lets the next one try. */
    @Synchronized
    fun finished(index: Long) {
        waiting.remove(index)
        if (probe == index) probe = null
    }

    /**
     * A request sent at [startedAtMs] was answered. One sent after the latest refusal ends the spell; returns true
     * when it did.
     */
    @Synchronized
    fun answered(startedAtMs: Long): Boolean {
        if (spellStartMs == NONE || startedAtMs <= lastRefusalMs) return false
        spellStartMs = NONE
        blockedUntilMs = NONE
        refusals = 0
        probe = null
        waiting.clear()
        return true
    }
}
