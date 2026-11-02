package com.nuvio.tv.ui.screens.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A rate-limit clamp from an earlier title does not follow the viewer into the next one. */
class NewPlaybackGateTest {
    @After fun cleanup() = ParallelRangeDataSource.resetRateLimitMirrors()

    @Test fun onlyAnotherUrlIsANewPlayback() {
        val g = NewPlaybackGate()
        assertTrue(g.isNew("https://a.example/film.mkv"))
        assertFalse(g.isNew("https://a.example/film.mkv"))
        assertTrue(g.isNew("http://192.168.1.10:8096/Videos/1/stream.mkv"))
        assertTrue(g.isNew("https://a.example/film.mkv"))
    }

    @Test fun aNewPlaybackClearsTheClampMirrors() {
        ParallelRangeDataSource.hudClampLatched = true
        ParallelRangeDataSource.hudClampTrips = 3
        ParallelRangeDataSource.hudClampLastHitAtMs = 123_456L
        ParallelRangeDataSource.hudDepthCap = 2
        ParallelRangeDataSource.hudDepthConfigured = 8
        ParallelRangeDataSource.hudNextStepAtMs = 130_000L

        ParallelRangeDataSource.resetRateLimitMirrors()

        assertFalse(ParallelRangeDataSource.hudClampLatched)
        assertEquals(0, ParallelRangeDataSource.hudClampTrips)
        assertEquals(0L, ParallelRangeDataSource.hudClampLastHitAtMs)
        assertEquals(0, ParallelRangeDataSource.hudDepthCap)
        assertEquals(0, ParallelRangeDataSource.hudDepthConfigured)
        assertEquals(0L, ParallelRangeDataSource.hudNextStepAtMs)
    }
}
