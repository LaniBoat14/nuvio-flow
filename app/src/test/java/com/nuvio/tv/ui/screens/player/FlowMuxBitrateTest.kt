package com.nuvio.tv.ui.screens.player

import org.junit.Assert.*
import org.junit.Test

class FlowMuxBitrateTest {
    @Test fun mediaRateDoesNotBecomeDownloadSpeed() {
        val meter = FlowMuxBitrate()
        meter.sample(0, 0, 0)
        // 50 MB fetched in one wall second represents five seconds of 80 Mbps media.
        meter.sample(1000, 5000, 50_000_000)
        assertEquals(80_000_000L, meter.averageBps)
        assertEquals(80_000_000L, meter.currentBps)
    }
    @Test fun sceneChangeUpdatesWindowSeparatelyFromAverage() {
        val meter = FlowMuxBitrate()
        for (i in 0..10) meter.sample(i * 1000L, i * 1000L, i * 10_000_000L)
        for (i in 11..21) meter.sample(i * 1000L, i * 1000L, 100_000_000L + (i-10) * 5_000_000L)
        assertEquals(40_000_000L, meter.currentBps)
        assertTrue(meter.averageBps!! > meter.currentBps!!)
    }
    @Test fun seekResetsBothReadings() {
        val meter = FlowMuxBitrate()
        meter.sample(0, 0, 0)
        meter.sample(1000, 5000, 50_000_000)
        meter.sample(2000, 120_000, 51_000_000, discontinuity = true)
        assertNull(meter.currentBps)
        assertNull(meter.averageBps)
    }
    @Test fun idleWindowDoesNotInventZeroBitrate() {
        val meter = FlowMuxBitrate()
        meter.sample(0, 0, 0)
        meter.sample(1000, 5000, 50_000_000)
        meter.sample(12_000, 5000, 50_000_000)
        assertNull(meter.currentBps)
        assertEquals(80_000_000L, meter.averageBps)
    }
}
