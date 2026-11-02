package com.nuvio.tv.ui.screens.search

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How long each search catalog typically takes to answer on this device, so live search can hold
 * the slow ones back until the user stops typing. A catalog it has not timed yet counts as fast.
 */
@Singleton
class SearchCatalogSpeed internal constructor(private val timeSource: TimeSource) {

    @Inject
    constructor() : this(TimeSource.Monotonic)

    private val typicalMs = ConcurrentHashMap<String, Long>()

    fun markNow(): TimeMark = timeSource.markNow()

    /** Answers quicker than this came from a cache and say nothing about the catalog. */
    fun recordAnswer(key: String, elapsedMs: Long) {
        if (elapsedMs < MIN_SAMPLE_MS) return
        typicalMs.merge(key, elapsedMs) { old, new -> (old * 2 + new) / 3 }
    }

    /** A request dropped before it answered still shows the catalog is at least that slow. */
    fun recordUnanswered(key: String, waitedMs: Long) {
        if (waitedMs >= SLOW_MS) recordAnswer(key, waitedMs)
    }

    fun isSlow(key: String): Boolean = (typicalMs[key] ?: 0L) >= SLOW_MS

    companion object {
        const val SLOW_MS = 1_500L
        private const val MIN_SAMPLE_MS = 50L

        fun key(addonBaseUrl: String, type: String, catalogId: String): String =
            "$addonBaseUrl|$type|$catalogId"
    }
}
