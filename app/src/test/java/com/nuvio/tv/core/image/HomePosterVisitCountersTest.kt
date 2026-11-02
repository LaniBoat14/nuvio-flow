package com.nuvio.tv.core.image

import coil3.decode.DataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomePosterVisitCountersTest {
    private fun fields(line: String) = line.removePrefix("HOME_POSTERS ").split(' ').associate {
        val (name, value) = it.split('=')
        name to value.toInt()
    }

    @Test fun summaryCountsEachTileOnceByItsFirstSource() {
        val counters = HomePosterVisitCounters()
        counters.success("a", DataSource.MEMORY_CACHE, retry = false)
        counters.success("a", DataSource.DISK, retry = false)
        counters.success("b", DataSource.DISK, retry = false)
        counters.success("c", DataSource.NETWORK, retry = false)
        counters.success("d", DataSource.MEMORY, retry = false)
        val summary = fields(counters.takeSummary(savedCopies = 2, seconds = 40))
        assertEquals(40, summary["seconds"])
        assertEquals(4, summary["tiles"])
        assertEquals(4, summary["shown"])
        assertEquals(2, summary["memory"])
        assertEquals(1, summary["disk"])
        assertEquals(1, summary["network"])
        assertEquals(0, summary["failed"])
        assertEquals(0, summary["missing"])
        assertEquals(2, summary["savedCopy"])
    }

    @Test fun failedRetriedRecoveredAndStillMissing() {
        val counters = HomePosterVisitCounters()
        counters.failure("a", retry = false)
        counters.failure("a", retry = true)
        counters.success("a", DataSource.NETWORK, retry = true)
        counters.failure("b", retry = false)
        counters.failure("b", retry = true)
        counters.success("c", DataSource.DISK, retry = false)
        val summary = fields(counters.takeSummary(savedCopies = 0, seconds = 5))
        assertEquals(3, summary["tiles"])
        assertEquals(2, summary["shown"])
        assertEquals(2, summary["failed"])
        assertEquals(3, summary["retried"])
        assertEquals(1, summary["recovered"])
        assertEquals(1, summary["missing"])
        assertEquals(1, summary["network"])
    }

    @Test fun redrawsOfARetriedTileFromMemoryAreNotCountedAsTries() {
        val counters = HomePosterVisitCounters()
        counters.failure("a", retry = false)
        counters.success("a", DataSource.DISK, retry = true)
        counters.success("a", DataSource.MEMORY_CACHE, retry = true)
        counters.success("a", DataSource.MEMORY_CACHE, retry = true)
        val summary = fields(counters.takeSummary(savedCopies = 0, seconds = 5))
        assertEquals(1, summary["retried"])
        assertEquals(1, summary["recovered"])
    }

    @Test fun eachVisitStartsFromZeroAndTheLineCarriesNoAddresses() {
        val counters = HomePosterVisitCounters()
        counters.failure("https://image.example/secret-token/p.jpg_229x343_v0", retry = true)
        val first = counters.takeSummary(savedCopies = 0, seconds = 1)
        assertFalse(first.contains("http"))
        assertFalse(first.contains("example"))
        val second = fields(counters.takeSummary(savedCopies = 0, seconds = 1))
        assertEquals(0, second["tiles"])
        assertEquals(0, second["retried"])
    }
}
