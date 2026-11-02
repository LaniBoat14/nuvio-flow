package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the downloads deliver over 25 s, for thumbnails to use only what the film does not need. */
class AutoSpeedupDeliveredTest {
    private var now = 0L
    private var bytes = 0L
    private var ahead = 20_000L

    private fun AutoSpeedupPolicy.feed(seconds: Int, kbps: Long, loading: Boolean = true) {
        repeat(seconds) {
            now += 1_000L
            if (loading) bytes += kbps * 1000L / 8L
            onSample(AutoSpeedupPolicy.Sample(now, ahead, loading, 0, bytes, fileBitrate = 80_000_000L))
        }
    }

    @Test fun notKnownBeforeTwentySeconds() {
        val p = AutoSpeedupPolicy(10)
        p.feed(19, 120_000)
        assertNull(p.deliveredNow(now))
        p.feed(2, 120_000)
        val d = p.deliveredNow(now)!!
        assertFalse(d.rested)
        assertEquals(120_000_000L, d.bps)
    }

    @Test fun onlyTheLast25SecondsCount() {
        val p = AutoSpeedupPolicy(10)
        p.feed(15, 60_000)
        p.feed(25, 150_000)
        assertEquals(150_000_000L, p.deliveredNow(now)!!.bps)
    }

    @Test fun aRestingLoaderMeansTheLineCarriedMore() {
        val p = AutoSpeedupPolicy(10)
        p.feed(5, 150_000)
        p.feed(20, 0, loading = false)
        assertTrue(p.deliveredNow(now)!!.rested)
    }

    @Test fun thePlanNeedsAtLeastOneRequest() {
        val p = AutoSpeedupPolicy(10)
        assertTrue(p.plannedRequests(80_000_000L) >= 1)
    }
}
